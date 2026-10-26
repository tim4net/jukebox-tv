package net.tfour.jukebox.update;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.FileProvider;

import com.liskovsoft.appupdatechecker2.AppUpdateChecker;
import com.liskovsoft.appupdatechecker2.AppUpdateCheckerListener;
import com.liskovsoft.smartyoutubetv2.common.R;

import net.tfour.jukebox.room.JukeboxForeground;
import net.tfour.jukebox.room.JukeboxLog;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Manifest check + install for the stjukebox flavor (tag {@code JukeboxUpdate}).
 *
 * The check drives the upstream {@link AppUpdateChecker} manifest machinery
 * with the resolved manifest URL (flavor default from
 * res/values/update_urls.xml, or the TEST-ONLY {@code debug.jukebox.update_url}
 * system-property override, accepted only under https://jukebox.tfour.net/install/).
 *
 * Build 2473, issue #5 (bug 2): install NO LONGER relies on the upstream
 * cache/update.apk download ({@link #install()} used to call
 * {@code AppUpdateChecker.installUpdate()}, which launched the installer on a
 * path that was never downloaded — roux2471.s3u2: InstallStaging died with
 * FileNotFoundException/ENOENT). Instead {@link ManifestChecker} captures the
 * manifest's own downloadUrl + versionCode, and on install chosen the
 * presenter downloads that URL itself into
 * {@code cacheDir()/jukebox-update/jukebox-update-<versionCode>.apk}
 * (written to {@code .part}, then renamed), verifies it (exists / size&gt;0 /
 * packageName == ours / versionCode == offered) and only then launches
 * ACTION_VIEW with a FileProvider content URI (authority
 * {@code <package>.jukeboxupdate}) + FLAG_GRANT_READ_URI_PERMISSION +
 * FLAG_ACTIVITY_NEW_TASK. Any failure deletes the partial file, logs, and
 * shows "Update download failed — will retry next time" on the card. Never
 * launch while the app is backgrounded (issue #3): the verified file is kept
 * and the install is offered again on the next foreground
 * ({@link #onAppForeground()}). Only one download at a time.
 *
 * The card itself stays non-modal ({@link JukeboxUpdateCard}): no player
 * pause, no focus steal, auto-hides after ~15s, and its keys are routed to it
 * app-wide while it is visible ({@link JukeboxUpdateKeyRouter}).
 *
 * All I/O sits behind overridable seams ({@link #createDownloader()},
 * {@link #createInstallLauncher()}, {@link #archiveInfoOf(String)},
 * {@link #runAsync(Runnable)}, {@link #runOnMain(Runnable)}) so the tests
 * can pin user-visible behavior without a network.
 */
public class JukeboxUpdatePresenter implements AppUpdateCheckerListener {
    /** Test-only manifest override: adb shell setprop debug.jukebox.update_url <url>. */
    static final String OVERRIDE_PROP = "debug.jukebox.update_url";
    /** Overrides are only accepted under this prefix (testers stay on our host). */
    static final String OVERRIDE_PREFIX = "https://jukebox.tfour.net/install/";

    /** Download directory under the app's cache dir (FileProvider cache-path). */
    static final String DOWNLOAD_DIR = "jukebox-update";
    /** FileProvider authority (declared in the stjukebox manifest). */
    static final String PROVIDER_AUTHORITY_SUFFIX = ".jukeboxupdate";
    /**
     * Build 2475, item 2: the pinned SHA-256 (hex, uppercase, no spaces) of
     * the only signing cert a downloaded update apk may carry.
     */
    static final String PINNED_SIGNING_CERT_SHA256 =
            "0DC9138549DDEDF665BC48F1F717B5CF35893943699746ED93657525FDE7C609";
    /** Log/download progress step sizes. */
    private static final long PROGRESS_BYTES_STEP = 2 * 1024 * 1024; // ~2MB
    private static final int PROGRESS_PERCENT_STEP = 10; // ~10%

    /** Supplies the currently resumed activity (where the prompt card attaches). */
    public interface CurrentActivity {
        Activity get();
    }

    /**
     * Build 2475, item 6: the immutable manifest facts of ONE offer, captured
     * once when install is chosen and passed through download/verify (never
     * re-read from the mutable offer fields while the install is in flight,
     * so a newer offer arriving meanwhile cannot swap it).
     */
    static final class Offer {
        final String url;
        final int versionCode;

        Offer(String url, int versionCode) {
            this.url = url;
            this.versionCode = versionCode;
        }
    }

    /** Downloads an apk URL to a destination file, reporting percent progress. */
    public interface ApkDownloader {
        void download(String url, File destFile, Progress progress) throws IOException;

        interface Progress {
            void onProgress(int percent);
        }
    }

    /** Launches the Android package installer for a verified apk file. */
    public interface InstallLauncher {
        void launch(String apkPath);
    }

    private final Context mAppContext;
    private final CurrentActivity mCurrentActivity;
    private final Object mDownloadLock = new Object();
    private volatile boolean mDownloading;
    /** A verified apk kept while the app was backgrounded (issue #3). */
    private volatile String mPendingInstallPath;
    /** The offer the pending install belongs to (item 6). */
    private volatile Offer mPendingOffer;
    private AppUpdateChecker mChecker;
    /** Manifest facts of the current offer (set by ManifestChecker). */
    private volatile String mOfferedVersionName;
    private volatile int mOfferedVersionCode;
    private volatile String mOfferedDownloadUrl;

    JukeboxUpdatePresenter(Context appContext, CurrentActivity currentActivity) {
        mAppContext = appContext.getApplicationContext();
        mCurrentActivity = currentActivity;
    }

    /** Runs the manifest check against the resolved URL (see {@link #resolveUrl()}). */
    void startCheck() {
        String url = resolveUrl();
        JukeboxLog.i(JukeboxUpdate.LOG_TAG, "check: url=" + url);
        checker().forceCheckForUpdates(new String[]{url});
    }

    /**
     * The app returned to the foreground: complete a download that finished
     * while the app was backgrounded (the verified file was kept, issue #3).
     * NOP when nothing is pending.
     */
    public void onAppForeground() {
        final String pending = mPendingInstallPath;
        // Build 2475, item 4: stale downloads never outlive a foreground pass.
        pruneStaleDownloads(pending != null ? new File(pending) : null);
        if (pending == null || !JukeboxForeground.isAppInForeground()) {
            return;
        }
        JukeboxLog.i(JukeboxUpdate.LOG_TAG,
                "foreground: offering the install deferred while the app was backgrounded");
        final Offer offer = mPendingOffer;
        runAsync(() -> launchOrDefer(pending, offer, false));
    }

    // ---- manifest check (upstream) ----

    /**
     * The manifest URL for this check: the flavor default, replaced by the
     * test-only system-property override when one is set AND valid. The URL
     * actually used is logged by the caller on every check.
     */
    String resolveUrl() {
        String url = defaultUrl();
        String override = getSystemProperty(OVERRIDE_PROP);
        if (override == null || override.trim().isEmpty()) {
            return url;
        }
        if (!override.startsWith(OVERRIDE_PREFIX)) {
            JukeboxLog.i(JukeboxUpdate.LOG_TAG, "ignore invalid override url (outside "
                    + OVERRIDE_PREFIX + "): " + override);
            return url;
        }
        JukeboxLog.i(JukeboxUpdate.LOG_TAG, "override url active (debug.jukebox.update_url): " + override);
        return override;
    }

    private String defaultUrl() {
        String[] urls = mAppContext.getResources().getStringArray(R.array.update_urls);
        return urls != null && urls.length > 0 ? urls[0] : "";
    }

    /** adb-settable system property, read via reflection (hidden API). Null when unset/unreadable. */
    private static String getSystemProperty(String key) {
        try {
            Class<?> props = Class.forName("android.os.SystemProperties");
            Method get = props.getMethod("get", String.class, String.class);
            Object value = get.invoke(null, key, "");
            return value instanceof String ? (String) value : null;
        } catch (Throwable unreadable) {
            return null;
        }
    }

    private AppUpdateChecker checker() {
        if (mChecker == null) {
            mChecker = new ManifestChecker(mAppContext, this);
        }
        return mChecker;
    }

    /**
     * Manifest-driven check that does NOT depend on the upstream apk download
     * (build 2473, bug 2: cache/update.apk is never used). Captures the
     * manifest's own downloadUrl/versionCode/versionName and offers straight
     * away; the apk is downloaded by the presenter, on demand, when the user
     * chooses install. The "no update" branch stays upstream (it only logs
     * and cleans up).
     */
    static class ManifestChecker extends AppUpdateChecker {
        private final JukeboxUpdatePresenter mPresenter;

        ManifestChecker(Context context, JukeboxUpdatePresenter presenter) {
            super(context, presenter);
            mPresenter = presenter;
        }

        @Override
        public void onChangelogReceived(boolean isLatestVersion, String latestVersionName,
                int latestVersionNumber, List<String> changelog, Uri[] downloadUris) {
            if (isLatestVersion || downloadUris == null || downloadUris.length == 0
                    || downloadUris[0] == null) {
                // No update (or nothing downloadable): upstream handles it.
                super.onChangelogReceived(isLatestVersion, latestVersionName,
                        latestVersionNumber, changelog, downloadUris);
                return;
            }
            // Record the manifest facts for the install path, then offer.
            mPresenter.offerFound(latestVersionName, latestVersionNumber, downloadUris[0].toString());
            mPresenter.onUpdateFound(latestVersionName, changelog, downloadUris[0].toString());
        }
    }

    /** Manifest facts of the current offer (called by ManifestChecker). */
    void offerFound(String versionName, int versionCode, String downloadUrl) {
        mOfferedVersionName = versionName;
        mOfferedVersionCode = versionCode;
        mOfferedDownloadUrl = downloadUrl;
    }

    /** Item 6: the current offer captured as one immutable value. */
    private Offer currentOffer() {
        return new Offer(mOfferedDownloadUrl, mOfferedVersionCode);
    }

    /**
     * Pure item-3 decision: null when {@code offeredVersionCode} may be
     * offered/installed against the installed {@code installedVersionCode}
     * (unknown installed build, or strictly newer), else the logged reason
     * the offer must be refused (a downgrade is never offered or installed).
     */
    static String downgradeFailureReason(int offeredVersionCode, int installedVersionCode) {
        if (installedVersionCode <= 0 || offeredVersionCode <= 0) {
            return null; // installed build unknown / nothing offered: nothing to compare
        }
        if (offeredVersionCode > installedVersionCode) {
            return null;
        }
        return "downgrade: offered versionCode " + offeredVersionCode
                + " is not newer than the installed " + installedVersionCode;
    }

    @Override
    public void onUpdateFound(String versionName, List<String> changelog, String apkPath) {
        // apkPath is the manifest's downloadUrl since build 2473 (the upstream
        // cache/update.apk path is never used or even downloaded).
        if (mOfferedDownloadUrl == null && apkPath != null) {
            mOfferedDownloadUrl = apkPath; // direct/test calls without a checker
        }
        int build = mOfferedVersionCode > 0 ? mOfferedVersionCode : buildCodeOf(apkPath);
        // Build 2475, item 3: a version we already have (or older) is never offered.
        String downgrade = downgradeFailureReason(build, installedVersionCode());
        if (downgrade != null) {
            JukeboxLog.i(JukeboxUpdate.LOG_TAG, "refusing " + downgrade);
            return;
        }
        JukeboxLog.i(JukeboxUpdate.LOG_TAG, "result: update available versionName=" + versionName
                + (build > 0 ? " (build " + build + ")" : "")
                + " changelogEntries=" + (changelog != null ? changelog.size() : 0)
                + " downloadUrl=" + mOfferedDownloadUrl);
        // Non-modal prompt on the current activity: no dialog activity, no
        // player pause, no focus steal (the card is DPAD-reachable instead).
        JukeboxUpdateCard.show(mCurrentActivity.get(), versionName, build, this::install);
    }

    @Override
    public void onUpdateError(Exception error) {
        JukeboxLog.i(JukeboxUpdate.LOG_TAG, "result: no update ("
                + (error != null && error.getMessage() != null ? error.getMessage() : "unknown") + ")");
    }

    // ---- install: download ourselves, verify, then (and only then) launch ----

    /** Item 1: the only download host we ever fetch from. */
    static final String DOWNLOAD_HOST = "jukebox.tfour.net";
    /** Item 1: the only path prefixes we ever download under. */
    static final String[] DOWNLOAD_PATH_PREFIXES = {"/apk/", "/install/"};

    /**
     * Pure item-1 decision: null when {@code url} is a download URL we may
     * fetch (https, our host, path under /apk/ or /install/), else the logged
     * reason it must be refused without downloading.
     */
    static String invalidDownloadUrlReason(String url) {
        URL parsed;
        try {
            parsed = new URL(url);
        } catch (IOException malformed) {
            return "malformed";
        }
        if (!"https".equals(parsed.getProtocol())) {
            return "protocol " + parsed.getProtocol();
        }
        if (!DOWNLOAD_HOST.equals(parsed.getHost())) {
            return "host " + parsed.getHost();
        }
        String path = parsed.getPath();
        for (String prefix : DOWNLOAD_PATH_PREFIXES) {
            if (path.startsWith(prefix)) {
                return null;
            }
        }
        return "path " + path;
    }

    /**
     * Install chosen (card Install button, card click, or the app-wide OK key
     * routing): downloads the manifest downloadUrl, verifies it, launches the
     * installer — or defers (backgrounded) / shows a retry notice (failure).
     * Only one download at a time. Never pauses playback, never steals focus.
     */
    void install() {
        // Item 6: capture the offer NOW, once, immutably — the queued install
        // must use it even if a newer offer arrives before it runs. Item 3:
        // a downgrade is never offered or installed.
        final Offer offer = currentOffer();
        String downgrade = downgradeFailureReason(offer.versionCode, installedVersionCode());
        if (downgrade != null) {
            JukeboxLog.i(JukeboxUpdate.LOG_TAG, "refusing " + downgrade);
            return;
        }
        synchronized (mDownloadLock) {
            if (mDownloading) {
                JukeboxLog.i(JukeboxUpdate.LOG_TAG,
                        "install chosen: a download is already in progress (ignored)");
                return;
            }
            mDownloading = true;
        }
        runAsync(() -> installAsync(offer));
    }

    private void installAsync(Offer offer) {
        try {
            String pending = mPendingInstallPath;
            if (pending != null) {
                // A verified apk is already on disk (kept while backgrounded):
                // reuse it, never re-download.
                JukeboxLog.i(JukeboxUpdate.LOG_TAG,
                        "install chosen: using the already downloaded update " + pending);
                launchOrDefer(pending, mPendingOffer, false);
                return;
            }
            String url = offer.url;
            int versionCode = offer.versionCode;
            if (url == null || url.trim().isEmpty() || versionCode <= 0) {
                JukeboxLog.i(JukeboxUpdate.LOG_TAG,
                        "install chosen: no manifest download url/versionCode known");
                runOnMain(() -> cardText("Update download failed \u2014 will retry next time"));
                return;
            }
            String badUrl = invalidDownloadUrlReason(url);
            if (badUrl != null) {
                JukeboxLog.i(JukeboxUpdate.LOG_TAG,
                        "invalid download url (" + badUrl + "), refusing: " + url);
                runOnMain(() -> cardText("Update download failed \u2014 will retry next time"));
                return;
            }
            File destFile = downloadDest(versionCode);
            // Build 2475, item 4: prune stale downloads before each download
            // (the current pending download's files survive).
            pruneStaleDownloads(destFile);
            JukeboxLog.i(JukeboxUpdate.LOG_TAG,
                    "install chosen: downloading the update ourselves"
                            + " (the upstream cache/update.apk is never used)");
            JukeboxLog.i(JukeboxUpdate.LOG_TAG,
                    "download start: url=" + url + " dest=" + destFile);
            runOnMain(() -> cardText("Downloading update\u2026 0%"));
            try {
                downloader().download(url, destFile, percent -> {
                    JukeboxLog.i(JukeboxUpdate.LOG_TAG,
                            "download progress: " + percent + "% (" + url + ")");
                    runOnMain(() -> cardText("Downloading update\u2026 " + percent + "%"));
                });
            } catch (Throwable error) {
                JukeboxLog.e(JukeboxUpdate.LOG_TAG, "download failed: " + error, error);
                delete(destFile);
                delete(partOf(destFile));
                runOnMain(() -> cardText("Update download failed \u2014 will retry next time"));
                return;
            }
            JukeboxLog.i(JukeboxUpdate.LOG_TAG,
                    "download done: bytes=" + (destFile.exists() ? destFile.length() : 0)
                            + " dest=" + destFile);
            launchOrDefer(destFile.getPath(), offer, true);
        } finally {
            synchronized (mDownloadLock) {
                mDownloading = false;
            }
        }
    }

    /** cacheDir()/jukebox-update/jukebox-update-<versionCode>.apk */
    private File downloadDest(int versionCode) {
        return new File(new File(mAppContext.getCacheDir(), DOWNLOAD_DIR),
                "jukebox-update-" + versionCode + ".apk");
    }

    /**
     * Build 2475, item 4: deletes every stale download in the download dir
     * ({@code jukebox-update-*.apk} and {@code *.part}), keeping only the
     * current pending file {@code keep} and its {@code .part}.
     */
    private void pruneStaleDownloads(File keep) {
        File[] files = new File(mAppContext.getCacheDir(), DOWNLOAD_DIR).listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (isPrunableDownloadFileName(file.getName(), keep)) {
                JukeboxLog.i(JukeboxUpdate.LOG_TAG, "pruning stale download: " + file);
                delete(file);
            }
        }
    }

    /**
     * Pure item-4 decision: true when {@code name} is a stale download file
     * ({@code jukebox-update-*.apk} / {@code *.part}) that is neither the
     * kept file {@code keep} nor its {@code .part}.
     */
    static boolean isPrunableDownloadFileName(String name, File keep) {
        if (name == null || !name.startsWith("jukebox-update-")) {
            return false;
        }
        String base = name.endsWith(".part")
                ? name.substring(0, name.length() - ".part".length()) : name;
        if (!base.endsWith(".apk")) {
            return false;
        }
        String keepName = keep != null ? keep.getName() : null;
        return !name.equals(keepName)
                && !(keepName != null && name.equals(keepName + ".part"));
    }

    private static File partOf(File destFile) {
        return new File(destFile.getPath() + ".part");
    }

    /**
     * Pure download decision (the I/O sits at the edges): verify the file,
     * log every check, then launch the installer — or, while the app is
     * backgrounded, keep the file and defer to the next foreground (issue #3).
     *
     * @param freshDownload true when {@code path} was just downloaded by this
     *                      caller (always launches, nothing pending to race);
     *                      false when {@code path} is the pending file kept
     *                      while backgrounded — then the launch is claimed
     *                      atomically so the install press and the foreground
     *                      hook can never both launch it (review fix #2).
     */
    private void launchOrDefer(String path, Offer offer, boolean freshDownload) {
        String bad = verifyFailureReason(path, offer);
        if (bad != null) {
            JukeboxLog.i(JukeboxUpdate.LOG_TAG,
                    "verify failed: " + bad + " \u2014 deleting, will retry next time");
            delete(new File(path));
            delete(partOf(new File(path)));
            setPendingInstall(null, null);
            runOnMain(() -> cardText("Update download failed \u2014 will retry next time"));
            return;
        }
        if (!JukeboxForeground.isAppInForeground()) {
            // Issue #3: never launch anything from the background. Keep the
            // verified file; the next foreground transition offers the install.
            setPendingInstall(path, offer);
            JukeboxLog.i(JukeboxUpdate.LOG_TAG,
                    "install deferred: app is backgrounded (keeping " + path + ")");
            return;
        }
        if (!freshDownload && !claimPendingLaunch(path)) {
            // Review fix #2 (build 2473): the install press and the foreground
            // hook can race on the same pending file — the loser must not
            // double-launch the installer.
            JukeboxLog.i(JukeboxUpdate.LOG_TAG, "launch skipped: " + path
                    + " already launched by another caller (install press vs foreground hook)");
            return;
        }
        installer().launch(path);
    }

    /**
     * Atomically records/clears the pending (deferred) install path together
     * with the offer it belongs to (item 6: the deferred install verifies
     * against its OWN offer, not whatever is current when it resumes).
     */
    private void setPendingInstall(String path, Offer offer) {
        synchronized (mDownloadLock) {
            mPendingInstallPath = path;
            mPendingOffer = offer;
        }
    }

    /**
     * Atomically claims the right to launch the pending {@code path}: true
     * exactly once per pending file, so racing callers (the install press and
     * the foreground hook) never double-launch the installer.
     */
    private boolean claimPendingLaunch(String path) {
        synchronized (mDownloadLock) {
            if (path != null && path.equals(mPendingInstallPath)) {
                mPendingInstallPath = null;
                mPendingOffer = null;
                return true;
            }
            return false;
        }
    }

    /**
     * @return null when the file verifies as our offered update ({@code offer},
     *         item 6 — never a re-read current offer), else the logged reason
     *         it must never be launched.
     */
    private String verifyFailureReason(String path, Offer offer) {
        File file = new File(path);
        boolean exists = file.exists();
        long size = exists ? file.length() : 0;
        JukeboxLog.i(JukeboxUpdate.LOG_TAG,
                "verify: exists=" + exists + " size=" + size + " path=" + path);
        if (!exists) {
            return "file does not exist";
        }
        if (size <= 0) {
            return "file is empty (zero bytes)";
        }
        PackageInfo info = archiveInfoOf(path);
        String pkg = info != null ? info.packageName : null;
        JukeboxLog.i(JukeboxUpdate.LOG_TAG, "verify: package=" + pkg
                + " expected=" + mAppContext.getPackageName());
        if (info == null || !mAppContext.getPackageName().equals(pkg)) {
            return "wrong package: " + pkg;
        }
        int expectedVersionCode = offer != null ? offer.versionCode : 0;
        int versionCode = (int) info.versionCode;
        JukeboxLog.i(JukeboxUpdate.LOG_TAG, "verify: versionCode=" + versionCode
                + " expected=" + expectedVersionCode);
        if (versionCode != expectedVersionCode) {
            return "wrong versionCode: " + versionCode + " (offered " + expectedVersionCode + ")";
        }
        // Build 2475, item 2: the archive's signer must be the pinned cert AND
        // the installed app's cert (a differently signed "update" is refused).
        String wrongSigner = signerCertFailureReason(
                archiveSigningCertSha256(path), installedSigningCertSha256());
        if (wrongSigner != null) {
            return wrongSigner;
        }
        // Build 2475, item 2: the manifest's optional sha256 for the apk
        // (absent = skip the file hash check; the cert pin above still applies).
        String wrongHash = manifestSha256FailureReason(
                manifestApkSha256(offer != null ? offer.url : null), sha256OfFile(file));
        if (wrongHash != null) {
            return wrongHash;
        }
        return null;
    }

    /**
     * Pure item-2 decision: null when the archive's signing cert digest is
     * the pinned constant AND the installed app's digest, else the logged
     * reason the apk must be deleted and never launched.
     */
    static String signerCertFailureReason(String archiveCertSha256, String installedCertSha256) {
        if (archiveCertSha256 == null || archiveCertSha256.trim().isEmpty()) {
            return "wrong signer: no signing cert in the archive";
        }
        if (!PINNED_SIGNING_CERT_SHA256.equalsIgnoreCase(archiveCertSha256.trim())) {
            return "wrong signer: archive cert " + archiveCertSha256
                    + " is not the pinned cert";
        }
        if (installedCertSha256 == null || installedCertSha256.trim().isEmpty()) {
            return "wrong signer: the installed app's signing cert is unknown";
        }
        if (!archiveCertSha256.trim().equalsIgnoreCase(installedCertSha256.trim())) {
            return "wrong signer: the installed app is signed differently ("
                    + installedCertSha256 + ")";
        }
        return null;
    }

    /**
     * Pure item-2 decision: null when the manifest carries no sha256 for the
     * apk (absent = skip) or the file hashes exactly as it says, else the
     * logged reason it must be deleted and never launched.
     */
    static String manifestSha256FailureReason(String manifestSha256, String fileSha256) {
        if (manifestSha256 == null || manifestSha256.trim().isEmpty()) {
            return null; // absent = skip the file hash check
        }
        if (fileSha256 == null || !manifestSha256.trim().equalsIgnoreCase(fileSha256.trim())) {
            return "sha256 mismatch: the file hashes " + fileSha256
                    + ", the manifest says " + manifestSha256;
        }
        return null;
    }

    /** Updates the card text on the current activity (download progress / failure). */
    private void cardText(String text) {
        Activity activity = mCurrentActivity.get();
        if (activity != null) {
            JukeboxUpdateCard.setText(activity, text);
        }
    }

    private static void delete(File file) {
        // No RuntimeException swallowing: a delete that blows up propagates to
        // the caller's own failure handling (logged there), never hidden here.
        if (file != null && file.exists() && !file.delete()) {
            file.deleteOnExit();
        }
    }

    // ---- overridable I/O seams (tests fake these) ----

    /** Production downloader: HttpURLConnection streaming to .part, then rename. */
    protected ApkDownloader createDownloader() {
        return (url, destFile, progress) -> downloadHttp(url, destFile, progress);
    }

    private ApkDownloader downloader() {
        return createDownloader();
    }

    /** Production installer: FileProvider content URI + ACTION_VIEW (+grant, new task). */
    protected InstallLauncher createInstallLauncher() {
        return this::launchInstaller;
    }

    private InstallLauncher installer() {
        return createInstallLauncher();
    }

    // ---- build 2475 review seams (tests fake these) ----

    /**
     * The versionCode installed right now (item 3): 0 when unknown, so the
     * downgrade guard never fires without a real installed build to compare.
     */
    protected int installedVersionCode() {
        try {
            PackageInfo info = mAppContext.getPackageManager()
                    .getPackageInfo(mAppContext.getPackageName(), 0);
            return info != null ? (int) info.versionCode : 0;
        } catch (PackageManager.NameNotFoundException notInstalled) {
            return 0;
        }
    }

    /**
     * SHA-256 (hex) of the downloaded apk's signing cert (item 2):
     * PackageManager archive info with GET_SIGNING_CERTIFICATES (API>=28)
     * / GET_SIGNATURES (&lt;28), first cert.
     */
    protected String archiveSigningCertSha256(String path) {
        return signingCertSha256Of(mAppContext.getPackageManager()
                .getPackageArchiveInfo(path, archiveSigningFlags()));
    }

    /**
     * SHA-256 (hex) of the installed app's signing cert (item 2): the
     * archive's signer must match the installed app's signer too.
     */
    protected String installedSigningCertSha256() {
        try {
            return signingCertSha256Of(mAppContext.getPackageManager()
                    .getPackageInfo(mAppContext.getPackageName(), archiveSigningFlags()));
        } catch (PackageManager.NameNotFoundException notInstalled) {
            return null;
        }
    }

    /**
     * The manifest's optional sha256 for the offered apk, or null when
     * absent (item 2): the manifest JSON behind {@link AppUpdateChecker}
     * carries no per-apk sha256 entry today, so absent = skip the file
     * hash check (the pinned-signer check above still guards the apk).
     */
    protected String manifestApkSha256(String downloadUrl) {
        return null;
    }

    /** SHA-256 (hex) of a file on disk (item 2), or null when unreadable. */
    protected String sha256OfFile(File file) {
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            return hexOf(digest.digest());
        } catch (IOException | RuntimeException | NoSuchAlgorithmException unreadable) {
            return null;
        }
    }

    /** PackageManager flags that populate the signing certs of an archive. */
    private static int archiveSigningFlags() {
        return Build.VERSION.SDK_INT >= 28
                ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
    }

    /** SHA-256 (hex, uppercase) of the package's first signing cert, or null. */
    private static String signingCertSha256Of(PackageInfo info) {
        Signature[] signatures = signaturesOf(info);
        if (signatures == null || signatures.length == 0) {
            return null;
        }
        try {
            return hexOf(MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray()));
        } catch (NoSuchAlgorithmException impossible) {
            return null;
        }
    }

    /** The signing certs of the package (SigningInfo on API>=28, signatures below). */
    private static Signature[] signaturesOf(PackageInfo info) {
        if (info == null) {
            return null;
        }
        if (Build.VERSION.SDK_INT >= 28 && info.signingInfo != null) {
            Signature[] history = info.signingInfo.getSigningCertificateHistory();
            return history != null && history.length > 0 ? history : info.signatures;
        }
        return info.signatures;
    }

    /** Lowercase bytes as uppercase hex. */
    private static String hexOf(byte[] digest) {
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(Character.forDigit((b >> 4) & 0xf, 16));
            hex.append(Character.forDigit(b & 0xf, 16));
        }
        return hex.toString().toUpperCase();
    }

    /** Download byte cap, ~200MB (item 7). */
    protected long maxDownloadBytes() {
        return 200L * 1024 * 1024;
    }

    /**
     * Streams the url into {@code destFile}.part and renames to
     * {@code destFile} on success. Logs progress every ~10% (known length) or
     * every ~2MB. Build 2475, item 7: redirects are never followed
     * ({@code setInstanceFollowRedirects(false)}, plus a getURL() re-check),
     * only a direct HTTP 200 is accepted, sizes above
     * {@link #maxDownloadBytes()} (Content-Length and streamed count) are
     * refused, and the {@code .part} file is deleted on ANY failure.
     */
    protected void downloadHttp(String url, File destFile, ApkDownloader.Progress progress)
            throws IOException {
        File dir = destFile.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        File part = partOf(destFile);
        long cap = maxDownloadBytes();
        URL target = new URL(url);
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) target.openConnection();
            connection.setConnectTimeout(30_000);
            connection.setReadTimeout(30_000);
            connection.setInstanceFollowRedirects(false);
            connection.connect();
            if (!target.equals(connection.getURL())) {
                throw new IOException("redirected to " + connection.getURL()
                        + " (redirects are never followed)");
            }
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code + " (only 200 is accepted): " + url);
            }
            long total = connection.getContentLengthLong();
            if (total > cap) {
                throw new IOException("Content-Length " + total + " exceeds the "
                        + cap + " byte cap: " + url);
            }
            try (InputStream rawIn = new BufferedInputStream(connection.getInputStream());
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(part))) {
                byte[] buffer = new byte[64 * 1024];
                long done = 0;
                long lastLoggedBytes = 0;
                int nextLoggedPercent = PROGRESS_PERCENT_STEP;
                int read;
                while ((read = rawIn.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                    done += read;
                    if (done > cap) {
                        throw new IOException("download exceeds the " + cap
                                + " byte cap (" + done + " bytes): " + url);
                    }
                    int percent = total > 0 ? (int) Math.min(100, done * 100 / total) : 0;
                    boolean percentStep = total > 0 && percent >= nextLoggedPercent;
                    boolean byteStep = done - lastLoggedBytes >= PROGRESS_BYTES_STEP;
                    if (percentStep || byteStep) {
                        if (percentStep) {
                            nextLoggedPercent += PROGRESS_PERCENT_STEP;
                        } else {
                            lastLoggedBytes = done;
                        }
                        JukeboxLog.i(JukeboxUpdate.LOG_TAG, "download progress: " + percent
                                + "% " + done + "/" + (total > 0 ? total : "?") + " bytes");
                        progress.onProgress(percent);
                    }
                }
            }
        } catch (IOException | RuntimeException failure) {
            delete(part);
            throw failure;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        if (!part.renameTo(destFile)) {
            delete(part);
            throw new IOException("cannot rename " + part + " to " + destFile);
        }
    }

    private void launchInstaller(String apkPath) {
        File file = new File(apkPath);
        Uri contentUri = FileProvider.getUriForFile(mAppContext,
                mAppContext.getPackageName() + PROVIDER_AUTHORITY_SUFFIX, file);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(contentUri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        JukeboxLog.i(JukeboxUpdate.LOG_TAG,
                "install: launching the package installer for " + contentUri);
        mAppContext.startActivity(intent);
    }

    /** Runs install work off the main thread. Tests run it inline/deferred. */
    protected void runAsync(Runnable runnable) {
        new Thread(runnable, "jukebox-update-install").start();
    }

    /** Runs card updates on the main thread. Tests run them inline. */
    protected void runOnMain(Runnable runnable) {
        new Handler(Looper.getMainLooper()).post(runnable);
    }

    /** Package archive info of a downloaded apk (I/O seam; tests fake it). */
    protected PackageInfo archiveInfoOf(String path) {
        return mAppContext.getPackageManager().getPackageArchiveInfo(path, 0);
    }

    /** The build (versionCode) of a downloaded apk path, or 0 when unknown. */
    private int buildCodeOf(String apkPath) {
        if (apkPath == null) {
            return 0;
        }
        try {
            PackageInfo info = archiveInfoOf(apkPath);
            return info != null ? (int) info.versionCode : 0;
        } catch (Throwable notAnApk) {
            return 0;
        }
    }
}

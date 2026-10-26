package net.tfour.jukebox.update;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
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
    /** Log/download progress step sizes. */
    private static final long PROGRESS_BYTES_STEP = 2 * 1024 * 1024; // ~2MB
    private static final int PROGRESS_PERCENT_STEP = 10; // ~10%

    /** Supplies the currently resumed activity (where the prompt card attaches). */
    public interface CurrentActivity {
        Activity get();
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
        if (pending == null || !JukeboxForeground.isAppInForeground()) {
            return;
        }
        JukeboxLog.i(JukeboxUpdate.LOG_TAG,
                "foreground: offering the install deferred while the app was backgrounded");
        runAsync(() -> launchOrDefer(pending, false));
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
            JukeboxUpdatePresenter presenter = mPresenter;
            presenter.onUpdateFound(latestVersionName, changelog, downloadUris[0].toString());
        }
    }

    /** Manifest facts of the current offer (called by ManifestChecker). */
    void offerFound(String versionName, int versionCode, String downloadUrl) {
        mOfferedVersionName = versionName;
        mOfferedVersionCode = versionCode;
        mOfferedDownloadUrl = downloadUrl;
    }

    @Override
    public void onUpdateFound(String versionName, List<String> changelog, String apkPath) {
        // apkPath is the manifest's downloadUrl since build 2473 (the upstream
        // cache/update.apk path is never used or even downloaded).
        if (mOfferedDownloadUrl == null && apkPath != null) {
            mOfferedDownloadUrl = apkPath; // direct/test calls without a checker
        }
        int build = mOfferedVersionCode > 0 ? mOfferedVersionCode : buildCodeOf(apkPath);
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

    /**
     * Install chosen (card Install button, card click, or the app-wide OK key
     * routing): downloads the manifest downloadUrl, verifies it, launches the
     * installer — or defers (backgrounded) / shows a retry notice (failure).
     * Only one download at a time. Never pauses playback, never steals focus.
     */
    void install() {
        synchronized (mDownloadLock) {
            if (mDownloading) {
                JukeboxLog.i(JukeboxUpdate.LOG_TAG,
                        "install chosen: a download is already in progress (ignored)");
                return;
            }
            mDownloading = true;
        }
        runAsync(this::installAsync);
    }

    private void installAsync() {
        try {
            String pending = mPendingInstallPath;
            if (pending != null) {
                // A verified apk is already on disk (kept while backgrounded):
                // reuse it, never re-download.
                JukeboxLog.i(JukeboxUpdate.LOG_TAG,
                        "install chosen: using the already downloaded update " + pending);
                launchOrDefer(pending, false);
                return;
            }
            String url = mOfferedDownloadUrl;
            int versionCode = mOfferedVersionCode;
            if (url == null || url.trim().isEmpty() || versionCode <= 0) {
                JukeboxLog.i(JukeboxUpdate.LOG_TAG,
                        "install chosen: no manifest download url/versionCode known");
                runOnMain(() -> cardText("Update download failed \u2014 will retry next time"));
                return;
            }
            File destFile = downloadDest(versionCode);
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
            launchOrDefer(destFile.getPath(), true);
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
    private void launchOrDefer(String path, boolean freshDownload) {
        String bad = verifyFailureReason(path, mOfferedVersionCode);
        if (bad != null) {
            JukeboxLog.i(JukeboxUpdate.LOG_TAG,
                    "verify failed: " + bad + " \u2014 deleting, will retry next time");
            delete(new File(path));
            delete(partOf(new File(path)));
            setPendingInstall(null);
            runOnMain(() -> cardText("Update download failed \u2014 will retry next time"));
            return;
        }
        if (!JukeboxForeground.isAppInForeground()) {
            // Issue #3: never launch anything from the background. Keep the
            // verified file; the next foreground transition offers the install.
            setPendingInstall(path);
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

    /** Atomically records/clears the pending (deferred) install path. */
    private void setPendingInstall(String path) {
        synchronized (mDownloadLock) {
            mPendingInstallPath = path;
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
                return true;
            }
            return false;
        }
    }

    /**
     * @return null when the file verifies as our offered update, else the
     *         logged reason it must never be launched.
     */
    private String verifyFailureReason(String path, int expectedVersionCode) {
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
        int versionCode = (int) info.versionCode;
        JukeboxLog.i(JukeboxUpdate.LOG_TAG, "verify: versionCode=" + versionCode
                + " expected=" + expectedVersionCode);
        if (versionCode != expectedVersionCode) {
            return "wrong versionCode: " + versionCode + " (offered " + expectedVersionCode + ")";
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
        try {
            if (file != null && file.exists() && !file.delete()) {
                file.deleteOnExit();
            }
        } catch (RuntimeException ignored) {
            // Nothing to delete (storage gone): nothing we can do.
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

    // ---- build 2475 review seams (PHASE 1 STUBS: unused, current behavior) ----

    /** The versionCode installed right now (downgrade guard, item 3). Stub. */
    protected int installedVersionCode() {
        return 0;
    }

    /** SHA-256 (hex) of the downloaded apk's signing cert (item 2). Stub. */
    protected String archiveSigningCertSha256(String path) {
        return null;
    }

    /** SHA-256 (hex) of the installed app's signing cert (item 2). Stub. */
    protected String installedSigningCertSha256() {
        return null;
    }

    /** The manifest's optional sha256 for the offered apk, or null when absent (item 2). Stub. */
    protected String manifestApkSha256(String downloadUrl) {
        return null;
    }

    /** SHA-256 (hex) of a file on disk (item 2). Stub. */
    protected String sha256OfFile(File file) {
        return null;
    }

    /** Download byte cap, ~200MB (item 7). Stub (unused yet). */
    protected long maxDownloadBytes() {
        return 200L * 1024 * 1024;
    }

    /**
     * Streams the url into {@code destFile}.part and renames to
     * {@code destFile} on success. Logs progress every ~10% (known length) or
     * every ~2MB.
     */
    protected void downloadHttp(String url, File destFile, ApkDownloader.Progress progress)
            throws IOException {
        File dir = destFile.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        File part = partOf(destFile);
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(30_000);
            connection.setReadTimeout(30_000);
            connection.connect();
            long total = connection.getContentLengthLong();
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

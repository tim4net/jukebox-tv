package net.tfour.jukebox.update;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.shadows.ShadowPackageManager;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Build 2473, issue #5 (bug 2): install must NOT rely on the upstream
 * cache/update.apk (roux2471.s3u2: the installer got a content URI for a file
 * that was never downloaded and died with FileNotFoundException/ENOENT).
 * On install chosen the manifest's own downloadUrl is downloaded to
 * cache/jukebox-update/jukebox-update-&lt;versionCode&gt;.apk (.part then
 * rename), verified (exists / size&gt;0 / packageName / versionCode) and
 * only then launched as ACTION_VIEW with a FileProvider content URI.
 * Failures delete the partial file, log, and show a retry notice on the
 * card. Never launch while the app is backgrounded: keep the file and offer
 * install on the next foreground. Only one download at a time.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class JukeboxUpdateInstallTest {

    private static final String MANIFEST_URL =
            "https://jukebox.tfour.net/install/Jukebox_32.68_arm64-v8a.apk";

    /** Fake downloader: records, writes bytes (or fails / stays empty). */
    private static class FakeDownloader implements JukeboxUpdatePresenter.ApkDownloader {
        int calls;
        String url;
        java.io.File dest;
        IOException failWith;
        boolean writeEmpty;
        boolean touchPart;
        final List<Integer> progress = new ArrayList<>();

        @Override
        public void download(String url, java.io.File dest, Progress progressListener) throws IOException {
            calls++;
            this.url = url;
            this.dest = dest;
            if (failWith != null) {
                if (touchPart) {
                    new FileOutputStream(dest.getPath() + ".part").close();
                }
                throw failWith;
            }
            dest.getParentFile().mkdirs();
            try (OutputStream out = new FileOutputStream(dest)) {
                if (!writeEmpty) {
                    out.write(new byte[8192]); // fake apk payload
                }
            }
            progressListener.onProgress(50);
            progressListener.onProgress(100);
        }
    }

    /** Records launches instead of starting an installer. */
    private static class RecordingLauncher implements JukeboxUpdatePresenter.InstallLauncher {
        int calls;
        String path;

        @Override
        public void launch(String apkPath) {
            calls++;
            path = apkPath;
        }
    }

    /** Presenter with all I/O seams faked (download / verify / launch / threads). */
    private static class TestPresenter extends JukeboxUpdatePresenter {
        final FakeDownloader downloader = new FakeDownloader();
        final RecordingLauncher launcher = new RecordingLauncher();
        PackageInfo archive;
        boolean productionLauncher;
        boolean deferAsync;
        final List<Runnable> deferred = new ArrayList<>();
        // Build 2475 review seams (items 2/3): defaults keep the CURRENT behavior
        // (no cert/sha256/downgrade checks yet), the new tests override them.
        int installedVersion;
        String archiveCertSha256 = PINNED_CERT_SHA256;
        String installedCertSha256 = PINNED_CERT_SHA256;
        // Issue #9, item 2 (review rv91): the production manifestApkSha256
        // runs (its override is GONE); only the manifest fetch is faked.
        String manifestSha256;
        String manifestJson;
        IOException manifestJsonFail;
        String fileSha256;

        TestPresenter(Context context, CurrentActivity current) {
            super(context, current);
        }

        @Override
        protected JukeboxUpdatePresenter.ApkDownloader createDownloader() {
            return downloader;
        }

        @Override
        protected JukeboxUpdatePresenter.InstallLauncher createInstallLauncher() {
            return productionLauncher ? super.createInstallLauncher() : launcher;
        }

        @Override
        protected void runAsync(Runnable runnable) {
            if (deferAsync) {
                deferred.add(runnable);
            } else {
                runnable.run();
            }
        }

        @Override
        protected void runOnMain(Runnable runnable) {
            runnable.run();
        }

        @Override
        protected PackageInfo archiveInfoOf(String path) {
            return archive;
        }

        @Override
        protected int installedVersionCode() {
            return installedVersion;
        }

        @Override
        protected String archiveSigningCertSha256(String path) {
            return archiveCertSha256;
        }

        @Override
        protected String installedSigningCertSha256() {
            return installedCertSha256;
        }

        /**
         * Issue #9, item 2 (review rv91): manifestApkSha256 is NO longer
         * overridden — the production decision (fetch + parse + pin the
         * offered URL) runs against this faked manifest JSON.
         */
        @Override
        protected String downloadManifestJson(String manifestUrl) throws IOException {
            if (manifestJsonFail != null) {
                throw manifestJsonFail;
            }
            if (manifestJson != null) {
                return manifestJson;
            }
            if (manifestSha256 == null) {
                return null; // absent entry = skip the file hash check
            }
            // Shorthand: a manifest pinning the offered URL with that sha256.
            return "{\"package\":{\"downloadUrl\":\"" + MANIFEST_URL
                    + "\",\"apkSha256\":\"" + manifestSha256 + "\"}}";
        }

        @Override
        protected String sha256OfFile(java.io.File file) {
            return fileSha256;
        }
    }

    /** The pinned signing-cert digest (build 2475, item 2; spec value, no spaces). */
    private static final String PINNED_CERT_SHA256 =
            "0DC9138549DDEDF665BC48F1F717B5CF35893943699746ED93657525FDE7C609";

    private Application app;
    private ActivityController<Activity> controller;
    private Activity activity;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        net.tfour.jukebox.room.JukeboxForeground.setTestOverride(true);
        // Nina's 2473 gazebo item (c): Robolectric defaults
        // canRequestPackageInstalls to false; every pre-existing
        // install-success test needs it granted (the (c) tests override
        // it back to false explicitly).
        shadowPm().setCanRequestPackageInstalls(true);
        ShadowLog.clear();
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
    }

    @After
    public void tearDown() {
        net.tfour.jukebox.room.JukeboxForeground.setTestOverride(null);
    }

    /** Drives the manifest result the way production does (via ManifestChecker). */
    private TestPresenter offered(TestPresenter presenter) {
        return offered(presenter, MANIFEST_URL, 2473);
    }

    /** Drives the manifest result with an explicit downloadUrl / versionCode. */
    private TestPresenter offered(TestPresenter presenter, String downloadUrl, int versionCode) {
        JukeboxUpdatePresenter.ManifestChecker checker =
                new JukeboxUpdatePresenter.ManifestChecker(app, presenter);
        checker.onChangelogReceived(false, "32.68", versionCode, Arrays.asList("fix"),
                new Uri[]{Uri.parse(downloadUrl)});
        return presenter;
    }

    private TestPresenter offeredPresenter() {
        return offered(new TestPresenter(app, () -> activity));
    }

    private TestPresenter offeredWithMatchingApk() {
        return offeredWithMatchingApk(MANIFEST_URL);
    }

    private TestPresenter offeredWithMatchingApk(String downloadUrl) {
        TestPresenter presenter = new TestPresenter(app, () -> activity);
        presenter.archive = matchingArchive();
        return offered(presenter, downloadUrl, 2473);
    }

    private PackageInfo matchingArchive() {
        PackageInfo info = new PackageInfo();
        info.packageName = app.getPackageName();
        info.versionCode = 2473;
        return info;
    }

    private java.io.File expectedApk() {
        return new java.io.File(new java.io.File(app.getCacheDir(), "jukebox-update"),
                "jukebox-update-2473.apk");
    }

    private static View cardOf(Activity activity) {
        View decor = activity.getWindow().getDecorView();
        return decor instanceof ViewGroup
                ? ((ViewGroup) decor).findViewWithTag(JukeboxUpdateCard.VIEW_TAG) : null;
    }

    private static String cardText(Activity activity) {
        View card = cardOf(activity);
        if (!(card instanceof ViewGroup)) {
            return "";
        }
        View text = ((ViewGroup) card).getChildAt(0);
        return text instanceof TextView ? ((TextView) text).getText().toString() : "";
    }

    private static boolean logged(String fragment) {
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            if (JukeboxUpdate.LOG_TAG.equals(item.tag) && item.msg != null && item.msg.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void installChosenDownloadsTheManifestUrlToOwnCacheFile() {
        TestPresenter presenter = offeredWithMatchingApk();
        assertEquals("card must name the offered build", "build 2473",
                cardText(activity).contains("build 2473") ? "build 2473" : cardText(activity));

        presenter.install();

        assertEquals("the manifest downloadUrl must be downloaded (not cache/update.apk)",
                MANIFEST_URL, presenter.downloader.url);
        assertEquals("dest must be cache/jukebox-update/jukebox-update-<versionCode>.apk",
                expectedApk(), presenter.downloader.dest);
        assertTrue("the apk file must exist after the download", expectedApk().exists());
        assertTrue("the apk file must not be empty", expectedApk().length() > 0);
        assertTrue(logged("install chosen"));
        assertTrue(logged("download start: url=" + MANIFEST_URL));
        assertTrue(logged("download done"));
        assertTrue(logged("verify"));
    }

    @Test
    public void downloadSuccessLaunchesTheInstallerWithAContentUri() {
        TestPresenter presenter = offeredWithMatchingApk();
        presenter.productionLauncher = true;

        presenter.install();

        Intent intent = Shadows.shadowOf(app).getNextStartedActivity();
        assertNotNull("a verified download must launch the package installer", intent);
        assertEquals(Intent.ACTION_VIEW, intent.getAction());
        assertEquals("application/vnd.android.package-archive", intent.getType());
        assertEquals("the installer must get a FileProvider content URI (never a file:// path)",
                "content", intent.getData().getScheme());
        assertTrue("authority must be <package>.jukeboxupdate: " + intent.getData(),
                intent.getData().getAuthority().endsWith(".jukeboxupdate"));
        assertTrue("must grant read permission on the content URI",
                (intent.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
        assertTrue("must start as a new task",
                (intent.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
    }

    @Test
    public void progressIsShownOnTheCardWhileDownloading() {
        TestPresenter presenter = offeredWithMatchingApk();

        presenter.install();

        assertTrue("the card must show download progress: " + cardText(activity),
                cardText(activity).contains("Downloading update"));
        assertTrue("the card must show a percent: " + cardText(activity),
                cardText(activity).contains("%"));
        assertTrue(logged("download progress"));
    }

    @Test
    public void downloadFailureDeletesThePartialShowsRetryTextAndNeverLaunches() {
        TestPresenter presenter = offeredWithMatchingApk();
        presenter.downloader.failWith = new IOException("connection reset by peer");
        presenter.downloader.touchPart = true;

        presenter.install();

        assertEquals("a failed download must never launch the installer",
                0, presenter.launcher.calls);
        assertFalse("the partial apk must be deleted", expectedApk().exists());
        assertFalse("the .part file must be deleted",
                new java.io.File(expectedApk().getPath() + ".part").exists());
        assertTrue(logged("download failed"));
        assertTrue("the card must tell the user the download failed: " + cardText(activity),
                cardText(activity).contains("Update download failed"));
        assertTrue(cardText(activity).contains("will retry next time"));
    }

    @Test
    public void zeroSizeApkDoesNotLaunchTheInstaller() {
        TestPresenter presenter = offeredWithMatchingApk();
        presenter.downloader.writeEmpty = true;

        presenter.install();

        assertEquals(0, presenter.launcher.calls);
        assertTrue(logged("verify failed"));
        assertTrue(logged("empty"));
        assertFalse("an unverified download must be deleted", expectedApk().exists());
    }

    @Test
    public void wrongPackageApkDoesNotLaunchTheInstaller() {
        TestPresenter presenter = offeredWithMatchingApk();
        PackageInfo evil = new PackageInfo();
        evil.packageName = "com.someone.else";
        evil.versionCode = 2473;
        presenter.archive = evil;

        presenter.install();

        assertEquals("a foreign package must never be launched", 0, presenter.launcher.calls);
        assertTrue(logged("verify failed"));
        assertTrue(logged("wrong package"));
        assertFalse(expectedApk().exists());
    }

    @Test
    public void wrongVersionCodeApkDoesNotLaunchTheInstaller() {
        TestPresenter presenter = offeredWithMatchingApk();
        PackageInfo other = matchingArchive();
        other.versionCode = 9999;
        presenter.archive = other;

        presenter.install();

        assertEquals(0, presenter.launcher.calls);
        assertTrue(logged("verify failed"));
        assertTrue(logged("wrong versionCode"));
        assertFalse(expectedApk().exists());
    }

    @Test
    public void backgroundedCompletionDefersUntilTheNextForeground() {
        TestPresenter presenter = offeredWithMatchingApk();

        // The app went to the background while the download was finishing.
        net.tfour.jukebox.room.JukeboxForeground.setTestOverride(false);
        presenter.install();
        assertEquals("never launch anything while the app is backgrounded",
                0, presenter.launcher.calls);
        assertTrue(logged("install deferred"));
        assertTrue("the verified file must be kept for the deferred install",
                expectedApk().exists());

        // Next foreground: the deferred install completes (no re-download).
        net.tfour.jukebox.room.JukeboxForeground.setTestOverride(true);
        presenter.onAppForeground();
        assertEquals("the deferred install must launch on the next foreground",
                1, presenter.launcher.calls);
        assertEquals(expectedApk().getPath(), presenter.launcher.path);
        assertEquals("the kept file must be reused, not re-downloaded",
                1, presenter.downloader.calls);
    }

    @Test
    public void installWithAPendingDownloadReusesTheFileWithoutRedownloading() {
        TestPresenter presenter = offeredWithMatchingApk();
        net.tfour.jukebox.room.JukeboxForeground.setTestOverride(false);
        presenter.install();
        assertEquals(0, presenter.launcher.calls);
        net.tfour.jukebox.room.JukeboxForeground.setTestOverride(true);

        // User presses OK again (or the router routes OK) before the foreground
        // hook runs: the pending file is used directly, no second download.
        presenter.install();
        assertEquals(1, presenter.launcher.calls);
        assertEquals(1, presenter.downloader.calls);
    }

    @Test
    public void aPendingInstallIsLaunchedExactlyOnceWhenThePressRacesTheForegroundHook() {
        TestPresenter presenter = offeredWithMatchingApk();
        // The download finished while the app was backgrounded: the verified
        // file is kept and the install is deferred.
        net.tfour.jukebox.room.JukeboxForeground.setTestOverride(false);
        presenter.install();
        net.tfour.jukebox.room.JukeboxForeground.setTestOverride(true);

        // The foreground hook fires (capturing the pending install, then
        // scheduling its launch) while the user presses OK at the same
        // moment: both race to launch the same pending file.
        presenter.deferAsync = true;
        presenter.onAppForeground(); // captures the pending install, schedules its launch
        presenter.deferAsync = false;
        presenter.install(); // the press claims the pending file and launches it
        assertEquals("the install press must launch the pending update", 1, presenter.launcher.calls);

        presenter.deferred.get(0).run(); // the foreground hook loses the race
        assertEquals("the pending install must be launched exactly once (press vs foreground hook race)",
                1, presenter.launcher.calls);
        assertTrue("the losing caller must log that it gave up the launch",
                logged("already launched"));
    }

    @Test
    public void onlyOneDownloadAtATime() {
        TestPresenter presenter = offeredWithMatchingApk();
        presenter.deferAsync = true;

        presenter.install();
        presenter.install(); // second press while the first download is in flight

        assertEquals("only one download may be queued", 1, presenter.deferred.size());
        presenter.deferred.get(0).run();
        assertEquals("the second press must not start another download",
                1, presenter.downloader.calls);
        assertTrue(logged("already in progress"));
    }

    @Test
    public void foregroundHookWithoutAPendingInstallDoesNothing() {
        TestPresenter presenter = offeredPresenter();
        presenter.onAppForeground(); // no crash, no launch
        assertEquals(0, presenter.launcher.calls);
        assertNull(Shadows.shadowOf(app).getNextStartedActivity());
    }

    // ---- build 2475, review items 1/2/3/4/6 (phase 1: failing tests only) ----

    // item 1: downloadUrl validation (https://jukebox.tfour.net/ under /apk/ or /install/)

    @Test
    public void plainHttpDownloadUrlIsRefusedWithoutDownloading() {
        TestPresenter presenter = offeredWithMatchingApk("http://jukebox.tfour.net/apk/jukebox-tv.apk");

        presenter.install();

        assertEquals("an http:// download url must never be downloaded", 0, presenter.downloader.calls);
        assertEquals("a refused download must never launch", 0, presenter.launcher.calls);
        assertTrue("the refusal must be logged", logged("invalid download url"));
        assertTrue("the user must see the retry notice: " + cardText(activity),
                cardText(activity).contains("Update download failed"));
    }

    @Test
    public void foreignHostDownloadUrlIsRefusedWithoutDownloading() {
        TestPresenter presenter =
                offeredWithMatchingApk("https://evil.example/apk/jukebox-tv.apk");

        presenter.install();

        assertEquals("a foreign host must never be downloaded", 0, presenter.downloader.calls);
        assertEquals(0, presenter.launcher.calls);
        assertTrue(logged("invalid download url"));
    }

    @Test
    public void downloadUrlPathOutsideApkOrInstallIsRefused() {
        TestPresenter presenter =
                offeredWithMatchingApk("https://jukebox.tfour.net/downloads/jukebox-tv.apk");

        presenter.install();

        assertEquals("a path outside /apk/ or /install/ must never be downloaded",
                0, presenter.downloader.calls);
        assertEquals(0, presenter.launcher.calls);
        assertTrue(logged("invalid download url"));
    }

    @Test
    public void jukeboxApkAndInstallUrlsAreDownloaded() {
        // Guard (must stay green): our own host + /apk/ and /install/ paths are fine.
        TestPresenter apk = offeredWithMatchingApk("https://jukebox.tfour.net/apk/jukebox-tv-2474-test.apk");
        apk.install();
        assertEquals("an /apk/ url on our host must be downloaded", 1, apk.downloader.calls);

        TestPresenter install =
                offeredWithMatchingApk("https://jukebox.tfour.net/install/jukebox-tv.apk");
        install.install();
        assertEquals("an /install/ url on our host must be downloaded", 1, install.downloader.calls);
    }

    // Issue #9, item 3: only the https default port (-1 / 443) is ever fetched.

    @Test
    public void explicitNonHttpsPortIsRefusedWithoutDownloading() {
        TestPresenter presenter =
                offeredWithMatchingApk("https://jukebox.tfour.net:8443/apk/jukebox-tv.apk");

        presenter.install();

        assertEquals("an explicit port other than 443 must never be downloaded",
                0, presenter.downloader.calls);
        assertEquals("a refused download must never launch", 0, presenter.launcher.calls);
        assertTrue("the refusal must be logged", logged("invalid download url"));
    }

    @Test
    public void explicitPort443IsStillDownloaded() {
        // Guard (must stay green): 443 is the https default — explicit is fine.
        TestPresenter presenter =
                offeredWithMatchingApk("https://jukebox.tfour.net:443/install/jukebox-tv.apk");

        presenter.install();

        assertEquals("explicit 443 must be downloaded like the implicit default",
                1, presenter.downloader.calls);
        assertEquals(1, presenter.launcher.calls);
    }

    // item 2: signer cert + manifest sha256 verification

    @Test
    public void wrongSignerCertRefusesLaunchAndDeletesTheApk() {
        TestPresenter presenter = offeredWithMatchingApk();
        // The downloaded apk is signed by someone else (not the pinned digest).
        presenter.archiveCertSha256 =
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        presenter.installedCertSha256 = PINNED_CERT_SHA256;

        presenter.install();

        assertEquals("an apk signed by someone else must never launch", 0, presenter.launcher.calls);
        assertFalse("a wrongly signed apk must be deleted", expectedApk().exists());
        assertTrue(logged("wrong signer"));
    }

    @Test
    public void signerCertNotMatchingTheInstalledAppRefusesLaunch() {
        TestPresenter presenter = offeredWithMatchingApk();
        // The archive cert matches the pin, but the installed app was signed
        // differently: the download must still be refused.
        presenter.archiveCertSha256 = PINNED_CERT_SHA256;
        presenter.installedCertSha256 =
                "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB";

        presenter.install();

        assertEquals(0, presenter.launcher.calls);
        assertFalse(expectedApk().exists());
        assertTrue(logged("wrong signer"));
    }

    @Test
    public void matchingPinnedSignerCertLaunches() {
        // Guard (must stay green): the pinned cert on both sides installs.
        TestPresenter presenter = offeredWithMatchingApk();
        presenter.archiveCertSha256 = PINNED_CERT_SHA256;
        presenter.installedCertSha256 = PINNED_CERT_SHA256;

        presenter.install();

        assertEquals(1, presenter.launcher.calls);
    }

    @Test
    public void manifestSha256MismatchRefusesLaunch() {
        TestPresenter presenter = offeredWithMatchingApk();
        presenter.manifestSha256 =
                "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC";
        presenter.fileSha256 =
                "DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD";

        presenter.install();

        assertEquals("a file hashing differently than the manifest says must never launch",
                0, presenter.launcher.calls);
        assertFalse(expectedApk().exists());
        assertTrue(logged("sha256"));
    }

    @Test
    public void matchingManifestSha256Launches() {
        // Guard (must stay green): the file hashes exactly as the manifest says.
        TestPresenter presenter = offeredWithMatchingApk();
        presenter.manifestSha256 = "ABC";
        presenter.fileSha256 = "ABC";

        presenter.install();

        assertEquals(1, presenter.launcher.calls);
    }

    @Test
    public void absentManifestSha256SkipsTheFileHashCheck() {
        // Guard (must stay green): no manifest sha256 entry = nothing to check.
        TestPresenter presenter = offeredWithMatchingApk();
        presenter.manifestSha256 = null;
        presenter.fileSha256 = null;

        presenter.install();

        assertEquals(1, presenter.launcher.calls);
    }

    // Issue #9, item 2 (review rv91): manifestApkSha256 is WIRED in production —
    // the tests above now drive the REAL production decision through the faked
    // downloadManifestJson seam (its TestPresenter override is gone). The
    // manifest's package entry must pin the OFFERED download URL before its
    // apkSha256 is enforced.

    @Test
    public void manifestSha256PinnedToADifferentDownloadUrlIsIgnored() {
        TestPresenter presenter = offeredWithMatchingApk();
        // The manifest supplies a sha256, but for ANOTHER apk: it must not be
        // applied to our offered download (mismatched pin = no check, launch).
        presenter.manifestJson = "{\"package\":{\"downloadUrl\":"
                + "\"https://jukebox.tfour.net/apk/other-2474.apk\","
                + "\"apkSha256\":\"CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC\"}}";
        presenter.fileSha256 =
                "DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD";

        presenter.install();

        assertEquals("a sha256 pinned to a different URL must be ignored (launch)",
                1, presenter.launcher.calls);
    }

    @Test
    public void unreadableManifestSkipsTheFileHashCheckWithoutCrashing() {
        TestPresenter presenter = offeredWithMatchingApk();
        // The manifest fetch itself blows up: skip the hash check, never crash
        // (the pinned-signer check above still guarded the download).
        presenter.manifestJsonFail = new IOException("connection reset by peer");

        presenter.install();

        assertEquals("an unreadable manifest must not fail the install", 1,
                presenter.launcher.calls);
    }

    @Test
    public void unparsableManifestJsonSkipsTheFileHashCheck() {
        TestPresenter presenter = offeredWithMatchingApk();
        presenter.manifestJson = "this is not the manifest you are looking for";

        presenter.install();

        assertEquals("an unparsable manifest supplies no sha256 (launch)", 1,
                presenter.launcher.calls);
    }

    @Test
    public void manifestSha256FromASoleDownloadUrlListEntryIsHonored() {
        TestPresenter presenter = offeredWithMatchingApk();
        // downloadUrlList (the non-abi form) with the single offered URL:
        // the sha256 is enforced against the downloaded file.
        presenter.manifestJson = "{\"package\":{\"downloadUrlList\":[\"" + MANIFEST_URL
                + "\"],\"apkSha256\":"
                + "\"CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC\"}}";
        presenter.fileSha256 =
                "DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD";

        presenter.install();

        assertEquals("a sole downloadUrlList entry pinning the offered URL must be"
                + " enforced (mismatch refuses)", 0, presenter.launcher.calls);
        assertFalse(expectedApk().exists());
    }

    @Test
    public void ambiguousDownloadUrlListSuppliesNoSha256() {
        // Pure decision: a list with MORE than one URL cannot pin which apk
        // the sha256 belongs to — no sha256, no check.
        String json = "{\"package\":{\"downloadUrlList\":[\"" + MANIFEST_URL
                + "\",\"https://jukebox.tfour.net/apk/x.apk\"],\"apkSha256\":"
                + "\"CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC\"}}";
        assertNull(JukeboxUpdatePresenter.apkSha256OfManifest(json, MANIFEST_URL,
                "arm64-v8a"));
    }

    @Test
    public void pureApkSha256OfManifestDecisions() {
        String sha = "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC";
        String pinning = "{\"package\":{\"downloadUrl\":\"" + MANIFEST_URL
                + "\",\"apkSha256\":\"" + sha + "\"}}";
        assertEquals("the manifest pins the offered URL: its sha256 is used",
                sha, JukeboxUpdatePresenter.apkSha256OfManifest(
                        pinning, MANIFEST_URL, "arm64-v8a"));
        assertNull("no package entry = nothing", JukeboxUpdatePresenter
                .apkSha256OfManifest("{\"versions\":{}}", MANIFEST_URL, null));
        assertNull("no downloadUrl match = nothing", JukeboxUpdatePresenter
                .apkSha256OfManifest(pinning, "https://jukebox.tfour.net/apk/other.apk",
                        "arm64-v8a"));
        assertNull("null manifest = nothing", JukeboxUpdatePresenter
                .apkSha256OfManifest(null, MANIFEST_URL, "arm64-v8a"));
    }

    // item 3: downgrade guard

    @Test
    public void offeredVersionCodeNotNewerThanInstalledIsNeverOfferedOrInstalled() {
        TestPresenter presenter = new TestPresenter(app, () -> activity);
        presenter.installedVersion = 2473;

        // The manifest offers the SAME build we already have (not newer).
        offered(presenter, MANIFEST_URL, 2473);

        assertNull("an equal versionCode must not be offered", cardOf(activity));
        presenter.install();
        assertEquals("a downgrade must never be downloaded", 0, presenter.downloader.calls);
        assertEquals(0, presenter.launcher.calls);
        assertTrue(logged("downgrade"));
    }

    // item 4: prune stale downloads

    @Test
    public void staleDownloadsArePrunedBeforeEachDownloadButTheCurrentOneSurvives() throws IOException {
        TestPresenter presenter = offeredWithMatchingApk();
        java.io.File dir = new java.io.File(app.getCacheDir(), "jukebox-update");
        assertTrue(dir.mkdirs() || dir.isDirectory());
        java.io.File staleApk = new java.io.File(dir, "jukebox-update-1111.apk");
        java.io.File stalePart = new java.io.File(dir, "jukebox-update-2222.apk.part");
        java.io.File currentPart = new java.io.File(dir, "jukebox-update-2473.apk.part");
        writeBytes(staleApk);
        writeBytes(stalePart);
        writeBytes(currentPart);

        presenter.install();

        assertFalse("old jukebox-update-*.apk files must be pruned", staleApk.exists());
        assertFalse("old *.part files must be pruned", stalePart.exists());
        assertTrue("the current pending download's files must survive",
                expectedApk().exists() || currentPart.exists());
    }

    @Test
    public void staleDownloadsArePrunedWhenTheAppComesToTheForeground() throws IOException {
        TestPresenter presenter = offeredWithMatchingApk();
        java.io.File dir = new java.io.File(app.getCacheDir(), "jukebox-update");
        assertTrue(dir.mkdirs() || dir.isDirectory());
        java.io.File staleApk = new java.io.File(dir, "jukebox-update-1111.apk");
        java.io.File stalePart = new java.io.File(dir, "jukebox-update-2222.apk.part");
        writeBytes(staleApk);
        writeBytes(stalePart);

        presenter.onAppForeground();

        assertFalse("old *.apk files must be pruned on foreground", staleApk.exists());
        assertFalse("old *.part files must be pruned on foreground", stalePart.exists());
    }

    private static void writeBytes(java.io.File file) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(new byte[64]);
        }
    }

    // item 6: the immutable Offer captured once

    @Test
    public void thePendingDownloadUsesTheOfferCapturedWhenInstallWasChosen() {
        TestPresenter presenter = offeredWithMatchingApk();
        presenter.deferAsync = true;
        presenter.install(); // queued behind the runAsync seam

        // A NEW offer arrives before the queued install runs: the queued work
        // must use the offer captured at queue time, not the new one.
        presenter.offerFound("33.0", 2474, "https://jukebox.tfour.net/apk/jukebox-tv-2474.apk");
        presenter.deferred.get(0).run();

        assertEquals("the captured offer's url must be downloaded",
                MANIFEST_URL, presenter.downloader.url);
        assertEquals("the captured offer's dest must be used",
                expectedApk().getPath(), presenter.downloader.dest.getPath());
        assertEquals("the captured offer's versionCode must be verified/launched",
                1, presenter.launcher.calls);
    }

    @Test
    public void aDeferredInstallVerifiesAgainstItsOwnOfferNotANewerOne() {
        TestPresenter presenter = offeredWithMatchingApk();
        // The download finishes while the app is backgrounded: the verified file
        // is kept for the next foreground transition.
        net.tfour.jukebox.room.JukeboxForeground.setTestOverride(false);
        presenter.install();
        net.tfour.jukebox.room.JukeboxForeground.setTestOverride(true);

        // A new offer (2474) arrives while the app is backgrounded; the deferred
        // install of the 2473 apk must still verify against ITS OWN offer.
        presenter.offerFound("33.0", 2474, "https://jukebox.tfour.net/apk/jukebox-tv-2474.apk");
        presenter.onAppForeground();

        assertEquals("the deferred 2473 install must still launch", 1, presenter.launcher.calls);
        assertEquals("the kept 2473 file must be launched (not deleted as 'wrong versionCode')",
                expectedApk().getPath(), presenter.launcher.path);
    }

    // ---- Nina's 2473 gazebo item (c): Fire OS blocks installs ("unknown apps") ----

    private ShadowPackageManager shadowPm() {
        return (ShadowPackageManager) Shadows.shadowOf(app.getPackageManager());
    }

    @Test
    public void blockedInstallPermissionOpensSettingsWithThePackageUriAndKeepsTheVerifiedApk() {
        TestPresenter presenter = offeredWithMatchingApk();
        shadowPm().setCanRequestPackageInstalls(false);

        presenter.install();

        assertEquals("an install without the unknown-apps permission must never launch the installer",
                0, presenter.launcher.calls);
        assertTrue("the verified apk must be kept pending until installs are allowed",
                expectedApk().exists());
        Intent settings = Shadows.shadowOf(app).getNextStartedActivity();
        assertNotNull("the user must be taken to the unknown-apps settings", settings);
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, settings.getAction());
        assertEquals("the settings uri must name our package",
                "package:" + app.getPackageName(), settings.getDataString());
        assertTrue("settings must open as a new task",
                (settings.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
        assertTrue("the card must tell the user to allow installs: " + cardText(activity),
                cardText(activity).contains("Allow Jukebox to install updates"));
    }

    @Test
    public void allowedOnTheNextForegroundResumesThePendingInstallWithoutSettingsAgain() {
        TestPresenter presenter = offeredWithMatchingApk();
        shadowPm().setCanRequestPackageInstalls(false);
        presenter.install();
        assertEquals(0, presenter.launcher.calls);
        assertNotNull("the settings intent must have been opened once (consumed here)",
                Shadows.shadowOf(app).getNextStartedActivity());

        // The user allowed installs and came back to the app.
        shadowPm().setCanRequestPackageInstalls(true);
        presenter.onAppForeground();

        assertEquals("the kept pending install must resume once installs are allowed",
                1, presenter.launcher.calls);
        assertEquals("the pending install must launch the kept apk",
                expectedApk().getPath(), presenter.launcher.path);
        assertNull("no settings intent again once the install resumed",
                Shadows.shadowOf(app).getNextStartedActivity());
    }

    @Test
    public void stillDeniedOnTheNextForegroundDoesNotReopenSettings() {
        TestPresenter presenter = offeredWithMatchingApk();
        shadowPm().setCanRequestPackageInstalls(false);
        presenter.install();
        assertEquals(0, presenter.launcher.calls);
        assertNotNull("the settings intent must have been opened once (consumed here)",
                Shadows.shadowOf(app).getNextStartedActivity());

        // Still denied on the next foreground: log and re-offer, but never
        // loop reopening settings.
        presenter.onAppForeground();

        assertEquals("still denied must never launch the installer", 0, presenter.launcher.calls);
        assertNull("settings must not be opened again while still denied",
                Shadows.shadowOf(app).getNextStartedActivity());
        assertTrue("the verified apk must stay pending while still denied",
                expectedApk().exists());
    }
}

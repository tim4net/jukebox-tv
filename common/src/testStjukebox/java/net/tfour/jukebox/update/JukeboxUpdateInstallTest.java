package net.tfour.jukebox.update;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
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
    }

    private Application app;
    private ActivityController<Activity> controller;
    private Activity activity;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        net.tfour.jukebox.room.JukeboxForeground.setTestOverride(true);
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
        JukeboxUpdatePresenter.ManifestChecker checker =
                new JukeboxUpdatePresenter.ManifestChecker(app, presenter);
        checker.onChangelogReceived(false, "32.68", 2473, Arrays.asList("fix"),
                new Uri[]{Uri.parse(MANIFEST_URL)});
        return presenter;
    }

    private TestPresenter offeredPresenter() {
        return offered(new TestPresenter(app, () -> activity));
    }

    private TestPresenter offeredWithMatchingApk() {
        TestPresenter presenter = new TestPresenter(app, () -> activity);
        presenter.archive = matchingArchive();
        return offered(presenter);
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
}

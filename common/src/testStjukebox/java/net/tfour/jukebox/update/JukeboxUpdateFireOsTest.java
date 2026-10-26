package net.tfour.jukebox.update;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
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
import org.robolectric.shadows.ShadowBuild;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.shadows.ShadowPackageManager;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Build 2477, issue #7 (review .rv71.md): Fire OS detection
 * ({@link JukeboxUpdatePresenter#isFireOs(Context)}) and the Fire OS behavior
 * of {@code openUnknownAppSourcesSettings()} / {@code onAppForeground()}.
 *
 * On Fire OS ({@code Build.MANUFACTURER} "Amazon" case-insensitive and/or the
 * {@code amazon.hardware.fire_tv} system feature) the unknown-apps settings
 * intent is a dead end (Device & Software > About, no per-app toggle), so it
 * is NEVER launched there: the card shows {@link #FIRE_OS_CARD_TEXT} instead,
 * the verified apk stays pending, the install is re-offered once installs are
 * allowed, and the still-denied foreground pass re-shows the Fire OS steps
 * (never {@link #ALLOW_INSTALLS_CARD_TEXT}, never settings again). Non-Fire
 * devices are unchanged (guarded by JukeboxUpdateInstallTest / the issue-#5
 * fallback tests).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class JukeboxUpdateFireOsTest {

    private static final String MANIFEST_URL =
            "https://jukebox.tfour.net/install/Jukebox_32.68_arm64-v8a.apk";

    /** Build 2477, issue #7: the on-screen steps shown on Fire OS. */
    private static final String FIRE_OS_CARD_TEXT =
            JukeboxUpdatePresenter.FIRE_OS_CARD_TEXT;
    /** Build 2473 gazebo item (c): the generic (non-Fire) blocked text. */
    private static final String ALLOW_INSTALLS_CARD_TEXT =
            JukeboxUpdatePresenter.ALLOW_INSTALLS_CARD_TEXT;

    /** Fake downloader: records, writes bytes. */
    private static class FakeDownloader implements JukeboxUpdatePresenter.ApkDownloader {
        int calls;
        String url;
        java.io.File dest;

        @Override
        public void download(String url, java.io.File dest, Progress progressListener) throws IOException {
            calls++;
            this.url = url;
            this.dest = dest;
            dest.getParentFile().mkdirs();
            try (OutputStream out = new FileOutputStream(dest)) {
                out.write(new byte[8192]); // fake apk payload
            }
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

        TestPresenter(Context context, CurrentActivity current) {
            super(context, current);
        }

        @Override
        protected JukeboxUpdatePresenter.ApkDownloader createDownloader() {
            return downloader;
        }

        @Override
        protected JukeboxUpdatePresenter.InstallLauncher createInstallLauncher() {
            return launcher;
        }

        @Override
        protected void runAsync(Runnable runnable) {
            runnable.run(); // inline (tests drive everything synchronously)
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
            return 0; // unknown installed build: no downgrade guard
        }

        @Override
        protected String archiveSigningCertSha256(String path) {
            return PINNED_CERT_SHA256;
        }

        @Override
        protected String installedSigningCertSha256() {
            return PINNED_CERT_SHA256;
        }

        @Override
        protected String manifestApkSha256(String downloadUrl) {
            return null; // absent = skip the file hash check
        }

        @Override
        protected String sha256OfFile(java.io.File file) {
            return null;
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
        // Fire OS: installs from unknown apps are blocked (the whole point).
        shadowPm().setCanRequestPackageInstalls(false);
        ShadowLog.clear();
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
    }

    @After
    public void tearDown() {
        net.tfour.jukebox.room.JukeboxForeground.setTestOverride(null);
        // Robolectric resets shadow state per test; be explicit anyway.
        ShadowBuild.reset();
    }

    private ShadowPackageManager shadowPm() {
        return (ShadowPackageManager) Shadows.shadowOf(app.getPackageManager());
    }

    // ---- issue #7: the small testable Fire detection ----

    @Test
    public void amazonManufacturerIsFireOsCaseInsensitively() {
        ShadowBuild.setManufacturer("Amazon");
        assertTrue("'Amazon' must detect as Fire OS",
                JukeboxUpdatePresenter.isFireOs(app));
        ShadowBuild.setManufacturer("amazon");
        assertTrue("'amazon' must detect as Fire OS (case-insensitive)",
                JukeboxUpdatePresenter.isFireOs(app));
        ShadowBuild.setManufacturer("AMAZON");
        assertTrue("'AMAZON' must detect as Fire OS (case-insensitive)",
                JukeboxUpdatePresenter.isFireOs(app));
    }

    @Test
    public void fireTvSystemFeatureAloneIsFireOs() {
        shadowPm().setSystemFeature(JukeboxUpdatePresenter.FIRE_TV_SYSTEM_FEATURE, true);
        assertTrue("the amazon.hardware.fire_tv feature must detect as Fire OS",
                JukeboxUpdatePresenter.isFireOs(app));
    }

    @Test
    public void noManufacturerAndNoFeatureIsNotFireOs() {
        shadowPm().setSystemFeature(JukeboxUpdatePresenter.FIRE_TV_SYSTEM_FEATURE, false);
        assertFalse("a non-Amazon device without the fire_tv feature is not Fire OS",
                JukeboxUpdatePresenter.isFireOs(app));
    }

    @Test
    public void anUnreadablePackageManagerIsNotFireOs() {
        Context blowing = new ContextWrapper(app) {
            @Override
            public PackageManager getPackageManager() {
                throw new RuntimeException("package manager unreadable");
            }
        };
        assertFalse("a PackageManager blowup must be tolerated as not-Fire",
                JukeboxUpdatePresenter.isFireOs(blowing));
    }

    // ---- issue #7: the blocked install on Fire OS never opens the settings ----

    /** Drives the manifest result the way production does (via ManifestChecker). */
    private TestPresenter fireTvPresenter() {
        // A Fire TV device: the system feature is the detection source.
        shadowPm().setSystemFeature(JukeboxUpdatePresenter.FIRE_TV_SYSTEM_FEATURE, true);
        TestPresenter presenter = new TestPresenter(app, () -> activity);
        PackageInfo info = new PackageInfo();
        info.packageName = app.getPackageName();
        info.versionCode = 2473;
        presenter.archive = info;
        JukeboxUpdatePresenter.ManifestChecker checker =
                new JukeboxUpdatePresenter.ManifestChecker(app, presenter);
        checker.onChangelogReceived(false, "32.68", 2473, Arrays.asList("fix"),
                new Uri[]{Uri.parse(MANIFEST_URL)});
        return presenter;
    }

    private java.io.File expectedApk() {
        return new java.io.File(new java.io.File(app.getCacheDir(), "jukebox-update"),
                "jukebox-update-2473.apk");
    }

    private static String cardText(Activity activity) {
        View decor = activity.getWindow().getDecorView();
        View card = decor instanceof ViewGroup
                ? ((ViewGroup) decor).findViewWithTag(JukeboxUpdateCard.VIEW_TAG) : null;
        if (!(card instanceof ViewGroup)) {
            return "";
        }
        View text = ((ViewGroup) card).getChildAt(0);
        return text instanceof TextView ? ((TextView) text).getText().toString() : "";
    }

    @Test
    public void blockedInstallOnFireOsNeverOpensSettingsAndShowsTheOnScreenSteps() {
        TestPresenter presenter = fireTvPresenter();

        presenter.install();

        assertEquals("a blocked install must never launch the installer",
                0, presenter.launcher.calls);
        assertNull("the unknown-apps settings intent must NOT be launched on Fire OS",
                Shadows.shadowOf(app).getNextStartedActivity());
        assertEquals("the card must carry the Fire OS on-screen steps: " + cardText(activity),
                FIRE_OS_CARD_TEXT, cardText(activity));
        assertTrue("the card steps must name the Fire TV path",
                cardText(activity).contains("My Fire TV > Developer options"));
        assertTrue("the verified apk must stay pending while blocked",
                expectedApk().exists());
        assertTrue("the refusal to open settings must be logged",
                logged("Fire OS detected"));
    }

    @Test
    public void stillDeniedOnTheFireOsForegroundShowsTheFireStepsWithoutSettingsAgain() {
        TestPresenter presenter = fireTvPresenter();
        presenter.install();
        assertEquals(0, presenter.launcher.calls);

        // Still denied on the next foreground: re-offer with the Fire OS
        // steps on the card, never the generic allow text, never settings.
        presenter.onAppForeground();

        assertEquals("still denied must never launch the installer",
                0, presenter.launcher.calls);
        assertNull("settings must never be opened on a Fire OS foreground pass",
                Shadows.shadowOf(app).getNextStartedActivity());
        assertEquals("the still-denied card must show the Fire OS steps, not the generic text: "
                        + cardText(activity),
                FIRE_OS_CARD_TEXT, cardText(activity));
        assertFalse("the generic allow-installs text must not be shown on Fire OS",
                ALLOW_INSTALLS_CARD_TEXT.equals(cardText(activity)));
        assertTrue("the verified apk must stay pending while still denied",
                expectedApk().exists());
    }

    @Test
    public void allowedOnTheNextForegroundResumesThePendingFireOsInstall() {
        TestPresenter presenter = fireTvPresenter();
        presenter.install();
        assertEquals(0, presenter.launcher.calls);

        // The user followed the on-screen steps and came back.
        shadowPm().setCanRequestPackageInstalls(true);
        presenter.onAppForeground();

        assertEquals("the kept pending install must resume once allowed",
                1, presenter.launcher.calls);
        assertEquals("the pending install must launch the kept apk",
                expectedApk().getPath(), presenter.launcher.path);
        assertNull("no settings intent once the install resumed",
                Shadows.shadowOf(app).getNextStartedActivity());
    }

    private static boolean logged(String fragment) {
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            if (JukeboxUpdate.LOG_TAG.equals(item.tag) && item.msg != null
                    && item.msg.contains(fragment)) {
                return true;
            }
        }
        return false;
    }
}
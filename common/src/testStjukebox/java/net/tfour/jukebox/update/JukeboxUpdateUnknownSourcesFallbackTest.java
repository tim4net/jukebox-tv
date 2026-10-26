package net.tfour.jukebox.update;

import android.app.Activity;
import android.app.Application;
import android.content.ActivityNotFoundException;
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
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Build 2477 (issue #5), ub-mode, PHASE 1 (failing tests only): Fire OS can
 * throw {@link ActivityNotFoundException} when the unknown-apps settings are
 * opened ({@code ACTION_MANAGE_UNKNOWN_APP_SOURCES} unresolvable). Required:
 * catch it, log under {@code JukeboxUpdate}, fall back to
 * {@code Settings.ACTION_SECURITY_SETTINGS} (NEW_TASK) and show
 * {@link #SECURITY_FALLBACK_CARD_TEXT} on the card; if the fallback ALSO
 * throws, log and show {@link #BOTH_UNRESOLVABLE_CARD_TEXT}, never crash.
 * The pending install file and the resume-on-return behavior stay unchanged.
 *
 * Robolectric never throws for an unresolvable intent (probed), so the tests
 * inject the exception via the {@link JukeboxUpdatePresenter#startActivity}
 * seam instead of the real {@code startActivity}. These tests are RED until
 * the production fallback exists.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class JukeboxUpdateUnknownSourcesFallbackTest {

    private static final String MANIFEST_URL =
            "https://jukebox.tfour.net/install/Jukebox_32.68_arm64-v8a.apk";

    /** Card text while the security-settings fallback is taken (build 2477 spec). */
    private static final String SECURITY_FALLBACK_CARD_TEXT =
            "Turn on 'Apps from Unknown Sources' for Jukebox in Settings > Security, then come back";

    /** Card text when even the fallback settings screen is unresolvable (build 2477 spec). */
    private static final String BOTH_UNRESOLVABLE_CARD_TEXT =
            "Allow installs from unknown sources for Jukebox in Settings, then come back";

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

    /** Presenter with all I/O seams faked, incl. the startActivity seam (Fire OS throws). */
    private static class TestPresenter extends JukeboxUpdatePresenter {
        final FakeDownloader downloader = new FakeDownloader();
        final RecordingLauncher launcher = new RecordingLauncher();
        PackageInfo archive;
        /** Intent actions no activity resolves on this (Fire OS-like) device. */
        final Set<String> unresolvableActions = new HashSet<>();

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

        /** Fire OS seam: unresolvable actions throw, everything else starts. */
        @Override
        protected void startActivity(Intent intent) {
            if (unresolvableActions.contains(intent.getAction())) {
                throw new ActivityNotFoundException(
                        "No Activity found to handle " + intent);
            }
            super.startActivity(intent);
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
        // Fire OS: installs from unknown apps are blocked, and the
        // unknown-apps settings activity itself is unresolvable.
        shadowPm().setCanRequestPackageInstalls(false);
        ShadowLog.clear();
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
    }

    @After
    public void tearDown() {
        net.tfour.jukebox.room.JukeboxForeground.setTestOverride(null);
    }

    private ShadowPackageManager shadowPm() {
        return (ShadowPackageManager) Shadows.shadowOf(app.getPackageManager());
    }

    /** Drives the manifest result the way production does (via ManifestChecker). */
    private TestPresenter offeredWithMatchingApk() {
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

    // ---- PHASE 1: these two are RED until the production fallback exists ----

    @Test
    public void unresolvableManageUnknownAppSourcesFallsBackToSecuritySettingsWithoutCrashing() {
        TestPresenter presenter = offeredWithMatchingApk();
        // Fire OS: ACTION_MANAGE_UNKNOWN_APP_SOURCES resolves to nothing.
        presenter.unresolvableActions.add(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);

        // Reaching the assertions at all IS the no-crash requirement: today
        // (phase 1) the ActivityNotFoundException from the startActivity seam
        // propagates out of install() and fails this test right here.
        presenter.install();

        assertEquals("the blocked install must never launch the installer",
                0, presenter.launcher.calls);
        assertTrue("the pending install must stay pending (file kept)",
                expectedApk().exists());
        Intent started = Shadows.shadowOf(app).getNextStartedActivity();
        assertNotNull("the security settings must be opened as the fallback", started);
        assertEquals("the fallback must be the generic security settings",
                Settings.ACTION_SECURITY_SETTINGS, started.getAction());
        assertTrue("the fallback must open as a new task",
                (started.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
        assertEquals("the card must show the fallback instructions: " + cardText(activity),
                SECURITY_FALLBACK_CARD_TEXT, cardText(activity));
    }

    @Test
    public void unresolvableManageUnknownAppSourcesAndSecuritySettingsShowsFinalTextWithoutCrashing() {
        TestPresenter presenter = offeredWithMatchingApk();
        // Fire OS: neither the unknown-apps NOR the security settings resolve.
        presenter.unresolvableActions.add(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
        presenter.unresolvableActions.add(Settings.ACTION_SECURITY_SETTINGS);

        // Reaching the assertions at all IS the no-crash requirement (phase 1:
        // the first ActivityNotFoundException fails this test right here).
        presenter.install();

        assertEquals("never launch the installer while blocked",
                0, presenter.launcher.calls);
        assertTrue("the pending install must stay pending (file kept)",
                expectedApk().exists());
        assertNull("no settings screen can be opened when both are unresolvable",
                Shadows.shadowOf(app).getNextStartedActivity());
        assertEquals("the card must show the final fallback instructions: " + cardText(activity),
                BOTH_UNRESOLVABLE_CARD_TEXT, cardText(activity));
    }
}
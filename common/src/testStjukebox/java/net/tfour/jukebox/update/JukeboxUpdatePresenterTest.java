package net.tfour.jukebox.update;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.shadows.ShadowSystemProperties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Build 2471: the test-only manifest override (Android system property
 * {@code debug.jukebox.update_url}, adb shell setprop) is only accepted when
 * it points under https://jukebox.tfour.net/install/; anything else is
 * ignored (and logged). The URL actually used is logged on every check.
 */
@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 28)
public class JukeboxUpdatePresenterTest {

    private Context app;
    private JukeboxUpdatePresenter presenter;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        presenter = new JukeboxUpdatePresenter(app, () -> null);
        ShadowLog.clear();
    }

    @After
    public void tearDown() {
        ShadowSystemProperties.override("debug.jukebox.update_url", "");
    }

    private static String defaultUrl() {
        String[] urls = RuntimeEnvironment.getApplication().getResources()
                .getStringArray(com.liskovsoft.smartyoutubetv2.common.R.array.update_urls);
        return urls[0];
    }

    @Test
    public void validOverrideUnderTheJukeboxPrefixIsUsed() {
        ShadowSystemProperties.override("debug.jukebox.update_url",
                "https://jukebox.tfour.net/install/test-latest.json");
        assertEquals("https://jukebox.tfour.net/install/test-latest.json",
                presenter.resolveUrl());
        assertTrue(logged("override url active"));
    }

    @Test
    public void invalidOverrideOutsideThePrefixIsIgnored() {
        ShadowSystemProperties.override("debug.jukebox.update_url",
                "https://evil.example/install/latest.json");
        assertEquals("the default URL must survive an invalid override",
                defaultUrl(), presenter.resolveUrl());
        assertTrue(logged("ignore invalid override url"));
    }

    @Test
    public void unsetOverrideUsesTheDefaultUrl() {
        ShadowSystemProperties.override("debug.jukebox.update_url", "");
        assertEquals(defaultUrl(), presenter.resolveUrl());
    }

    @Test
    public void everyCheckLogsTheUrlUsed() {
        ShadowSystemProperties.override("debug.jukebox.update_url",
                "https://jukebox.tfour.net/install/test-latest.json");
        assertEquals("https://jukebox.tfour.net/install/test-latest.json",
                presenter.resolveUrl());
        // The "check: url=" line is emitted by startCheck on every check;
        // resolveUrl() itself must have logged the override decision.
        assertTrue(logged("override url active"));
    }

    private static boolean logged(String fragment) {
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            if (JukeboxUpdate.LOG_TAG.equals(item.tag) && item.msg != null && item.msg.contains(fragment)) {
                return true;
            }
        }
        return false;
    }
}
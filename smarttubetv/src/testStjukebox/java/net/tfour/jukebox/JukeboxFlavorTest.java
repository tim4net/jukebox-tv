package net.tfour.jukebox;

import com.liskovsoft.smartyoutubetv2.tv.BuildConfig;
import com.liskovsoft.smartyoutubetv2.tv.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;

/**
 * Jukebox flavor sanity checks. Lives in src/testStjukebox so it only runs
 * for the stjukebox flavor (task :smarttubetv:testStjukeboxDebugUnitTest).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class JukeboxFlavorTest {
    @Test
    public void applicationIdIsJukebox() {
        assertEquals("net.tfour.jukebox", BuildConfig.APPLICATION_ID);
    }

    @Test
    public void appNameIsJukeboxTv() {
        assertEquals("Jukebox TV", RuntimeEnvironment.getApplication().getString(R.string.app_name));
    }

    @Test
    public void versionCodeIsTheFlavorOverride() throws Exception {
        // Build 2469: the stjukebox flavor carries its own versionCode on top
        // of defaultConfig's 2464, so other flavors stay unaffected.
        assertEquals(2469, RuntimeEnvironment.getApplication().getPackageManager()
                .getPackageInfo(RuntimeEnvironment.getApplication().getPackageName(), 0).versionCode);
    }
}

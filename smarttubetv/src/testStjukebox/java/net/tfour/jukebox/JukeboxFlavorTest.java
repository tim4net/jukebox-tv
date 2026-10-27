package net.tfour.jukebox;

import com.liskovsoft.smartyoutubetv2.tv.BuildConfig;
import com.liskovsoft.smartyoutubetv2.tv.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

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
        // Build 2477: the stjukebox flavor carries its own versionCode on top
        // of defaultConfig's 2464, so other flavors stay unaffected.
        assertEquals(2488, RuntimeEnvironment.getApplication().getPackageManager()
                .getPackageInfo(RuntimeEnvironment.getApplication().getPackageName(), 0).versionCode);
    }

    @Test
    public void jukeboxUpdateFileProviderIsDeclared() {
        // Build 2473: the self-update installer hands the downloaded apk to the
        // package installer via content://<package>.jukeboxupdate (FLAG_GRANT_
        // READ_URI_PERMISSION). A missing provider would break every update install.
        android.content.pm.ProviderInfo provider = RuntimeEnvironment.getApplication()
                .getPackageManager()
                .resolveContentProvider(BuildConfig.APPLICATION_ID + ".jukeboxupdate", 0);
        assertNotNull("the ${applicationId}.jukeboxupdate FileProvider must be merged in",
                provider);
        assertFalse("the update apk provider must not be exported", provider.exported);
    }
}

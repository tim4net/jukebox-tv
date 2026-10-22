package net.tfour.jukebox;

import com.liskovsoft.smartyoutubetv2.common.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * The stjukebox flavor must point the built-in self-updater at our own
 * manifest only (no upstream SmartTube URLs). Lives in src/testStjukebox so
 * it only runs for the stjukebox flavor (task :common:testStjukeboxDebugUnitTest).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class UpdateUrlsTest {
    @Test
    public void updateUrlsContainOnlyJukeboxManifest() {
        List<String> urls = Arrays.asList(
                RuntimeEnvironment.getApplication().getResources().getStringArray(R.array.update_urls));

        assertEquals(1, urls.size());
        assertEquals("https://jukebox.tfour.net/install/latest.json", urls.get(0));
    }
}

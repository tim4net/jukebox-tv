package com.liskovsoft.smartyoutubetv2.common.utils;

import android.content.Intent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Upstream smoke test for playback URL handling: how youtube watch/share/shorts
 * links are turned into a video id before the player starts (IntentExtractor is
 * the helper used by the app when handling ACTION_VIEW/ACTION_SEND intents).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class IntentExtractorTest {
    private static final String VIDEO_ID = "dQw4w9WgXcQ";

    private static Intent view(String url) {
        return new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
    }

    @Test
    public void watchUrlWithVParam() {
        assertEquals(VIDEO_ID, IntentExtractor.extractVideoId(view("https://www.youtube.com/watch?v=" + VIDEO_ID)));
    }

    @Test
    public void watchUrlWithVParamAndExtraParams() {
        assertEquals(VIDEO_ID, IntentExtractor.extractVideoId(view("https://www.youtube.com/watch?v=" + VIDEO_ID + "&t=42s")));
    }

    @Test
    public void mobileWatchUrl() {
        assertEquals(VIDEO_ID, IntentExtractor.extractVideoId(view("https://m.youtube.com/watch?v=" + VIDEO_ID)));
    }

    @Test
    public void youtuBeShareLink() {
        assertEquals(VIDEO_ID, IntentExtractor.extractVideoId(view("https://youtu.be/" + VIDEO_ID)));
    }

    @Test
    public void shortsLink() {
        assertEquals(VIDEO_ID, IntentExtractor.extractVideoId(view("https://www.youtube.com/shorts/" + VIDEO_ID)));
    }

    @Test
    public void vndYoutubeScheme() {
        assertEquals(VIDEO_ID, IntentExtractor.extractVideoId(view("vnd.youtube://" + VIDEO_ID)));
    }

    @Test
    public void watchVideosListUsesFirstId() {
        assertEquals(VIDEO_ID, IntentExtractor.extractVideoId(view("https://youtube.com/watch_videos?video_ids=" + VIDEO_ID + ",aaaaaaaaaaa")));
    }

    @Test
    public void actionSendWithUrlInExtraText() {
        // ACTION_SEND shares arrive as EXTRA_TEXT (no intent data); upstream
        // parses the text itself as the url
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.putExtra(Intent.EXTRA_TEXT, "https://www.youtube.com/watch?v=" + VIDEO_ID);

        assertEquals(VIDEO_ID, IntentExtractor.extractVideoId(intent));
    }

    @Test
    public void nonVideoUrlsReturnNull() {
        assertNull(IntentExtractor.extractVideoId(view("https://www.youtube.com/results?search_query=twice")));
        assertNull(IntentExtractor.extractVideoId(view("https://www.youtube.com/channel/UCtDjOV5nk982w35AIdVDuNw")));
        assertNull(IntentExtractor.extractVideoId(view("https://www.youtube.com/playlist?list=RDCLAK5uy_mk6AmqcHgCRhyJuYsQz5CCVdCF4SRGivs")));
        assertNull(IntentExtractor.extractVideoId(null));
    }

    @Test
    public void searchQueryIsExtracted() {
        assertEquals("twice", IntentExtractor.extractSearchText(view("https://www.youtube.com/results?search_query=twice")));
    }
}

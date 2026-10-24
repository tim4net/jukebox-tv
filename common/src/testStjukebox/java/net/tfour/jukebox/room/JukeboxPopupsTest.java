package net.tfour.jukebox.room;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/** Pop-Up Video bubbles: /api/popups payload parsing, popups host setting and timing selection. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class JukeboxPopupsTest {
    private static final String V1 = "dQw4w9WgXcQ";

    @Test
    public void parsesAndSortsBubblesSkippingInvalidEntries() {
        List<JukeboxProtocol.Bubble> bubbles = JukeboxProtocol.parsePopups(
                "{\"videoId\":\"" + V1 + "\",\"bubbles\":["
                        + "{\"atSec\":20.5,\"text\":\"Later\"},"
                        + "{\"atSec\":10,\"text\":\"First\"},"
                        + "{\"text\":\"no time\"},"              // missing atSec
                        + "{\"atSec\":5,\"text\":\"\"},"         // empty text
                        + "{\"atSec\":-3,\"text\":\"negative\"}," // negative atSec
                        + "\"garbage\","                         // not an object
                        + "{\"atSec\":true,\"text\":\"bool\"}"   // atSec not a number
                        + "]}");
        assertNotNull(bubbles);
        assertEquals(2, bubbles.size());
        assertEquals(10.0, bubbles.get(0).atSec, 0.0);
        assertEquals("First", bubbles.get(0).text);
        assertEquals(20.5, bubbles.get(1).atSec, 0.0);
    }

    @Test
    public void malformedOrEmptyPopupsPayloadsSilentlyNoOp() {
        assertNull(JukeboxProtocol.parsePopups(null));
        assertNull(JukeboxProtocol.parsePopups(""));
        assertNull(JukeboxProtocol.parsePopups("not json"));
        assertNull(JukeboxProtocol.parsePopups("{\"error\":\"not found\"}")); // 404-ish body
        assertNotNull(JukeboxProtocol.parsePopups("{\"videoId\":\"" + V1 + "\",\"bubbles\":[]}"));
        assertTrue(JukeboxProtocol.parsePopups("{\"videoId\":\"" + V1 + "\",\"bubbles\":[]}").isEmpty());
    }

    @Test
    public void popupsFlagIsStrictBooleanWithDefaultOff() {
        assertTrue(JukeboxProtocol.parse(
                "{\"t\":\"state\",\"room\":\"r\",\"popups\":true,\"listeners\":0}").isPopupsEnabled());
        assertTrue(JukeboxProtocol.parse(
                "{\"t\":\"state\",\"room\":\"r\",\"settings\":{\"popups\":true}}").isPopupsEnabled());
        // Missing, false or non-boolean values => OFF.
        assertFalse(JukeboxProtocol.parse(
                "{\"t\":\"state\",\"room\":\"r\",\"queue\":[]}").isPopupsEnabled());
        assertFalse(JukeboxProtocol.parse(
                "{\"t\":\"state\",\"room\":\"r\",\"popups\":false}").isPopupsEnabled());
        assertFalse(JukeboxProtocol.parse(
                "{\"t\":\"state\",\"room\":\"r\",\"popups\":\"true\"}").isPopupsEnabled());
        assertFalse(JukeboxProtocol.parse(
                "{\"t\":\"state\",\"room\":\"r\",\"popups\":1}").isPopupsEnabled());
        assertFalse(JukeboxProtocol.parse(
                "{\"t\":\"state\",\"room\":\"r\",\"settings\":{\"popups\":\"true\"}}").isPopupsEnabled());
        assertFalse(JukeboxProtocol.parse("not json").isPopupsEnabled());
    }

    @Test
    public void selectBubbleUsesSixSecondWindow() {
        List<JukeboxProtocol.Bubble> bubbles = JukeboxProtocol.parsePopups(
                "{\"videoId\":\"" + V1 + "\",\"bubbles\":[{\"atSec\":10,\"text\":\"a\"},{\"atSec\":20,\"text\":\"b\"}]}");
        Set<Integer> shown = new HashSet<>();
        assertEquals(-1, JukeboxProtocol.selectBubble(bubbles, 0, shown));      // before first
        assertEquals(-1, JukeboxProtocol.selectBubble(bubbles, 9_999, shown)); // 1ms early
        assertEquals(0, JukeboxProtocol.selectBubble(bubbles, 10_000, shown)); // [atSec, atSec+6s)
        assertEquals(0, JukeboxProtocol.selectBubble(bubbles, 15_999, shown));
        assertEquals(-1, JukeboxProtocol.selectBubble(bubbles, 16_000, shown)); // window over
        assertEquals(1, JukeboxProtocol.selectBubble(bubbles, 25_000, shown));  // second bubble
        assertEquals(-1, JukeboxProtocol.selectBubble(bubbles, 26_000, shown)); // both over
    }

    @Test
    public void eachBubbleShowsOncePerSong() {
        List<JukeboxProtocol.Bubble> bubbles = JukeboxProtocol.parsePopups(
                "{\"videoId\":\"" + V1 + "\",\"bubbles\":[{\"atSec\":10,\"text\":\"a\"}]}");
        Set<Integer> shown = new HashSet<>();
        assertEquals(0, JukeboxProtocol.selectBubble(bubbles, 12_000, shown));
        shown.add(0); // mark as shown
        assertEquals(-1, JukeboxProtocol.selectBubble(bubbles, 12_000, shown)); // not again
        assertEquals(-1, JukeboxProtocol.selectBubble(bubbles, 11_000, shown));
    }

    @Test
    public void fractionalSecondsAreRespected() {
        List<JukeboxProtocol.Bubble> bubbles = JukeboxProtocol.parsePopups(
                "{\"videoId\":\"" + V1 + "\",\"bubbles\":[{\"atSec\":10.5,\"text\":\"a\"}]}");
        Set<Integer> shown = new HashSet<>();
        assertEquals(-1, JukeboxProtocol.selectBubble(bubbles, 10_499, shown));
        assertEquals(0, JukeboxProtocol.selectBubble(bubbles, 10_500, shown));
        assertTrue(JukeboxProtocol.isBubbleActive(bubbles.get(0), 16_499));
        assertFalse(JukeboxProtocol.isBubbleActive(bubbles.get(0), 16_500));
    }

    @Test
    public void selectionGuards() {
        assertEquals(-1, JukeboxProtocol.selectBubble(null, 1000, null));
        assertEquals(-1, JukeboxProtocol.selectBubble(Collections.<JukeboxProtocol.Bubble>emptyList(), 1000, null));
        List<JukeboxProtocol.Bubble> bubbles = JukeboxProtocol.parsePopups(
                "{\"videoId\":\"" + V1 + "\",\"bubbles\":[{\"atSec\":10,\"text\":\"a\"}]}");
        assertEquals(-1, JukeboxProtocol.selectBubble(bubbles, -1, new HashSet<Integer>())); // no position yet
    }
}

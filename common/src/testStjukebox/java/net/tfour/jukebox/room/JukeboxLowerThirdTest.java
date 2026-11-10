package net.tfour.jukebox.room;

import android.app.Activity;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Lower-third panel: one panel per activity (a second show replaces the first),
 * the cards run in sequence, and the panel removes itself once the last card's
 * duration has elapsed on the (shadow) main looper.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class JukeboxLowerThirdTest {
    private Activity activity;

    @Before
    public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
    }

    @After
    public void tearDown() {
        // Leave no panel or pending main-looper callbacks behind.
        JukeboxLowerThird.dismiss(activity);
        ShadowLooper shadowMain = Shadows.shadowOf(Looper.getMainLooper());
        shadowMain.idleFor(30, TimeUnit.SECONDS);
        shadowMain.runToEndOfTasks();
    }

    @Test
    public void showAddsExactlyOnePanelWithTheCardText() {
        JukeboxLowerThird.show(activity, cards("Sarah", "happy birthday!", "Recorded in one take."));
        assertEquals(1, panelCount());
        JukeboxLowerThird panel = panelOf();
        assertNotNull(panel);
        assertEquals("For Sarah", panel.getTitleText().toString());
        assertEquals("happy birthday!", panel.getBodyText().toString());
    }

    @Test
    public void secondShowReplacesTheFirstStillOnePanel() {
        JukeboxLowerThird.show(activity, cards("Sarah", "happy birthday!", "Recorded in one take."));
        shadowMain().idleFor(3, TimeUnit.SECONDS); // mid first card

        JukeboxLowerThird.show(activity, cards("Alex", null, "B-side hit."));
        assertEquals("a second show must not add a second panel", 1, panelCount());
        JukeboxLowerThird panel = panelOf();
        assertNotNull(panel);
        assertEquals("For Alex", panel.getTitleText().toString());
        assertEquals("a title-only card has no second line", "", panel.getBodyText().toString());

        // The new sequence runs from its start: card 0 for 10s, then the note card.
        shadowMain().idleFor(11, TimeUnit.SECONDS);
        assertEquals("Now playing", panel.getTitleText().toString());
        assertEquals("B-side hit.", panel.getBodyText().toString());
    }

    @Test
    public void panelRemovesItselfAfterTheLastCardDuration() {
        JukeboxLowerThird.show(activity, cards("Sarah", "happy birthday!", "Recorded in one take."));
        assertNotNull(panelOf());
        shadowMain().idleFor(5, TimeUnit.SECONDS); // mid first card: still there
        assertNotNull(panelOf());
        shadowMain().idleFor(14, TimeUnit.SECONDS); // past 10s + 8s
        assertNull("the panel must be gone once the last card elapsed", panelOf());
    }

    @Test
    public void dismissRemovesThePanelAndCancelsPendingCallbacks() {
        JukeboxLowerThird.show(activity, cards("Sarah", "happy birthday!", "Recorded in one take."));
        assertNotNull(panelOf());
        shadowMain().idleFor(4, TimeUnit.SECONDS);

        JukeboxLowerThird.dismiss(activity);
        assertNull(panelOf());
        // Advancing past all durations must not resurrect or re-add anything.
        shadowMain().idleFor(30, TimeUnit.SECONDS);
        assertNull(panelOf());
    }

    @Test
    public void showWithNoCardsLeavesNoPanel() {
        JukeboxLowerThird.show(activity, JukeboxLowerThirdPlan.plan("s1", null, null, null));
        assertNull(panelOf());
    }

    private static List<JukeboxLowerThirdPlan.Card> cards(String to, String message, String note) {
        return JukeboxLowerThirdPlan.plan("s1", to, message, note);
    }

    private static ShadowLooper shadowMain() {
        return Shadows.shadowOf(Looper.getMainLooper());
    }

    private FrameLayout decor() {
        return (FrameLayout) activity.getWindow().getDecorView();
    }

    private int panelCount() {
        int count = 0;
        FrameLayout frame = decor();
        for (int i = 0; i < frame.getChildCount(); i++) {
            if (JukeboxLowerThird.TAG.equals(frame.getChildAt(i).getTag())) {
                count++;
            }
        }
        return count;
    }

    private JukeboxLowerThird panelOf() {
        View view = decor().findViewWithTag(JukeboxLowerThird.TAG);
        return view instanceof JukeboxLowerThird ? (JukeboxLowerThird) view : null;
    }
}

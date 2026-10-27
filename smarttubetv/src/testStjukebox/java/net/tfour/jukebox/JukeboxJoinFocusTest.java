package net.tfour.jukebox;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.liskovsoft.smartyoutubetv2.tv.R;

import net.tfour.jukebox.room.JukeboxRoom;
import net.tfour.jukebox.room.JukeboxRoomClient;

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

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Join screen default focus (Tim): an idle room focuses BROWSE YOUTUBE
 * (R.id.jukebox_browse) on create and again on resume. The room code
 * (R.id.jukebox_code) is not focusable and never gets default focus.
 * {@link JukeboxJoinActivity#defaultFocusId(boolean)} is Browse when idle
 * and the QR join action when a room song is playing.
 *
 * Resume while a song stays playing restores that QR default unless Browse
 * already has focus. A playing room that goes idle, then resumes, focuses
 * Browse again.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w960dp-h540dp-land-television-notouch")
public class JukeboxJoinFocusTest {
    private static final String VIDEO = "dQw4w9WgXcQ";

    @Before
    public void setUp() throws Exception {
        resetRoom();
    }

    @After
    public void tearDown() throws Exception {
        // Stop the room, drain anything it posted, then drop the singleton so a
        // later test's idle() cannot observe a room this screen started.
        Shadows.shadowOf(org.robolectric.RuntimeEnvironment.getApplication().getMainLooper()).idle();
        JukeboxRoom room = JukeboxRoom.instance(
                org.robolectric.RuntimeEnvironment.getApplication());
        room.stop();
        Shadows.shadowOf(org.robolectric.RuntimeEnvironment.getApplication().getMainLooper()).idle();
        resetRoom();
    }

    private static void resetRoom() throws Exception {
        Field field = JukeboxRoom.class.getDeclaredField("sInstance");
        field.setAccessible(true);
        field.set(null, null);
    }

    @Test
    public void idleRoomFocusesBrowseOnCreateNotRoomCode() {
        ActivityController<JukeboxJoinActivity> ac =
                Robolectric.buildActivity(JukeboxJoinActivity.class).setup();
        JukeboxJoinActivity a = ac.get();
        Shadows.shadowOf(a.getMainLooper()).idle();

        View code = a.findViewById(R.id.jukebox_code);
        View browse = a.findViewById(R.id.jukebox_browse);
        assertFalse("the room code is not a focus target", code.isFocusable());
        assertFalse("the room code is not focusable in touch mode", code.isFocusableInTouchMode());
        assertFalse("the room code is not the layout default", code.isFocusedByDefault());
        assertTrue("Browse is the layout's default focus", browse.isFocusedByDefault());
        View focused = a.getWindow().getDecorView().findFocus();
        assertSame("idle create must focus BROWSE YOUTUBE, not " + name(focused)
                        + " (codeFocused=" + code.isFocused()
                        + " browseFocused=" + browse.isFocused() + ")",
                browse, focused);
        assertFalse("the room code must not own the default focus", code.isFocused());
        ac.pause().stop().destroy();
    }

    @Test
    public void playingRoomGoesIdleThenResumeRestoresBrowse() throws Exception {
        // Room playing, focus moved off Browse onto the QR, then the room goes
        // idle. Pause and resume must leave BROWSE YOUTUBE focused.
        HoldingClient client = installHoldingRoom();
        ActivityController<JukeboxJoinActivity> ac =
                Robolectric.buildActivity(JukeboxJoinActivity.class).setup();
        JukeboxJoinActivity a = ac.get();
        Shadows.shadowOf(a.getMainLooper()).idle();
        layoutLikeTheTv(a);

        View code = a.findViewById(R.id.jukebox_code);
        View browse = a.findViewById(R.id.jukebox_browse);
        View qr = a.findViewById(R.id.jukebox_qr);
        client.videoId = VIDEO;
        a.bind(null, null, null, "Song");
        assertTrue("a playing room moves focus off Browse onto the QR",
                qr.isFocusable() && qr.hasFocus());
        assertFalse(browse.hasFocus());

        client.videoId = null;
        a.bind(null, null, null, null);
        // Idle again, but focus is not on Browse. Resume is what puts it back.
        TextView other = new TextView(a);
        other.setFocusable(true);
        other.setText("other");
        ViewGroup content = a.findViewById(android.R.id.content);
        content.addView(other, new ViewGroup.LayoutParams(48, 48));
        layoutLikeTheTv(a);
        assertTrue(other.requestFocus() && other.hasFocus());
        assertFalse(browse.hasFocus());
        ac.pause();
        ac.resume();
        Shadows.shadowOf(a.getMainLooper()).idle();

        View focused = a.getWindow().getDecorView().findFocus();
        assertSame("after the room goes idle, resume must focus BROWSE YOUTUBE, not "
                        + name(focused),
                browse, focused);
        assertFalse("the room code must not take focus across that resume", code.isFocused());
        assertFalse(qr.hasFocus());
        ac.pause().stop().destroy();
    }

    @Test
    public void playingRoomResumeKeepsFocusOnBrowse() throws Exception {
        HoldingClient client = installHoldingRoom();
        ActivityController<JukeboxJoinActivity> ac =
                Robolectric.buildActivity(JukeboxJoinActivity.class).setup();
        JukeboxJoinActivity a = ac.get();
        Shadows.shadowOf(a.getMainLooper()).idle();
        layoutLikeTheTv(a);

        View browse = a.findViewById(R.id.jukebox_browse);
        View qr = a.findViewById(R.id.jukebox_qr);
        client.videoId = VIDEO;
        a.bind(null, null, null, "Song");
        assertTrue(qr.isFocusable());
        assertTrue("the user moved to Browse while the room stayed playing",
                browse.requestFocus() && browse.hasFocus());

        ac.pause();
        ac.resume();
        Shadows.shadowOf(a.getMainLooper()).idle();

        View focused = a.getWindow().getDecorView().findFocus();
        assertSame("resume must not pull focus off Browse while the room stays playing, focused "
                        + name(focused),
                browse, focused);
        ac.pause().stop().destroy();
    }

    @Test
    public void playingRoomResumeFocusesQr() throws Exception {
        HoldingClient client = installHoldingRoom();
        ActivityController<JukeboxJoinActivity> ac =
                Robolectric.buildActivity(JukeboxJoinActivity.class).setup();
        JukeboxJoinActivity a = ac.get();
        Shadows.shadowOf(a.getMainLooper()).idle();
        layoutLikeTheTv(a);

        View browse = a.findViewById(R.id.jukebox_browse);
        View qr = a.findViewById(R.id.jukebox_qr);
        client.videoId = VIDEO;
        a.bind(null, null, null, "Song");
        assertTrue("focus is on the QR", qr.hasFocus() && qr.requestFocus());
        assertFalse(browse.hasFocus());

        ac.pause();
        ac.resume();
        Shadows.shadowOf(a.getMainLooper()).idle();

        View focused = a.getWindow().getDecorView().findFocus();
        assertSame("resume must leave the QR focused while the room stays playing, not "
                        + name(focused),
                qr, focused);
        ac.pause().stop().destroy();
    }

    @Test
    public void playingRoomResumeFocusesQrWhenBrowseDoesNotHaveFocus() throws Exception {
        HoldingClient client = installHoldingRoom();
        ActivityController<JukeboxJoinActivity> ac =
                Robolectric.buildActivity(JukeboxJoinActivity.class).setup();
        JukeboxJoinActivity a = ac.get();
        Shadows.shadowOf(a.getMainLooper()).idle();
        layoutLikeTheTv(a);

        View browse = a.findViewById(R.id.jukebox_browse);
        View qr = a.findViewById(R.id.jukebox_qr);
        client.videoId = VIDEO;
        a.bind(null, null, null, "Song");
        assertTrue("a playing room focuses the QR", qr.hasFocus());
        // Park focus on neither Browse nor the QR. Resume must still choose the QR.
        TextView other = new TextView(a);
        other.setFocusable(true);
        other.setText("other");
        ViewGroup content = a.findViewById(android.R.id.content);
        content.addView(other, new ViewGroup.LayoutParams(48, 48));
        layoutLikeTheTv(a);
        assertTrue(other.requestFocus() && other.hasFocus());
        assertFalse(browse.hasFocus());
        assertFalse(qr.hasFocus());

        ac.pause();
        ac.resume();
        Shadows.shadowOf(a.getMainLooper()).idle();

        View focused = a.getWindow().getDecorView().findFocus();
        assertSame("resume must focus the QR when Browse does not have it, not " + name(focused),
                qr, focused);
        assertFalse(browse.hasFocus());
        ac.pause().stop().destroy();
    }

    @Test
    public void defaultFocusIdIsBrowseWhenIdleAndQrWhenPlaying() {
        assertEquals(R.id.jukebox_browse, JukeboxJoinActivity.defaultFocusId(false));
        assertEquals(R.id.jukebox_qr, JukeboxJoinActivity.defaultFocusId(true));
    }

    /** Room whose current video id the test sets, with no socket and no HTTP. */
    private static HoldingClient installHoldingRoom() throws Exception {
        HoldingClient client = new HoldingClient();
        Class<?> factoryClass = Class.forName("net.tfour.jukebox.room.JukeboxRoom$ClientFactory");
        Class<?> executorClass = Class.forName("net.tfour.jukebox.room.JukeboxRoom$Executor");
        Object factory = Proxy.newProxyInstance(
                factoryClass.getClassLoader(),
                new Class<?>[] {factoryClass},
                (proxy, method, args) -> {
                    if ("build".equals(method.getName())) {
                        return client;
                    }
                    if ("hashCode".equals(method.getName())) {
                        return System.identityHashCode(proxy);
                    }
                    if ("equals".equals(method.getName())) {
                        return proxy == args[0];
                    }
                    if ("toString".equals(method.getName())) {
                        return "holding-room";
                    }
                    return null;
                });
        Constructor<JukeboxRoom> ctor = JukeboxRoom.class.getDeclaredConstructor(
                android.content.Context.class, factoryClass, executorClass);
        ctor.setAccessible(true);
        JukeboxRoom room = ctor.newInstance(RuntimeEnvironment.getApplication(), factory, null);
        Field field = JukeboxRoom.class.getDeclaredField("sInstance");
        field.setAccessible(true);
        field.set(null, room);
        return client;
    }

    /** Robolectric does not measure the decor. Focus needs a real layout pass. */
    private static void layoutLikeTheTv(JukeboxJoinActivity activity) {
        View root = activity.getWindow().getDecorView();
        root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1920, 1080);
    }

    private static String name(View view) {
        if (view == null) {
            return "nothing";
        }
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null && text.length() > 0) {
                return text.toString();
            }
        }
        return view.getClass().getSimpleName();
    }

    /** {@link JukeboxRoomClient} that reports one video id and never touches the network. */
    private static final class HoldingClient extends JukeboxRoomClient {
        String videoId;

        HoldingClient() {
            super("https://jukebox.example",
                    new Transport() {
                        @Override
                        public void connect(String url, Callbacks cb) {
                        }

                        @Override
                        public boolean send(String text) {
                            return false;
                        }

                        @Override
                        public void close() {
                        }
                    },
                    new Http() {
                        @Override
                        public String postJson(String url, String body) {
                            return "{}";
                        }

                        @Override
                        public String get(String url) {
                            return "{}";
                        }
                    },
                    new Scheduler() {
                        @Override
                        public void schedule(Runnable r, long delayMs) {
                        }

                        @Override
                        public void cancelAll() {
                        }
                    },
                    new Store() {
                        final Map<String, String> m = new HashMap<>();

                        @Override
                        public String get(String key) {
                            return m.get(key);
                        }

                        @Override
                        public void put(String key, String value) {
                            if (value == null) {
                                m.remove(key);
                            } else {
                                m.put(key, value);
                            }
                        }
                    },
                    new Listener() {
                        @Override
                        public void onRoomReady(String room, String code, String joinUrl) {
                        }

                        @Override
                        public void onPlay(String songId, String videoId, String title) {
                        }

                        @Override
                        public void onStatus(String status) {
                        }

                        @Override
                        public void onState(String nowPlayingTitle, int listeners) {
                        }
                    },
                    "");
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public String getCurrentVideoId() {
            return videoId;
        }

        @Override
        public boolean isRunning() {
            return true;
        }
    }
}

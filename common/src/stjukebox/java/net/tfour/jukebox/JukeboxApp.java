package net.tfour.jukebox;

import android.app.Application;
import android.content.Context;

import com.liskovsoft.smartyoutubetv2.common.utils.JukeboxLaunch;

import net.tfour.jukebox.room.JukeboxForeground;

/**
 * Process-start hook for the stjukebox flavor, invoked reflectively from the
 * very start of MainApplication.onCreate (common/main cannot reference flavor
 * classes). Wires the flavor-wide foreground tracker (see {@link JukeboxForeground})
 * and registers it as the launch gate (see {@link JukeboxLaunch}) — process-wide
 * started/resumed activity state plus a caller-aware answer — so every
 * app-initiated activity start is gated and logged from the earliest moment.
 */
public final class JukeboxApp {
    private JukeboxApp() {
    }

    public static void init(Application app) {
        JukeboxForeground.init(app);
        JukeboxLaunch.init(true, new JukeboxLaunch.Gate() {
            @Override
            public boolean isAppInForeground() {
                return JukeboxForeground.isAppInForeground();
            }

            @Override
            public boolean isCallerInForeground(Context caller) {
                return JukeboxForeground.isCallerInForeground(caller);
            }
        });
    }
}

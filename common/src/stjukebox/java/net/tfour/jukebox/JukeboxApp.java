package net.tfour.jukebox;

import android.app.Application;

import com.liskovsoft.smartyoutubetv2.common.utils.JukeboxLaunch;

import net.tfour.jukebox.room.JukeboxForeground;

/**
 * Process-start hook for the stjukebox flavor, invoked reflectively from
 * MainApplication.onCreate (common/main cannot reference flavor classes).
 * Wires the flavor-wide foreground tracker (see {@link JukeboxForeground}) and
 * registers it as the launch gate (see {@link JukeboxLaunch}), so every
 * app-initiated activity start is gated and logged from the earliest moment.
 */
public final class JukeboxApp {
    private JukeboxApp() {
    }

    public static void init(Application app) {
        JukeboxForeground.init(app);
        JukeboxLaunch.init(true, JukeboxForeground::isAppInForeground);
    }
}

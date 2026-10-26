package net.tfour.jukebox.update;

import android.content.Context;

import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.AppUpdatePresenter;

import net.tfour.jukebox.room.JukeboxLog;

import java.util.List;

/**
 * {@link AppUpdatePresenter} with jukebox logging (tag {@code JukeboxUpdate}):
 * the offer and the no-offer outcome are logged so gazebo runs can verify the
 * self-update path. Showing stays with the base class, which already refuses to
 * show a dialog while the player is open or the app is backgrounded; a
 * non-forced check only pins the update row on the browse screen otherwise.
 */
public class JukeboxUpdatePresenter extends AppUpdatePresenter {
    JukeboxUpdatePresenter(Context context) {
        super(context);
    }

    @Override
    public void onUpdateFound(String versionName, List<String> changelog, String apkPath) {
        JukeboxLog.i(JukeboxUpdate.LOG_TAG,
                "offer: version=" + versionName + " apk=" + apkPath
                        + " changelogEntries=" + (changelog != null ? changelog.size() : 0));
        super.onUpdateFound(versionName, changelog, apkPath);
    }

    @Override
    public void onUpdateError(Exception error) {
        JukeboxLog.i(JukeboxUpdate.LOG_TAG, "no offer: "
                + (error != null && error.getMessage() != null ? error.getMessage() : "unknown"));
        super.onUpdateError(error);
    }
}

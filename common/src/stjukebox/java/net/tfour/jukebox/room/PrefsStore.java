package net.tfour.jukebox.room;

import android.content.Context;
import android.content.SharedPreferences;

/** {@link JukeboxRoomClient.Store} over the shared preferences file "jukebox_room". */
public class PrefsStore implements JukeboxRoomClient.Store {
    private static final String PREFS_NAME = "jukebox_room";

    private final SharedPreferences mPrefs;

    public PrefsStore(Context context) {
        mPrefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    @Override
    public String get(String key) {
        return mPrefs.getString(key, null);
    }

    @Override
    public void put(String key, String value) {
        SharedPreferences.Editor editor = mPrefs.edit();
        if (value == null) {
            editor.remove(key);
        } else {
            editor.putString(key, value);
        }
        editor.apply();
    }
}

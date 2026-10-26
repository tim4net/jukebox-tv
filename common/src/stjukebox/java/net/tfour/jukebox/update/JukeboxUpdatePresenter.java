package net.tfour.jukebox.update;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageInfo;

import com.liskovsoft.appupdatechecker2.AppUpdateChecker;
import com.liskovsoft.appupdatechecker2.AppUpdateCheckerListener;
import com.liskovsoft.smartyoutubetv2.common.R;

import net.tfour.jukebox.room.JukeboxLog;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Manifest check for the stjukebox flavor (tag {@code JukeboxUpdate}), build 2471.
 *
 * Wraps the existing upstream download+install machinery ({@link AppUpdateChecker}:
 * latest.json manifest, apk download, {@code installUpdate()} -> Android
 * package-install confirm), driven with the resolved manifest URL. The URL is
 * the flavor default (res/values/update_urls.xml) unless a TEST-ONLY override
 * is set via the Android system property {@code debug.jukebox.update_url}
 * ({@code adb shell setprop debug.jukebox.update_url <url>}); the override is
 * accepted ONLY when it points under https://jukebox.tfour.net/install/, and
 * every check logs the URL it used.
 *
 * The upstream dialog/browse-row "offer" is NOT used: when a newer versionCode
 * is available, a small NON-MODAL corner card ({@link JukeboxUpdateCard}) is
 * attached to the current activity's decor instead — it does not pause or
 * steal focus from the player and auto-hides after ~15s.
 */
public class JukeboxUpdatePresenter implements AppUpdateCheckerListener {
    /** Test-only manifest override: adb shell setprop debug.jukebox.update_url <url>. */
    static final String OVERRIDE_PROP = "debug.jukebox.update_url";
    /** Overrides are only accepted under this prefix (testers stay on our host). */
    static final String OVERRIDE_PREFIX = "https://jukebox.tfour.net/install/";

    /** Supplies the currently resumed activity (where the prompt card attaches). */
    public interface CurrentActivity {
        Activity get();
    }

    private final Context mAppContext;
    private final CurrentActivity mCurrentActivity;
    private AppUpdateChecker mChecker;

    JukeboxUpdatePresenter(Context appContext, CurrentActivity currentActivity) {
        mAppContext = appContext.getApplicationContext();
        mCurrentActivity = currentActivity;
    }

    /** Runs the manifest check against the resolved URL (see {@link #resolveUrl()}). */
    void startCheck() {
        String url = resolveUrl();
        JukeboxLog.i(JukeboxUpdate.LOG_TAG, "check: url=" + url);
        checker().forceCheckForUpdates(new String[]{url});
    }

    /**
     * The manifest URL for this check: the flavor default, replaced by the
     * test-only system-property override when one is set AND valid. The URL
     * actually used is logged by the caller on every check.
     */
    String resolveUrl() {
        String url = defaultUrl();
        String override = getSystemProperty(OVERRIDE_PROP);
        if (override == null || override.trim().isEmpty()) {
            return url;
        }
        if (!override.startsWith(OVERRIDE_PREFIX)) {
            JukeboxLog.i(JukeboxUpdate.LOG_TAG, "ignore invalid override url (outside "
                    + OVERRIDE_PREFIX + "): " + override);
            return url;
        }
        JukeboxLog.i(JukeboxUpdate.LOG_TAG, "override url active (debug.jukebox.update_url): " + override);
        return override;
    }

    private String defaultUrl() {
        String[] urls = mAppContext.getResources().getStringArray(R.array.update_urls);
        return urls != null && urls.length > 0 ? urls[0] : "";
    }

    /** adb-settable system property, read via reflection (hidden API). Null when unset/unreadable. */
    private static String getSystemProperty(String key) {
        try {
            Class<?> props = Class.forName("android.os.SystemProperties");
            Method get = props.getMethod("get", String.class, String.class);
            Object value = get.invoke(null, key, "");
            return value instanceof String ? (String) value : null;
        } catch (Throwable unreadable) {
            return null;
        }
    }

    private AppUpdateChecker checker() {
        if (mChecker == null) {
            mChecker = new AppUpdateChecker(mAppContext, this);
        }
        return mChecker;
    }

    @Override
    public void onUpdateFound(String versionName, List<String> changelog, String apkPath) {
        int build = buildCodeOf(apkPath);
        JukeboxLog.i(JukeboxUpdate.LOG_TAG, "result: update available versionName=" + versionName
                + (build > 0 ? " (build " + build + ")" : "")
                + " changelogEntries=" + (changelog != null ? changelog.size() : 0)
                + " apk=" + apkPath);
        // Non-modal prompt on the current activity: no dialog activity, no
        // player pause, no focus steal (the card is DPAD-reachable instead).
        JukeboxUpdateCard.show(mCurrentActivity.get(), versionName, build, this::install);
    }

    @Override
    public void onUpdateError(Exception error) {
        JukeboxLog.i(JukeboxUpdate.LOG_TAG, "result: no update ("
                + (error != null && error.getMessage() != null ? error.getMessage() : "unknown") + ")");
    }

    /** Existing upstream download+install path; the Android package-install confirm stays. */
    void install() {
        JukeboxLog.i(JukeboxUpdate.LOG_TAG, "install chosen: launching the package installer");
        checker().installUpdate();
    }

    /** The build (versionCode) of the downloaded apk, or 0 when unknown. */
    private int buildCodeOf(String apkPath) {
        if (apkPath == null) {
            return 0;
        }
        try {
            PackageInfo info = mAppContext.getPackageManager().getPackageArchiveInfo(apkPath, 0);
            return info != null ? (int) info.versionCode : 0;
        } catch (Throwable notAnApk) {
            return 0;
        }
    }
}
package net.tfour.jukebox.update;

import androidx.core.content.FileProvider;

/**
 * stjukebox self-update FileProvider (build 2473, issue #5): serves the
 * apk downloaded into {@code cacheDir()/jukebox-update/} to the Android
 * package installer as a {@code content://} URI (authority
 * {@code <package>.jukeboxupdate}, paths in res/xml/jukebox_update_paths.xml).
 * A distinct class so it can never merge-fight the upstream
 * {@code ${applicationId}.update_provider} FileProvider.
 */
public class JukeboxUpdateFileProvider extends FileProvider {
}

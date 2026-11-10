package net.tfour.jukebox.update;

import net.tfour.jukebox.room.JukeboxLog;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Issue #28: the production APK download that used to sit in
 * {@code JukeboxUpdatePresenter.downloadHttp}, moved here unchanged in
 * behavior: stream into a {@code .part} file and rename on success, never
 * follow redirects ({@code setInstanceFollowRedirects(false)} plus a
 * getURL() re-check), accept only a direct HTTP 200, refuse sizes above the
 * cap (Content-Length and streamed count), delete the {@code .part} file on
 * ANY failure, and log progress throttled by {@link ProgressThrottle}.
 */
class HttpApkDownloader implements JukeboxUpdatePresenter.ApkDownloader {
    /** Download byte cap, ~200MB (item 7). */
    static final long DEFAULT_MAX_BYTES = 200L * 1024 * 1024;

    private final long mMaxBytes;

    HttpApkDownloader() {
        this(DEFAULT_MAX_BYTES);
    }

    HttpApkDownloader(long maxBytes) {
        mMaxBytes = maxBytes;
    }

    /** The {@code .part} staging file {@code destFile} is streamed into. */
    static File partOf(File destFile) {
        return new File(destFile.getPath() + ".part");
    }

    @Override
    public void download(String url, File destFile, Progress progress) throws IOException {
        ensureParentDir(destFile);
        File part = partOf(destFile);
        URL target = new URL(url);
        HttpURLConnection connection = null;
        try {
            connection = openChecked(target, url);
            long total = connection.getContentLengthLong();
            checkLength(total, url);
            stream(connection, total, part, url, progress);
        } catch (IOException | RuntimeException failure) {
            JukeboxUpdatePresenter.delete(part);
            throw failure;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        renameInto(part, destFile);
    }

    private static void ensureParentDir(File destFile) throws IOException {
        File dir = destFile.getParentFile();
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
    }

    /** Connects and refuses redirects / non-200 responses up front. */
    private HttpURLConnection openChecked(URL target, String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) target.openConnection();
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(30_000);
        connection.setInstanceFollowRedirects(false);
        connection.connect();
        if (!target.equals(connection.getURL())) {
            throw new IOException("redirected to " + connection.getURL()
                    + " (redirects are never followed)");
        }
        int code = connection.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            throw new IOException("HTTP " + code + " (only 200 is accepted): " + url);
        }
        return connection;
    }

    /** Refuses an over-cap Content-Length before any body byte is streamed. */
    private void checkLength(long total, String url) throws IOException {
        if (total > mMaxBytes) {
            throw new IOException("Content-Length " + total + " exceeds the "
                    + mMaxBytes + " byte cap: " + url);
        }
    }

    /** Streams the response body into {@code part} under the byte cap. */
    private void stream(HttpURLConnection connection, long total, File part, String url,
            Progress progress) throws IOException {
        try (InputStream rawIn = new BufferedInputStream(connection.getInputStream());
             OutputStream out = new BufferedOutputStream(new FileOutputStream(part))) {
            byte[] buffer = new byte[64 * 1024];
            long done = 0;
            ProgressThrottle throttle = new ProgressThrottle(total);
            int read;
            while ((read = rawIn.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                done += read;
                if (done > mMaxBytes) {
                    throw new IOException("download exceeds the " + mMaxBytes
                            + " byte cap (" + done + " bytes): " + url);
                }
                report(done, total, throttle, progress);
            }
        }
    }

    private static void report(long done, long total, ProgressThrottle throttle,
            Progress progress) {
        int percent = throttle.onBytes(done);
        if (percent != ProgressThrottle.NO_REPORT) {
            JukeboxLog.i(JukeboxUpdate.LOG_TAG, "download progress: " + percent
                    + "% " + done + "/" + (total > 0 ? total : "?") + " bytes");
            progress.onProgress(percent);
        }
    }

    private static void renameInto(File part, File destFile) throws IOException {
        if (!part.renameTo(destFile)) {
            JukeboxUpdatePresenter.delete(part);
            throw new IOException("cannot rename " + part + " to " + destFile);
        }
    }
}

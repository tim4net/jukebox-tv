package net.tfour.jukebox.update;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Build 2475, review item 7 (+ item 1 redirect): the production HTTP
 * downloader ({@code JukeboxUpdatePresenter.downloadHttp}, real
 * {@link java.net.HttpURLConnection} against a local HTTP server) must only
 * accept a direct HTTP 200, must treat any 3xx redirect as a failure
 * ({@code setInstanceFollowRedirects(false)}), must refuse downloads whose
 * Content-Length (or streamed byte count) exceeds the ~200MB cap, and must
 * delete the {@code .part} file on ANY failure.
 *
 * <p>(204 is used for the non-200 case because {@code getInputStream()} already
 * throws on 4xx/5xx today; a 2xx-but-not-200 must be refused by the explicit
 * response-code check.)
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class JukeboxUpdateDownloadHttpTest {

    /** Presenter exposing the PRODUCTION downloader (real HttpURLConnection). */
    private static class TestPresenter extends JukeboxUpdatePresenter {
        long maxBytes = 200L * 1024 * 1024;

        TestPresenter(Context context) {
            super(context, () -> null);
        }

        @Override
        protected long maxDownloadBytes() {
            return maxBytes;
        }
    }

    /**
     * Tiny raw-socket HTTP server (com.sun.net.httpserver is not on the
     * android unit-test classpath): one request per connection, answered with
     * the fixed raw response bytes registered for the request path.
     */
    private static final class RawHttpServer {
        private final ServerSocket socket;
        private final Map<String, String> routes = new HashMap<>();

        RawHttpServer() throws IOException {
            socket = new ServerSocket(0, 10, InetAddress.getByName("localhost"));
            Thread acceptor = new Thread(this::acceptLoop);
            acceptor.setDaemon(true);
            acceptor.start();
        }

        int port() {
            return socket.getLocalPort();
        }

        void route(String path, String rawResponse) {
            routes.put(path, rawResponse);
        }

        private void acceptLoop() {
            while (true) {
                try (Socket client = socket.accept()) {
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(client.getInputStream(), StandardCharsets.ISO_8859_1));
                    String requestLine = reader.readLine();
                    // Drain the rest of the request head.
                    String line;
                    while (requestLine != null && (line = reader.readLine()) != null && !line.isEmpty()) { }
                    String path = requestLine == null ? "" : pathOf(requestLine);
                    String response = routes.get(path);
                    if (response == null) {
                        response = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n";
                    }
                    client.getOutputStream().write(response.getBytes(StandardCharsets.ISO_8859_1));
                    client.getOutputStream().flush();
                } catch (IOException closed) {
                    return; // stopped
                }
            }
        }

        private static String pathOf(String requestLine) {
            String path = requestLine.split(" ").length > 1 ? requestLine.split(" ")[1] : requestLine;
            int query = path.indexOf('?');
            return query >= 0 ? path.substring(0, query) : path;
        }

        void stop() throws IOException {
            socket.close();
        }
    }

    private static final JukeboxUpdatePresenter.ApkDownloader.Progress NO_PROGRESS = percent -> { };

    private RawHttpServer server;
    private TestPresenter presenter;
    private File dest;
    private File part;

    @Before
    public void setUp() throws Exception {
        net.tfour.jukebox.room.JukeboxForeground.setTestOverride(true);
        ShadowLog.clear();
        server = new RawHttpServer();
        presenter = new TestPresenter(RuntimeEnvironment.getApplication());
        dest = new File(RuntimeEnvironment.getApplication().getCacheDir(),
                "jukebox-update-download-http.apk");
        part = new File(dest.getPath() + ".part");
        dest.delete();
        part.delete();
    }

    @After
    public void tearDown() throws Exception {
        server.stop();
        net.tfour.jukebox.room.JukeboxForeground.setTestOverride(null);
    }

    private String url(String path) {
        return "http://localhost:" + server.port() + path;
    }

    @Test
    public void http200DownloadsAllBytesAndLeavesNoPart() throws Exception {
        server.route("/ok", "HTTP/1.1 200 OK\r\nContent-Length: 15\r\n\r\nfake apk payload");
        dest.getParentFile().mkdirs();

        presenter.downloadHttp(url("/ok"), dest, NO_PROGRESS);

        assertTrue("a 200 must be streamed to the dest file", dest.exists());
        assertArrayEquals("all response bytes must arrive",
                "fake apk payload".getBytes(StandardCharsets.UTF_8), readAll(dest));
        assertFalse("the .part file must be gone after the rename", part.exists());
    }

    @Test
    public void non200IsAFailureAndDeletesThePart() throws Exception {
        // 204: a success-family status the HttpURLConnection happily streams
        // today; only an explicit response-code check can refuse it.
        server.route("/nocontent", "HTTP/1.1 204 No Content\r\n\r\n");
        dest.getParentFile().mkdirs();

        try {
            presenter.downloadHttp(url("/nocontent"), dest, NO_PROGRESS);
            fail("a 204 response must fail the download (only HTTP 200 is accepted)");
        } catch (IOException expected) {
        }
        assertFalse("the .part file must be deleted on a failed download", part.exists());
        assertFalse("nothing may be renamed into place on a failed download", dest.exists());
    }

    @Test
    public void redirectIsAFailureAndDeletesThePart() throws Exception {
        // 302 redirecting to /final; the redirect must NOT be followed.
        server.route("/moved", "HTTP/1.1 302 Found\r\nLocation: "
                + url("/final") + "\r\nContent-Length: 0\r\n\r\n");
        server.route("/final", "HTTP/1.1 200 OK\r\nContent-Length: 15\r\n\r\nlanded elsewhere");
        dest.getParentFile().mkdirs();

        try {
            presenter.downloadHttp(url("/moved"), dest, NO_PROGRESS);
            fail("a 3xx redirect must fail the download (redirects are never followed)");
        } catch (IOException expected) {
        }
        assertFalse("the .part file must be deleted on a refused redirect", part.exists());
        assertFalse(dest.exists());
    }

    @Test
    public void contentLengthAboveTheCapIsRefusedBeforeStreaming() throws Exception {
        // The Content-Length lies about a ~250MB body while sending 16 bytes.
        server.route("/big", "HTTP/1.1 200 OK\r\nContent-Length: "
                + (250L * 1024 * 1024) + "\r\n\r\n0123456789abcdef");
        dest.getParentFile().mkdirs();

        try {
            presenter.downloadHttp(url("/big"), dest, NO_PROGRESS);
            fail("a Content-Length above the cap must be refused before streaming");
        } catch (IOException expected) {
        }
        assertFalse("the .part file must be deleted on a refused download", part.exists());
        assertFalse(dest.exists());
    }

    @Test
    public void streamedBytesAboveTheCapAbortTheDownload() throws Exception {
        // Cap lowered to 1KB via the seam: a 4KB body must abort mid-stream.
        presenter.maxBytes = 1024;
        byte[] body = new byte[4096];
        server.route("/big", "HTTP/1.1 200 OK\r\nContent-Length: " + body.length
                + "\r\n\r\n" + new String(body, StandardCharsets.ISO_8859_1));
        dest.getParentFile().mkdirs();

        try {
            presenter.downloadHttp(url("/big"), dest, NO_PROGRESS);
            fail("streaming more than the cap must abort the download");
        } catch (IOException expected) {
        }
        assertFalse("the .part file must be deleted when the cap aborts", part.exists());
        assertFalse(dest.exists());
    }

    private static byte[] readAll(File file) throws IOException {
        try (InputStream in = new java.io.FileInputStream(file)) {
            byte[] out = new byte[(int) file.length()];
            int done = 0;
            int read;
            while (done < out.length && (read = in.read(out, done, out.length - done)) > 0) {
                done += read;
            }
            return out;
        }
    }
}
package net.tfour.jukebox.update;

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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Build 2475, review item 7 (+ item 1 redirect): the production HTTP
 * downloader ({@link HttpApkDownloader}, real
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
public class HttpApkDownloaderTest {

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
    private HttpApkDownloader downloader;
    private File dest;
    private File part;

    @Before
    public void setUp() throws Exception {
        net.tfour.jukebox.room.JukeboxForeground.setTestOverride(true);
        ShadowLog.clear();
        server = new RawHttpServer();
        downloader = new HttpApkDownloader();
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

    /** A raw HTTP 200 response carrying {@code body} with its exact Content-Length. */
    private static String ok200(byte[] body) {
        return "HTTP/1.1 200 OK\r\nContent-Length: " + body.length
                + "\r\n\r\n" + new String(body, StandardCharsets.ISO_8859_1);
    }

    /** A header-less (Connection: close) body of {@code bodyBytes} over a
     * {@code cap}-byte downloader must abort mid-stream and leave no files. */
    private void assertHeaderlessBodyAborts(String path, long cap, int bodyBytes)
            throws Exception {
        downloader = new HttpApkDownloader(cap);
        String body = new String(new byte[bodyBytes], StandardCharsets.ISO_8859_1);
        server.route(path, "HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n" + body);
        dest.getParentFile().mkdirs();

        try {
            downloader.download(url(path), dest, NO_PROGRESS);
            fail("a header-less body over the cap must abort mid-stream");
        } catch (IOException expected) {
        }
        assertFalse("the .part file must be deleted when the cap aborts", part.exists());
        assertFalse(dest.exists());
    }

    /** The download of {@code path} must throw and leave neither a {@code .part}
     * nor a renamed dest behind. */
    private void assertDownloadFailsAndLeavesNoPart(String path, String failMessage,
            String partMessage) throws Exception {
        try {
            downloader.download(url(path), dest, NO_PROGRESS);
            fail(failMessage);
        } catch (IOException expected) {
        }
        assertFalse(partMessage, part.exists());
        assertFalse(dest.exists());
    }

    @Test
    public void http200DownloadsAllBytesAndLeavesNoPart() throws Exception {
        server.route("/ok", "HTTP/1.1 200 OK\r\nContent-Length: 15\r\n\r\nfake apk payload");
        dest.getParentFile().mkdirs();

        downloader.download(url("/ok"), dest, NO_PROGRESS);

        assertTrue("a 200 must be streamed to the dest file", dest.exists());
        assertArrayEquals("all response bytes must arrive",
                "fake apk payload".getBytes(StandardCharsets.UTF_8), readAll(dest));
        assertFalse("the .part file must be gone after the rename", part.exists());
    }

    @Test
    public void oneByteBodyLands() throws Exception {
        // A read() of exactly 1 byte is still body data: the loop condition
        // must accept it (> 0, not > 1).
        server.route("/one-byte", ok200(new byte[] {42}));
        dest.getParentFile().mkdirs();

        downloader.download(url("/one-byte"), dest, NO_PROGRESS);

        assertTrue("a 1-byte body must land", dest.exists());
        assertEquals("exactly one body byte must arrive", 1, dest.length());
        assertArrayEquals("the single body byte must be the payload byte",
                new byte[] {42}, readAll(dest));
        assertFalse("the .part file must be gone after the rename", part.exists());
    }

    @Test
    public void non200IsAFailureAndDeletesThePart() throws Exception {
        // 204: a success-family status the HttpURLConnection happily streams
        // today; only an explicit response-code check can refuse it.
        server.route("/nocontent", "HTTP/1.1 204 No Content\r\n\r\n");
        dest.getParentFile().mkdirs();

        assertDownloadFailsAndLeavesNoPart("/nocontent",
                "a 204 response must fail the download (only HTTP 200 is accepted)",
                "the .part file must be deleted on a failed download");
    }

    @Test
    public void redirectIsAFailureAndDeletesThePart() throws Exception {
        // 302 redirecting to /final; the redirect must NOT be followed.
        server.route("/moved", "HTTP/1.1 302 Found\r\nLocation: "
                + url("/final") + "\r\nContent-Length: 0\r\n\r\n");
        server.route("/final", "HTTP/1.1 200 OK\r\nContent-Length: 15\r\n\r\nlanded elsewhere");
        dest.getParentFile().mkdirs();

        assertDownloadFailsAndLeavesNoPart("/moved",
                "a 3xx redirect must fail the download (redirects are never followed)",
                "the .part file must be deleted on a refused redirect");
    }

    @Test
    public void contentLengthAboveTheCapIsRefusedBeforeStreaming() throws Exception {
        // The Content-Length lies about a ~250MB body while sending 16 bytes.
        server.route("/big", "HTTP/1.1 200 OK\r\nContent-Length: "
                + (250L * 1024 * 1024) + "\r\n\r\n0123456789abcdef");
        dest.getParentFile().mkdirs();

        try {
            downloader.download(url("/big"), dest, NO_PROGRESS);
            fail("a Content-Length above the cap must be refused before streaming");
        } catch (IOException expected) {
        }
        assertFalse("the .part file must be deleted on a refused download", part.exists());
        assertFalse(dest.exists());
    }

    @Test
    public void streamedBytesAboveTheCapAbortTheDownload() throws Exception {
        // Cap lowered to 1KB via the seam: a 4KB body with NO Content-Length
        // (Connection: close instead) must abort mid-stream — only the
        // streamed-count check can catch it.
        assertHeaderlessBodyAborts("/big", 1024, 4096);
    }

    @Test
    public void multiChunkBodyOverTheCapAborts() throws Exception {
        // A 200KB header-less body over a 100KB cap: the 64KB read buffer
        // means no single read exceeds the cap, so only the running total
        // (done += read) can trip it.
        assertHeaderlessBodyAborts("/multi-chunk", 100 * 1024, 200 * 1024);
    }

    @Test
    public void defaultCapIs200MbAndNoArgConstructorUsesIt() throws Exception {
        assertEquals(200L * 1024 * 1024, HttpApkDownloader.DEFAULT_MAX_BYTES);
        downloader = new HttpApkDownloader();
        server.route("/default-cap", ok200(new byte[4096]));
        dest.getParentFile().mkdirs();

        downloader.download(url("/default-cap"), dest, NO_PROGRESS);

        assertTrue("a 4096-byte body is far under the default cap: it must land",
                dest.exists());
        assertEquals("all 4096 bytes must arrive", 4096, dest.length());
        assertFalse("the .part file must be gone after the rename", part.exists());
    }

    @Test
    public void bodyOfExactlyTheCapSucceeds() throws Exception {
        downloader = new HttpApkDownloader(1024);
        server.route("/exact", ok200(new byte[1024]));
        dest.getParentFile().mkdirs();

        downloader.download(url("/exact"), dest, NO_PROGRESS);

        assertTrue("a body of exactly the cap must succeed", dest.exists());
        assertEquals(1024, dest.length());
        assertFalse("the .part file must be gone after the rename", part.exists());
    }

    @Test
    public void bodyOfExactlyTheCapWithoutContentLengthSucceeds() throws Exception {
        // Pins the streamed-count boundary: done == cap must NOT abort, so a
        // header-less (Connection: close) body of exactly the cap succeeds.
        downloader = new HttpApkDownloader(1024);
        byte[] body = new byte[1024];
        server.route("/exact-unknown-length", "HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n"
                + new String(body, StandardCharsets.ISO_8859_1));
        dest.getParentFile().mkdirs();

        downloader.download(url("/exact-unknown-length"), dest, NO_PROGRESS);

        assertTrue("a header-less body of exactly the cap must succeed", dest.exists());
        assertEquals(1024, dest.length());
        assertFalse("the .part file must be gone after the rename", part.exists());
    }

    @Test
    public void missingParentDirIsCreated() throws Exception {
        dest = new File(RuntimeEnvironment.getApplication().getCacheDir(),
                "jukebox-missing-" + System.nanoTime() + "/nested/x.apk");
        part = new File(dest.getPath() + ".part");
        server.route("/nested", ok200(new byte[200]));
        try {
            downloader.download(url("/nested"), dest, NO_PROGRESS);
            assertTrue("the missing parent dirs must be created and the file land there",
                    dest.exists());
        } finally {
            dest.delete();
            File nested = dest.getParentFile();
            if (nested != null) {
                nested.delete();
                File missing = nested.getParentFile();
                if (missing != null) {
                    missing.delete();
                }
            }
        }
    }

    @Test
    public void renameFailureDeletesThePart() throws Exception {
        // dest occupied by a NON-EMPTY directory: the rename must fail and the
        // staged .part must be deleted.
        assertTrue("dest must start as a non-empty directory", dest.mkdirs());
        File inside = new File(dest, "occupied");
        try {
            assertTrue(inside.createNewFile());
            server.route("/ok", ok200("rename me".getBytes(StandardCharsets.UTF_8)));
            try {
                downloader.download(url("/ok"), dest, NO_PROGRESS);
                fail("renaming onto a non-empty directory must fail the download");
            } catch (IOException expected) {
            }
            assertFalse("the .part file must be deleted when the rename fails", part.exists());
        } finally {
            inside.delete();
            dest.delete();
        }
    }

    @Test
    public void knownLengthDownloadReportsProgressThroughTheThrottle() throws Exception {
        server.route("/ok", "HTTP/1.1 200 OK\r\nContent-Length: 16\r\n\r\nfake apk payload");
        dest.getParentFile().mkdirs();
        java.util.List<Integer> reported = new java.util.ArrayList<>();
        java.util.List<Boolean> partExistedAtReport = new java.util.ArrayList<>();

        downloader.download(url("/ok"), dest, percent -> {
            reported.add(percent);
            partExistedAtReport.add(part.exists());
        });

        assertEquals("one 16-byte chunk of a 16-byte body reports 100% once",
                java.util.Collections.singletonList(100), reported);
        assertEquals("progress must be reported while the body is staged in the .part file",
                java.util.Collections.singletonList(true), partExistedAtReport);
    }

    @Test
    public void presenterCreatesTheHttpApkDownloader() {
        assertTrue("createDownloader() must return the production HttpApkDownloader",
                new JukeboxUpdatePresenter(RuntimeEnvironment.getApplication(), () -> null)
                        .createDownloader() instanceof HttpApkDownloader);
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
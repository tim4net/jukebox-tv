package net.tfour.jukebox.room;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * OkHttp WebSocket transport for {@link JukeboxRoomClient}. Callbacks arrive on OkHttp's
 * threads; the client marshals them through its own lock.
 */
public class OkHttpTransport implements JukeboxRoomClient.Transport {
    private static final String LOG_TAG = "JukeboxRoom";

    private final OkHttpClient mClient;
    private volatile WebSocket mWebSocket;

    public OkHttpTransport(OkHttpClient client) {
        mClient = client;
    }

    @Override
    public void connect(String url, final JukeboxRoomClient.Transport.Callbacks cb) {
        // A forced reconnect (client restart while an old socket is still around)
        // must not leave the old WebSocket open: cancel it before connecting anew.
        // Cancelling a socket that already failed/closed is a harmless no-op.
        WebSocket old = mWebSocket;
        mWebSocket = null;
        if (old != null) {
            try {
                old.cancel();
            } catch (RuntimeException ignored) {
                // Already dead.
            }
        }
        mWebSocket = mClient.newWebSocket(new Request.Builder().url(url).build(), new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                cb.onOpen();
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                cb.onMessage(text);
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                JukeboxLog.i(LOG_TAG, "ws closing code=" + code + " reason=" + reason);
                webSocket.close(1000, null); // finish the close handshake; onClosed follows
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                JukeboxLog.i(LOG_TAG, "ws closed code=" + code + " reason=" + reason);
                cb.onClosed();
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                JukeboxLog.e(LOG_TAG, "ws failure", t);
                cb.onFailure(t);
            }
        });
    }

    @Override
    public boolean send(String text) {
        WebSocket webSocket = mWebSocket;
        return webSocket != null && webSocket.send(text);
    }

    @Override
    public void close() {
        WebSocket webSocket = mWebSocket;
        mWebSocket = null;
        if (webSocket != null) {
            webSocket.close(1000, null);
        }
    }
}

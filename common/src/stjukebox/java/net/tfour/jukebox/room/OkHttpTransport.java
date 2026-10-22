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
    private final OkHttpClient mClient;
    private volatile WebSocket mWebSocket;

    public OkHttpTransport(OkHttpClient client) {
        mClient = client;
    }

    @Override
    public void connect(String url, final JukeboxRoomClient.Transport.Callbacks cb) {
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
                webSocket.close(1000, null); // finish the close handshake; onClosed follows
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                cb.onClosed();
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
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

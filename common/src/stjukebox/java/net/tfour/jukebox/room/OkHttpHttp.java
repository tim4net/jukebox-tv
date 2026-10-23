package net.tfour.jukebox.room;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Blocking OkHttp implementation of {@link Http}. Non-2xx responses throw
 * {@link JukeboxRoomClient.HttpStatusException}. Must not be used on the Android
 * main thread.
 */
public class OkHttpHttp implements JukeboxRoomClient.Http {
    private final OkHttpClient mClient;

    public OkHttpHttp(OkHttpClient client) {
        mClient = client;
    }

    @Override
    public String postJson(String url, String body) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(MediaType.parse("application/json; charset=utf-8"), body == null ? "" : body))
                .build();
        return execute(request);
    }

    @Override
    public String get(String url) throws IOException {
        Request request = new Request.Builder().url(url).get().build();
        return execute(request);
    }

    private String execute(Request request) throws IOException {
        Call call = mClient.newBuilder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
                .newCall(request);
        Response response = call.execute();
        try {
            if (!response.isSuccessful()) {
                throw new JukeboxRoomClient.HttpStatusException(response.code());
            }
            ResponseBody responseBody = response.body();
            return responseBody != null ? responseBody.string() : null;
        } finally {
            response.close();
        }
    }
}

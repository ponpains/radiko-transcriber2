package com.ponpains.shioriarchive;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ArchiveRepository {
    public interface RefreshCallback {
        void onRefreshed(List<ArchiveEvent> events, String updatedAt);
    }

    private static final String REMOTE_URL =
            "https://raw.githubusercontent.com/ponpains/radiko-transcriber2/shiori-archive-mvp/shiori-archive/data/archive.json";
    private static final long REFRESH_INTERVAL_MS = 12L * 60L * 60L * 1000L;
    private static final int MAX_BYTES = 5 * 1024 * 1024;

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    public ArchiveRepository(Context context) {
        this.context = context.getApplicationContext();
    }

    public ArchiveData loadBestAvailable() {
        try {
            File cache = new File(context.getFilesDir(), "archive_remote.json");
            if (cache.isFile()) return parse(readAll(new FileInputStream(cache)));
        } catch (Exception ignored) { }

        try (InputStream in = context.getAssets().open("archive_seed.json")) {
            return parse(readAll(in));
        } catch (Exception e) {
            return new ArchiveData(Collections.emptyList(), "取得できませんでした");
        }
    }

    public void refreshIfNeeded(RefreshCallback callback) {
        SharedPreferences prefs = context.getSharedPreferences("archive", Context.MODE_PRIVATE);
        long last = prefs.getLong("last_refresh", 0L);
        if (System.currentTimeMillis() - last < REFRESH_INTERVAL_MS) return;

        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(REMOTE_URL);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", "ShioriArchive/0.1 (+public metadata reader)");
                int code = conn.getResponseCode();
                if (code != 200) return;

                byte[] bytes;
                try (InputStream in = new BufferedInputStream(conn.getInputStream())) {
                    bytes = readAll(in);
                }
                if (bytes.length == 0 || bytes.length > MAX_BYTES) return;

                ArchiveData parsed = parse(bytes);
                if (parsed.events.isEmpty()) return;

                File tmp = new File(context.getFilesDir(), "archive_remote.json.tmp");
                File dst = new File(context.getFilesDir(), "archive_remote.json");
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    out.write(bytes);
                    out.getFD().sync();
                }
                if (!tmp.renameTo(dst)) {
                    try (FileOutputStream out = new FileOutputStream(dst)) { out.write(bytes); }
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                }
                prefs.edit().putLong("last_refresh", System.currentTimeMillis()).apply();
                main.post(() -> callback.onRefreshed(parsed.events, parsed.updatedAt));
            } catch (Exception ignored) {
                // Network refresh is optional. The bundled archive remains usable offline.
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    private ArchiveData parse(byte[] bytes) throws Exception {
        JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        JSONArray arr = root.getJSONArray("events");
        List<ArchiveEvent> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            try { out.add(ArchiveEvent.fromJson(arr.getJSONObject(i))); }
            catch (Exception ignored) { }
        }
        Collections.sort(out);
        return new ArchiveData(out, root.optString("updatedAt", ""));
    }

    private byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buffer)) >= 0) {
            total += n;
            if (total > MAX_BYTES) throw new IllegalStateException("archive too large");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    public static final class ArchiveData {
        public final List<ArchiveEvent> events;
        public final String updatedAt;
        ArchiveData(List<ArchiveEvent> events, String updatedAt) {
            this.events = events;
            this.updatedAt = updatedAt;
        }
    }
}

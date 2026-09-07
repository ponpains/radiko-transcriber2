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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ArchiveRepository {
    public interface RefreshCallback {
        void onRefreshed(List<ArchiveEvent> events, String updatedAt);
    }

    private static final String REMOTE_ROOT =
            "https://raw.githubusercontent.com/ponpains/radiko-transcriber2/shiori-archive-mvp/shiori-archive/data/";
    private static final String MANIFEST_URL = REMOTE_ROOT + "manifest.json";
    private static final long REFRESH_INTERVAL_MS = 12L * 60L * 60L * 1000L;
    private static final int MAX_FILE_BYTES = 5 * 1024 * 1024;
    private static final int MAX_TOTAL_BYTES = 20 * 1024 * 1024;
    private static final int MAX_SHARDS = 100;

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    public ArchiveRepository(Context context) {
        this.context = context.getApplicationContext();
    }

    public ArchiveData loadBestAvailable() {
        try {
            File cache = new File(context.getFilesDir(), "archive_remote.json");
            if (cache.isFile()) return parse(readAll(new FileInputStream(cache), MAX_TOTAL_BYTES));
        } catch (Exception ignored) { }

        try (InputStream in = context.getAssets().open("archive_seed.json")) {
            return parse(readAll(in, MAX_TOTAL_BYTES));
        } catch (Exception e) {
            return new ArchiveData(Collections.emptyList(), "取得できませんでした");
        }
    }

    public void refreshIfNeeded(RefreshCallback callback) {
        SharedPreferences prefs = context.getSharedPreferences("archive", Context.MODE_PRIVATE);
        long last = prefs.getLong("last_refresh", 0L);
        if (System.currentTimeMillis() - last < REFRESH_INTERVAL_MS) return;

        executor.execute(() -> {
            try {
                byte[] manifestBytes = download(MANIFEST_URL, 128 * 1024);
                JSONObject manifest = new JSONObject(new String(manifestBytes, StandardCharsets.UTF_8));
                JSONArray files = manifest.getJSONArray("files");
                if (files.length() == 0 || files.length() > MAX_SHARDS) return;

                Map<String, JSONObject> merged = new LinkedHashMap<>();
                int total = manifestBytes.length;
                for (int i = 0; i < files.length(); i++) {
                    String name = files.getString(i);
                    if (!isSafeShardName(name)) return;
                    byte[] bytes = download(REMOTE_ROOT + name, MAX_FILE_BYTES);
                    total += bytes.length;
                    if (total > MAX_TOTAL_BYTES) return;
                    JSONObject shard = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                    JSONArray events = shard.optJSONArray("events");
                    if (events == null) continue;
                    for (int j = 0; j < events.length(); j++) {
                        JSONObject event = events.optJSONObject(j);
                        if (event == null) continue;
                        String id = event.optString("id", "").trim();
                        if (!id.isEmpty()) merged.put(id, event);
                    }
                }
                if (merged.isEmpty()) return;

                JSONObject combined = new JSONObject();
                combined.put("schemaVersion", 1);
                combined.put("updatedAt", manifest.optString("updatedAt", ""));
                combined.put("notice", "公開情報の日時・短い要約・タグ・出典URLのみ。記録なしは活動なしを意味しません。");
                JSONArray array = new JSONArray();
                for (JSONObject event : merged.values()) array.put(event);
                combined.put("events", array);
                byte[] combinedBytes = combined.toString().getBytes(StandardCharsets.UTF_8);
                if (combinedBytes.length > MAX_TOTAL_BYTES) return;

                ArchiveData parsed = parse(combinedBytes);
                if (parsed.events.isEmpty()) return;
                saveCache(combinedBytes);
                prefs.edit().putLong("last_refresh", System.currentTimeMillis()).apply();
                main.post(() -> callback.onRefreshed(parsed.events, parsed.updatedAt));
            } catch (Exception ignored) {
                // 更新に失敗しても、同梱データまたは直近キャッシュをそのまま使う。
            }
        });
    }

    private boolean isSafeShardName(String name) {
        return name != null && name.matches("[A-Za-z0-9_.-]+\\.json") && !name.contains("..");
    }

    private byte[] download(String urlString, int limit) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlString);
            if (!"https".equalsIgnoreCase(url.getProtocol())) throw new IllegalArgumentException("https only");
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "ShioriArchive/0.2 (+public metadata reader)");
            if (conn.getResponseCode() != 200) throw new IllegalStateException("http error");
            try (InputStream in = new BufferedInputStream(conn.getInputStream())) {
                return readAll(in, limit);
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void saveCache(byte[] bytes) throws Exception {
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

    private byte[] readAll(InputStream in, int limit) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buffer)) >= 0) {
            total += n;
            if (total > limit) throw new IllegalStateException("archive too large");
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

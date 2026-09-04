package com.example.radikotranscriber;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.*;

public class EpisodeStore extends SQLiteOpenHelper {
    private static final String DB_NAME = "radiko_transcripts.db";
    private static final int DB_VERSION = 1;

    public static class Episode {
        public long id;
        public String program;
        public String title;
        public String url;
        public String transcript;
        public String status;
        public long startedAt;
        public long updatedAt;
    }

    public EpisodeStore(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE episodes (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "program TEXT NOT NULL DEFAULT ''," +
                "title TEXT NOT NULL DEFAULT ''," +
                "url TEXT NOT NULL DEFAULT ''," +
                "transcript TEXT NOT NULL DEFAULT ''," +
                "status TEXT NOT NULL DEFAULT 'saved'," +
                "started_at INTEGER NOT NULL," +
                "updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_episodes_updated ON episodes(updated_at DESC)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}

    public long createEpisode(String program, String title, String url) {
        long now = System.currentTimeMillis();
        ContentValues v = new ContentValues();
        v.put("program", safe(program));
        v.put("title", safe(title));
        v.put("url", safe(url));
        v.put("transcript", "");
        v.put("status", "saved");
        v.put("started_at", now);
        v.put("updated_at", now);
        return getWritableDatabase().insertOrThrow("episodes", null, v);
    }

    public void updateMeta(long id, String program, String title, String url) {
        ContentValues v = new ContentValues();
        v.put("program", safe(program));
        v.put("title", safe(title));
        v.put("url", safe(url));
        v.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("episodes", v, "id=?", new String[]{String.valueOf(id)});
    }

    public void updateTranscript(long id, String transcript, String status) {
        if (id <= 0) return;
        ContentValues v = new ContentValues();
        v.put("transcript", safe(transcript));
        if (status != null) v.put("status", status);
        v.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("episodes", v, "id=?", new String[]{String.valueOf(id)});
    }

    public Episode getEpisode(long id) {
        Cursor c = getReadableDatabase().query("episodes", null, "id=?",
                new String[]{String.valueOf(id)}, null, null, null);
        try {
            return c.moveToFirst() ? fromCursor(c) : null;
        } finally { c.close(); }
    }

    public ArrayList<Episode> listEpisodes(String query) {
        ArrayList<Episode> out = new ArrayList<>();
        String q = safe(query).trim();
        Cursor c;
        if (q.isEmpty()) {
            c = getReadableDatabase().query("episodes", null, null, null, null, null,
                    "updated_at DESC", "200");
        } else {
            String like = "%" + q + "%";
            c = getReadableDatabase().query("episodes", null,
                    "program LIKE ? OR title LIKE ? OR transcript LIKE ?",
                    new String[]{like, like, like}, null, null, "updated_at DESC", "200");
        }
        try {
            while (c.moveToNext()) out.add(fromCursor(c));
        } finally { c.close(); }
        return out;
    }

    public int count() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM episodes", null);
        try { return c.moveToFirst() ? c.getInt(0) : 0; }
        finally { c.close(); }
    }

    public void deleteEpisode(long id) {
        getWritableDatabase().delete("episodes", "id=?", new String[]{String.valueOf(id)});
    }

    public String exportJson() {
        JSONArray arr = new JSONArray();
        for (Episode e : listEpisodes("")) {
            try {
                JSONObject o = new JSONObject();
                o.put("id", e.id);
                o.put("program", e.program);
                o.put("episode", e.title);
                o.put("url", e.url);
                o.put("status", e.status);
                o.put("startedAt", iso(e.startedAt));
                o.put("updatedAt", iso(e.updatedAt));
                o.put("transcript", e.transcript);
                arr.put(o);
            } catch (Exception ignored) {}
        }
        try {
            JSONObject root = new JSONObject();
            root.put("format", "radiko-transcriber-export-v1");
            root.put("exportedAt", iso(System.currentTimeMillis()));
            root.put("episodes", arr);
            return root.toString(2);
        } catch (Exception e) {
            return "{\"episodes\":[]}";
        }
    }

    public void migrateLegacyIfNeeded(Context context) {
        if (count() > 0) return;
        String old = context.getSharedPreferences("state", Context.MODE_PRIVATE)
                .getString("transcript", "");
        if (old == null || old.trim().isEmpty()) return;
        long id = createEpisode("", "旧バージョンから移行", "");
        updateTranscript(id, old, "imported");
    }

    private Episode fromCursor(Cursor c) {
        Episode e = new Episode();
        e.id = c.getLong(c.getColumnIndexOrThrow("id"));
        e.program = c.getString(c.getColumnIndexOrThrow("program"));
        e.title = c.getString(c.getColumnIndexOrThrow("title"));
        e.url = c.getString(c.getColumnIndexOrThrow("url"));
        e.transcript = c.getString(c.getColumnIndexOrThrow("transcript"));
        e.status = c.getString(c.getColumnIndexOrThrow("status"));
        e.startedAt = c.getLong(c.getColumnIndexOrThrow("started_at"));
        e.updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"));
        return e;
    }

    private static String safe(String s) { return s == null ? "" : s; }

    public static String displayDate(long time) {
        return new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN).format(new Date(time));
    }

    private static String iso(long time) {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.JAPAN).format(new Date(time));
    }
}

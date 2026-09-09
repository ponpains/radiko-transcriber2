package com.example.radikotranscriber;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** Small in-database history used only for explicit legacy reprocessing/restoration. */
public final class TranscriptHistoryV032 {
    public static final String VERSION = "transcript-history-v032-2026-09-09";
    private static final String TABLE = "transcript_versions_v032";

    public static final class Snapshot {
        public long id;
        public long episodeId;
        public String label = "";
        public String transcript = "";
        public String segmentsJson = "";
        public long createdAt;
    }

    private TranscriptHistoryV032() {}

    public static void backup(EpisodeStore store, long episodeId, String label) {
        if (store == null || episodeId <= 0) return;
        EpisodeStore.Episode e = store.getEpisode(episodeId);
        if (e == null) return;
        SQLiteDatabase db = store.getWritableDatabase();
        ensure(db);

        JSONArray segments = new JSONArray();
        try {
            for (EpisodeStore.Segment s : store.listSegments(episodeId)) {
                JSONObject o = new JSONObject();
                o.put("id", s.id);
                o.put("text", safe(s.text));
                segments.put(o);
            }
        } catch (Exception ignored) {}

        ContentValues v = new ContentValues();
        v.put("episode_id", episodeId);
        v.put("label", safe(label));
        v.put("transcript", safe(e.transcript));
        v.put("segments_json", segments.toString());
        v.put("created_at", System.currentTimeMillis());
        db.insert(TABLE, null, v);

        // Keep a small local undo history; the table lives inside the normal app database, so the
        // existing full backup also carries it automatically.
        try {
            db.execSQL("DELETE FROM " + TABLE + " WHERE episode_id=? AND id NOT IN (" +
                            "SELECT id FROM " + TABLE + " WHERE episode_id=? ORDER BY id DESC LIMIT 5)",
                    new Object[]{episodeId, episodeId});
        } catch (Exception ignored) {}
    }

    public static boolean hasBackup(EpisodeStore store, long episodeId) {
        return latest(store, episodeId) != null;
    }

    public static Snapshot latest(EpisodeStore store, long episodeId) {
        if (store == null || episodeId <= 0) return null;
        SQLiteDatabase db = store.getReadableDatabase();
        ensure(store.getWritableDatabase());
        Cursor c = db.query(TABLE, null, "episode_id=?", new String[]{String.valueOf(episodeId)},
                null, null, "id DESC", "1");
        try {
            if (!c.moveToFirst()) return null;
            Snapshot s = new Snapshot();
            s.id = c.getLong(c.getColumnIndexOrThrow("id"));
            s.episodeId = c.getLong(c.getColumnIndexOrThrow("episode_id"));
            s.label = c.getString(c.getColumnIndexOrThrow("label"));
            s.transcript = c.getString(c.getColumnIndexOrThrow("transcript"));
            s.segmentsJson = c.getString(c.getColumnIndexOrThrow("segments_json"));
            s.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
            return s;
        } finally { c.close(); }
    }

    public static void restore(EpisodeStore store, Snapshot snapshot) {
        if (store == null || snapshot == null || snapshot.episodeId <= 0) return;
        replaceFinal(store, snapshot.episodeId, snapshot.transcript);
        try {
            JSONArray a = new JSONArray(safe(snapshot.segmentsJson));
            HashMap<Long, String> texts = new HashMap<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                long id = o.optLong("id", -1L);
                if (id > 0) texts.put(id, o.optString("text", ""));
            }
            replaceSegments(store, texts);
        } catch (Exception ignored) {}
    }

    public static void replaceFinal(EpisodeStore store, long episodeId, String text) {
        if (store == null || episodeId <= 0) return;
        ContentValues v = new ContentValues();
        v.put("transcript", safe(text));
        v.put("updated_at", System.currentTimeMillis());
        store.getWritableDatabase().update("episodes", v, "id=?",
                new String[]{String.valueOf(episodeId)});
    }

    public static void replaceSegments(EpisodeStore store, Map<Long, String> texts) {
        if (store == null || texts == null || texts.isEmpty()) return;
        SQLiteDatabase db = store.getWritableDatabase();
        db.beginTransaction();
        try {
            for (Map.Entry<Long, String> entry : texts.entrySet()) {
                ContentValues v = new ContentValues();
                v.put("text", safe(entry.getValue()));
                db.update("segments", v, "id=?", new String[]{String.valueOf(entry.getKey())});
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    private static void ensure(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "episode_id INTEGER NOT NULL," +
                "label TEXT NOT NULL DEFAULT ''," +
                "transcript TEXT NOT NULL DEFAULT ''," +
                "segments_json TEXT NOT NULL DEFAULT ''," +
                "created_at INTEGER NOT NULL," +
                "FOREIGN KEY(episode_id) REFERENCES episodes(id) ON DELETE CASCADE)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_transcript_versions_v032_episode ON " +
                TABLE + "(episode_id, id DESC)");
    }

    private static String safe(String s) { return s == null ? "" : s; }
}

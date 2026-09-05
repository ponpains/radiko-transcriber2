package com.example.radikotranscriber;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/** Internal diagnostics for assistant debugging. Audio itself is never included. */
public class DiagnosticStore extends SQLiteOpenHelper {
    private static final String DB_NAME = "radiko_diagnostics.db";
    private static final int DB_VERSION = 1;
    private final Context appContext;

    public DiagnosticStore(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
        appContext = context.getApplicationContext();
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE events (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "episode_id INTEGER NOT NULL DEFAULT -1," +
                "at_ms INTEGER NOT NULL," +
                "kind TEXT NOT NULL," +
                "detail TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE INDEX idx_diag_episode ON events(episode_id, at_ms)");
        db.execSQL("CREATE INDEX idx_diag_time ON events(at_ms)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS events");
        onCreate(db);
    }

    public void log(long episodeId, String kind, String detail) {
        try {
            ContentValues v = new ContentValues();
            v.put("episode_id", episodeId);
            v.put("at_ms", System.currentTimeMillis());
            v.put("kind", safe(kind));
            v.put("detail", safe(detail));
            getWritableDatabase().insert("events", null, v);
            pruneOld();
        } catch (Exception ignored) {}
    }

    private void pruneOld() {
        try {
            long cutoff = System.currentTimeMillis() - 45L * 24L * 60L * 60L * 1000L;
            getWritableDatabase().delete("events", "at_ms<?", new String[]{String.valueOf(cutoff)});
        } catch (Exception ignored) {}
    }

    public String exportPack(EpisodeStore store, int maxEpisodes) {
        try {
            JSONObject root = new JSONObject();
            root.put("format", "radiko-transcriber-diagnostics-v3");
            root.put("generatedAt", iso(System.currentTimeMillis()));
            root.put("purpose", "assistant-debugging");
            root.put("audioIncluded", false);

            JSONObject app = new JSONObject();
            app.put("package", appContext.getPackageName());
            try {
                android.content.pm.PackageInfo info = appContext.getPackageManager()
                        .getPackageInfo(appContext.getPackageName(), 0);
                app.put("versionName", info.versionName == null ? "" : info.versionName);
                app.put("versionCode", info.getLongVersionCode());
            } catch (Exception ignored) {}
            app.put("manufacturer", Build.MANUFACTURER);
            app.put("model", Build.MODEL);
            app.put("device", Build.DEVICE);
            app.put("sdk", Build.VERSION.SDK_INT);
            app.put("android", Build.VERSION.RELEASE);
            root.put("app", app);

            JSONArray episodes = new JSONArray();
            ArrayList<EpisodeStore.Episode> all = store.listEpisodes("");
            int n = Math.min(Math.max(1, maxEpisodes), all.size());
            for (int i = 0; i < n; i++) {
                EpisodeStore.Episode e = all.get(i);
                JSONObject o = new JSONObject();
                o.put("id", e.id);
                o.put("program", e.program);
                o.put("episode", e.title);
                o.put("url", e.url);
                o.put("status", e.status);
                o.put("playbackSpeed", e.playbackSpeed);
                o.put("durationMs", e.durationMs);
                o.put("startedAt", iso(e.startedAt));
                o.put("updatedAt", iso(e.updatedAt));
                o.put("tags", e.tags);
                o.put("keyPoints", e.keyPoints);
                o.put("notes", e.notes);
                o.put("rawTranscript", e.rawTranscript);
                o.put("autoTranscript", e.autoTranscript);
                o.put("finalTranscript", e.transcript);
                o.put("formatLearning", FormatLearningStore.toJson(appContext, e.program));
                o.put("programContext", KerekereContextProfile.describe(e.program));

                JSONArray segs = new JSONArray();
                for (EpisodeStore.Segment s : store.listSegments(e.id)) {
                    JSONObject so = new JSONObject();
                    so.put("startMs", s.startMs);
                    so.put("endMs", s.endMs);
                    so.put("raw", s.rawText);
                    so.put("text", s.text);
                    so.put("topicBreak", s.topicBreak);
                    segs.put(so);
                }
                o.put("segments", segs);

                JSONArray rules = new JSONArray();
                for (EpisodeStore.Correction c : store.getCorrections(e.program)) {
                    JSONObject co = new JSONObject();
                    co.put("program", c.program);
                    co.put("wrong", c.wrong);
                    co.put("correct", c.correct);
                    co.put("uses", c.uses);
                    rules.put(co);
                }
                o.put("corrections", rules);
                o.put("diagnostics", eventsForEpisode(e.id));
                episodes.put(o);
            }
            root.put("episodes", episodes);
            return root.toString(2);
        } catch (Exception e) {
            return "{\"format\":\"radiko-transcriber-diagnostics-v3\",\"error\":\"export_failed\"}";
        }
    }

    private JSONArray eventsForEpisode(long episodeId) {
        JSONArray out = new JSONArray();
        Cursor c = getReadableDatabase().query("events", null, "episode_id=?",
                new String[]{String.valueOf(episodeId)}, null, null, "at_ms ASC", "6000");
        try {
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                try {
                    long at = c.getLong(c.getColumnIndexOrThrow("at_ms"));
                    o.put("atMs", at);
                    o.put("at", iso(at));
                    o.put("kind", c.getString(c.getColumnIndexOrThrow("kind")));
                    o.put("detail", c.getString(c.getColumnIndexOrThrow("detail")));
                    out.put(o);
                } catch (Exception ignored) {}
            }
        } finally { c.close(); }
        return out;
    }

    private static String iso(long time) {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.JAPAN)
                .format(new Date(time));
    }

    private static String safe(String s) { return s == null ? "" : s; }
}

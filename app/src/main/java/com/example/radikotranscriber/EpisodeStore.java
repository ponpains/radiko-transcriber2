package com.example.radikotranscriber;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URLDecoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;

public class EpisodeStore extends SQLiteOpenHelper {
    private static final String DB_NAME = "radiko_transcripts.db";
    private static final int DB_VERSION = 3;
    private final Context appContext;

    public static class Episode {
        public long id;
        public String program = "";
        public String title = "";
        public String url = "";
        public String rawTranscript = "";
        public String autoTranscript = "";
        public String transcript = "";
        public String status = "saved";
        public float playbackSpeed = 1.0f;
        public String notes = "";
        public String tags = "";
        public String keyPoints = "";
        public long mediaStartMs = 0L;
        public long durationMs = 0L;
        public long startedAt;
        public long updatedAt;
    }

    public static class Segment {
        public long id;
        public long episodeId;
        public long startMs;
        public long endMs;
        public String rawText = "";
        public String text = "";
        public boolean topicBreak;
    }

    public static class Correction {
        public long id;
        public String program = "";
        public String wrong = "";
        public String correct = "";
        public int uses;
    }

    public static class ProgramStats {
        public String program = "";
        public int episodeCount;
        public long totalChars;
        public long totalDurationMs;
        public long lastUpdatedAt;
    }

    public EpisodeStore(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
        appContext = context.getApplicationContext();
    }

    @Override public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE episodes (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "program TEXT NOT NULL DEFAULT ''," +
                "title TEXT NOT NULL DEFAULT ''," +
                "url TEXT NOT NULL DEFAULT ''," +
                "raw_transcript TEXT NOT NULL DEFAULT ''," +
                "auto_transcript TEXT NOT NULL DEFAULT ''," +
                "transcript TEXT NOT NULL DEFAULT ''," +
                "status TEXT NOT NULL DEFAULT 'saved'," +
                "playback_speed REAL NOT NULL DEFAULT 1.0," +
                "notes TEXT NOT NULL DEFAULT ''," +
                "tags TEXT NOT NULL DEFAULT ''," +
                "key_points TEXT NOT NULL DEFAULT ''," +
                "media_start_ms INTEGER NOT NULL DEFAULT 0," +
                "duration_ms INTEGER NOT NULL DEFAULT 0," +
                "started_at INTEGER NOT NULL," +
                "updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_episodes_updated ON episodes(updated_at DESC)");
        createCorrectionsTable(db);
        createSegmentsTable(db);
    }

    private void createCorrectionsTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS corrections (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "program TEXT NOT NULL DEFAULT ''," +
                "wrong TEXT NOT NULL," +
                "correct TEXT NOT NULL," +
                "uses INTEGER NOT NULL DEFAULT 1," +
                "created_at INTEGER NOT NULL," +
                "updated_at INTEGER NOT NULL," +
                "UNIQUE(program, wrong, correct))");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_corrections_program ON corrections(program, uses DESC)");
    }

    private void createSegmentsTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS segments (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "episode_id INTEGER NOT NULL," +
                "start_ms INTEGER NOT NULL DEFAULT 0," +
                "end_ms INTEGER NOT NULL DEFAULT 0," +
                "raw_text TEXT NOT NULL DEFAULT ''," +
                "text TEXT NOT NULL DEFAULT ''," +
                "topic_break INTEGER NOT NULL DEFAULT 0," +
                "FOREIGN KEY(episode_id) REFERENCES episodes(id) ON DELETE CASCADE)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_segments_episode ON segments(episode_id, start_ms)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            try { db.execSQL("ALTER TABLE episodes ADD COLUMN raw_transcript TEXT NOT NULL DEFAULT ''"); } catch (Exception ignored) {}
            try { db.execSQL("ALTER TABLE episodes ADD COLUMN auto_transcript TEXT NOT NULL DEFAULT ''"); } catch (Exception ignored) {}
            try { db.execSQL("ALTER TABLE episodes ADD COLUMN playback_speed REAL NOT NULL DEFAULT 1.0"); } catch (Exception ignored) {}
            try { db.execSQL("UPDATE episodes SET raw_transcript=transcript WHERE raw_transcript='' OR raw_transcript IS NULL"); } catch (Exception ignored) {}
            try { db.execSQL("UPDATE episodes SET auto_transcript=transcript WHERE auto_transcript='' OR auto_transcript IS NULL"); } catch (Exception ignored) {}
            createCorrectionsTable(db);
        }
        if (oldVersion < 3) {
            try { db.execSQL("ALTER TABLE episodes ADD COLUMN notes TEXT NOT NULL DEFAULT ''"); } catch (Exception ignored) {}
            try { db.execSQL("ALTER TABLE episodes ADD COLUMN tags TEXT NOT NULL DEFAULT ''"); } catch (Exception ignored) {}
            try { db.execSQL("ALTER TABLE episodes ADD COLUMN key_points TEXT NOT NULL DEFAULT ''"); } catch (Exception ignored) {}
            try { db.execSQL("ALTER TABLE episodes ADD COLUMN media_start_ms INTEGER NOT NULL DEFAULT 0"); } catch (Exception ignored) {}
            try { db.execSQL("ALTER TABLE episodes ADD COLUMN duration_ms INTEGER NOT NULL DEFAULT 0"); } catch (Exception ignored) {}
            createSegmentsTable(db);
        }
    }

    public long createEpisode(String program, String title, String url) {
        long now = System.currentTimeMillis();
        ContentValues v = new ContentValues();
        v.put("program", safe(program));
        v.put("title", safe(title));
        v.put("url", safe(url));
        v.put("raw_transcript", "");
        v.put("auto_transcript", "");
        v.put("transcript", "");
        v.put("status", "saved");
        v.put("playback_speed", 1.0f);
        v.put("notes", "");
        v.put("tags", "");
        v.put("key_points", "");
        v.put("media_start_ms", parseMediaStartMs(url));
        v.put("duration_ms", 0L);
        v.put("started_at", now);
        v.put("updated_at", now);
        long id = getWritableDatabase().insertOrThrow("episodes", null, v);
        ensureStarterCorrections(program);
        return id;
    }

    public void updateMeta(long id, String program, String title, String url) {
        if (id <= 0) return;
        ContentValues v = new ContentValues();
        v.put("program", safe(program));
        v.put("title", safe(title));
        v.put("url", safe(url));
        v.put("media_start_ms", parseMediaStartMs(url));
        v.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("episodes", v, "id=?", new String[]{String.valueOf(id)});
        ensureStarterCorrections(program);
    }

    public void updateEpisodeExtras(long id, String notes, String tags, String keyPoints) {
        if (id <= 0) return;
        ContentValues v = new ContentValues();
        v.put("notes", safe(notes));
        v.put("tags", safe(tags));
        v.put("key_points", safe(keyPoints));
        v.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("episodes", v, "id=?", new String[]{String.valueOf(id)});
    }

    public void updateRecognition(long id, String rawTranscript, String autoTranscript,
                                  String status, float playbackSpeed, long durationMs) {
        if (id <= 0) return;
        String auto = safe(autoTranscript);
        String program = programForEpisode(id);
        String formatted = FormatLearningStore.apply(appContext, program, auto);
        ContentValues v = new ContentValues();
        v.put("raw_transcript", safe(rawTranscript));
        v.put("auto_transcript", auto);
        v.put("transcript", formatted);
        if (status != null) v.put("status", status);
        v.put("playback_speed", playbackSpeed);
        v.put("duration_ms", Math.max(0L, durationMs));
        v.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("episodes", v, "id=?", new String[]{String.valueOf(id)});
    }

    public void updateEditedTranscript(long id, String transcript) {
        if (id <= 0) return;
        Episode e = getEpisode(id);
        if (e != null && !safe(transcript).equals(e.autoTranscript)) {
            FormatLearningStore.learn(appContext, e.program, e.autoTranscript, transcript);
        }
        ContentValues v = new ContentValues();
        v.put("transcript", safe(transcript));
        v.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("episodes", v, "id=?", new String[]{String.valueOf(id)});
    }

    private void updateLearningBaseline(long id, String text) {
        ContentValues v = new ContentValues();
        v.put("auto_transcript", safe(text));
        v.put("transcript", safe(text));
        v.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("episodes", v, "id=?", new String[]{String.valueOf(id)});
    }

    private String programForEpisode(long id) {
        Cursor c = getReadableDatabase().query("episodes", new String[]{"program"}, "id=?",
                new String[]{String.valueOf(id)}, null, null, null);
        try { return c.moveToFirst() ? safe(c.getString(0)) : ""; }
        finally { c.close(); }
    }

    public Episode getEpisode(long id) {
        Cursor c = getReadableDatabase().query("episodes", null, "id=?", new String[]{String.valueOf(id)}, null, null, null);
        try { return c.moveToFirst() ? fromCursor(c) : null; }
        finally { c.close(); }
    }

    public ArrayList<Episode> listEpisodes(String query) { return listEpisodes(query, ""); }

    public ArrayList<Episode> listEpisodes(String query, String programFilter) {
        return listEpisodesInternal(query, programFilter, "1000");
    }

    public ArrayList<Episode> listAllEpisodes() {
        return listEpisodesInternal("", "", null);
    }

    private ArrayList<Episode> listEpisodesInternal(String query, String programFilter, String limit) {
        ArrayList<Episode> out = new ArrayList<>();
        String q = safe(query).trim();
        String pf = safe(programFilter).trim();
        ArrayList<String> where = new ArrayList<>();
        ArrayList<String> args = new ArrayList<>();
        if (!q.isEmpty()) {
            where.add("(program LIKE ? OR title LIKE ? OR transcript LIKE ? OR raw_transcript LIKE ? OR notes LIKE ? OR tags LIKE ? OR key_points LIKE ?)");
            String like = "%" + q + "%";
            for (int i = 0; i < 7; i++) args.add(like);
        }
        if (!pf.isEmpty()) { where.add("program=?"); args.add(pf); }
        String selection = where.isEmpty() ? null : join(where, " AND ");
        String[] selectionArgs = args.isEmpty() ? null : args.toArray(new String[0]);
        Cursor c = getReadableDatabase().query("episodes", null, selection, selectionArgs, null, null,
                "updated_at DESC", limit);
        try { while (c.moveToNext()) out.add(fromCursor(c)); }
        finally { c.close(); }
        return out;
    }

    public ArrayList<String> listPrograms() {
        ArrayList<String> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT DISTINCT program FROM episodes WHERE TRIM(program)<>'' ORDER BY program COLLATE NOCASE", null);
        try { while (c.moveToNext()) out.add(c.getString(0)); }
        finally { c.close(); }
        return out;
    }

    public ProgramStats getProgramStats(String program) {
        ProgramStats s = new ProgramStats();
        s.program = safe(program);
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*), COALESCE(SUM(LENGTH(transcript)),0), COALESCE(SUM(duration_ms),0), COALESCE(MAX(updated_at),0) FROM episodes WHERE program=?",
                new String[]{s.program});
        try {
            if (c.moveToFirst()) {
                s.episodeCount = c.getInt(0);
                s.totalChars = c.getLong(1);
                s.totalDurationMs = c.getLong(2);
                s.lastUpdatedAt = c.getLong(3);
            }
        } finally { c.close(); }
        return s;
    }

    public int count() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM episodes", null);
        try { return c.moveToFirst() ? c.getInt(0) : 0; }
        finally { c.close(); }
    }

    public void deleteEpisode(long id) {
        getWritableDatabase().delete("episodes", "id=?", new String[]{String.valueOf(id)});
    }

    public long addSegment(long episodeId, long startMs, long endMs, String rawText, String text, boolean topicBreak) {
        if (episodeId <= 0) return -1L;
        ContentValues v = new ContentValues();
        v.put("episode_id", episodeId);
        v.put("start_ms", Math.max(0L, startMs));
        v.put("end_ms", Math.max(startMs, endMs));
        v.put("raw_text", safe(rawText));
        v.put("text", safe(text));
        v.put("topic_break", topicBreak ? 1 : 0);
        return getWritableDatabase().insert("segments", null, v);
    }

    public ArrayList<Segment> listSegments(long episodeId) {
        ArrayList<Segment> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("segments", null, "episode_id=?",
                new String[]{String.valueOf(episodeId)}, null, null, "start_ms ASC, id ASC");
        try {
            while (c.moveToNext()) {
                Segment s = new Segment();
                s.id = c.getLong(c.getColumnIndexOrThrow("id"));
                s.episodeId = c.getLong(c.getColumnIndexOrThrow("episode_id"));
                s.startMs = c.getLong(c.getColumnIndexOrThrow("start_ms"));
                s.endMs = c.getLong(c.getColumnIndexOrThrow("end_ms"));
                s.rawText = c.getString(c.getColumnIndexOrThrow("raw_text"));
                s.text = c.getString(c.getColumnIndexOrThrow("text"));
                s.topicBreak = c.getInt(c.getColumnIndexOrThrow("topic_break")) != 0;
                out.add(s);
            }
        } finally { c.close(); }
        return out;
    }

    public String transcriptWithTimestamps(long episodeId) {
        ArrayList<Segment> list = listSegments(episodeId);
        if (list.isEmpty()) {
            Episode e = getEpisode(episodeId);
            return e == null ? "" : e.transcript;
        }
        StringBuilder out = new StringBuilder();
        boolean first = true;
        for (Segment s : list) {
            if (!first) out.append(s.topicBreak ? "\n\n" : "\n");
            out.append("[").append(timeLabel(s.startMs)).append("] ").append(s.text.trim());
            first = false;
        }
        return out.toString();
    }

    public void addCorrection(String program, String wrong, String correct) {
        addCorrectionInternal(program, wrong, correct, true);
    }
    private void ensureCorrection(String program, String wrong, String correct) {
        addCorrectionInternal(program, wrong, correct, false);
    }

    private void addCorrectionInternal(String program, String wrong, String correct, boolean incrementExisting) {
        program = safe(program).trim();
        wrong = safe(wrong).trim();
        correct = safe(correct).trim();
        if (wrong.length() < 2 || wrong.length() > 40 || correct.isEmpty() || correct.length() > 40) return;
        if (semantic(wrong).equals(semantic(correct))) return;
        SQLiteDatabase db = getWritableDatabase();
        long now = System.currentTimeMillis();
        Cursor c = db.query("corrections", new String[]{"id", "uses"}, "program=? AND wrong=? AND correct=?",
                new String[]{program, wrong, correct}, null, null, null);
        try {
            if (c.moveToFirst()) {
                if (incrementExisting) {
                    ContentValues u = new ContentValues();
                    u.put("uses", c.getInt(1) + 1);
                    u.put("updated_at", now);
                    db.update("corrections", u, "id=?", new String[]{String.valueOf(c.getLong(0))});
                }
                return;
            }
        } finally { c.close(); }
        ContentValues v = new ContentValues();
        v.put("program", program);
        v.put("wrong", wrong);
        v.put("correct", correct);
        v.put("uses", 1);
        v.put("created_at", now);
        v.put("updated_at", now);
        db.insert("corrections", null, v);
    }

    public ArrayList<Correction> getCorrections(String program) {
        ArrayList<Correction> out = new ArrayList<>();
        String p = safe(program).trim();
        Cursor c;
        if (p.isEmpty()) {
            c = getReadableDatabase().query("corrections", null, "program=''", null, null, null,
                    "uses DESC, LENGTH(wrong) DESC", "300");
        } else {
            c = getReadableDatabase().query("corrections", null, "program=? OR program=''",
                    new String[]{p}, null, null,
                    "CASE WHEN program='" + sqlLiteral(p) + "' THEN 0 ELSE 1 END, uses DESC, LENGTH(wrong) DESC", "300");
        }
        try {
            while (c.moveToNext()) {
                Correction r = new Correction();
                r.id = c.getLong(c.getColumnIndexOrThrow("id"));
                r.program = c.getString(c.getColumnIndexOrThrow("program"));
                // Old versions sometimes mixed line breaks into learned word replacements.
                // Normalize them at read time so they no longer control paragraph layout.
                r.wrong = normalizeLexical(c.getString(c.getColumnIndexOrThrow("wrong")));
                r.correct = normalizeLexical(c.getString(c.getColumnIndexOrThrow("correct")));
                r.uses = c.getInt(c.getColumnIndexOrThrow("uses"));
                if (!r.wrong.isEmpty() && !r.correct.isEmpty()) out.add(r);
            }
        } finally { c.close(); }
        return out;
    }

    public void deleteCorrection(long id) {
        getWritableDatabase().delete("corrections", "id=?", new String[]{String.valueOf(id)});
    }

    public void adjustCorrectionPriority(long id, int delta) {
        Cursor c = getReadableDatabase().query("corrections", new String[]{"uses"}, "id=?",
                new String[]{String.valueOf(id)}, null, null, null);
        int uses = 1;
        try { if (c.moveToFirst()) uses = c.getInt(0); }
        finally { c.close(); }
        ContentValues v = new ContentValues();
        v.put("uses", Math.max(1, uses + delta));
        v.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("corrections", v, "id=?", new String[]{String.valueOf(id)});
    }

    public String applyCorrections(String program, String text) {
        String out = safe(text);
        for (Correction r : getCorrections(program)) {
            if (!r.wrong.isEmpty() && out.contains(r.wrong)) out = out.replace(r.wrong, r.correct);
        }
        return out;
    }

    public ArrayList<String> getBiasStrings(String program, String title) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        addUsefulBias(set, safe(program));
        addUsefulBias(set, safe(title));
        for (Correction r : getCorrections(program)) {
            String corrected = normalizeLexical(r.correct);
            if (corrected.length() >= 2) set.add(corrected);
            if (set.size() >= 50) break;
        }
        return new ArrayList<>(set);
    }

    private void addUsefulBias(LinkedHashSet<String> set, String s) {
        s = s.trim();
        if (s.isEmpty()) return;
        if (s.length() <= 50) set.add(s);
        for (String x : s.split("[\\s/／・|｜,，:：()（）]+")) {
            x = x.trim();
            if (x.length() >= 2 && x.length() <= 30) set.add(x);
        }
    }

    public String chooseBestCandidate(String program, ArrayList<String> candidates, float[] confidence) {
        if (candidates == null || candidates.isEmpty()) return "";
        ArrayList<Correction> rules = getCorrections(program);
        double best = -1e9;
        String bestText = candidates.get(0);
        for (int i = 0; i < candidates.size(); i++) {
            String candidate = candidates.get(i) == null ? "" : candidates.get(i);
            double score = confidence != null && i < confidence.length && confidence[i] >= 0
                    ? confidence[i] * 3.0 : 0.0;
            for (Correction r : rules) {
                if (candidate.contains(r.correct)) score += 5.0 + Math.min(r.uses, 8);
                if (candidate.contains(r.wrong)) score -= 4.0 + Math.min(r.uses, 8);
            }
            if (score > best) { best = score; bestText = candidate; }
        }
        return applyCorrections(program, bestText);
    }

    public int learnCorrectionsFromEdit(long episodeId, String editedText) {
        Episode e = getEpisode(episodeId);
        if (e == null) return 0;
        String before = safe(e.autoTranscript), after = safe(editedText);
        if (before.equals(after)) return 0;

        boolean formattingLearned = FormatLearningStore.learn(appContext, e.program, before, after);
        ArrayList<String[]> pairs = new ArrayList<>();
        collectDiffs(before, after, pairs, 0);
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        int learned = 0;
        for (String[] pair : pairs) {
            // Newlines/extra spaces are layout information, not lexical corrections.
            String wrong = normalizeLexical(cleanupDiff(pair[0]));
            String correct = normalizeLexical(cleanupDiff(pair[1]));
            if (wrong.length() < 2 || wrong.length() > 40 || correct.isEmpty() || correct.length() > 40) continue;
            if (semantic(wrong).equals(semantic(correct))) continue;
            String key = wrong + "\u0000" + correct;
            if (!seen.add(key)) continue;
            addCorrection(e.program, wrong, correct);
            learned++;
        }
        updateLearningBaseline(episodeId, after);
        return learned + (formattingLearned ? 1 : 0);
    }

    private void collectDiffs(String a, String b, ArrayList<String[]> out, int depth) {
        if (depth > 20 || a.equals(b)) return;
        int prefix = commonPrefix(a, b);
        a = a.substring(prefix);
        b = b.substring(prefix);
        int suffix = commonSuffix(a, b);
        String am = suffix == 0 ? a : a.substring(0, a.length() - suffix);
        String bm = suffix == 0 ? b : b.substring(0, b.length() - suffix);
        if (am.isEmpty() && bm.isEmpty()) return;
        if (semantic(am).equals(semantic(bm))) return;
        if (am.length() <= 40 && bm.length() <= 40) {
            out.add(new String[]{am, bm});
            return;
        }
        int[] anchor = findAnchor(am, bm);
        if (anchor == null) return;
        collectDiffs(am.substring(0, anchor[0]), bm.substring(0, anchor[1]), out, depth + 1);
        collectDiffs(am.substring(anchor[0] + anchor[2]), bm.substring(anchor[1] + anchor[2]), out, depth + 1);
    }

    private int[] findAnchor(String a, String b) {
        if (a.length() < 8 || b.length() < 8) return null;
        int bestA = -1, bestB = -1, bestLen = 0;
        for (int i = 0; i <= a.length() - 8; i += 3) {
            String token = a.substring(i, i + 8);
            int j = b.indexOf(token);
            if (j < 0) continue;
            int len = 8;
            while (i + len < a.length() && j + len < b.length()
                    && a.charAt(i + len) == b.charAt(j + len)) len++;
            if (len > bestLen) { bestA = i; bestB = j; bestLen = len; }
        }
        return bestLen >= 8 ? new int[]{bestA, bestB, bestLen} : null;
    }

    private int commonPrefix(String a, String b) {
        int n = Math.min(a.length(), b.length()), i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) i++;
        return i;
    }

    private int commonSuffix(String a, String b) {
        int n = Math.min(a.length(), b.length()), i = 0;
        while (i < n && a.charAt(a.length() - 1 - i) == b.charAt(b.length() - 1 - i)) i++;
        return i;
    }

    private String cleanupDiff(String s) {
        return safe(s).replaceAll("^[\\s、。！？!?，,.・：；]+|[\\s、。！？!?，,.・：；]+$", "").trim();
    }

    private static String normalizeLexical(String s) {
        return safe(s).replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ')
                .replaceAll("[ \\t]+", " ").trim();
    }

    private String semantic(String s) {
        return safe(s).replaceAll("[\\s、。！？!?，,.・：；\"'「」『』（）()]+", "");
    }

    private void ensureStarterCorrections(String program) {
        String p = safe(program).trim();
        if (p.contains("けれけれ")) {
            ensureCorrection(p, "キレキレ", "けれけれ");
            ensureCorrection(p, "キレレ", "けれけれ");
            ensureCorrection(p, "テレテレ", "けれけれ");
            ensureCorrection(p, "中田詩織", "永田詩央里");
            ensureCorrection(p, "中田詩央里", "永田詩央里");
            ensureCorrection(p, "永田詩織", "永田詩央里");
            ensureCorrection(p, "長田詩織", "永田詩央里");
        }
    }

    public String exportJson() { return exportBackupJson(); }

    public String exportBackupJson() {
        try {
            JSONObject root = new JSONObject();
            root.put("format", "radiko-transcriber-backup-v4");
            root.put("exportedAt", iso(System.currentTimeMillis()));
            JSONArray eps = new JSONArray();
            for (Episode e : listAllEpisodes()) {
                JSONObject o = new JSONObject();
                o.put("id", e.id);
                o.put("program", e.program);
                o.put("episode", e.title);
                o.put("url", e.url);
                o.put("status", e.status);
                o.put("playbackSpeed", e.playbackSpeed);
                o.put("notes", e.notes);
                o.put("tags", e.tags);
                o.put("keyPoints", e.keyPoints);
                o.put("mediaStartMs", e.mediaStartMs);
                o.put("durationMs", e.durationMs);
                o.put("startedAtMs", e.startedAt);
                o.put("updatedAtMs", e.updatedAt);
                o.put("startedAt", iso(e.startedAt));
                o.put("updatedAt", iso(e.updatedAt));
                o.put("rawTranscript", e.rawTranscript);
                o.put("autoTranscript", e.autoTranscript);
                o.put("transcript", e.transcript);
                JSONArray segs = new JSONArray();
                for (Segment s : listSegments(e.id)) {
                    JSONObject so = new JSONObject();
                    so.put("startMs", s.startMs);
                    so.put("endMs", s.endMs);
                    so.put("rawText", s.rawText);
                    so.put("text", s.text);
                    so.put("topicBreak", s.topicBreak);
                    segs.put(so);
                }
                o.put("segments", segs);
                eps.put(o);
            }
            root.put("episodes", eps);

            JSONArray cs = new JSONArray();
            Cursor c = getReadableDatabase().query("corrections", null, null, null, null, null, "program, uses DESC");
            try {
                while (c.moveToNext()) {
                    JSONObject o = new JSONObject();
                    o.put("program", c.getString(c.getColumnIndexOrThrow("program")));
                    o.put("wrong", c.getString(c.getColumnIndexOrThrow("wrong")));
                    o.put("correct", c.getString(c.getColumnIndexOrThrow("correct")));
                    o.put("uses", c.getInt(c.getColumnIndexOrThrow("uses")));
                    cs.put(o);
                }
            } finally { c.close(); }
            root.put("corrections", cs);

            JSONArray formats = new JSONArray();
            for (String p : listPrograms()) formats.put(FormatLearningStore.toJson(appContext, p));
            root.put("formatLearning", formats);
            return root.toString(2);
        } catch (Exception e) {
            return "{\"format\":\"radiko-transcriber-backup-v4\",\"episodes\":[],\"corrections\":[]}";
        }
    }

    public String exportCsv() {
        StringBuilder out = new StringBuilder();
        out.append("id,program,episode,started_at,updated_at,status,playback_speed,duration,media_start,url,tags,key_points,notes,transcript\r\n");
        for (Episode e : listAllEpisodes()) {
            out.append(e.id).append(',').append(csv(e.program)).append(',').append(csv(e.title)).append(',')
                    .append(csv(iso(e.startedAt))).append(',').append(csv(iso(e.updatedAt))).append(',')
                    .append(csv(e.status)).append(',').append(e.playbackSpeed).append(',')
                    .append(csv(formatDuration(e.durationMs))).append(',').append(csv(timeLabel(e.mediaStartMs))).append(',')
                    .append(csv(e.url)).append(',').append(csv(e.tags)).append(',').append(csv(e.keyPoints)).append(',')
                    .append(csv(e.notes)).append(',').append(csv(e.transcript)).append("\r\n");
        }
        return out.toString();
    }

    public int importBackupJson(String json, boolean replace) throws Exception {
        JSONObject root = new JSONObject(json);
        JSONArray eps = root.optJSONArray("episodes");
        JSONArray cs = root.optJSONArray("corrections");
        JSONArray formats = root.optJSONArray("formatLearning");
        SQLiteDatabase db = getWritableDatabase();
        int imported = 0;
        db.beginTransaction();
        try {
            if (replace) {
                db.delete("segments", null, null);
                db.delete("episodes", null, null);
                db.delete("corrections", null, null);
            }
            HashMap<Long, Long> idMap = new HashMap<>();
            if (eps != null) {
                for (int i = 0; i < eps.length(); i++) {
                    JSONObject o = eps.getJSONObject(i);
                    ContentValues v = new ContentValues();
                    v.put("program", o.optString("program", ""));
                    v.put("title", o.optString("episode", o.optString("title", "")));
                    v.put("url", o.optString("url", ""));
                    v.put("raw_transcript", o.optString("rawTranscript", o.optString("transcript", "")));
                    v.put("auto_transcript", o.optString("autoTranscript", o.optString("transcript", "")));
                    v.put("transcript", o.optString("transcript", ""));
                    v.put("status", o.optString("status", "saved"));
                    v.put("playback_speed", (float)o.optDouble("playbackSpeed", 1.0));
                    v.put("notes", o.optString("notes", ""));
                    v.put("tags", o.optString("tags", ""));
                    v.put("key_points", o.optString("keyPoints", ""));
                    v.put("media_start_ms", o.optLong("mediaStartMs", parseMediaStartMs(o.optString("url", ""))));
                    v.put("duration_ms", o.optLong("durationMs", 0L));
                    v.put("started_at", o.optLong("startedAtMs", System.currentTimeMillis()));
                    v.put("updated_at", o.optLong("updatedAtMs", System.currentTimeMillis()));
                    long newId = db.insertOrThrow("episodes", null, v);
                    idMap.put(o.optLong("id", -1L), newId);
                    JSONArray segs = o.optJSONArray("segments");
                    if (segs != null) {
                        for (int j = 0; j < segs.length(); j++) {
                            JSONObject so = segs.getJSONObject(j);
                            ContentValues sv = new ContentValues();
                            sv.put("episode_id", newId);
                            sv.put("start_ms", so.optLong("startMs", 0L));
                            sv.put("end_ms", so.optLong("endMs", 0L));
                            sv.put("raw_text", so.optString("rawText", ""));
                            sv.put("text", so.optString("text", ""));
                            sv.put("topic_break", so.optBoolean("topicBreak", false) ? 1 : 0);
                            db.insert("segments", null, sv);
                        }
                    }
                    imported++;
                }
            }
            if (cs != null) {
                for (int i = 0; i < cs.length(); i++) {
                    JSONObject o = cs.getJSONObject(i);
                    ContentValues v = new ContentValues();
                    v.put("program", o.optString("program", ""));
                    v.put("wrong", o.optString("wrong", ""));
                    v.put("correct", o.optString("correct", ""));
                    v.put("uses", Math.max(1, o.optInt("uses", 1)));
                    v.put("created_at", System.currentTimeMillis());
                    v.put("updated_at", System.currentTimeMillis());
                    db.insertWithOnConflict("corrections", null, v, SQLiteDatabase.CONFLICT_REPLACE);
                }
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }

        if (formats != null) {
            for (int i = 0; i < formats.length(); i++) {
                FormatLearningStore.fromJson(appContext, formats.optJSONObject(i));
            }
        }
        return imported;
    }

    public void autoBackup(Context context) {
        try {
            File dir = context.getFilesDir();
            File b0 = new File(dir, "radio_backup_0.json");
            File b1 = new File(dir, "radio_backup_1.json");
            File b2 = new File(dir, "radio_backup_2.json");
            if (b2.exists()) b2.delete();
            if (b1.exists()) b1.renameTo(b2);
            if (b0.exists()) b0.renameTo(b1);
            try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(b0), "UTF-8")) {
                w.write(exportBackupJson());
            }
        } catch (Exception ignored) {}
    }

    public int restoreLatestAutoBackup(Context context) throws Exception {
        File f = new File(context.getFilesDir(), "radio_backup_0.json");
        if (!f.exists()) throw new Exception("自動バックアップがありません");
        StringBuilder s = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) s.append(line).append('\n');
        }
        return importBackupJson(s.toString(), true);
    }

    public void migrateLegacyIfNeeded(Context context) {
        if (count() > 0) return;
        String old = context.getSharedPreferences("state", Context.MODE_PRIVATE).getString("transcript", "");
        if (old == null || old.trim().isEmpty()) return;
        long id = createEpisode("", "旧バージョンから移行", "");
        updateRecognition(id, old, old, "imported", 1.0f, 0L);
    }

    private Episode fromCursor(Cursor c) {
        Episode e = new Episode();
        e.id = c.getLong(c.getColumnIndexOrThrow("id"));
        e.program = stringCol(c, "program");
        e.title = stringCol(c, "title");
        e.url = stringCol(c, "url");
        e.rawTranscript = stringCol(c, "raw_transcript");
        e.autoTranscript = stringCol(c, "auto_transcript");
        e.transcript = stringCol(c, "transcript");
        e.status = stringCol(c, "status");
        e.playbackSpeed = c.getFloat(c.getColumnIndexOrThrow("playback_speed"));
        e.notes = stringCol(c, "notes");
        e.tags = stringCol(c, "tags");
        e.keyPoints = stringCol(c, "key_points");
        e.mediaStartMs = c.getLong(c.getColumnIndexOrThrow("media_start_ms"));
        e.durationMs = c.getLong(c.getColumnIndexOrThrow("duration_ms"));
        e.startedAt = c.getLong(c.getColumnIndexOrThrow("started_at"));
        e.updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"));
        return e;
    }

    private String stringCol(Cursor c, String name) {
        int i = c.getColumnIndex(name);
        return i < 0 || c.isNull(i) ? "" : c.getString(i);
    }

    public static long parseMediaStartMs(String url) {
        try {
            if (url == null) return 0L;
            int q = url.indexOf('?');
            if (q < 0) return 0L;
            for (String pair : url.substring(q + 1).split("&")) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2 && ("t".equals(kv[0]) || "time".equals(kv[0]))) {
                    return Math.max(0L, Math.round(Double.parseDouble(
                            URLDecoder.decode(kv[1], "UTF-8")) * 1000.0));
                }
            }
        } catch (Exception ignored) {}
        return 0L;
    }

    public static String displayDate(long time) {
        return time <= 0 ? "—" : new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN).format(new Date(time));
    }

    public static String formatDuration(long ms) {
        if (ms <= 0) return "—";
        long sec = ms / 1000L, h = sec / 3600L, m = (sec % 3600L) / 60L, s = sec % 60L;
        return h > 0 ? String.format(Locale.JAPAN, "%d:%02d:%02d", h, m, s)
                : String.format(Locale.JAPAN, "%d:%02d", m, s);
    }

    public static String timeLabel(long ms) {
        long sec = Math.max(0L, ms) / 1000L, h = sec / 3600L, m = (sec % 3600L) / 60L, s = sec % 60L;
        return String.format(Locale.JAPAN, "%02d:%02d:%02d", h, m, s);
    }

    private static String iso(long time) {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.JAPAN).format(new Date(time));
    }

    private static String csv(String s) {
        String x = safe(s).replace("\r\n", "\n").replace("\r", "\n");
        return "\"" + x.replace("\"", "\"\"") + "\"";
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static String join(ArrayList<String> list, String sep) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) b.append(sep);
            b.append(list.get(i));
        }
        return b.toString();
    }

    private static String sqlLiteral(String s) { return safe(s).replace("'", "''"); }
}

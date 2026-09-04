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
    private static final int DB_VERSION = 2;

    public static class Episode {
        public long id;
        public String program;
        public String title;
        public String url;
        public String rawTranscript;
        public String autoTranscript;
        public String transcript;
        public String status;
        public float playbackSpeed;
        public long startedAt;
        public long updatedAt;
    }

    public static class Correction {
        public long id;
        public String program;
        public String wrong;
        public String correct;
        public int uses;
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
                "raw_transcript TEXT NOT NULL DEFAULT ''," +
                "auto_transcript TEXT NOT NULL DEFAULT ''," +
                "transcript TEXT NOT NULL DEFAULT ''," +
                "status TEXT NOT NULL DEFAULT 'saved'," +
                "playback_speed REAL NOT NULL DEFAULT 1.0," +
                "started_at INTEGER NOT NULL," +
                "updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_episodes_updated ON episodes(updated_at DESC)");
        createCorrectionsTable(db);
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

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            try { db.execSQL("ALTER TABLE episodes ADD COLUMN raw_transcript TEXT NOT NULL DEFAULT ''"); } catch (Exception ignored) {}
            try { db.execSQL("ALTER TABLE episodes ADD COLUMN auto_transcript TEXT NOT NULL DEFAULT ''"); } catch (Exception ignored) {}
            try { db.execSQL("ALTER TABLE episodes ADD COLUMN playback_speed REAL NOT NULL DEFAULT 1.0"); } catch (Exception ignored) {}
            db.execSQL("UPDATE episodes SET raw_transcript=transcript WHERE raw_transcript='' OR raw_transcript IS NULL");
            db.execSQL("UPDATE episodes SET auto_transcript=transcript WHERE auto_transcript='' OR auto_transcript IS NULL");
            createCorrectionsTable(db);
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
        v.put("started_at", now);
        v.put("updated_at", now);
        long id = getWritableDatabase().insertOrThrow("episodes", null, v);
        ensureStarterCorrections(program);
        return id;
    }

    public void updateMeta(long id, String program, String title, String url) {
        ContentValues v = new ContentValues();
        v.put("program", safe(program));
        v.put("title", safe(title));
        v.put("url", safe(url));
        v.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("episodes", v, "id=?", new String[]{String.valueOf(id)});
        ensureStarterCorrections(program);
    }

    public void updateTranscript(long id, String transcript, String status) {
        if (id <= 0) return;
        ContentValues v = new ContentValues();
        v.put("transcript", safe(transcript));
        if (status != null) v.put("status", status);
        v.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("episodes", v, "id=?", new String[]{String.valueOf(id)});
    }

    public void updateRecognition(long id, String rawTranscript, String autoTranscript,
                                  String status, float playbackSpeed) {
        if (id <= 0) return;
        ContentValues v = new ContentValues();
        v.put("raw_transcript", safe(rawTranscript));
        v.put("auto_transcript", safe(autoTranscript));
        v.put("transcript", safe(autoTranscript));
        if (status != null) v.put("status", status);
        v.put("playback_speed", playbackSpeed);
        v.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("episodes", v, "id=?", new String[]{String.valueOf(id)});
    }

    public void updateEditedTranscript(long id, String transcript) {
        if (id <= 0) return;
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

    public Episode getEpisode(long id) {
        Cursor c = getReadableDatabase().query("episodes", null, "id=?",
                new String[]{String.valueOf(id)}, null, null, null);
        try { return c.moveToFirst() ? fromCursor(c) : null; }
        finally { c.close(); }
    }

    public ArrayList<Episode> listEpisodes(String query) {
        return listEpisodes(query, "");
    }

    public ArrayList<Episode> listEpisodes(String query, String programFilter) {
        ArrayList<Episode> out = new ArrayList<>();
        String q = safe(query).trim();
        String pf = safe(programFilter).trim();
        ArrayList<String> where = new ArrayList<>();
        ArrayList<String> args = new ArrayList<>();

        if (!q.isEmpty()) {
            where.add("(program LIKE ? OR title LIKE ? OR transcript LIKE ? OR raw_transcript LIKE ?)");
            String like = "%" + q + "%";
            args.add(like); args.add(like); args.add(like); args.add(like);
        }
        if (!pf.isEmpty()) {
            where.add("program=?");
            args.add(pf);
        }

        String selection = where.isEmpty() ? null : join(where, " AND ");
        String[] selectionArgs = args.isEmpty() ? null : args.toArray(new String[0]);
        Cursor c = getReadableDatabase().query("episodes", null, selection, selectionArgs,
                null, null, "updated_at DESC", "500");
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

    public int count() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM episodes", null);
        try { return c.moveToFirst() ? c.getInt(0) : 0; }
        finally { c.close(); }
    }

    public void deleteEpisode(long id) {
        getWritableDatabase().delete("episodes", "id=?", new String[]{String.valueOf(id)});
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
        if (wrong.length() < 2 || wrong.length() > 24 || correct.isEmpty() || correct.length() > 24) return;
        if (semantic(wrong).equals(semantic(correct))) return;

        SQLiteDatabase db = getWritableDatabase();
        long now = System.currentTimeMillis();
        Cursor c = db.query("corrections", new String[]{"id", "uses"},
                "program=? AND wrong=? AND correct=?", new String[]{program, wrong, correct},
                null, null, null);
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
            c = getReadableDatabase().query("corrections", null, "program=''", null,
                    null, null, "uses DESC, LENGTH(wrong) DESC", "100");
        } else {
            c = getReadableDatabase().query("corrections", null, "program=? OR program=''",
                    new String[]{p}, null, null, "uses DESC, LENGTH(wrong) DESC", "100");
        }
        try {
            while (c.moveToNext()) {
                Correction r = new Correction();
                r.id = c.getLong(c.getColumnIndexOrThrow("id"));
                r.program = c.getString(c.getColumnIndexOrThrow("program"));
                r.wrong = c.getString(c.getColumnIndexOrThrow("wrong"));
                r.correct = c.getString(c.getColumnIndexOrThrow("correct"));
                r.uses = c.getInt(c.getColumnIndexOrThrow("uses"));
                out.add(r);
            }
        } finally { c.close(); }
        return out;
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
            if (r.correct.length() >= 2) set.add(r.correct);
            if (set.size() >= 30) break;
        }
        return new ArrayList<>(set);
    }

    private void addUsefulBias(LinkedHashSet<String> set, String s) {
        s = s.trim();
        if (s.isEmpty()) return;
        if (s.length() <= 40) set.add(s);
        for (String x : s.split("[\\s/／・|｜,，:：()（）]+")) {
            x = x.trim();
            if (x.length() >= 2 && x.length() <= 24) set.add(x);
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
                if (candidate.contains(r.correct)) score += 5.0 + Math.min(r.uses, 5);
                if (candidate.contains(r.wrong)) score -= 4.0 + Math.min(r.uses, 5);
            }
            if (score > best) { best = score; bestText = candidate; }
        }
        return bestText;
    }

    public int learnCorrectionsFromEdit(long episodeId, String editedText) {
        Episode e = getEpisode(episodeId);
        if (e == null) return 0;
        String before = safe(e.autoTranscript);
        String after = safe(editedText);
        if (before.equals(after)) return 0;

        ArrayList<String[]> pairs = new ArrayList<>();
        collectDiffs(before, after, pairs, 0);
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        int learned = 0;
        for (String[] pair : pairs) {
            String wrong = cleanupDiff(pair[0]);
            String correct = cleanupDiff(pair[1]);
            if (wrong.length() < 2 || wrong.length() > 24 || correct.isEmpty() || correct.length() > 24) continue;
            if (semantic(wrong).equals(semantic(correct))) continue;
            String key = wrong + "\u0000" + correct;
            if (!seen.add(key)) continue;
            addCorrection(e.program, wrong, correct);
            learned++;
        }
        updateLearningBaseline(episodeId, after);
        return learned;
    }

    private void collectDiffs(String a, String b, ArrayList<String[]> out, int depth) {
        if (depth > 16 || a.equals(b)) return;
        int prefix = commonPrefix(a, b);
        a = a.substring(prefix);
        b = b.substring(prefix);
        int suffix = commonSuffix(a, b);
        String am = suffix == 0 ? a : a.substring(0, a.length() - suffix);
        String bm = suffix == 0 ? b : b.substring(0, b.length() - suffix);
        if (am.isEmpty() && bm.isEmpty()) return;
        if (semantic(am).equals(semantic(bm))) return;

        if (am.length() <= 24 && bm.length() <= 24) {
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
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) i++;
        return i;
    }

    private int commonSuffix(String a, String b) {
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && a.charAt(a.length() - 1 - i) == b.charAt(b.length() - 1 - i)) i++;
        return i;
    }

    private String cleanupDiff(String s) {
        return safe(s).replaceAll("^[\\s、。,.，]+|[\\s、。,.，]+$", "").trim();
    }

    private static String semantic(String s) {
        return safe(s).replaceAll("[\\s\\p{Punct}、。！？!?，．・：；「」『』（）［］【】]+", "");
    }

    private void ensureStarterCorrections(String program) {
        String p = safe(program);
        if (p.contains("けれけれ")) {
            ensureCorrection(p, "キレキレ", "けれけれ");
            ensureCorrection(p, "中田詩織", "永田詩央里");
        }
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
                o.put("playbackSpeed", e.playbackSpeed);
                o.put("startedAt", iso(e.startedAt));
                o.put("updatedAt", iso(e.updatedAt));
                o.put("rawTranscript", e.rawTranscript);
                o.put("autoTranscript", e.autoTranscript);
                o.put("transcript", e.transcript);
                arr.put(o);
            } catch (Exception ignored) {}
        }

        JSONArray corrections = new JSONArray();
        Cursor c = getReadableDatabase().query("corrections", null, null, null, null, null,
                "program, uses DESC, LENGTH(wrong) DESC");
        try {
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                o.put("program", c.getString(c.getColumnIndexOrThrow("program")));
                o.put("wrong", c.getString(c.getColumnIndexOrThrow("wrong")));
                o.put("correct", c.getString(c.getColumnIndexOrThrow("correct")));
                o.put("uses", c.getInt(c.getColumnIndexOrThrow("uses")));
                corrections.put(o);
            }
        } catch (Exception ignored) {}
        finally { c.close(); }

        try {
            JSONObject root = new JSONObject();
            root.put("format", "radiko-transcriber-export-v2");
            root.put("exportedAt", iso(System.currentTimeMillis()));
            root.put("episodes", arr);
            root.put("corrections", corrections);
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
        updateRecognition(id, old, old, "imported", 1.0f);
    }

    private Episode fromCursor(Cursor c) {
        Episode e = new Episode();
        e.id = c.getLong(c.getColumnIndexOrThrow("id"));
        e.program = c.getString(c.getColumnIndexOrThrow("program"));
        e.title = c.getString(c.getColumnIndexOrThrow("title"));
        e.url = c.getString(c.getColumnIndexOrThrow("url"));
        e.transcript = c.getString(c.getColumnIndexOrThrow("transcript"));
        e.rawTranscript = column(c, "raw_transcript", e.transcript);
        e.autoTranscript = column(c, "auto_transcript", e.transcript);
        if (e.rawTranscript == null || e.rawTranscript.isEmpty()) e.rawTranscript = e.transcript;
        if (e.autoTranscript == null || e.autoTranscript.isEmpty()) e.autoTranscript = e.transcript;
        e.status = c.getString(c.getColumnIndexOrThrow("status"));
        int speedIndex = c.getColumnIndex("playback_speed");
        e.playbackSpeed = speedIndex >= 0 ? c.getFloat(speedIndex) : 1.0f;
        e.startedAt = c.getLong(c.getColumnIndexOrThrow("started_at"));
        e.updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"));
        return e;
    }

    private String column(Cursor c, String name, String fallback) {
        int i = c.getColumnIndex(name);
        return i >= 0 ? c.getString(i) : fallback;
    }

    private static String join(ArrayList<String> list, String sep) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) b.append(sep);
            b.append(list.get(i));
        }
        return b.toString();
    }

    private static String safe(String s) { return s == null ? "" : s; }

    public static String displayDate(long time) {
        return new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN).format(new Date(time));
    }

    private static String iso(long time) {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.JAPAN).format(new Date(time));
    }
}

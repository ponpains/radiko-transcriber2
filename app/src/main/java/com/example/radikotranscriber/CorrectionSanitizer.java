package com.example.radikotranscriber;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Keeps the learned dictionary conservative.
 *
 * Older builds learned arbitrary diff spans. That produced inverse pairs such as A->B and B->A,
 * very short common-word replacements, and several competing replacements for the same source.
 * Those rules then cascaded because replacements were applied sequentially. This class removes
 * ambiguous legacy rules, installs a DB guard for future inserts, and provides single-pass
 * replacement so replacement output is never fed into another rule in the same pass.
 */
public final class CorrectionSanitizer {
    private static final String DB_PROGRAM = "program";

    private CorrectionSanitizer() {}

    private static class Row {
        long id;
        String program;
        String wrong;
        String correct;
        int uses;
        boolean protectedRule;
    }

    public static int sanitize(Context context) {
        EpisodeStore store = new EpisodeStore(context);
        SQLiteDatabase db = store.getWritableDatabase();
        int removed = 0;
        try {
            db.execSQL("DROP TRIGGER IF EXISTS corrections_guard_v16");
            ArrayList<Row> rows = readRows(db);
            HashSet<Long> delete = new HashSet<>();
            HashMap<String, ArrayList<Row>> byWrong = new HashMap<>();
            HashMap<String, Row> byPair = new HashMap<>();

            for (Row r : rows) {
                r.protectedRule = isProtected(r.wrong, r.correct);
                byWrong.computeIfAbsent(key(r.program, r.wrong), k -> new ArrayList<>()).add(r);
                byPair.put(pairKey(r.program, r.wrong, r.correct), r);
                if (!r.protectedRule && isUnsafeExisting(r)) delete.add(r.id);
            }

            // Multiple answers for the same source are unsafe. Keep a protected rule, otherwise
            // only keep a clearly established winner; ties/one-off conflicts are all removed.
            for (ArrayList<Row> group : byWrong.values()) {
                if (group.size() <= 1) continue;
                Row keep = null;
                for (Row r : group) if (r.protectedRule) { keep = r; break; }
                if (keep == null) {
                    Row best = null;
                    int second = -1;
                    for (Row r : group) {
                        if (best == null || r.uses > best.uses) {
                            if (best != null) second = Math.max(second, best.uses);
                            best = r;
                        } else second = Math.max(second, r.uses);
                    }
                    if (best != null && best.uses >= 3 && best.uses >= second + 2) keep = best;
                }
                for (Row r : group) if (keep == null || r.id != keep.id) delete.add(r.id);
            }

            // Direct inverse pairs are the most damaging form of the old learning pollution.
            for (Row r : rows) {
                Row reverse = byPair.get(pairKey(r.program, r.correct, r.wrong));
                if (reverse == null || reverse.id == r.id) continue;
                if (r.protectedRule && !reverse.protectedRule) delete.add(reverse.id);
                else if (reverse.protectedRule && !r.protectedRule) delete.add(r.id);
                else if (!r.protectedRule && !reverse.protectedRule) {
                    if (r.uses > reverse.uses + 1) delete.add(reverse.id);
                    else if (reverse.uses > r.uses + 1) delete.add(r.id);
                    else { delete.add(r.id); delete.add(reverse.id); }
                }
            }

            // Remove short cycles of length 3-5 as well.
            HashMap<String, ArrayList<Row>> graph = new HashMap<>();
            for (Row r : rows) {
                if (!delete.contains(r.id)) graph.computeIfAbsent(key(r.program, r.wrong), k -> new ArrayList<>()).add(r);
            }
            for (Row r : rows) {
                if (r.protectedRule || delete.contains(r.id)) continue;
                if (reaches(graph, r.program, r.correct, r.wrong, 5, new HashSet<>())) delete.add(r.id);
            }

            db.beginTransaction();
            try {
                for (Long id : delete) {
                    removed += db.delete("corrections", "id=?", new String[]{String.valueOf(id)});
                }
                ensureProtectedStarters(db);
                installGuard(db);
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
        } catch (Exception ignored) {
            try { installGuard(db); } catch (Exception ignored2) {}
        } finally {
            try { store.close(); } catch (Exception ignored) {}
        }
        return removed;
    }

    /** Strict gate used for automatic learning. Manual dictionary edits still go through DB guard. */
    public static boolean isSafeLearnedPair(String wrong, String correct) {
        String w = normalize(wrong);
        String c = normalize(correct);
        if (w.isEmpty() || c.isEmpty() || semantic(w).equals(semantic(c))) return false;
        if (isProtected(w, c)) return true;
        int wl = semantic(w).length(), cl = semantic(c).length();
        if (wl < 4 || cl < 2 || wl > 28 || cl > 28) return false;
        if (countWords(w) > 4 || countWords(c) > 4) return false;
        if (containsSentenceBoundary(w) || containsSentenceBoundary(c)) return false;

        // A one-off edit that rewrites most of a phrase is usually alignment noise, not a reusable
        // spelling correction. Proper-name corrections remain well below this threshold.
        String ws = semantic(w), cs = semantic(c);
        int max = Math.max(ws.length(), cs.length());
        if (max >= 8 && levenshtein(ws, cs) > Math.ceil(max * 0.55)) return false;
        return true;
    }

    /** Applies rules against the original input only. Output from one rule cannot trigger another. */
    public static String applySinglePass(ArrayList<EpisodeStore.Correction> rules, String text) {
        String src = text == null ? "" : text;
        if (src.isEmpty() || rules == null || rules.isEmpty()) return src;

        // One source -> one answer. Prefer highest use count and then the longest target.
        LinkedHashMap<String, EpisodeStore.Correction> chosen = new LinkedHashMap<>();
        for (EpisodeStore.Correction r : rules) {
            if (r == null) continue;
            String w = normalize(r.wrong), c = normalize(r.correct);
            if (w.isEmpty() || c.isEmpty() || w.equals(c)) continue;
            if (!isProtected(w, c) && semantic(w).length() < 4) continue;
            EpisodeStore.Correction old = chosen.get(w);
            if (old == null || r.uses > old.uses
                    || (r.uses == old.uses && c.length() > normalize(old.correct).length())) {
                EpisodeStore.Correction copy = new EpisodeStore.Correction();
                copy.id = r.id; copy.program = r.program; copy.wrong = w; copy.correct = c; copy.uses = r.uses;
                chosen.put(w, copy);
            }
        }
        ArrayList<EpisodeStore.Correction> safe = new ArrayList<>(chosen.values());

        StringBuilder out = new StringBuilder(src.length() + 32);
        for (int i = 0; i < src.length();) {
            EpisodeStore.Correction best = null;
            for (EpisodeStore.Correction r : safe) {
                String w = r.wrong;
                if (i + w.length() > src.length() || !src.regionMatches(i, w, 0, w.length())) continue;
                if (best == null || w.length() > best.wrong.length()
                        || (w.length() == best.wrong.length() && r.uses > best.uses)) best = r;
            }
            if (best != null) {
                out.append(best.correct);
                i += best.wrong.length();
            } else {
                out.append(src.charAt(i++));
            }
        }
        return out.toString();
    }

    private static ArrayList<Row> readRows(SQLiteDatabase db) {
        ArrayList<Row> out = new ArrayList<>();
        Cursor c = db.query("corrections", new String[]{"id","program","wrong","correct","uses"},
                null, null, null, null, "uses DESC, id ASC");
        try {
            while (c.moveToNext()) {
                Row r = new Row();
                r.id = c.getLong(0); r.program = safe(c.getString(1));
                r.wrong = normalize(c.getString(2)); r.correct = normalize(c.getString(3));
                r.uses = c.getInt(4); out.add(r);
            }
        } finally { c.close(); }
        return out;
    }

    private static boolean isUnsafeExisting(Row r) {
        String w = r.wrong, c = r.correct;
        if (w.isEmpty() || c.isEmpty() || semantic(w).equals(semantic(c))) return true;
        int wl = semantic(w).length(), cl = semantic(c).length();
        if (wl < 4 || cl < 2 || wl > 32 || cl > 32) return true;
        if (r.uses <= 1 && (w.length() > 24 || c.length() > 24 || countWords(w) > 4 || countWords(c) > 4)) return true;
        if (r.uses <= 1 && (containsSentenceBoundary(w) || containsSentenceBoundary(c))) return true;
        if (r.uses <= 1 && Math.max(wl, cl) >= 8
                && levenshtein(semantic(w), semantic(c)) > Math.ceil(Math.max(wl, cl) * 0.62)) return true;
        return false;
    }

    private static boolean reaches(HashMap<String, ArrayList<Row>> graph, String program,
                                   String from, String target, int depth, Set<String> seen) {
        if (depth <= 0) return false;
        if (normalize(from).equals(normalize(target))) return true;
        String state = key(program, from);
        if (!seen.add(state)) return false;
        ArrayList<Row> next = graph.get(state);
        if (next != null) for (Row r : next) {
            if (reaches(graph, program, r.correct, target, depth - 1, seen)) return true;
        }
        return false;
    }

    private static void ensureProtectedStarters(SQLiteDatabase db) {
        ArrayList<String> programs = new ArrayList<>();
        Cursor c = db.rawQuery("SELECT DISTINCT program FROM episodes WHERE program LIKE '%けれけれ%'", null);
        try { while (c.moveToNext()) programs.add(safe(c.getString(0))); } finally { c.close(); }
        long now = System.currentTimeMillis();
        String[][] rules = {
                {"キレキレ","けれけれ"},{"キレレ","けれけれ"},{"テレテレ","けれけれ"},
                {"中田詩織","永田詩央里"},{"中田詩央里","永田詩央里"},
                {"永田詩織","永田詩央里"},{"長田詩織","永田詩央里"}
        };
        for (String p : programs) for (String[] r : rules) {
            ContentValues v = new ContentValues();
            v.put("program", p); v.put("wrong", r[0]); v.put("correct", r[1]); v.put("uses", 2);
            v.put("created_at", now); v.put("updated_at", now);
            db.insertWithOnConflict("corrections", null, v, SQLiteDatabase.CONFLICT_IGNORE);
        }
    }

    private static void installGuard(SQLiteDatabase db) {
        db.execSQL("DROP TRIGGER IF EXISTS corrections_guard_v16");
        db.execSQL("CREATE TRIGGER corrections_guard_v16 BEFORE INSERT ON corrections WHEN " +
                "TRIM(NEW.wrong)=TRIM(NEW.correct) OR " +
                "(LENGTH(TRIM(NEW.wrong))<=3 AND NOT (NEW.correct='けれけれ' AND NEW.wrong IN ('キレキレ','キレレ','テレテレ'))) OR " +
                "LENGTH(TRIM(NEW.wrong))>28 OR LENGTH(TRIM(NEW.correct))>28 OR " +
                "EXISTS(SELECT 1 FROM corrections c WHERE c.program=NEW.program AND c.wrong=NEW.correct AND c.correct=NEW.wrong) OR " +
                "EXISTS(SELECT 1 FROM corrections c WHERE c.program=NEW.program AND c.wrong=NEW.wrong AND c.correct<>NEW.correct) " +
                "BEGIN SELECT RAISE(IGNORE); END");
    }

    private static boolean isProtected(String wrong, String correct) {
        String w = normalize(wrong), c = normalize(correct);
        if ("けれけれ".equals(c) && ("キレキレ".equals(w) || "キレレ".equals(w) || "テレテレ".equals(w))) return true;
        if ("永田詩央里".equals(c) && ("中田詩織".equals(w) || "中田詩央里".equals(w)
                || "永田詩織".equals(w) || "長田詩織".equals(w))) return true;
        return false;
    }

    private static String key(String p, String w) { return safe(p) + "\u0001" + normalize(w); }
    private static String pairKey(String p, String w, String c) { return key(p,w) + "\u0002" + normalize(c); }

    private static int countWords(String s) {
        String x = normalize(s); if (x.isEmpty()) return 0;
        return x.split("\\s+").length;
    }
    private static boolean containsSentenceBoundary(String s) {
        return s.indexOf('。') >= 0 || s.indexOf('！') >= 0 || s.indexOf('？') >= 0
                || s.indexOf('!') >= 0 || s.indexOf('?') >= 0 || s.indexOf('\n') >= 0;
    }
    private static String normalize(String s) {
        return safe(s).replace("\r\n", " ").replace('\r',' ').replace('\n',' ')
                .replaceAll("[ \\t]+", " ").trim();
    }
    private static String semantic(String s) {
        return normalize(s).replaceAll("[\\s、。！？!?，,.・：；\\\"'「」『』（）()]+", "");
    }
    private static String safe(String s) { return s == null ? "" : s; }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }
}

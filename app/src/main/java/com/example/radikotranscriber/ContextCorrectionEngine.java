package com.example.radikotranscriber;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;

/** Conservative local post-correction. */
public final class ContextCorrectionEngine {
    private ContextCorrectionEngine() {}

    public static String refine(String program, String previousText, String segment) {
        String s = safe(segment);
        if (s.isEmpty()) return s;

        // Program-specific context always runs before generic Japanese homophones.
        s = KerekereContextProfile.refine(program, previousText, s);
        s = KerekereContextBoost.refine(program, previousText, s);
        String context = tail(previousText, 420) + " " + s;

        if (containsAny(context, "ツアー", "ライブ", "ステージ", "チケット", "体育館", "アリーナ")) {
            s = s.replace("昼公園", "昼公演").replace("夜公園", "夜公演");
            s = replaceBounded(s, "公園", "公演",
                    new String[]{"ツアー","ライブ","チケット","ステージ","ファイナル","アリーナ"});
        }
        if (containsAny(context, "ピーマン", "野菜", "苗", "収穫")) {
            s = s.replace("初就活", "初収穫").replace("初 就活", "初収穫");
        }
        if (s.contains("ペット")) {
            s = s.replace("ペットを買ったことがない", "ペットを飼ったことがない");
        }

        if (containsAny(context, "カーテン", "寝坊", "朝です", "起きたら")
                && containsAny(s, "外のるを確認", "外の るを確認")) {
            s = s.replace("外のるを確認", "外の明るさを確認")
                    .replace("外の るを確認", "外の明るさを確認");
        }

        return cleanupSpacing(s);
    }

    /**
     * Applies context correction to a finished transcript and performs a second fuzzy cumulative
     * duplicate pass. This is a safety net; the primary dedupe now happens before commit.
     */
    public static String refineTranscript(String program, String text) {
        String src = safe(text).replace("\r\n", "\n").replace('\r','\n');
        String[] paragraphs = src.split("\\n\\n", -1);
        StringBuilder out = new StringBuilder(src.length());
        String recent = "";
        for (String paragraph : paragraphs) {
            StringBuilder pOut = new StringBuilder();
            for (String line : paragraph.split("\\n", -1)) {
                String refined = refine(program, recent, line);
                if (refined.trim().isEmpty()) continue;

                RecognitionTextMerger.MergeResult merge = RecognitionTextMerger.merge(recent, refined);
                if (merge.duplicate) continue;
                String append = merge.overlapCompactChars >= 20 ? merge.append : refined;
                if (append.trim().isEmpty()) continue;

                String formatted = TranscriptSentenceFormatter.formatBlock(append, true);
                if (formatted.isEmpty()) continue;
                if (pOut.length() > 0) pOut.append('\n');
                pOut.append(formatted);
                recent = appendTail(recent, formatted);
            }
            if (pOut.length() > 0) {
                if (out.length() > 0) out.append("\n\n");
                out.append(pOut);
            }
        }
        return out.toString().replaceAll("\\n{3,}", "\n\n").trim();
    }

    /** Updates only the final transcript and corrected segment text; raw/auto remain auditable. */
    public static Result refineEpisode(Context context, EpisodeStore store, long episodeId) {
        Result result = new Result();
        EpisodeStore.Episode e = store.getEpisode(episodeId);
        if (e == null) return result;

        String corrected = refineTranscript(e.program, e.transcript);
        SQLiteDatabase db = store.getWritableDatabase();
        if (!corrected.equals(e.transcript)) {
            ContentValues v = new ContentValues();
            v.put("transcript", corrected);
            v.put("updated_at", System.currentTimeMillis());
            db.update("episodes", v, "id=?", new String[]{String.valueOf(episodeId)});
            result.transcriptChanged = true;
            result.changedChars = Math.abs(corrected.length() - e.transcript.length());
        }

        ArrayList<EpisodeStore.Segment> segments = store.listSegments(episodeId);
        String previous = "";
        for (EpisodeStore.Segment seg : segments) {
            String fixed = refine(e.program, previous, seg.text);
            fixed = TranscriptSentenceFormatter.formatBlock(fixed, true);
            if (!fixed.equals(seg.text)) {
                ContentValues sv = new ContentValues();
                sv.put("text", fixed);
                db.update("segments", sv, "id=?", new String[]{String.valueOf(seg.id)});
                result.segmentChanges++;
            }
            previous = appendTail(previous, fixed);
        }
        result.finalText = corrected;
        return result;
    }

    public static class Result {
        public boolean transcriptChanged;
        public int segmentChanges;
        public int changedChars;
        public String finalText = "";
        public boolean changed() { return transcriptChanged || segmentChanges > 0; }
    }

    public static String brief(String s) {
        String x = safe(s).replace('\n',' ').replace('\r',' ');
        return x.length() <= 140 ? x : x.substring(0, 140) + "…";
    }

    private static String replaceBounded(String s, String wrong, String correct, String[] hints) {
        int i = s.indexOf(wrong);
        while (i >= 0) {
            int from = Math.max(0, i - 35), to = Math.min(s.length(), i + wrong.length() + 35);
            String around = s.substring(from, to);
            if (containsAny(around, hints)) {
                s = s.substring(0, i) + correct + s.substring(i + wrong.length());
                i = s.indexOf(wrong, i + correct.length());
            } else i = s.indexOf(wrong, i + wrong.length());
        }
        return s;
    }

    private static String appendTail(String previous, String line) {
        String s = safe(previous) + "\n" + safe(line);
        return s.length() > 1400 ? s.substring(s.length() - 1400) : s;
    }

    private static String tail(String text, int max) {
        String s = safe(text);
        return s.length() <= max ? s : s.substring(s.length() - max);
    }

    private static String cleanupSpacing(String s) {
        return safe(s)
                .replaceAll("[ \\t]+([、。！？!?])", "$1")
                .replaceAll("([（(]) +", "$1")
                .replaceAll(" +([）)])", "$1")
                .replaceAll("[ \\t]{2,}", " ")
                .trim();
    }

    private static boolean containsAny(String s, String... terms) {
        for (String t : terms) if (s.contains(t)) return true;
        return false;
    }

    private static String safe(String s) { return s == null ? "" : s; }
}

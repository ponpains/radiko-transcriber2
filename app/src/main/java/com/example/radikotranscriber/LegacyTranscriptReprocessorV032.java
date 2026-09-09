package com.example.radikotranscriber;

import android.content.Context;

import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * Produces a preview using the current local correction/formatting stack without mutating storage.
 * The caller decides whether to apply it, after first saving a TranscriptHistoryV032 snapshot.
 */
public final class LegacyTranscriptReprocessorV032 {
    public static final String VERSION = "legacy-reprocessor-v032-2026-09-09";

    public static final class Result {
        public String sourceLabel = "";
        public String currentText = "";
        public String sourceText = "";
        public String processedText = "";
        public final LinkedHashMap<Long, String> segmentTexts = new LinkedHashMap<>();
        public boolean safeToApply;
        public String warning = "";

        public boolean changed() { return !safe(currentText).equals(safe(processedText)); }
    }

    private LegacyTranscriptReprocessorV032() {}

    public static Result preview(Context context, EpisodeStore store, long episodeId) {
        Result out = new Result();
        if (context == null || store == null || episodeId <= 0) {
            out.warning = "再処理する回を読み込めませんでした";
            return out;
        }
        EpisodeStore.Episode e = store.getEpisode(episodeId);
        if (e == null) {
            out.warning = "この回の保存データがありません";
            return out;
        }
        out.currentText = safe(e.transcript);

        ArrayList<EpisodeStore.Segment> segments = store.listSegments(episodeId);
        String candidate;
        if (!segments.isEmpty()) {
            out.sourceLabel = "保存済みの生認識セグメント";
            StringBuilder source = new StringBuilder();
            StringBuilder joined = new StringBuilder();
            String previous = "";
            for (EpisodeStore.Segment seg : segments) {
                String base = !safe(seg.rawText).trim().isEmpty() ? seg.rawText : seg.text;
                base = safe(base).trim();
                if (base.isEmpty()) continue;
                if (source.length() > 0) source.append(seg.topicBreak ? "\n\n" : "\n");
                source.append(base);

                String corrected = store.applyCorrections(e.program, base);
                corrected = ContextCorrectionEngine.refine(e.program, previous, corrected);
                corrected = CorrectionSafetyV032.choose(base, corrected, e.program);
                corrected = TranscriptSentenceFormatter.formatBlock(corrected, true).trim();
                if (corrected.isEmpty()) corrected = base;
                out.segmentTexts.put(seg.id, corrected);

                if (joined.length() > 0) joined.append(seg.topicBreak ? "\n\n" : "\n");
                joined.append(corrected);
                previous = appendTail(previous, corrected);
            }
            out.sourceText = source.toString();
            candidate = joined.toString();
        } else {
            String raw = safe(e.rawTranscript).trim();
            String auto = safe(e.autoTranscript).trim();
            if (!raw.isEmpty()) {
                out.sourceLabel = "保存済みの生認識全文";
                out.sourceText = raw;
            } else if (!auto.isEmpty()) {
                out.sourceLabel = "保存済みの自動変換全文";
                out.sourceText = auto;
            } else {
                out.sourceLabel = "現在の文字起こし（旧データのため元認識なし）";
                out.sourceText = safe(e.transcript);
            }
            candidate = store.applyCorrections(e.program, out.sourceText);
            candidate = ContextCorrectionEngine.refineTranscript(e.program, candidate);
        }

        candidate = TranscriptFinalProofreaderV027.proofread(context, e.program, candidate);
        candidate = FormatLearningStore.apply(context, e.program, candidate);
        candidate = RadioMailLayoutV028.apply(candidate).trim();
        out.processedText = candidate;

        int sourceChars = semantic(out.sourceText).length();
        int currentChars = semantic(out.currentText).length();
        int resultChars = semantic(out.processedText).length();
        if (resultChars == 0) {
            out.warning = "再処理結果が空になったため置き換えません";
            return out;
        }

        // Formatting and known-term repairs should not erase a large part of an episode. This guard
        // is deliberately whole-episode and therefore tolerant of normal local corrections.
        int baseline = currentChars >= 120 ? currentChars : sourceChars;
        if (baseline >= 120) {
            double ratio = resultChars / (double)Math.max(1, baseline);
            if (ratio < 0.72) {
                out.warning = "再処理結果が現在の文字起こしより大幅に短いため、安全のため置き換えを止めました";
                return out;
            }
            if (ratio > 1.38) {
                out.warning = "再処理結果が不自然に長くなったため、安全のため置き換えを止めました";
                return out;
            }
        }
        out.safeToApply = true;
        if (!out.changed()) out.warning = "現在の変換結果とほぼ同じです";
        return out;
    }

    private static String appendTail(String previous, String line) {
        String s = safe(previous) + "\n" + safe(line);
        return s.length() > 1400 ? s.substring(s.length() - 1400) : s;
    }

    private static String semantic(String s) {
        return safe(s).replaceAll("[\\s、。！？!?，,.・･：:；;\\\"'’「」『』()（）]+", "");
    }

    private static String safe(String s) { return s == null ? "" : s; }
}

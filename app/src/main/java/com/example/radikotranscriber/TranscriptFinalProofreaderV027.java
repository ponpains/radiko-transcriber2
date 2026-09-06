package com.example.radikotranscriber;

import android.content.Context;

import java.util.ArrayList;

/**
 * v0.27 final safety net. Runs after v0.26's proofreader and removes only local replay artifacts
 * that survived live suffix-prefix dedupe. It intentionally compares a very small neighborhood so
 * a host deliberately repeating a phrase later in the show is preserved.
 */
public final class TranscriptFinalProofreaderV027 {
    public static final String VERSION = "final-proofreader-v027-2026-09-06";

    private TranscriptFinalProofreaderV027() {}

    public static String proofread(Context context, String program, String text) {
        String s = TranscriptFinalProofreaderV026.proofread(context, program, text);
        s = KerekereObservedCorrectionsV027.refineTranscript(program, s);
        s = removeLocalReplayResidue(s);
        s = TranscriptSentenceFormatter.formatBlockPreservingParagraphs(s);
        s = KerekereFixedTemplate.refineTranscript(program, s);
        s = KerekereObservedCorrectionsV027.refineTranscript(program, s);
        return normalize(s);
    }

    private static String removeLocalReplayResidue(String text) {
        String src = normalize(text);
        String[] lines = src.split("\\n", -1);
        ArrayList<String> kept = new ArrayList<>();

        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) {
                if (!kept.isEmpty() && !kept.get(kept.size() - 1).isEmpty()) kept.add("");
                continue;
            }

            // First try the proven live merger against only the recent local tail.
            String recent = recentText(kept, 5, 900);
            RecognitionTextMerger.MergeResult direct = RecognitionTextMerger.merge(recent, line);
            if (direct.duplicate && direct.overlapCompactChars >= 18) continue;
            if (direct.overlapCompactChars >= 18 && !direct.append.trim().isEmpty()) {
                line = direct.append.trim();
            } else {
                // A replay may have 1-8 garbage characters before the repeated block (e.g. "まで"
                // or "仕事で"). Try short prefix skips, but only accept a high-confidence local
                // overlap; the skipped garbage is intentionally discarded.
                String rescued = stripNoisyReplayPrefix(recent, line);
                if (rescued != null) {
                    if (rescued.isEmpty()) continue;
                    line = rescued;
                }
            }

            // Catch a whole line repeated with one or two intervening ASR fragments. We only look
            // back four non-empty lines and demand strong similarity plus substantial length.
            int duplicateAt = findRecentDuplicate(kept, line);
            if (duplicateAt >= 0) {
                String old = kept.get(duplicateAt);
                if (semantic(line).length() > semantic(old).length() + 6) kept.set(duplicateAt, line);
                continue;
            }

            kept.add(line);
        }

        StringBuilder out = new StringBuilder();
        boolean lastBlank = true;
        for (String line : kept) {
            if (line.isEmpty()) {
                if (!lastBlank && out.length() > 0) out.append('\n');
                lastBlank = true;
                continue;
            }
            if (out.length() > 0) out.append('\n');
            out.append(line);
            lastBlank = false;
        }
        return normalize(out.toString());
    }

    private static String stripNoisyReplayPrefix(String recent, String line) {
        if (semantic(recent).length() < 22 || semantic(line).length() < 22) return null;
        int maxChars = Math.min(12, line.length() - 10);
        for (int cut = 1; cut <= maxChars; cut++) {
            char prev = line.charAt(cut - 1);
            if (Character.isHighSurrogate(prev)) continue;
            String rest = line.substring(cut).trim();
            if (semantic(rest).length() < 18) continue;
            RecognitionTextMerger.MergeResult m = RecognitionTextMerger.merge(recent, rest);
            if (m.overlapCompactChars < 18 || m.similarity < 0.93) continue;
            if (m.duplicate || semantic(m.append).length() <= 1) return "";
            if (m.overlapCompactChars >= 24) return m.append.trim();
        }
        return null;
    }

    private static int findRecentDuplicate(ArrayList<String> kept, String line) {
        String b = semantic(line);
        if (b.length() < 24) return -1;
        int checked = 0;
        for (int i = kept.size() - 1; i >= 0 && checked < 4; i--) {
            String old = kept.get(i);
            if (old.isEmpty()) continue;
            checked++;
            String a = semantic(old);
            if (a.length() < 24) continue;
            double lengthRatio = Math.min(a.length(), b.length()) / (double)Math.max(a.length(), b.length());
            if (lengthRatio < 0.72) continue;
            double sim = similarity(a, b);
            if (sim >= (Math.min(a.length(), b.length()) >= 60 ? 0.88 : 0.92)) return i;
        }
        return -1;
    }

    private static String recentText(ArrayList<String> kept, int lines, int maxChars) {
        StringBuilder b = new StringBuilder();
        int count = 0;
        for (int i = kept.size() - 1; i >= 0 && count < lines; i--) {
            String x = kept.get(i);
            if (x.isEmpty()) continue;
            if (b.length() == 0) b.insert(0, x);
            else b.insert(0, x + "\n");
            count++;
            if (b.length() > maxChars) break;
        }
        if (b.length() > maxChars) return b.substring(b.length() - maxChars);
        return b.toString();
    }

    private static double similarity(String a, String b) {
        if (a.equals(b)) return 1.0;
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
        return Math.max(0.0, 1.0 - prev[b.length()] / (double)Math.max(a.length(), b.length()));
    }

    private static String semantic(String s) {
        return safe(s).replaceAll("[\\s、。！？!?，,.・･：:；;\\\"'’「」『』()（）]+", "");
    }

    private static String normalize(String s) {
        return safe(s).replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("[ \\t]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n").trim();
    }

    private static String safe(String s) { return s == null ? "" : s; }
}

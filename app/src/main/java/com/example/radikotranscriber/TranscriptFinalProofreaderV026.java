package com.example.radikotranscriber;

import android.content.Context;

import java.util.ArrayList;

/**
 * Conservative whole-transcript proofreader run only after live recognition and selective
 * re-recognition have finished.  It can see the complete episode, so it fixes structural artifacts
 * that are unsafe to guess while audio is still arriving.  It never fabricates missing free talk.
 */
public final class TranscriptFinalProofreaderV026 {
    public static final String VERSION = "final-proofreader-v026-2026-09-06";

    private TranscriptFinalProofreaderV026() {}

    public static String proofread(Context context, String program, String text) {
        String s = normalize(text);
        if (s.isEmpty()) return s;

        // Re-run only deterministic/local knowledge. Online methods below apply aliases already
        // verified during this episode; they do not perform network I/O here.
        s = applyLineContext(program, s);
        s = OnlineEntityVerifierV4.refineKnown(program, s);
        s = OnlineMusicTitleFallbackV026.refineKnown(program, s);
        s = KerekereObservedCorrectionsV025.refineTranscript(program, s);

        if (KerekereContextProfile.applies(program)) {
            s = cleanupKerekereEnd(s);
            s = cleanupColdOpenArtifact(s);
            s = repairObservedBoundaryFractures(s);
            s = repairVerifiedMusicSpellings(s);
        }

        s = removeAdjacentFuzzyDuplicates(s);
        s = TranscriptSentenceFormatter.formatBlockPreservingParagraphs(s);
        s = KerekereFixedTemplate.refineTranscript(program, s);
        s = KerekereObservedCorrectionsV025.refineTranscript(program, s);

        // The user's edits have consistently taught this profile. Applying it at the very end makes
        // the saved transcript use that learned paragraph density instead of only recording it for
        // future sessions.
        if (context != null) s = FormatLearningStore.apply(context, program, s);
        return normalizeParagraphs(s);
    }

    /** Categorizes auto→user-final edits for diagnostics without learning brittle phrase rules. */
    public static String editSummary(String autoText, String finalText) {
        String a = normalize(autoText), b = normalize(finalText);
        if (a.equals(b)) return "unchanged";
        String ac = semantic(a), bc = semantic(b);
        int semanticDelta = Math.abs(ac.length() - bc.length());
        int lineDelta = Math.abs(nonEmptyLines(a) - nonEmptyLines(b));
        int paragraphDelta = Math.abs(paragraphs(a) - paragraphs(b));
        double same = similarity(ac, bc);
        String category;
        if (same >= 0.995 && (lineDelta > 0 || paragraphDelta > 0)) category = "formatting";
        else if (same >= 0.970 && semanticDelta <= 40) category = "minor_wording";
        else category = "content_correction";
        return "category=" + category + ";similarity=" + String.format(java.util.Locale.US, "%.4f", same)
                + ";semanticDelta=" + semanticDelta + ";lineDelta=" + lineDelta
                + ";paragraphDelta=" + paragraphDelta;
    }

    private static String applyLineContext(String program, String text) {
        StringBuilder out = new StringBuilder();
        String recent = "";
        for (String raw : normalize(text).split("\\n", -1)) {
            String line = raw.trim();
            if (line.isEmpty()) {
                if (out.length() > 0 && !endsWith(out, "\n\n")) out.append('\n');
                continue;
            }
            String fixed = ContextCorrectionEngine.refine(program, recent, line);
            fixed = KerekereFutureScheduleContext.refine(program, recent, fixed);
            fixed = KerekereObservedCorrectionsV025.refine(program, recent, fixed);
            if (out.length() > 0 && !endsWith(out, "\n")) out.append('\n');
            out.append(fixed);
            recent = tail(recent + "\n" + fixed, 1800);
        }
        return out.toString().trim();
    }

    private static String cleanupKerekereEnd(String s) {
        // Once the canonical sign-off is reached there is no valid spoken programme text after it.
        // This removes the v0.25 artifact "ポケモン 聞いてけれ" without relying on that phrase.
        int signoff = s.lastIndexOf("来週も聞いてけれ");
        if (signoff >= 0) {
            int end = signoff + "来週も聞いてけれ".length();
            while (end < s.length() && "。！!？? 　\t".indexOf(s.charAt(end)) >= 0) end++;
            if (end < s.length()) {
                String suffix = s.substring(end).trim();
                if (!suffix.isEmpty() && suffix.length() <= 180) s = s.substring(0, end).trim();
            }
        }
        return s;
    }

    private static String cleanupColdOpenArtifact(String s) {
        String[] lines = s.split("\\n", -1);
        StringBuilder out = new StringBuilder();
        boolean nearGreeting = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (i < 8 && containsAny(line, "永田詩央里です", "こんばんハタハタ")) nearGreeting = true;
            if (nearGreeting && i < 10 && line.matches("^[ー―−ｰ～〜]+[。！？!?]?$")) continue;
            if (out.length() > 0) out.append('\n');
            out.append(lines[i]);
        }
        return out.toString();
    }

    private static String repairObservedBoundaryFractures(String s) {
        String x = s;
        // Only high-specificity cross-callback splits. Generic "kanji + next line" merging would
        // destroy the user's desired one-sentence-per-line layout.
        x = x.replaceAll("(?m)(1週間に[0-9０-９]+冊)読[。]?[ \\t]*\\n+[ \\t]*める", "$1読める");
        x = x.replaceAll("(?m)読[。]?[ \\t]*\\n+[ \\t]*めるように", "読めるように");
        x = x.replaceAll("(?m)詳しく[。]?[ \\t]*\\n+[ \\t]*は(?=番組|公式|こちら|サイト|ホームページ)", "詳しくは");
        x = x.replaceAll("(?m)とのこと[。]?[ \\t]*\\n+[ \\t]*で(?=早速|続いて|では|すぐ)", "とのことで");
        x = x.replaceAll("(?m)ヘ[ \\t]*\\n+[ \\t]*ッドフォン", "ヘッドフォン");
        x = x.replaceAll("(?m)([0-9０-９]+つ)お[。]?[ \\t]*\\n+[ \\t]*知らせ", "$1お知らせ");
        return x;
    }

    private static String repairVerifiedMusicSpellings(String s) {
        String x = s;
        // These are exact, cue-gated spellings for errors already observed in the user's diagnostic;
        // broad occurrences of the same ordinary words are not touched.
        if (containsAny(x, "今日の私は機嫌がいい", "今日の私はキゲンがいい")
                && containsAny(x, "はるかり", "HALCALI")) {
            x = x.replace("はるかり", "HALCALI")
                    .replace("今日の私は機嫌がいい", "今日の私はキゲンがいい");
        }
        if (containsAny(x, "ご機嫌 ラジオ", "ごきげんRADIO", "ごきげん ラジオ")
                && containsAny(x, "ザモッツァ", "THE MODS", "ザ・モッズ")) {
            x = x.replace("ザモッツァ", "THE MODS")
                    .replace("ザ モッツァ", "THE MODS")
                    .replace("ご機嫌 ラジオ", "ごきげんRADIO")
                    .replace("ごきげん ラジオ", "ごきげんRADIO");
        }
        return x;
    }

    private static String removeAdjacentFuzzyDuplicates(String text) {
        String[] paragraphs = normalize(text).split("\\n\\n", -1);
        StringBuilder all = new StringBuilder();
        for (String paragraph : paragraphs) {
            ArrayList<String> kept = new ArrayList<>();
            for (String raw : paragraph.split("\\n")) {
                String line = raw.trim();
                if (line.isEmpty()) continue;
                if (!kept.isEmpty()) {
                    String prev = kept.get(kept.size() - 1);
                    String a = semantic(prev), b = semantic(line);
                    double sim = similarity(a, b);
                    boolean duplicate = Math.min(a.length(), b.length()) >= 9
                            && sim >= (Math.min(a.length(), b.length()) >= 24 ? 0.91 : 0.95);
                    if (duplicate) {
                        // Keep the more complete variant, but only for immediately adjacent lines.
                        if (b.length() > a.length() + 2) kept.set(kept.size() - 1, line);
                        continue;
                    }
                }
                kept.add(line);
            }
            if (kept.isEmpty()) continue;
            if (all.length() > 0) all.append("\n\n");
            for (int i = 0; i < kept.size(); i++) {
                if (i > 0) all.append('\n');
                all.append(kept.get(i));
            }
        }
        return all.toString();
    }

    private static int nonEmptyLines(String s) {
        int n = 0;
        for (String line : normalize(s).split("\\n")) if (!line.trim().isEmpty()) n++;
        return n;
    }

    private static int paragraphs(String s) {
        String x = normalize(s);
        return x.isEmpty() ? 0 : x.split("\\n[ \\t]*\\n+").length;
    }

    private static double similarity(String a, String b) {
        String x = safe(a), y = safe(b);
        if (x.isEmpty() || y.isEmpty()) return x.equals(y) ? 1.0 : 0.0;
        if (x.equals(y)) return 1.0;
        if (Math.max(x.length(), y.length()) > 12000) {
            // Full transcripts are mostly equal; a bounded prefix/suffix proxy avoids a large DP.
            int n = Math.min(5000, Math.min(x.length(), y.length()));
            double p = similaritySmall(x.substring(0, n), y.substring(0, n));
            int xs = Math.max(0, x.length() - n), ys = Math.max(0, y.length() - n);
            double q = similaritySmall(x.substring(xs), y.substring(ys));
            double len = Math.min(x.length(), y.length()) / (double)Math.max(x.length(), y.length());
            return p * 0.4 + q * 0.4 + len * 0.2;
        }
        return similaritySmall(x, y);
    }

    private static double similaritySmall(String x, String y) {
        int[] prev = new int[y.length() + 1], cur = new int[y.length() + 1];
        for (int j = 0; j <= y.length(); j++) prev[j] = j;
        for (int i = 1; i <= x.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= y.length(); j++) {
                int cost = x.charAt(i - 1) == y.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return Math.max(0.0, 1.0 - prev[y.length()] / (double)Math.max(x.length(), y.length()));
    }

    private static String semantic(String s) {
        return safe(s).replaceAll("[\\s、。！？!?，,.・･：:；;\\\"'’「」『』()（）]+", "");
    }

    private static String normalize(String s) {
        return safe(s).replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("[ \\t]+", " ").replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n").trim();
    }

    private static String normalizeParagraphs(String s) {
        return normalize(s).replaceAll("\\n{3,}", "\n\n").trim();
    }

    private static boolean endsWith(StringBuilder b, String suffix) {
        if (b.length() < suffix.length()) return false;
        for (int i = 0; i < suffix.length(); i++)
            if (b.charAt(b.length() - suffix.length() + i) != suffix.charAt(i)) return false;
        return true;
    }

    private static String tail(String s, int max) {
        String x = safe(s);
        return x.length() <= max ? x : x.substring(x.length() - max);
    }

    private static boolean containsAny(String s, String... values) {
        String x = safe(s);
        for (String v : values) if (v != null && !v.isEmpty() && x.contains(v)) return true;
        return false;
    }

    private static String safe(String s) { return s == null ? "" : s; }
}

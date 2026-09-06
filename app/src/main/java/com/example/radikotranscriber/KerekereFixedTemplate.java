package com.example.radikotranscriber;

import java.util.ArrayList;

/**
 * Stable fixed-wording templates for ≠ME 永田詩央里のけれけれ.
 *
 * v0.24 treats the tiny cold-open greeting separately from the later normal program introduction.
 * The greeting is replaced only at the very beginning and only when the distinctive ハタハタ cue
 * is present. This lets us be strong where the wording is known without turning later free talk
 * into a template.
 */
public final class KerekereFixedTemplate {
    public static final String VERSION = "kerekere-fixed-template-v024-2026-09-06";

    public static final String COLD_OPEN_1 = "こんばんは ノットイコールミーの永田詩央里です。";
    public static final String COLD_OPEN_2 = "こんばんハタハタ。";
    public static final String OPENING_TITLE = "ノットイコールミー永田詩央里のけれけれ";
    public static final String OPENING_1 = "この番組はアイドルグループ ノットイコールミーの永田詩央里が パーソナリティを務めるラジオ番組 けれけれです。";
    public static final String OPENING_2 = "金曜の夜 1週間頑張ったラジオの向こうのあなたが 少しでも ほっこりできる時間になったら嬉しいので みんな最後まで聞いてけれ。";
    public static final String OPENING_3 = "番組の感想は ハッシュタグ 永田ラジオでたくさん投稿してください。";

    private static final String COLD_OPEN_INLINE = COLD_OPEN_1 + " " + COLD_OPEN_2;
    private static final String COLD_OPEN_BLOCK = COLD_OPEN_1 + "\n" + COLD_OPEN_2;
    private static final String OPENING_INLINE = OPENING_TITLE + "。 " + OPENING_1 + " " + OPENING_2 + " " + OPENING_3;
    private static final String OPENING_BLOCK = OPENING_TITLE + "\n\n" + OPENING_1 + "\n" + OPENING_2 + "\n" + OPENING_3;
    private static final String ENDING_1 = "ここまでのお相手はノットイコールミー永田詩央里でした。";
    private static final String ENDING_2 = "来週も聞いてけれ";

    private KerekereFixedTemplate() {}

    public static ArrayList<String> biasTerms(String program) {
        ArrayList<String> out = new ArrayList<>();
        if (!KerekereContextProfile.applies(program)) return out;
        out.add(COLD_OPEN_1);
        out.add("こんばんハタハタ");
        out.add("ノットイコールミー永田詩央里のけれけれ");
        out.add("アイドルグループ ノットイコールミー");
        out.add("永田詩央里がパーソナリティを務める");
        out.add("ラジオ番組 けれけれです");
        out.add("金曜の夜");
        out.add("1週間頑張ったラジオの向こうのあなた");
        out.add("少しでもほっこりできる時間になったら嬉しいので");
        out.add("みんな最後まで聞いてけれ");
        out.add("ハッシュタグ 永田ラジオ");
        out.add("たくさん投稿してください");
        out.add("ここまでのお相手はノットイコールミー永田詩央里でした");
        out.add("来週も聞いてけれ");
        return out;
    }

    /** Correct one live result while preserving episode-specific talk before/after it. */
    public static String refineSegment(String program, String previousText, String segment) {
        String s = safe(segment).trim();
        if (!KerekereContextProfile.applies(program) || s.isEmpty()) return s;

        s = refineColdOpen(previousText, s);

        if (isOpeningCandidate(previousText, s)) {
            int end = openingEnd(s);
            if (end > 0) {
                String tail = trimLeadingPunctuation(s.substring(end).trim());
                s = OPENING_INLINE + (tail.isEmpty() ? "" : " " + tail);
            }
        }

        s = refineEndingInline(s);
        return s.replaceAll("[ \\t]{2,}", " ").trim();
    }

    /** Final pass: repair the first greeting and the normal fixed block even after a cold-open. */
    public static String refineTranscript(String program, String transcript) {
        String s = safe(transcript).replace("\r\n", "\n").replace('\r', '\n').trim();
        if (!KerekereContextProfile.applies(program) || s.isEmpty()) return s;

        s = refineColdOpenTranscript(s);

        OpeningWindow window = findOpeningWindow(s);
        if (window != null) {
            String before = s.substring(0, window.start).replaceAll("[ \\t]+$", "").replaceAll("\\n+$", "");
            String after = window.end < s.length() ? trimLeadingPunctuation(s.substring(window.end).trim()) : "";
            s = before + (before.isEmpty() ? "" : "\n") + OPENING_BLOCK
                    + (after.isEmpty() ? "" : "\n" + after);
        }

        int signoff = lastIndexOfAny(s, new String[]{"ここまでのお相手", "ここまでのおあいて"});
        if (signoff >= 0) {
            int next = indexOfAny(s, signoff, new String[]{"来週も", "来週 も"});
            if (next >= 0 && next - signoff < 240) {
                int end = sentenceEndAfter(s, next);
                String before = s.substring(0, signoff).replaceAll("[ \\t]+$", "").replaceAll("\\n+$", "");
                String after = end < s.length() ? s.substring(end).trim() : "";
                String fixed = ENDING_1 + "\n" + ENDING_2;
                s = before + (before.isEmpty() ? "" : "\n") + fixed
                        + (after.isEmpty() ? "" : "\n" + after);
            }
        }

        return s.replaceAll("\\n{3,}", "\n\n").trim();
    }

    private static String refineColdOpen(String previousText, String current) {
        // Only the first recognizer result is eligible.  "ハタハタ" is distinctive enough that a
        // mangled "ノットイコールミーの永田詩央里です" can safely be restored around it.
        if (compact(previousText).length() > 8) return current;
        String compact = compact(current);
        if (!compact.contains("ハタハタ")) return current;
        if (!containsAny(compact, "こんばんは", "こんばん", "今晩")) return current;
        if (compact.length() > 100) return current;

        int h = current.indexOf("ハタハタ");
        if (h < 0) return current;
        int end = h + "ハタハタ".length();
        while (end < current.length() && "。！？!?、 ".indexOf(current.charAt(end)) >= 0) end++;
        String tail = trimLeadingPunctuation(current.substring(end));
        return COLD_OPEN_INLINE + (tail.isEmpty() ? "" : " " + tail);
    }

    private static String refineColdOpenTranscript(String s) {
        int limit = Math.min(260, s.length());
        String head = s.substring(0, limit);
        String c = compact(head);
        if (!c.contains("ハタハタ") || !containsAny(c, "こんばんは", "こんばん", "今晩")) return s;

        int h = head.indexOf("ハタハタ");
        if (h < 0) return s;
        int end = h + "ハタハタ".length();
        while (end < head.length() && "。！？!?、 \t".indexOf(head.charAt(end)) >= 0) end++;

        // v0.23 sometimes put the host identification on the line after "こんばんハタハタ".
        // If that next short line is clearly only the host ID, absorb it into the canonical block.
        int nl = head.indexOf('\n', end);
        if (nl >= 0 && nl - end < 8) {
            int nextNl = head.indexOf('\n', nl + 1);
            if (nextNl < 0) nextNl = head.length();
            String next = head.substring(nl + 1, nextNl);
            String nc = compact(next);
            if (nc.length() <= 35 && containsAny(nc, "永田詩央里", "ノットイコール", "長田詩織", "中田詩織")) {
                end = nextNl;
            }
        }
        String tail = trimLeadingPunctuation(s.substring(Math.min(end, s.length())));
        return COLD_OPEN_BLOCK + (tail.isEmpty() ? "" : "\n" + tail);
    }

    private static OpeningWindow findOpeningWindow(String s) {
        int max = Math.min(s.length(), 4200);
        String head = s.substring(0, max);
        int desc = head.indexOf("この番組は");
        while (desc >= 0) {
            int probeEnd = Math.min(head.length(), desc + 1050);
            String probe = head.substring(desc, probeEnd);
            if (openingAnchorScore(probe) >= 7) {
                int endRel = openingEnd(probe);
                if (endRel > 0) {
                    int start = titleBlockStart(head, desc);
                    return new OpeningWindow(start, desc + endRel);
                }
            }
            desc = head.indexOf("この番組は", desc + 5);
        }
        return null;
    }

    private static int titleBlockStart(String head, int descriptionPos) {
        int cue = Math.max(head.lastIndexOf("それではそろそろ始め", descriptionPos),
                head.lastIndexOf("そろそろ始め", descriptionPos));
        if (cue >= 0 && descriptionPos - cue < 500) {
            int nl = head.indexOf('\n', cue);
            if (nl >= 0 && nl < descriptionPos) return skipWhitespace(head, nl + 1);
        }

        int line = head.lastIndexOf('\n', Math.max(0, descriptionPos - 1));
        if (line >= 0 && descriptionPos - line < 140) return skipWhitespace(head, line + 1);
        return descriptionPos;
    }

    private static boolean isOpeningCandidate(String previousText, String current) {
        String prev = safe(previousText);
        String recent = tail(prev, 800);
        boolean earlyEnough = compact(prev).length() <= 3500;
        boolean cueNearby = containsAny(recent, "それではそろそろ始め", "そろそろ始めて", "そろそろ始めて行きましょう");
        if (!earlyEnough && !cueNearby) return false;
        return openingAnchorScore(safe(current)) >= 7;
    }

    private static int openingAnchorScore(String source) {
        String x = compact(source);
        int score = 0;
        if (containsAny(x, "この番組", "ラジオ番組")) score++;
        if (x.contains("アイドルグループ")) score++;
        if (containsAny(x, "パーソナリティ", "務める")) score++;
        if (containsAny(x, "金曜の夜", "金曜")) score++;
        if (containsAny(x, "1週間頑張", "一週間頑張")) score++;
        if (x.contains("ラジオの向こう")) score++;
        if (x.contains("ほっこり")) score++;
        if (containsAny(x, "最後まで聞いて", "最後まで聴いて")) score++;
        if (x.contains("番組の感想")) score++;
        if (x.contains("ハッシュタグ")) score++;
        if (containsAny(x, "投稿してください", "投稿して下さい")) score++;
        return score;
    }

    private static int openingEnd(String s) {
        int p = lastIndexOfAny(s, new String[]{"投稿してください", "投稿して下さい"});
        if (p < 0) return -1;
        String matched = s.startsWith("投稿して下さい", p) ? "投稿して下さい" : "投稿してください";
        int end = p + matched.length();
        while (end < s.length()) {
            char c = s.charAt(end);
            if (c == '。' || c == '！' || c == '!' || Character.isWhitespace(c)) end++;
            else break;
        }
        return end;
    }

    private static String refineEndingInline(String s) {
        String x = s;
        if (containsAny(x, "ここまでのお相手", "ここまでのおあいて")) {
            x = x.replace("ノット ノットイコールミー", "ノットイコールミー")
                    .replace("ノット エコールミー", "ノットイコールミー")
                    .replace("ノットエコールミー", "ノットイコールミー")
                    .replace("ノットイコールに", "ノットイコールミー")
                    .replace("乗って これに", "ノットイコールミー")
                    .replace("乗ってこれに", "ノットイコールミー")
                    .replace("長田しおり", "永田詩央里")
                    .replace("長田詩織", "永田詩央里")
                    .replace("長田 詩織", "永田詩央里")
                    .replace("中田詩織", "永田詩央里");
        }
        if (containsAny(x, "来週も", "来週 も")) {
            x = x.replace("来週も聞いてくれ", ENDING_2)
                    .replace("来週も聞いてくる", ENDING_2)
                    .replace("来週も聞いてこれ", ENDING_2)
                    .replace("来週も聞いてけれー", ENDING_2)
                    .replace("来週も聞いてけれえ", ENDING_2)
                    .replace("来週 も聞いてけれ", ENDING_2);
        }
        return x;
    }

    private static int sentenceEndAfter(String s, int from) {
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '!' || c == '?') return i + 1;
            if (c == '\n' && i > from + 12) return i;
        }
        return s.length();
    }

    private static int skipWhitespace(String s, int i) {
        int p = Math.max(0, i);
        while (p < s.length() && Character.isWhitespace(s.charAt(p))) p++;
        return p;
    }

    private static int indexOfAny(String s, int from, String[] values) {
        int best = -1;
        for (String v : values) {
            int p = s.indexOf(v, from);
            if (p >= 0 && (best < 0 || p < best)) best = p;
        }
        return best;
    }

    private static int lastIndexOfAny(String s, String[] values) {
        int best = -1;
        for (String v : values) best = Math.max(best, s.lastIndexOf(v));
        return best;
    }

    private static String trimLeadingPunctuation(String s) {
        return safe(s).replaceFirst("^[\\s。！？!?、]+", "").trim();
    }

    private static boolean containsAny(String s, String... values) {
        String x = safe(s);
        for (String v : values) if (x.contains(v)) return true;
        return false;
    }

    private static String compact(String s) {
        return safe(s).replaceAll("[\\s、。！？!?，,.・：；『』「」()（）]+", "");
    }

    private static String tail(String s, int max) {
        String x = safe(s);
        return x.length() <= max ? x : x.substring(x.length() - max);
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static final class OpeningWindow {
        final int start, end;
        OpeningWindow(int start, int end) { this.start = start; this.end = end; }
    }
}

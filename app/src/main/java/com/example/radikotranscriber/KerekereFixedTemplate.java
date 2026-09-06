package com.example.radikotranscriber;

import java.util.ArrayList;

/**
 * Stable fixed-wording templates for ≠ME 永田詩央里のけれけれ.
 *
 * This is intentionally narrow.  It is not a generic rewrite engine: the opening is restored only
 * at the beginning of a kerekere transcript when many independent anchors are present, and the
 * closing is restored only around the explicit "ここまでのお相手" / "来週も" sign-off.
 */
public final class KerekereFixedTemplate {
    public static final String VERSION = "kerekere-fixed-template-v022-2026-09-06";

    public static final String OPENING_TITLE = "ノットイコールミー永田詩央里のけれけれ";
    public static final String OPENING_1 = "この番組はアイドルグループ ノットイコールミーの永田詩央里が パーソナリティを務めるラジオ番組 けれけれです。";
    public static final String OPENING_2 = "金曜の夜 1週間頑張ったラジオの向こうのあなたが 少しでも ほっこりできる時間になったら嬉しいので みんな最後まで聞いてけれ。";
    public static final String OPENING_3 = "番組の感想は ハッシュタグ 永田ラジオでたくさん投稿してください。";

    private static final String OPENING_INLINE = OPENING_TITLE + "。 " + OPENING_1 + " " + OPENING_2 + " " + OPENING_3;
    private static final String OPENING_BLOCK = OPENING_TITLE + "\n\n" + OPENING_1 + "\n" + OPENING_2 + "\n" + OPENING_3;
    private static final String ENDING_1 = "ここまでのお相手はノットイコールミー永田詩央里でした。";
    private static final String ENDING_2 = "来週も聞いてけれ";

    private KerekereFixedTemplate() {}

    public static ArrayList<String> biasTerms(String program) {
        ArrayList<String> out = new ArrayList<>();
        if (!KerekereContextProfile.applies(program)) return out;
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

    /** Corrects a single live recognition result while preserving any episode-specific tail. */
    public static String refineSegment(String program, String previousText, String segment) {
        String s = safe(segment).trim();
        if (!KerekereContextProfile.applies(program) || s.isEmpty()) return s;

        if (isOpeningCandidate(previousText, s)) {
            int end = openingEnd(s);
            if (end > 0) {
                String tail = s.substring(end).trim();
                s = OPENING_INLINE + (tail.isEmpty() ? "" : " " + trimLeadingPunctuation(tail));
            }
        }

        s = refineEndingInline(s);
        return s.replaceAll("[ \\t]{2,}", " ").trim();
    }

    /** Final-pass form.  This guarantees the stable wording even if the opening crossed segments. */
    public static String refineTranscript(String program, String transcript) {
        String s = safe(transcript).replace("\r\n", "\n").replace('\r', '\n').trim();
        if (!KerekereContextProfile.applies(program) || s.isEmpty()) return s;

        int searchEnd = Math.min(s.length(), 900);
        String head = s.substring(0, searchEnd);
        if (isOpeningCandidate("", head)) {
            int end = openingEnd(head);
            if (end > 0) {
                String tail = s.substring(end).trim();
                tail = trimLeadingPunctuation(tail);
                s = OPENING_BLOCK + (tail.isEmpty() ? "" : "\n" + tail);
            }
        }

        int signoff = lastIndexOfAny(s, new String[]{"ここまでのお相手", "ここまでのおあいて"});
        if (signoff >= 0) {
            int next = indexOfAny(s, signoff, new String[]{"来週も", "来週 も"});
            if (next >= 0 && next - signoff < 220) {
                int end = sentenceEndAfter(s, next);
                if (end < next) end = s.length();
                String before = s.substring(0, signoff).replaceAll("[ \\t]+$", "").replaceAll("\\n+$", "");
                String after = end < s.length() ? s.substring(end).trim() : "";
                String fixed = ENDING_1 + "\n" + ENDING_2;
                s = before + (before.isEmpty() ? "" : "\n") + fixed
                        + (after.isEmpty() ? "" : "\n" + after);
            }
        }

        return s.replaceAll("\\n{3,}", "\n\n").trim();
    }

    private static boolean isOpeningCandidate(String previousText, String current) {
        String prev = compact(safe(previousText));
        // Opening may be the first recognizer segment or may spill into the second one.  Do not
        // rewrite once a real body of the episode already exists.
        if (prev.length() > 450) return false;
        String x = compact(safe(previousText) + " " + safe(current));
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
        return score >= 7;
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
        for (String v : values) if (safe(s).contains(v)) return true;
        return false;
    }

    private static String compact(String s) {
        return safe(s).replaceAll("[\\s、。！？!?，,.・：；『』「」()（）]+", "");
    }

    private static String safe(String s) { return s == null ? "" : s; }
}

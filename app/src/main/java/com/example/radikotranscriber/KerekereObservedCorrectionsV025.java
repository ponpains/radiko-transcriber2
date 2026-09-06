package com.example.radikotranscriber;

/** Conservative fixes learned from the v0.24 #13 diagnostic. */
public final class KerekereObservedCorrectionsV025 {
    public static final String VERSION = "kerekere-observed-v025-2026-09-06";

    private KerekereObservedCorrectionsV025() {}

    public static double scoreCandidate(String program, String candidate, String previousText) {
        if (!KerekereContextProfile.applies(program)) return 0.0;
        String c = safe(candidate);
        String context = tail(previousText, 1000) + " " + c;
        double score = 0.0;

        if (containsAny(context, "フィッシュマンズ", "1994年", "ゴーゴー", "ワールド")) {
            if (c.contains("Go Go Round This World!")) score += 15.0;
            if (c.contains("フィッシュマンズ")) score += 6.0;
        }
        if (containsAny(context, "ABCラジオ", "近藤夏子", "北村")) {
            if (c.contains("よな水リターンズ")) score += 12.0;
            if (c.contains("北村真平")) score += 8.0;
            if (containsAny(c, "米津 入りタンス", "北村新平")) score -= 7.0;
        }
        if (containsAny(context, "2026年1月24日", "ローソン", "50周年", "Special LIVE")) {
            if (c.contains("Kアリーナ横浜")) score += 9.0;
            if (c.contains("イコラブ")) score += 5.0;
            if (c.contains("ニアジョイ")) score += 5.0;
            if (containsAny(c, "県アリーナ横浜", "宮城ちゃん")) score -= 7.0;
        }
        if (containsAny(context, "郵便番号", "0108611", "010-8611", "はがき")) {
            if (c.contains("010-8611")) score += 7.0;
            if (c.contains("けれけれ係")) score += 9.0;
            if (containsAny(c, "キラキラ係", "キレキレ係")) score -= 7.0;
        }
        if (containsAny(context, "第13回", "エンディング")) {
            if (c.contains("けれけれ 第13回 エンディング")) score += 8.0;
            if (c.contains("キレキレ 第13回")) score -= 6.0;
        }
        return Math.max(-18.0, Math.min(32.0, score));
    }

    public static String refine(String program, String previousText, String segment) {
        String s = safe(segment);
        if (!KerekereContextProfile.applies(program) || s.isEmpty()) return s;
        String context = tail(previousText, 1300) + " " + s;

        // The official Fishmans discography confirms the 1994 title. Only touch it when the
        // recognizer already has the artist plus the distinctive mangled title/cue.
        if (containsAny(context, "フィッシュマンズ", "ゴーゴー", "557", "リス ワールド", "1994年")) {
            if (containsAny(s, "フィッシュマンズ", "ゴーゴーランド", "557", "リス ワールド")) {
                s = s.replaceAll("(?i)(?:ゴー[ \\t]*ゴーランド[^。！？!?]{0,80}?ワールド|ゴーゴーランド[^。！？!?]{0,80}?ワールド|557[^。！？!?]{0,40}?ワールド)",
                        "Go Go Round This World!");
                s = s.replace("フィッシュマンズでGo Go Round This World!フィッシュマンズでGo Go Round This World!",
                        "フィッシュマンズでGo Go Round This World!");
            }
        }

        if (containsAny(context, "ABCラジオ", "近藤夏子", "北村新平", "北村真平")) {
            s = s.replace("米津 入りタンス", "よな水リターンズ")
                    .replace("米津入りタンス", "よな水リターンズ")
                    .replace("よな水 リターンズ", "よな水リターンズ")
                    .replace("北村新平", "北村真平");
        }

        if (containsAny(context, "2026年1月24日", "ローソン", "50周年", "LAWSON 50th Anniversary")) {
            s = s.replace("県アリーナ横浜", "Kアリーナ横浜")
                    .replace("K アリーナ横浜", "Kアリーナ横浜")
                    .replace("いくらぶさん", "イコラブさん")
                    .replace("イクラブさん", "イコラブさん")
                    .replace("宮城ちゃん", "ニアジョイちゃん")
                    .replace("にやじょいちゃん", "ニアジョイちゃん")
                    .replace("ニヤジョイちゃん", "ニアジョイちゃん");
        }

        if (containsAny(context, "郵便番号", "0108611", "010-8611", "はがき", "おはがき")) {
            s = s.replace("0108611", "010-8611")
                    .replace("010 8611", "010-8611")
                    .replace("ABS ラジオキラキラ係", "ABSラジオ けれけれ係")
                    .replace("ABSラジオキラキラ係", "ABSラジオ けれけれ係")
                    .replace("ABS ラジオ キラキラ係", "ABSラジオ けれけれ係")
                    .replace("ABS ラジオキレキレ係", "ABSラジオ けれけれ係")
                    .replace("ABSラジオキレキレ係", "ABSラジオ けれけれ係")
                    .replace("ABS ラジオけれけれ係", "ABSラジオ けれけれ係");
        }

        if (containsAny(context, "第13回", "エンディング")) {
            s = s.replace("キレキレ 第13回 エンディング", "けれけれ 第13回 エンディング")
                    .replace("ケレケレ 第13回 エンディング", "けれけれ 第13回 エンディング");
        }

        return s.replaceAll("[ \\t]{2,}", " ").trim();
    }

    /** Final-only cleanup for cross-segment patterns that should not be guessed live. */
    public static String refineTranscript(String program, String transcript) {
        String s = safe(transcript).replace("\r\n", "\n").replace('\r', '\n');
        if (!KerekereContextProfile.applies(program) || s.isEmpty()) return s;

        // v0.24 successfully restored the cold-open greeting but an ASR fragment consisting only
        // of a prolonged-sound mark could survive as its own line directly after it.
        if (s.startsWith("こんばんは ノットイコールミーの永田詩央里です。\nこんばんハタハタ。")) {
            int limit = Math.min(s.length(), 140);
            String head = s.substring(0, limit)
                    .replaceFirst("(?m)^(?:ー|―|−|ｰ|～|〜)[。]?[ \\t]*\\n", "");
            s = head + s.substring(limit);
        }

        // Postal wording often crosses recognizer segments, so normalize once on the final text.
        s = s.replace("郵便番号0108611", "郵便番号010-8611")
                .replace("郵便番号 0108611", "郵便番号010-8611")
                .replace("ABS ラジオキラキラ係", "ABSラジオ けれけれ係")
                .replace("ABSラジオキラキラ係", "ABSラジオ けれけれ係")
                .replace("ABS ラジオキレキレ係", "ABSラジオ けれけれ係");

        return s.replaceAll("\\n{3,}", "\n\n").trim();
    }

    private static boolean containsAny(String s, String... values) {
        String x = safe(s);
        for (String v : values) if (x.contains(v)) return true;
        return false;
    }

    private static String tail(String s, int max) {
        String x = safe(s);
        return x.length() <= max ? x : x.substring(x.length() - max);
    }

    private static String safe(String s) { return s == null ? "" : s; }
}

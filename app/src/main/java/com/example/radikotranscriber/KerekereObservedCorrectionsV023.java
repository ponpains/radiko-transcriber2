package com.example.radikotranscriber;

/** Conservative corrections learned from the v0.22 diagnostic. */
public final class KerekereObservedCorrectionsV023 {
    public static final String VERSION = "kerekere-observed-v023-2026-09-06";

    private KerekereObservedCorrectionsV023() {}

    public static double scoreCandidate(String program, String candidate, String previousText) {
        if (!KerekereContextProfile.applies(program)) return 0.0;
        String c = safe(candidate);
        String context = tail(previousText, 700) + " " + c;
        double score = 0.0;

        if (radioNameSlot(previousText, c)) {
            if (c.contains("ガンバレないわ")) score += 18.0;
            if (c.contains("しゃかかな")) score += 18.0;
            if (containsAny(c, "頑張らないわ", "頑張れないわ", "釈迦 かな", "釈迦かな", "さかの")) score -= 9.0;
        }
        if (containsAny(context, "夜について", "テレビ大陸", "テレビ タイ", "テレビ 体")) {
            if (c.contains("テレビ大陸音頭")) score += 12.0;
            if (c.contains("夜について")) score += 7.0;
            if (containsAny(c, "テレビ タイ レコード", "テレビ 体", "テレビ大陸 温度")) score -= 7.0;
        }
        if (containsAny(context, "コーヒー", "外旭川", "秋田空港")) {
            if (c.contains("ナガハマコーヒー")) score += 9.0;
            if (containsAny(c, "長浜 コーヒー", "長浜コーヒー")) score -= 5.0;
        }
        if (containsAny(context, "ドキュメンタリー映画", "約束の歌")) {
            if (c.contains("≠ME THE MOVIE -約束の歌-")) score += 10.0;
        }
        if (containsAny(context, "大感謝祭", "シアター", "9月18日")) {
            if (c.contains("イコノイジョイ大感謝祭 2025")) score += 10.0;
            if (containsAny(c, "イコノイド 感謝祭", "イコノイド感謝祭")) score -= 6.0;
        }
        if (containsAny(context, "全国ツアー 2025", "カフェ", "樂園", "楽園")) {
            if (c.contains("We want to find \"カフェ樂園\"")) score += 9.0;
            if (c.contains("LaLa arena TOKYO-BAY")) score += 8.0;
        }
        return Math.max(-20.0, Math.min(30.0, score));
    }

    public static String refine(String program, String previousText, String segment) {
        String s = safe(segment);
        if (!KerekereContextProfile.applies(program) || s.isEmpty()) return s;
        String context = tail(previousText, 900) + " " + s;

        if (radioNameSlot(previousText, s)) {
            s = s.replaceAll("頑張(?:ら|れ)ない(?:わ|は)", "ガンバレないわ")
                    .replaceAll("がんば(?:ら|れ)ない(?:わ|は)", "ガンバレないわ")
                    .replaceAll("釈迦[ \\t]*かな", "しゃかかな")
                    .replaceAll("しゃか[ \\t]+かな", "しゃかかな")
                    .replace("さかのさん", "しゃかかなさん")
                    .replace("さかかなさん", "しゃかかなさん")
                    .replace("さかがなさん", "しゃかかなさん");
        }

        if (containsAny(context, "夜について", "テレビ タイ レコード", "テレビ 体", "テレビ大陸 温度")) {
            s = s.replace("テレビ タイ レコード", "テレビ大陸音頭")
                    .replace("テレビ タイレコード", "テレビ大陸音頭")
                    .replace("テレビ大陸 温度", "テレビ大陸音頭")
                    .replace("テレビ大陸温度", "テレビ大陸音頭")
                    .replace("テレビ 体", "テレビ大陸音頭");
        }

        if (containsAny(context, "コーヒー", "外旭川", "秋田空港")) {
            s = s.replace("長浜 コーヒー", "ナガハマコーヒー")
                    .replace("長浜コーヒー", "ナガハマコーヒー");
        }

        if (containsAny(context, "ドキュメンタリー映画", "約束の歌")) {
            s = s.replace("の抵抗で ミー ザ ムービン 約束の歌", "≠ME THE MOVIE -約束の歌-")
                    .replace("ノットイコール ミー ザ ムービー 約束の歌", "≠ME THE MOVIE -約束の歌-")
                    .replace("ノットイコールミー ザ ムービー 約束の歌", "≠ME THE MOVIE -約束の歌-")
                    .replace("ノットイコール ミー ザ ムービン 約束の歌", "≠ME THE MOVIE -約束の歌-");
        }

        if (containsAny(context, "大感謝祭", "シアター H", "シアターH", "9月18日")) {
            s = s.replace("イコノイド 感謝祭2025", "イコノイジョイ大感謝祭 2025")
                    .replace("イコノイド 感謝祭 2025", "イコノイジョイ大感謝祭 2025")
                    .replace("イコノイド感謝祭2025", "イコノイジョイ大感謝祭 2025")
                    .replace("イコノイジョイ 感謝祭2025", "イコノイジョイ大感謝祭 2025");
        }

        if (containsAny(context, "全国ツアー 2025", "カフェ 楽園", "カフェ樂園", "LaLa", "ララアリーナ")) {
            s = s.replace("ヴィーモンド 2ファンド カフェ 楽園", "We want to find \"カフェ樂園\"")
                    .replace("ヴィーモンド 2ファンド カフェ樂園", "We want to find \"カフェ樂園\"")
                    .replace("We want to find カフェ 楽園", "We want to find \"カフェ樂園\"")
                    .replace("We want to find カフェ樂園", "We want to find \"カフェ樂園\"")
                    .replace("ララアリーナ東京ベイ", "LaLa arena TOKYO-BAY")
                    .replace("ララ アリーナ東京ベイ", "LaLa arena TOKYO-BAY")
                    .replace("7 アリーナ東京ベイ", "LaLa arena TOKYO-BAY");
        }

        if (containsAny(context, "なまはげ", "男鹿", "半島")) {
            s = s.replace("小川半島のなまはげ", "男鹿半島のなまはげ")
                    .replace("小川半島 のなまはげ", "男鹿半島のなまはげ");
        }

        if (containsAny(context, "郵便番号", "おはがき", "はがき", "係まで")) {
            s = s.replace("けらけら係", "けれけれ係")
                    .replace("けりけり 係", "けれけれ係")
                    .replace("けりけり係", "けれけれ係")
                    .replace("けりけれ係", "けれけれ係")
                    .replace("けれけれ がっかり", "けれけれ係");
            if (s.contains("010-8611") && s.contains("ABS") && s.contains("けれけれ係")) {
                s = s.replaceAll("郵便番号[ \\t]*010-8611[ \\t]*ABS[ \\t]*ラジオ[ \\t]*けれけれ係(?:まで)?(?:お願い(?:します)?|までお願いします)?",
                        "郵便番号010-8611 ABSラジオ けれけれ係までお願いします");
            }
        }

        if (containsAny(context, "秋田のしおり", "コーナーでした")) {
            s = s.replace("以上 明人のしおり", "以上 秋田のしおり")
                    .replace("以上 明人のしおりのコーナーでした", "以上 秋田のしおりのコーナーでした");
        }

        return s.replaceAll("[ \\t]{2,}", " ").trim();
    }

    private static boolean radioNameSlot(String previous, String current) {
        String p = tail(previous, 140).replace('\n', ' ');
        String c = safe(current);
        int idx = p.lastIndexOf("ラジオネーム");
        if (idx >= 0 && p.length() - idx <= 85) return true;
        return c.startsWith("ラジオネーム") || c.contains(" ラジオネーム ")
                || c.matches(".*(?:都|道|府|県)[ \\t]*ラジオネーム.*");
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

package com.example.radikotranscriber;

import java.util.ArrayList;

/**
 * Small offline knowledge snapshot for recurring/official ≠ME and けれけれ wording.
 *
 * The app must not need a successful web request to spell an announcement it already knows.  This
 * class intentionally contains only high-value official/stable names plus a few date/venue-gated
 * announcements observed in the diagnostic.  It is not a general "correct everything to a press
 * release" pass: spoken shorthand is preserved unless the recognition is clearly broken.
 */
public final class KerekereOfficialKnowledge {
    public static final String VERSION = "kerekere-official-v024-2026-09-06";

    public static final String TOUR_2025 = "≠ME 全国ツアー2025「We want to find \"カフェ樂園\"」追加公演";
    public static final String TOUR_VENUE = "LaLa arena TOKYO-BAY";
    public static final String CHIKAPPA_2025 = "ちかっぱ祭2025";
    public static final String LAWSON_LIVE = "LAWSON 50th Anniversary presents ＝LOVE・≠ME・≒JOY Special LIVE";
    public static final String LAWSON_VENUE = "Kアリーナ横浜";

    private KerekereOfficialKnowledge() {}

    public static ArrayList<String> biasTerms(String program) {
        ArrayList<String> out = new ArrayList<>();
        if (!KerekereContextProfile.applies(program)) return out;

        // Stable show/group spellings.
        out.add("≠ME");
        out.add("ノットイコールミー");
        out.add("永田詩央里");
        out.add("≠ME 永田詩央里のけれけれ");
        out.add("ABSラジオ");
        out.add("永田ラジオ");
        out.add("radikoポッドキャスト");
        out.add("しおりん聞いてけれ");
        out.add("秋田のしおり");
        out.add("ラジオ猫");
        out.add("郵便番号010-8611");
        out.add("ABSラジオ けれけれ係");

        // User/high-frequency radio names.
        out.add("ななお");
        out.add("ガンバレないわ");
        out.add("しゃかかな");

        // Official event/place spellings relevant to the September 2025 episodes under test.
        out.add(TOUR_2025);
        out.add(TOUR_VENUE);
        out.add(CHIKAPPA_2025);
        out.add("マリンメッセ福岡B館");
        out.add(LAWSON_LIVE);
        out.add(LAWSON_VENUE);
        out.add("＝LOVE");
        out.add("≒JOY");
        out.add("イコラブ");
        out.add("ニアジョイ");
        out.add("ヤマキウ南倉庫");

        // Proper nouns repeatedly observed in this show's diagnostics.
        out.add("麻丘めぐみ");
        out.add("悲しみよこんにちは");
        out.add("テレビ大陸音頭");
        out.add("夜について");
        out.add("ナガハマコーヒー");
        out.add("八木沼秀光");
        out.add("焼きそばかおり");
        out.add("福島暢啓の いんじゃない？");
        return out;
    }

    public static double scoreCandidate(String program, String candidate, String previousText) {
        if (!KerekereContextProfile.applies(program)) return 0.0;
        String c = safe(candidate);
        String context = tail(previousText, 900) + " " + c;
        double score = 0.0;

        if (containsAny(context, "9月20日", "9月21日", "LaLa", "ララアリーナ", "全国ツアー2025")) {
            if (c.contains(TOUR_2025)) score += 14.0;
            if (c.contains(TOUR_VENUE)) score += 10.0;
            if (containsAny(c, "ピーマン追加公演", "カフェ 楽園", "カフェ楽園")) score -= 7.0;
        }
        if (containsAny(context, "12月7日", "ちかっぱ", "マリンメッセ")) {
            if (c.contains(CHIKAPPA_2025)) score += 11.0;
            if (c.contains("ちかっぱさん2025")) score -= 7.0;
        }
        if (containsAny(context, "2026年1月24日", "Kアリーナ", "ローソン", "50周年")) {
            if (c.contains(LAWSON_LIVE)) score += 15.0;
            if (c.contains(LAWSON_VENUE)) score += 8.0;
            if (c.contains("イコラブ")) score += 4.0;
            if (c.contains("ニアジョイ")) score += 4.0;
        }
        if (containsAny(context, "ヤマキ", "南倉庫", "ステッカー")) {
            if (c.contains("ヤマキウ南倉庫")) score += 9.0;
            if (containsAny(c, "やまきゅう南倉庫", "ヤマキュウ南倉庫")) score -= 6.0;
        }
        if (containsAny(context, "麻丘めぐみ", "悲しみ")) {
            if (c.contains("悲しみよこんにちは")) score += 9.0;
            if (c.contains("悲しみをこんにちは")) score -= 5.0;
        }
        if (containsAny(context, "郵便番号", "010-8611", "おはがき", "はがき")) {
            if (c.contains("けれけれ係")) score += 10.0;
            if (containsAny(c, "KK係", "けらけら係", "けりけり係")) score -= 7.0;
        }
        return Math.max(-24.0, Math.min(38.0, score));
    }

    public static String refine(String program, String previousText, String segment) {
        String s = safe(segment);
        if (!KerekereContextProfile.applies(program) || s.isEmpty()) return s;
        String context = tail(previousText, 1100) + " " + s;

        // Stable identifiers. Keep these narrow; "きらきら" in ordinary speech must remain valid.
        s = s.replace("長田 ラジオ", "永田ラジオ")
                .replace("長田ラジオ", "永田ラジオ")
                .replace("ラジコン ポッドキャスト", "radikoポッドキャスト")
                .replace("ラジコンポッドキャスト", "radikoポッドキャスト")
                .replace("ラジコ ポッドキャスト", "radikoポッドキャスト");

        if (containsAny(context, "9月20日", "9月21日", "LaLa", "ララアリーナ", "全国ツアー2025")) {
            s = s.replace("乗ってイコールミー全国ツアー2025ピーマン追加公演", TOUR_2025)
                    .replace("ノットイコールミー全国ツアー2025ピーマン追加公演", TOUR_2025)
                    .replace("≠ME全国ツアー2025ピーマン追加公演", TOUR_2025)
                    .replace("全国ツアー2025ピーマン追加公演", "全国ツアー2025「We want to find \"カフェ樂園\"」追加公演")
                    .replace("全国ツアー 2025 ピーマン追加公演", "全国ツアー2025「We want to find \"カフェ樂園\"」追加公演")
                    .replace("ララアリーナ東京ベイ", TOUR_VENUE)
                    .replace("ララ アリーナ東京ベイ", TOUR_VENUE)
                    .replace("LaLa アリーナ東京ベイ", TOUR_VENUE);
            if (s.contains("全国ツアー2025") && !s.contains("≠ME 全国ツアー")
                    && containsAny(s, "乗ってイコールミー", "ノットイコールミー")) {
                s = s.replace("乗ってイコールミー", "≠ME")
                        .replace("ノットイコールミー", "≠ME");
            }
        }

        if (containsAny(context, "12月7日", "ちかっぱ", "マリンメッセ")) {
            s = s.replace("ちかっぱさん2025", CHIKAPPA_2025)
                    .replace("ちかっぱ さん2025", CHIKAPPA_2025)
                    .replace("ちかっぱ祭 2025", CHIKAPPA_2025);
        }

        if (containsAny(context, "2026年1月24日", "K アリーナ", "Kアリーナ", "ローソン", "50周年")) {
            s = s.replace("K アリーナ横浜", LAWSON_VENUE)
                    .replace("イクラブさん", "イコラブさん")
                    .replace("イコールラブさん", "イコラブさん")
                    .replace("にや酔いちゃん", "ニアジョイちゃん")
                    .replace("ニヤジョイちゃん", "ニアジョイちゃん")
                    .replace("ニア 酔いちゃん", "ニアジョイちゃん");
            s = replaceLawsonTitle(s);
        }

        if (containsAny(context, "ヤマキ", "やまきゅう", "南倉庫", "ステッカー")) {
            s = s.replace("やまきゅう 南倉庫", "ヤマキウ南倉庫")
                    .replace("やまきゅう南倉庫", "ヤマキウ南倉庫")
                    .replace("ヤマキュウ 南倉庫", "ヤマキウ南倉庫")
                    .replace("ヤマキュウ南倉庫", "ヤマキウ南倉庫");
        }

        if (containsAny(context, "麻丘めぐみ", "悲しみをこんにちは", "悲しみよこんにちは")) {
            s = s.replace("悲しみをこんにちは", "悲しみよこんにちは");
        }

        if (containsAny(context, "郵便番号", "010-8611", "おはがき", "はがき", "係まで")) {
            s = s.replace("ABS ラジオ KK 係", "ABSラジオ けれけれ係")
                    .replace("ABSラジオ KK係", "ABSラジオ けれけれ係")
                    .replace("ABS ラジオ けらけら係", "ABSラジオ けれけれ係")
                    .replace("ABS ラジオ けりけり係", "ABSラジオ けれけれ係")
                    .replace("けらけら係", "けれけれ係")
                    .replace("けりけり係", "けれけれ係");
        }

        if (containsAny(context, "過去回", "過去会", "radiko", "ポッドキャスト")) {
            s = s.replace("過去会", "過去回")
                    .replace("ラジコ ポッドキャスト", "radikoポッドキャスト")
                    .replace("ラジコポッドキャスト", "radikoポッドキャスト")
                    .replace("ラジコン ポッドキャスト", "radikoポッドキャスト");
        }

        return s.replaceAll("[ \\t]{2,}", " ").trim();
    }

    private static String replaceLawsonTitle(String s) {
        String x = s;
        String[] starts = {
                "ローソンフィフティーズアニバーサリー",
                "ローソン フィフティーズ アニバーサリー",
                "ローソン50thアニバーサリー",
                "LAWSON 50th Anniversary"
        };
        int start = -1;
        for (String marker : starts) {
            int p = x.indexOf(marker);
            if (p >= 0 && (start < 0 || p < start)) start = p;
        }
        if (start < 0) return x;
        int live = x.indexOf("スペシャルライブ", start);
        if (live < 0 || live - start > 190) return x;
        int end = live + "スペシャルライブ".length();
        return x.substring(0, start) + LAWSON_LIVE + x.substring(end);
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

package com.example.radikotranscriber;

import java.util.ArrayList;

/**
 * Extra program-language knowledge learned from real v0.18 diagnostics and the user's corrected
 * transcript. Kept separate from the generic learned replacement dictionary so ordinary Japanese
 * is never globally rewritten just because one episode contained a special phrase.
 */
public final class KerekereContextBoost {
    public static final String VERSION = "kerekere-boost-v019-2026-09-06";

    private static final String[] EXTRA_BIAS = {
            "ツイメモ", "天邪鬼", "終戦記念日", "The Birthday", "なぜか今日は",
            "稲庭うどん", "ラジオ猫", "秋田のしおり", "しおりん聞いてけれ",
            "ノットイコールミー", "永田詩央里", "永田ラジオ", "radikoポッドキャスト",
            "全国ツアー2025", "約束の歌", "ライブ＆ドキュメンタリー映画"
    };

    private KerekereContextBoost() {}

    public static ArrayList<String> biasTerms(String program) {
        ArrayList<String> out = new ArrayList<>();
        if (!KerekereContextProfile.applies(program)) return out;
        for (String s : EXTRA_BIAS) out.add(s);
        return out;
    }

    public static double scoreCandidate(String program, String candidate, String previousText) {
        if (!KerekereContextProfile.applies(program)) return 0.0;
        String c = safe(candidate);
        String context = tail(previousText, 420) + " " + c;
        double score = 0.0;

        for (String term : EXTRA_BIAS) if (c.contains(term)) score += 3.5;
        if (containsAny(c, "中田しおり", "中田詩織", "長出し より", "長出しより")) score -= 8.0;
        if (containsAny(c, "乗ってくるみ", "乗ってくるみー", "ノットイコールに")) score -= 7.0;
        if (containsAny(c, "ゲレ ケレ", "ゲレケレ", "ケロケロ")
                && containsAny(context, "番組", "ラジオ", "第", "エンディング", "永田詩央里")) score -= 7.0;
        if (c.contains("水面 も") && context.contains("メモアプリ")) score -= 7.0;
        if (c.contains("天ノ弱") && containsAny(context, "すぎる", "ラジオ猫", "テレビ 犬")) score -= 6.0;
        return Math.max(-18.0, Math.min(18.0, score));
    }

    public static String refine(String program, String previousText, String segment) {
        String s = safe(segment);
        if (!KerekereContextProfile.applies(program) || s.isEmpty()) return s;
        String context = tail(previousText, 500) + " " + s;

        // Host/group identity is effectively fixed in this show.
        s = s.replace("中田しおり", "永田詩央里")
                .replace("中田 詩織", "永田詩央里")
                .replace("長出し より", "永田詩央里")
                .replace("長出しより", "永田詩央里");

        if (containsAny(context, "アイドルグループ", "ノイミー", "ツアー", "ライブ",
                "公式ホームページ", "永田詩央里", "この番組")) {
            s = s.replace("乗ってくるみー", "ノットイコールミー")
                    .replace("乗ってくるみ", "ノットイコールミー")
                    .replace("ノットイコールに", "ノットイコールミー")
                    .replace("ノットイコールミーー", "ノットイコールミー");
        }

        if (containsAny(context, "この番組", "ラジオ番組", "第7回", "エンディング", "過去回",
                "永田詩央里", "聞いてけれ", "ABS")) {
            s = s.replace("ゲレ ケレ", "けれけれ")
                    .replace("ゲレケレ", "けれけれ")
                    .replace("ケロケロ", "けれけれ")
                    .replace("ケレ ケレ", "けれけれ");
        }

        if (containsAny(context, "メモアプリ", "Twitter みたい", "5年以上", "メモに取って")) {
            s = s.replace("水面 も", "ツイメモ")
                    .replace("水面も", "ツイメモ")
                    .replace("ツイ メモ", "ツイメモ");
        }

        if (containsAny(context, "テレビ 犬", "ラジオ猫", "すぎるんじゃない", "すぎる")) {
            s = s.replace("天ノ弱", "天邪鬼")
                    .replace("天の弱", "天邪鬼");
        }

        s = s.replace("幼少期は盛大な苔", "幼少期は盛大なコケ")
                .replace("盛大な苔を経験", "盛大なコケを経験");

        if (containsAny(context, "稲庭うどん", "食べ", "収録")) {
            s = s.replace("視力が 終わった後", "収録が終わった後")
                    .replace("視力が終わった後", "収録が終わった後");
        }

        if (containsAny(context, "なまはげ", "銀座", "秋田料理")) {
            s = s.replace("北料理", "秋田料理")
                    .replace("修理にもいつか", "しおりんにもいつか")
                    .replace("修理にも", "しおりんにも");
        }

        if (containsAny(context, "公式ハッシュタグ", "ハッシュタグ", "番組公式")) {
            s = s.replace("長田 ラジオ", "永田ラジオ")
                    .replace("長田ラジオ", "永田ラジオ");
        }
        if (containsAny(context, "過去回", "ポッドキャスト", "Podcast")) {
            s = s.replace("ラジコン ポッドキャスト", "radikoポッドキャスト")
                    .replace("ラジコンポッドキャスト", "radikoポッドキャスト");
        }

        return cleanup(s);
    }

    private static String cleanup(String s) {
        return safe(s).replaceAll("[ \\t]+([、。！？!?])", "$1")
                .replaceAll("[ \\t]{2,}", " ").trim();
    }

    private static boolean containsAny(String s, String... values) {
        String x = safe(s);
        for (String v : values) if (v != null && !v.isEmpty() && x.contains(v)) return true;
        return false;
    }

    private static String tail(String s, int max) {
        String x = safe(s);
        return x.length() <= max ? x : x.substring(x.length() - max);
    }

    private static String safe(String s) { return s == null ? "" : s; }
}

package com.example.radikotranscriber;

import java.util.ArrayList;
import java.util.LinkedHashSet;

/**
 * Structural language hints for "≠ME 永田詩央里のけれけれ".
 * Corrections here are deliberately tied to radio-name slots, fixed show wording, or narrow topic
 * context. Ordinary Japanese must never be globally rewritten just because the show once used it.
 */
public final class KerekereProgramStructure {
    public static final String VERSION = "kerekere-structure-v021-2026-09-06";

    private static final String[] IMPORTANT_RADIO_NAMES = {
            "ななお", "ガンバレないわ", "しゃかかな"
    };

    private static final String[] FIXED_PHRASES = {
            "皆さんこんばんは", "本日もよろしくお願いします", "ラジオネーム",
            "それではそろそろ始めていきましょう", "≠ME 永田詩央里のけれけれ",
            "アイドルグループ≠ME", "永田詩央里", "しおりん聞いてけれ", "秋田のしおり",
            "ラジオ猫", "公式ハッシュタグは永田ラジオです", "永田ラジオ",
            "過去回はradikoポッドキャストで聞くことができます", "radikoポッドキャスト",
            "ここまでのお相手は≠ME永田詩央里でした", "来週も聞いてけれ",
            "郵便番号010-8611", "ABSラジオ けれけれ係"
    };

    private static final String[] DIAGNOSTIC_VOCAB = {
            "ラブリィエブリイ", "プリンセス プリンセス", "DIAMONDS", "モータービート",
            "モータウン", "恋はあせらず", "ミッシェル・ガン・エレファント", "竹下通り",
            "ババヘラ", "広栄堂", "生グソ", "たけや製パン", "アベックトースト",
            "粒あんグッディ", "学生調理"
    };

    private KerekereProgramStructure() {}

    public static ArrayList<String> biasTerms(String program, String episodeContext) {
        ArrayList<String> out = new ArrayList<>();
        if (!KerekereContextProfile.applies(program)) return out;
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String n : IMPORTANT_RADIO_NAMES) set.add(n);
        for (String p : FIXED_PHRASES) set.add(p);
        for (String p : DIAGNOSTIC_VOCAB) {
            if (set.size() >= 38) break;
            set.add(p);
        }
        for (String token : usefulContextTokens(episodeContext)) {
            if (set.size() >= 44) break;
            set.add(token);
        }
        out.addAll(set);
        return out;
    }

    public static double scoreCandidate(String program, String candidate,
                                        String previousText, String episodeContext) {
        if (!KerekereContextProfile.applies(program)) return 0.0;
        String c = safe(candidate);
        String prev = tail(previousText, 620);
        String joined = prev + " " + c;
        double score = 0.0;

        if (radioNameSlot(prev, c)) {
            for (String n : IMPORTANT_RADIO_NAMES) if (c.contains(n)) score += 22.0;
            if (containsAny(c, "菜々緒", "七尾", "ナナオ", "なな尾")) score -= 9.0;
            if (containsAny(c, "頑張れないわ", "がんばれないわ", "ガンバレないは", "頑張れないは")) score -= 8.0;
            if (containsAny(c, "釈迦かな", "社会かな", "シャカかな", "しゃかカナ",
                    "さかがな", "さかかな", "しゃかがな")) score -= 9.0;
        }

        if (openingContext(joined)) {
            if (c.contains("皆さんこんばんは")) score += 7.0;
            if (c.contains("本日もよろしくお願いします")) score += 6.0;
            if (c.contains("≠ME") || c.contains("ノットイコールミー")) score += 6.0;
            if (c.contains("永田詩央里")) score += 12.0;
            if (c.contains("けれけれ") || c.contains("ケレケレ")) score += 8.0;
            if (containsAny(c, "長門 詩織", "長門詩織", "長田しおり", "長田 詩織", "中田詩織")) score -= 10.0;
            if (c.matches(".*永田詩央里のケレ$")) score -= 5.0;
        }
        if (endingContext(joined)) {
            if (c.contains("ここまでのお相手")) score += 7.0;
            if (c.contains("永田詩央里")) score += 10.0;
            if (c.contains("来週も聞いてけれ")) score += 14.0;
            if (c.contains("永田ラジオ")) score += 9.0;
            if (c.contains("radikoポッドキャスト")) score += 8.0;
        }
        if (cornerContext(joined)) {
            if (c.contains("しおりん聞いてけれ")) score += 9.0;
            if (c.contains("秋田のしおり")) score += 9.0;
            if (c.contains("ラジオ猫")) score += 7.0;
        }

        if (musicContext(joined)) {
            if (c.contains("どこかで聞いたことがあります")) score += 4.5;
            if (c.contains("どこかで消えたことがあります")) score -= 4.5;
            if (c.contains("ラブリィエブリイ")) score += 6.0;
            if (c.contains("ミッシェル・ガン・エレファント")) score += 8.0;
            if (c.contains("モータービート")) score += 4.0;
        }
        if (containsAny(joined, "ラジオ体操", "第2", "第二")) {
            if (c.contains("第二")) score += 2.5;
            if (c.contains("退任ってちょっと大人向け")) score -= 3.0;
        }

        int contextMatches = 0;
        for (String token : usefulContextTokens(episodeContext)) {
            if (token.length() >= 3 && c.contains(token)) {
                score += Math.min(3.2, 0.9 + token.length() * 0.13);
                if (++contextMatches >= 6) break;
            }
        }
        return Math.max(-28.0, Math.min(42.0, score));
    }

    public static String refine(String program, String previousText, String segment) {
        String s = safe(segment);
        if (!KerekereContextProfile.applies(program) || s.isEmpty()) return s;
        String prev = tail(previousText, 700);
        String context = prev + " " + s;

        if (radioNameSlot(prev, s)) {
            s = s.replace("菜々緒", "ななお")
                    .replace("七尾", "ななお")
                    .replace("ナナオ", "ななお")
                    .replace("なな尾", "ななお")
                    .replace("頑張れないわ", "ガンバレないわ")
                    .replace("がんばれないわ", "ガンバレないわ")
                    .replace("ガンバレないは", "ガンバレないわ")
                    .replace("頑張れないは", "ガンバレないわ")
                    .replace("釈迦かな", "しゃかかな")
                    .replace("社会かな", "しゃかかな")
                    .replace("シャカかな", "しゃかかな")
                    .replace("しゃかカナ", "しゃかかな")
                    .replace("さかがな", "しゃかかな")
                    .replace("さかかな", "しゃかかな")
                    .replace("しゃかがな", "しゃかかな");
        }

        if (openingContext(context)) {
            s = s.replace("長門 詩織", "永田詩央里")
                    .replace("長門詩織", "永田詩央里")
                    .replace("長田しおり", "永田詩央里")
                    .replace("長田 詩織", "永田詩央里")
                    .replace("中田詩織", "永田詩央里")
                    .replace("中田 詩織", "永田詩央里");
            s = s.replace("永田詩央里のケレケレ", "永田詩央里のけれけれ")
                    .replace("永田詩央里のケレ", "永田詩央里のけれけれ")
                    .replace("永田詩央里のけれけれケレ", "永田詩央里のけれけれ");
            if (s.contains("この番組はアイドルグループです 金曜の夜")) {
                s = s.replace("この番組はアイドルグループです 金曜の夜",
                        "この番組はアイドルグループ≠MEの永田詩央里が務めるラジオ番組「けれけれ」です。金曜の夜");
            }
        }

        if (endingContext(context)) {
            if (context.contains("ここまでのお相手")) {
                s = s.replace("ノットイコールに長年しおり", "≠ME 永田詩央里")
                        .replace("ノットイコールに長田しおり", "≠ME 永田詩央里")
                        .replace("ノットイコールに永田詩央里", "≠ME 永田詩央里")
                        .replace("ノットイコールミー長田しおり", "≠ME 永田詩央里")
                        .replace("ノットイコールミー永田しおり", "≠ME 永田詩央里");
            }
            s = s.replace("来週も聞いてくる", "来週も聞いてけれ")
                    .replace("来週も聞いてくれ", "来週も聞いてけれ")
                    .replace("来週も聞いてこれ", "来週も聞いてけれ")
                    .replace("永田 ラジオ", "永田ラジオ")
                    .replace("過去会", "過去回")
                    .replace("ラジコ ポッドキャスト", "radikoポッドキャスト")
                    .replace("ラジコポッドキャスト", "radikoポッドキャスト");
        }

        if (containsAny(context, "公式ハッシュタグ", "ハッシュタグ")) {
            s = s.replace("永田 ラジオ", "永田ラジオ")
                    .replace("長田 ラジオ", "永田ラジオ")
                    .replace("長田ラジオ", "永田ラジオ");
        }
        if (containsAny(context, "郵便番号", "おはがき", "はがき", "係までお願いします")) {
            s = s.replace("ラジオ キラキラ係までお願いします", "ABSラジオ けれけれ係までお願いします")
                    .replace("ラジオ キレキレ 係までお願いします", "ABSラジオ けれけれ係までお願いします")
                    .replace("ラジオ キレキレ係までお願いします", "ABSラジオ けれけれ係までお願いします");
            if (s.contains("郵便番号 ABSラジオ けれけれ係"))
                s = s.replace("郵便番号 ABSラジオ けれけれ係", "郵便番号010-8611 ABSラジオ けれけれ係");
            if (s.contains("郵便番号 ラジオ"))
                s = s.replace("郵便番号 ラジオ", "郵便番号010-8611 ABSラジオ");
        }

        // Program-specific nouns that are unambiguous in this show.
        s = s.replace("ラジオ ネコ", "ラジオ猫")
                .replace("ラジオ 猫", "ラジオ猫")
                .replace("ラジオねこ", "ラジオ猫");

        if (containsAny(context, "ラジオ体操", "元気もりもり", "第2", "第二")) {
            s = s.replace("後ろ へ 体をさらす", "後ろへ体を反らす")
                    .replace("後ろへ 体をさらす", "後ろへ体を反らす")
                    .replace("退任ってちょっと大人向け", "第2ってちょっと大人向け")
                    .replace("あの大事に比べて", "第1に比べて");
        }
        if (containsAny(context, "夏休み", "オムライス", "母")) {
            s = s.replace("母のつくと オムライス", "母の作ったオムライス")
                    .replace("母のつくとオムライス", "母の作ったオムライス");
        }

        if (musicContext(context)) {
            s = s.replace("見た 楽曲", "似た楽曲")
                    .replace("見た楽曲", "似た楽曲")
                    .replace("修理 エブリイ", "ラブリィエブリイ")
                    .replace("修理エブリイ", "ラブリィエブリイ")
                    .replace("どこかで消えたことがあります", "どこかで聞いたことがあります")
                    .replace("モーター ビート", "モータービート")
                    .replace("恋は焦らず", "恋はあせらず")
                    .replace("まさに修理が発見した", "まさにしおりんが発見した")
                    .replace("修理 の音楽が恐れです", "しおりんの音楽眼が恐るべし")
                    .replace("修理の音楽が恐れです", "しおりんの音楽眼が恐るべし")
                    .replace("修理 の音楽眼が恐れです", "しおりんの音楽眼が恐るべし")
                    .replace("音楽眼が恐れです", "音楽眼が恐るべし");
        }

        if (containsAny(context, "ポップアップ", "T シャツ", "Tシャツ", "ドクロ", "アルバム")) {
            s = s.replace("ミスチルガンエレファント", "ミッシェル・ガン・エレファント")
                    .replace("ミスチルがエレファント", "ミッシェル・ガン・エレファント")
                    .replace("ッシェルがエレファント", "ミッシェル・ガン・エレファント")
                    .replace("シェルがエレファント", "ミッシェル・ガン・エレファント");
        }

        if (containsAny(context, "秋田", "アイス", "道端", "パラソル")) {
            s = s.replace("パパヘラ", "ババヘラ");
        }
        if (containsAny(context, "秋田市", "かき氷", "生グソ")) {
            s = s.replace("光栄堂", "広栄堂")
                    .replace("生臭という", "生グソという");
        }
        if (containsAny(context, "パン", "製パン", "アベックトースト", "学生調理")) {
            s = s.replace("竹谷製パン", "たけや製パン")
                    .replace("エフェクトトースト", "アベックトースト")
                    .replace("ズブ アングッディ", "粒あんグッディ")
                    .replace("つぶあんグッディ", "粒あんグッディ")
                    .replace("という患者さん", "という会社さん");
        }
        if (containsAny(context, "はがき", "おはがき") && s.contains("10万円ほどありがとうございます")) {
            s = s.replace("10万円ほどありがとうございます", "10枚ほどありがとうございます");
        }
        if (containsAny(context, "37", "暑", "外") && s.contains("37の")) {
            s = s.replace("37の", "37度");
        }

        return cleanup(s);
    }

    private static boolean radioNameSlot(String previous, String current) {
        String p = tail(previous, 120).replace("\n", " ");
        String c = safe(current);
        int idx = Math.max(p.lastIndexOf("ラジオネーム"), p.lastIndexOf("RN"));
        if (idx >= 0 && p.length() - idx <= 75) return true;
        return c.startsWith("ラジオネーム") || c.contains(" ラジオネーム ");
    }

    private static boolean openingContext(String s) {
        return containsAny(s, "皆さんこんばんは", "本日もよろしく", "始めていきましょう",
                "始めて行きましょう", "この番組は", "パーソナリティを務める",
                "ノットイコールミー", "≠ME 永田詩央里の");
    }

    private static boolean endingContext(String s) {
        return containsAny(s, "エンディング", "ここまでのお相手", "最後まで聞いて",
                "公式ハッシュタグ", "過去回", "過去会", "来週も", "係までお願いします");
    }

    private static boolean cornerContext(String s) {
        return containsAny(s, "こちらのコーナー", "コーナーです", "コーナーをやり",
                "しおりん聞いて", "秋田のしおり", "ラジオ猫", "ラジオ ネコ");
    }

    private static boolean musicContext(String s) {
        return containsAny(s, "楽曲", "前奏", "曲", "モータービート", "モータウン", "ダイヤモンド",
                "プリンセス", "音楽", "ポップアップ", "Tシャツ", "T シャツ");
    }

    private static ArrayList<String> usefulContextTokens(String source) {
        ArrayList<String> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        String s = safe(source).replace('「', ' ').replace('」', ' ').replace('『', ' ').replace('』', ' ');
        for (String x : s.split("[\\s/／・|｜,，:：()（）<>【】\\[\\]。！？!?]+")) {
            x = x.trim();
            if (x.length() < 2 || x.length() > 28) continue;
            if (x.matches("[0-9０-９]+")) continue;
            if (seen.add(x)) out.add(x);
            if (out.size() >= 24) break;
        }
        return out;
    }

    private static String cleanup(String s) {
        return safe(s).replaceAll("[ \\t]+([、。！？!?])", "$1")
                .replaceAll("[ \\t]{2,}", " ").trim();
    }

    private static boolean containsAny(String s, String... values) {
        String x = safe(s);
        for (String v : values) if (!v.isEmpty() && x.contains(v)) return true;
        return false;
    }

    private static String tail(String s, int max) {
        String x = safe(s);
        return x.length() <= max ? x : x.substring(x.length() - max);
    }

    private static String safe(String s) { return s == null ? "" : s; }
}

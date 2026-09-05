package com.example.radikotranscriber;

import java.util.ArrayList;
import java.util.LinkedHashSet;

/**
 * Program-specific language profile for ABS radio "≠ME 永田詩央里のけれけれ".
 *
 * The vocabulary is intentionally separated from the user's learned replacement dictionary.
 * A program term can be highly likely without being a safe global replacement (for example
 * "キレキレ" can be a perfectly valid ordinary word). This profile therefore contributes
 * recognition bias/candidate scoring and only performs corrections when surrounding show context
 * makes the intended phrase strong.
 *
 * Seeded from official ≠ME information and public radiko episode descriptions through 2026-09-05.
 */
public final class KerekereContextProfile {
    public static final String PROFILE_VERSION = "kerekere-2026-09-05-v1";

    private static final String[] CORE = {
            "≠ME", "ノットイコールミー", "永田詩央里", "しおりん", "けれけれ",
            "聞いてけれ", "しおりん聞いてけれ", "永田ラジオ", "ABSラジオ", "秋田放送",
            "radiko", "Podcast", "ラジオネーム"
    };

    private static final String[] CORNERS = {
            "秋田のしおり", "ラジオ猫", "ラジオ猫の名前会議", "ラジオ猫ステッカー",
            "しおりん聞いてけれ", "共食い", "共食い情報", "共食い目撃情報",
            "今週のラッキーステッカー", "韻踏みラジオネームグランプリ",
            "もう一度聴かせてけれ", "けれけれ名場面", "永田旅", "こんばんハタハタ",
            "ふつおた", "おたがき"
    };

    // Current members plus 菅波美玲, useful for episodes from the user's older archive period.
    private static final String[] MEMBERS = {
            "尾木波菜", "落合希来里", "蟹沢萌子", "河口夏音", "川中子奈月心", "櫻井もも",
            "菅波美玲", "鈴木瞳美", "谷崎早耶", "冨田菜々風", "永田詩央里", "本田珠由記"
    };

    private static final String[] AKITA = {
            "秋田", "竿燈まつり", "いぶりがっこ", "なまはげ", "ババヘラ", "金萬",
            "稲庭うどん", "比内地鶏", "きりたんぽ", "横手やきそば", "ジュンサイ",
            "超神ネイガー", "秋田ノーザンハピネッツ", "大湯環状列石", "黒又山",
            "手長足長", "セリオン", "小坂町"
    };

    private static final String[] KNOWN_BAD = {
            "中田詩織", "中田詩央里", "永田詩織", "長田詩織", "長田しおり",
            "長田ラジオ", "けれねれ", "けれけ！", "ケレケレ", "キレレ",
            "乗って行こうぜミー", "乗っていこうぜミー", "乗ってくるみ", "乗っていく俺に",
            "持っていく俺に"
    };

    private static final String[] BLOCKED_AUTOMATIC_SOURCES = {
            // These occurred in polluted learned dictionaries or are common enough that global
            // replacement would be dangerous. Show-specific variants are handled contextually.
            "キラキラ", "キレキレ", "キレレ", "テレテレ", "けれ", "くる", "きた", "きて",
            "言葉", "方が"
    };

    private KerekereContextProfile() {}

    public static boolean applies(String program) {
        String p = safe(program).replace(" ", "");
        return p.contains("けれけれ") || (p.contains("永田詩央里") && p.contains("ラジオ"));
    }

    /** Highest-priority phrases sent to RecognizerIntent.EXTRA_BIASING_STRINGS. */
    public static ArrayList<String> biasTerms(String program) {
        ArrayList<String> out = new ArrayList<>();
        if (!applies(program)) return out;
        LinkedHashSet<String> set = new LinkedHashSet<>();
        addAll(set, CORE);
        addAll(set, CORNERS);
        addAll(set, MEMBERS);
        // Keep the recognizer list compact. Akita vocabulary is useful but lower priority.
        for (String s : AKITA) {
            if (set.size() >= 50) break;
            set.add(s);
        }
        out.addAll(set);
        return out;
    }

    /**
     * Extra score for recognition candidates. Program vocabulary outweighs small confidence
     * differences only where it is safe. Ambiguous words such as "キラキラ", "キレキレ" and
     * "テレテレ" are neither punished nor blindly rewritten.
     */
    public static double scoreCandidate(String program, String candidate, String previousText) {
        if (!applies(program)) return 0.0;
        String c = safe(candidate);
        if (c.isEmpty()) return 0.0;
        String context = tail(previousText, 420) + " " + c;
        double score = 0.0;

        boolean showContext = showTitleContext(context);
        boolean corner = cornerContext(context);
        boolean hashtag = hashtagContext(context);

        for (String t : CORE) {
            if (!c.contains(t)) continue;
            if ("けれけれ".equals(t)) score += showContext ? 10.0 : 2.0;
            else if ("聞いてけれ".equals(t) || "しおりん聞いてけれ".equals(t))
                score += corner ? 10.0 : 2.0;
            else if ("永田ラジオ".equals(t)) score += hashtag ? 10.0 : 3.0;
            else score += coreWeight(t);
        }
        for (String t : CORNERS) if (c.contains(t)) score += 7.0;

        boolean groupContext = containsAny(context, "≠ME", "ノットイコール", "ノイミー", "メンバー",
                "ツアー", "ライブ", "アイドルグループ", "永田詩央里");
        if (groupContext) for (String t : MEMBERS) if (c.contains(t)) score += 5.5;

        boolean akitaContext = containsAny(context, "秋田", "ABS", "秋田のしおり", "県", "市", "町");
        if (akitaContext) for (String t : AKITA) if (c.contains(t)) score += 2.8;

        for (String bad : KNOWN_BAD) if (c.contains(bad)) score -= 8.0;
        if (corner && containsAny(c, "聞いてくれ", "聞いてくけれ")) score -= 7.0;
        if (hashtag && c.contains("長田ラジオ")) score -= 10.0;

        return Math.max(-24.0, Math.min(28.0, score));
    }

    /** Program-aware high-confidence corrections; never guesses missing content. */
    public static String refine(String program, String previousText, String segment) {
        String s = safe(segment);
        if (!applies(program) || s.isEmpty()) return s;
        String context = tail(previousText, 520) + " " + s;

        // Host name: repeated diagnostic confusion and effectively fixed identity in this program.
        s = s.replace("中田詩織", "永田詩央里")
                .replace("中田詩央里", "永田詩央里")
                .replace("永田詩織", "永田詩央里")
                .replace("長田詩織", "永田詩央里")
                .replace("長田しおり", "永田詩央里");

        if (showTitleContext(context)) {
            // Important: "キラキラ" is not here. It is a valid ordinary word.
            s = replaceNearby(s, "けれねれ", "けれけれ", 48,
                    "番組", "ラジオ", "永田", "詩央里", "ノットイコール", "パーソナリティ", "過去回");
            s = replaceNearby(s, "けれけ！", "けれけれ", 48,
                    "番組", "ラジオ", "永田", "詩央里", "ノットイコール", "パーソナリティ");
            s = replaceNearby(s, "ケレケレ", "けれけれ", 48,
                    "番組", "ラジオ", "永田", "詩央里", "ノットイコール", "パーソナリティ", "過去回");
            s = replaceNearby(s, "キレキレ", "けれけれ", 42,
                    "番組", "ラジオ", "永田", "詩央里", "ノットイコール", "パーソナリティ", "過去回");
            s = replaceNearby(s, "キレレ", "けれけれ", 42,
                    "番組", "ラジオ", "永田", "詩央里", "ノットイコール", "パーソナリティ", "過去回");
            s = replaceNearby(s, "テレテレ", "けれけれ", 42,
                    "番組", "ラジオ", "永田", "詩央里", "ノットイコール", "パーソナリティ", "過去回");
        }

        String joined = tail(previousText, 280) + " " + s;
        if (groupContext(joined)) {
            s = s.replace("乗って行こうぜミー", "ノットイコールミー")
                    .replace("乗っていこうぜミー", "ノットイコールミー")
                    .replace("乗ってくるみ", "ノットイコールミー")
                    .replace("乗っていく俺に", "ノットイコールミー")
                    .replace("持っていく俺に", "ノットイコールミー");
        }

        if (cornerContext(joined)) {
            s = s.replace("しおりん 聞いてくれ", "しおりん 聞いてけれ")
                    .replace("しおりん聞いてくれ", "しおりん聞いてけれ")
                    .replace("しおり 聞いてくれ", "しおりん 聞いてけれ")
                    .replace("しおりん 聞いてくけれ", "しおりん 聞いてけれ")
                    .replace("しおりん聞いてくけれ", "しおりん聞いてけれ");
        }
        if (containsAny(joined, "来週も", "最後まで") && s.contains("聞いてくれ")) {
            s = s.replace("聞いてくれ", "聞いてけれ");
        }

        if (hashtagContext(joined)) s = s.replace("長田ラジオ", "永田ラジオ");

        if (containsAny(joined, "ラジオ猫", "名前会議", "祝い花", "ステッカー", "番組キャラクター")) {
            s = s.replace("ラジオ ネコ", "ラジオ猫")
                    .replace("ラジオねこ", "ラジオ猫");
        }
        if (containsAny(joined, "秋田", "コーナー", "ABS")) {
            s = s.replace("秋田の詩織", "秋田のしおり");
        }

        return cleanupSpacing(s);
    }

    public static boolean isUnsafeAutomaticPair(String program, String wrong, String correct) {
        if (!applies(program)) return false;
        String w = normalize(wrong), c = normalize(correct);
        for (String blocked : BLOCKED_AUTOMATIC_SOURCES) {
            if (w.equals(blocked)) return true;
        }
        // Program title/phrase variants must be context-gated, never reusable global replacements.
        if ("けれけれ".equals(c) && containsAny(w, "キラキラ", "キレキレ", "キレレ", "テレテレ",
                "けれねれ", "ケレケレ", "けれけ！")) return true;
        if ("聞いてけれ".equals(c) && containsAny(w, "聞いてくれ", "聞いてくけれ")) return true;
        return false;
    }

    public static String describe(String program) {
        if (!applies(program)) return "profile=generic";
        return "profile=" + PROFILE_VERSION + ";biasTerms=" + biasTerms(program).size()
                + ";contextPriority=program>generic;globalShowNameReplacement=false";
    }

    private static double coreWeight(String term) {
        if ("永田詩央里".equals(term) || "ノットイコールミー".equals(term) || "≠ME".equals(term)) return 11.0;
        return 4.5;
    }

    private static boolean showTitleContext(String s) {
        return containsAny(s, "この番組", "番組", "ラジオ番組", "パーソナリティ", "過去回",
                "永田詩央里", "ノットイコールミー", "ABS", "来週も", "最後まで", "公式ハッシュタグ");
    }

    private static boolean groupContext(String s) {
        return containsAny(s, "アイドルグループ", "≠ME", "ノットイコール", "ノイミー", "ツアー",
                "ライブ", "メンバー", "公式ホームページ", "永田詩央里");
    }

    private static boolean cornerContext(String s) {
        return containsAny(s, "しおりん", "こちらのコーナー", "コーナーをやり", "何でも送って",
                "ラジオネーム", "以上", "お便りお待ち", "聞いてけれ");
    }

    private static boolean hashtagContext(String s) {
        return containsAny(s, "ハッシュタグ", "公式ハッシュタグ", "番組の感想", "番組公式X");
    }

    private static String replaceNearby(String text, String wrong, String correct, int radius, String... hints) {
        String s = text;
        int i = s.indexOf(wrong);
        while (i >= 0) {
            int from = Math.max(0, i - radius);
            int to = Math.min(s.length(), i + wrong.length() + radius);
            String around = s.substring(from, to);
            if (containsAny(around, hints)) {
                s = s.substring(0, i) + correct + s.substring(i + wrong.length());
                i = s.indexOf(wrong, i + correct.length());
            } else {
                i = s.indexOf(wrong, i + wrong.length());
            }
        }
        return s;
    }

    private static void addAll(LinkedHashSet<String> set, String[] values) {
        for (String s : values) if (s != null && !s.trim().isEmpty()) set.add(s.trim());
    }

    private static String tail(String s, int max) {
        String x = safe(s);
        return x.length() <= max ? x : x.substring(x.length() - max);
    }

    private static String cleanupSpacing(String s) {
        return safe(s)
                .replaceAll("[ \\t]+([、。！？!?])", "$1")
                .replaceAll("([（(]) +", "$1")
                .replaceAll(" +([）)])", "$1")
                .replaceAll("[ \\t]{2,}", " ")
                .trim();
    }

    private static String normalize(String s) {
        return safe(s).replace("\r\n", " ").replace('\r',' ').replace('\n',' ')
                .replaceAll("[ \\t]+", " ").trim();
    }

    private static boolean containsAny(String s, String... terms) {
        String x = safe(s);
        for (String t : terms) if (t != null && !t.isEmpty() && x.contains(t)) return true;
        return false;
    }

    private static String safe(String s) { return s == null ? "" : s; }
}

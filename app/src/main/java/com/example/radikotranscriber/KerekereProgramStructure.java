package com.example.radikotranscriber;

import java.util.ArrayList;
import java.util.LinkedHashSet;

/**
 * Structural language hints for "≠ME 永田詩央里のけれけれ".
 * This is deliberately contextual: a radio-name spelling is strongly preferred only in a
 * radio-name slot, and fixed show phrases are boosted only near the matching show state.
 */
public final class KerekereProgramStructure {
    public static final String VERSION = "kerekere-structure-v020-2026-09-06";

    private static final String[] IMPORTANT_RADIO_NAMES = {
            "ななお", "ガンバレないわ", "しゃかかな"
    };

    private static final String[] FIXED_PHRASES = {
            "皆さんこんばんは", "本日もよろしくお願いします", "ラジオネーム",
            "それではそろそろ始めていきましょう", "≠ME 永田詩央里のけれけれ",
            "しおりん聞いてけれ", "秋田のしおり", "ここで1曲聞いてください",
            "けれけれエンディングです", "公式ハッシュタグは永田ラジオです",
            "過去回はradikoポッドキャストで聞くことができます",
            "ここまでのお相手は≠ME永田詩央里でした", "来週も聞いてけれ"
    };

    private KerekereProgramStructure() {}

    public static ArrayList<String> biasTerms(String program, String episodeContext) {
        ArrayList<String> out = new ArrayList<>();
        if (!KerekereContextProfile.applies(program)) return out;
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String n : IMPORTANT_RADIO_NAMES) set.add(n);
        for (String p : FIXED_PHRASES) set.add(p);
        for (String token : usefulContextTokens(episodeContext)) {
            if (set.size() >= 28) break;
            set.add(token);
        }
        out.addAll(set);
        return out;
    }

    public static double scoreCandidate(String program, String candidate,
                                        String previousText, String episodeContext) {
        if (!KerekereContextProfile.applies(program)) return 0.0;
        String c = safe(candidate);
        String prev = tail(previousText, 520);
        String joined = prev + " " + c;
        double score = 0.0;

        if (radioNameSlot(prev, c)) {
            for (String n : IMPORTANT_RADIO_NAMES) if (c.contains(n)) score += 19.0;
            if (containsAny(c, "菜々緒", "七尾", "ナナオ", "なな尾")) score -= 8.0;
            if (containsAny(c, "頑張れないわ", "がんばれないわ", "ガンバレないは", "頑張れないは")) score -= 7.0;
            if (containsAny(c, "釈迦かな", "社会かな", "シャカかな", "しゃかカナ")) score -= 7.0;
        }

        if (openingContext(joined)) {
            if (c.contains("皆さんこんばんは")) score += 7.0;
            if (c.contains("本日もよろしくお願いします")) score += 6.0;
            if (c.contains("≠ME") || c.contains("ノットイコールミー")) score += 5.0;
            if (c.contains("永田詩央里")) score += 7.0;
            if (c.contains("けれけれ")) score += 6.0;
        }
        if (endingContext(joined)) {
            if (c.contains("ここまでのお相手")) score += 7.0;
            if (c.contains("永田詩央里")) score += 8.0;
            if (c.contains("来週も聞いてけれ")) score += 12.0;
            if (c.contains("永田ラジオ")) score += 8.0;
            if (c.contains("radikoポッドキャスト")) score += 7.0;
        }
        if (cornerContext(joined)) {
            if (c.contains("しおりん聞いてけれ")) score += 9.0;
            if (c.contains("秋田のしおり")) score += 9.0;
        }

        int contextMatches = 0;
        for (String token : usefulContextTokens(episodeContext)) {
            if (token.length() >= 3 && c.contains(token)) {
                score += Math.min(3.0, 0.8 + token.length() * 0.12);
                if (++contextMatches >= 5) break;
            }
        }
        return Math.max(-22.0, Math.min(35.0, score));
    }

    public static String refine(String program, String previousText, String segment) {
        String s = safe(segment);
        if (!KerekereContextProfile.applies(program) || s.isEmpty()) return s;
        String prev = tail(previousText, 620);
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
                    .replace("しゃかカナ", "しゃかかな");
        }

        if (endingContext(context)) {
            if (context.contains("ここまでのお相手")) {
                s = s.replace("ノットイコールに長年しおり", "≠ME 永田詩央里")
                        .replace("ノットイコールに長田しおり", "≠ME 永田詩央里")
                        .replace("ノットイコールミー長田しおり", "≠ME 永田詩央里")
                        .replace("ノットイコールミー永田しおり", "≠ME 永田詩央里");
            }
            s = s.replace("来週も聞いてくる", "来週も聞いてけれ")
                    .replace("来週も聞いてくれ", "来週も聞いてけれ")
                    .replace("来週も聞いてこれ", "来週も聞いてけれ")
                    .replace("永田 ラジオ", "永田ラジオ");
        }
        if (containsAny(context, "公式ハッシュタグ", "ハッシュタグ")) {
            s = s.replace("永田 ラジオ", "永田ラジオ")
                    .replace("長田ラジオ", "永田ラジオ");
        }
        return cleanup(s);
    }

    private static boolean radioNameSlot(String previous, String current) {
        String p = tail(previous, 110).replace("\n", " ");
        String c = safe(current);
        int idx = Math.max(p.lastIndexOf("ラジオネーム"), p.lastIndexOf("RN"));
        if (idx >= 0 && p.length() - idx <= 70) return true;
        return c.startsWith("ラジオネーム") || c.contains(" ラジオネーム ");
    }

    private static boolean openingContext(String s) {
        return containsAny(s, "皆さんこんばんは", "本日もよろしく", "始めていきましょう",
                "この番組は", "パーソナリティを務める");
    }

    private static boolean endingContext(String s) {
        return containsAny(s, "エンディング", "ここまでのお相手", "最後まで聞いて",
                "公式ハッシュタグ", "過去回", "来週も");
    }

    private static boolean cornerContext(String s) {
        return containsAny(s, "こちらのコーナー", "コーナーです", "コーナーをやり",
                "しおりん聞いて", "秋田のしおり");
    }

    private static ArrayList<String> usefulContextTokens(String source) {
        ArrayList<String> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        String s = safe(source).replace('「', ' ').replace('」', ' ').replace('『', ' ').replace('』', ' ');
        for (String x : s.split("[\\s/／・|｜,，:：()（）<>【】\\[\\]。！？!?]+")) {
            x = x.trim();
            if (x.length() < 2 || x.length() > 24) continue;
            if (x.matches("[0-9０-９]+")) continue;
            if (seen.add(x)) out.add(x);
            if (out.size() >= 20) break;
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

package com.example.radikotranscriber;

/** Conservative, program-scoped fixes learned from the v0.23 #12 diagnostic. */
public final class KerekereObservedCorrectionsV024 {
    public static final String VERSION = "kerekere-observed-v024-2026-09-06";

    private KerekereObservedCorrectionsV024() {}

    public static double scoreCandidate(String program, String candidate, String previousText) {
        if (!KerekereContextProfile.applies(program)) return 0.0;
        String c = safe(candidate);
        String context = tail(previousText, 700) + " " + c;
        double score = 0.0;

        if (radioNameSlot(previousText, c)) {
            if (c.contains("ガンバレないわ")) score += 16.0;
            if (c.contains("しゃかかな")) score += 16.0;
            if (containsAny(c, "頑張れない わ", "頑張れないわ", "釈迦 かな")) score -= 8.0;
        }
        if (containsAny(context, "公式ハッシュタグ", "ハッシュタグ")) {
            if (c.contains("永田ラジオ")) score += 9.0;
            if (containsAny(c, "長田 ラジオ", "長田です")) score -= 6.0;
        }
        if (containsAny(context, "過去回", "過去会", "ポッドキャスト")) {
            if (c.contains("けれけれ")) score += 6.0;
            if (containsAny(c, "キラキラ 過去", "キレキレ 過去")) score -= 5.0;
        }
        if (containsAny(context, "新習志野", "深夜ラジオ")) {
            if (c.contains("韻が踏める")) score += 6.0;
            if (c.contains("慰める")) score -= 4.0;
        }
        return Math.max(-16.0, Math.min(24.0, score));
    }

    public static String refine(String program, String previousText, String segment) {
        String s = safe(segment);
        if (!KerekereContextProfile.applies(program) || s.isEmpty()) return s;
        String context = tail(previousText, 900) + " " + s;

        if (radioNameSlot(previousText, s)) {
            s = s.replaceAll("頑張れない[ \\t]+わ(?=[ \\t]*(?:しおりん|しよりん|しおり))", "ガンバレないわ ")
                    .replaceAll("頑張れない[ \\t]*わ", "ガンバレないわ")
                    .replaceAll("釈迦[ \\t]+かな", "しゃかかな");
        }

        if (containsAny(context, "公式ハッシュタグ", "ハッシュタグ")) {
            s = s.replace("ハッシュタグ長田です", "ハッシュタグ 永田ラジオです")
                    .replace("ハッシュタグ 長田です", "ハッシュタグ 永田ラジオです")
                    .replace("ハッシュタグ 長田 ラジオ", "ハッシュタグ 永田ラジオ")
                    .replace("ハッシュタグ長田 ラジオ", "ハッシュタグ 永田ラジオ");
        }

        if (containsAny(context, "過去回", "過去会", "radiko", "ポッドキャスト")) {
            s = s.replace("キラキラ 過去回", "けれけれ 過去回")
                    .replace("キレキレ 過去回", "けれけれ 過去回")
                    .replace("ケレケレ 過去回", "けれけれ 過去回")
                    .replace("キラキラ 過去会", "けれけれ 過去回")
                    .replace("キレキレ 過去会", "けれけれ 過去回");
        }

        if (containsAny(context, "送ってもらう", "こちらのコーナー", "しおりん 聞いて")) {
            s = s.replace("送ってもらう 粉です", "送ってもらうコーナーです")
                    .replace("送ってもらう粉です", "送ってもらうコーナーです")
                    .replace("しおり 聞いてくれ", "しおりん 聞いてけれ")
                    .replace("しおりん 聞いてけれれ", "しおりん 聞いてけれ");
        }

        if (containsAny(context, "第10回", "スペシャル", "放送記念")) {
            s = s.replace("スペシャル会", "スペシャル回");
        }
        if (containsAny(context, "ラジオ界", "ラジオ会", "ドン")) {
            s = s.replace("ラジオ会のドン", "ラジオ界のドン")
                    .replace("ラジオ会のどん", "ラジオ界のドン");
        }
        if (containsAny(context, "冷凍倉庫", "ハンバーガー", "パティ", "ポテト")) {
            s = s.replace("逆さなの", "逆サウナの")
                    .replace("パチンポテト", "パティやポテト")
                    .replace("パチン ポテト", "パティやポテト");
        }
        if (containsAny(context, "新習志野", "深夜ラジオ")) {
            s = s.replace("新習志野 と 深夜ラジオで慰める", "新習志野と深夜ラジオで韻が踏める")
                    .replace("新習志野と深夜ラジオで慰める", "新習志野と深夜ラジオで韻が踏める");
        }
        if (containsAny(context, "同タイトル", "同名", "麻丘めぐみ", "斉藤由貴")) {
            s = s.replace("同名 医局", "同名異曲")
                    .replace("同名医局", "同名異曲");
        }
        if (containsAny(context, "曲", "楽曲", "選曲")) {
            s = s.replace("私の選挙区", "私の選曲")
                    .replace("この選挙区", "この選曲");
        }

        return s.replaceAll("[ \\t]{2,}", " ").trim();
    }

    private static boolean radioNameSlot(String previous, String current) {
        String p = tail(previous, 160).replace('\n', ' ');
        String c = safe(current);
        int idx = p.lastIndexOf("ラジオネーム");
        if (idx >= 0 && p.length() - idx <= 95) return true;
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

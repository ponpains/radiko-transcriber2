package com.example.radikotranscriber;

import java.util.ArrayList;

/** Narrow, source-verified spellings for errors actually observed in diagnostics. */
public final class KerekereVerifiedLexicon {
    public static final String VERSION = "kerekere-verified-lexicon-v022-2026-09-06";

    private KerekereVerifiedLexicon() {}

    public static ArrayList<String> biasTerms(String program) {
        ArrayList<String> out = new ArrayList<>();
        if (!KerekereContextProfile.applies(program)) return out;
        out.add("赤い公園");
        out.add("NOW ON AIR");
        out.add("佐野元春");
        out.add("悲しきレイディオ");
        out.add("ミッシェル・ガン・エレファント");
        out.add("THEE MICHELLE GUN ELEPHANT");
        out.add("スイミング・ラジオ");
        out.add("アベフトシ");
        out.add("スピッツ");
        out.add("ラジオデイズ");
        out.add("The Buggles");
        out.add("Video Killed the Radio Star");
        out.add("ラジオ・スターの悲劇");
        out.add("純烈");
        out.add("君が涙をくれる時");
        return out;
    }

    public static double scoreCandidate(String program, String candidate, String previousText) {
        if (!KerekereContextProfile.applies(program)) return 0.0;
        String c = safe(candidate);
        String context = tail(previousText, 600) + " " + c;
        double score = 0.0;

        if (containsAny(context, "赤い公園", "ナウオンエアー", "NOW ON AIR")) {
            if (c.contains("NOW ON AIR")) score += 7.0;
            if (c.contains("ナウオンエアー")) score -= 1.5;
        }
        if (containsAny(context, "佐野元春", "悲しき")) {
            if (c.contains("悲しきレイディオ")) score += 8.0;
            if (containsAny(c, "悲しきれるよ", "悲しきレディオ")) score -= 5.0;
        }
        if (containsAny(context, "ミッシェル", "ミシェル", "スイミング")) {
            if (c.contains("ミッシェル・ガン・エレファント")) score += 9.0;
            if (c.contains("スイミング・ラジオ")) score += 6.0;
            if (containsAny(c, "ミシェルガ エレファント", "ミッシェルガンエレファント")) score -= 4.0;
            if (c.contains("アベフトシ")) score += 5.0;
        }
        if (containsAny(context, "バグルス", "ラジオスター", "レリオスター")) {
            if (c.contains("The Buggles")) score += 5.0;
            if (c.contains("Video Killed the Radio Star")) score += 7.0;
        }
        if (containsAny(context, "君が涙をくれる時", "順列", "純烈")) {
            if (c.contains("純烈")) score += 6.0;
            if (c.contains("順列")) score -= 4.0;
        }
        return Math.max(-12.0, Math.min(18.0, score));
    }

    public static String refine(String program, String previousText, String segment) {
        String s = safe(segment);
        if (!KerekereContextProfile.applies(program) || s.isEmpty()) return s;
        String context = tail(previousText, 750) + " " + s;

        if (containsAny(context, "赤い公園", "ナウオンエアー", "NOW ON AIR")) {
            s = s.replace("ナウオンエアー", "NOW ON AIR")
                    .replace("ナウ オン エアー", "NOW ON AIR");
        }

        if (containsAny(context, "佐野元春", "悲しきれるよ", "悲しきレディオ", "悲しきレイディオ")) {
            s = s.replace("悲しきれるよ", "悲しきレイディオ")
                    .replace("悲しきレディオ", "悲しきレイディオ")
                    .replace("悲しき RADIO", "悲しきレイディオ");
        }

        if (containsAny(context, "ミッシェル", "ミシェル", "スイミング ラジオ", "スイミング・ラジオ")) {
            s = s.replace("ミッシェルガンエレファント", "ミッシェル・ガン・エレファント")
                    .replace("ミッシェル ガン エレファント", "ミッシェル・ガン・エレファント")
                    .replace("ミシェルガンエレファント", "ミッシェル・ガン・エレファント")
                    .replace("ミシェルガ エレファント", "ミッシェル・ガン・エレファント")
                    .replace("ミシェル ガ エレファント", "ミッシェル・ガン・エレファント")
                    .replace("ミシェル・ガン・エレファント", "ミッシェル・ガン・エレファント")
                    .replace("スイミング ラジオ", "スイミング・ラジオ")
                    .replace("スイミングラジオ", "スイミング・ラジオ");
            if (containsAny(context, "ギター", "ギターソロ") && containsAny(s, "阿部太", "アベ太")) {
                s = s.replace("阿部太", "アベフトシ").replace("アベ太", "アベフトシ");
            }
        }

        if (containsAny(context, "バグルス", "レリオ スター", "ラジオスター", "ラジオ・スター")) {
            s = s.replace("ザ バグルス", "The Buggles")
                    .replace("ザ・バグルス", "The Buggles")
                    .replace("ビデオキルドザレリオ スター", "Video Killed the Radio Star")
                    .replace("ビデオキルドザレリオスター", "Video Killed the Radio Star")
                    .replace("ビデオキルドザラジオスター", "Video Killed the Radio Star")
                    .replace("ビデオキルのザラジオスター", "Video Killed the Radio Star")
                    .replace("ビデオキルドザレディオスター", "Video Killed the Radio Star");
        }

        if (containsAny(context, "君が涙をくれる時", "順列 さん", "順列さん")) {
            s = s.replace("順列 さん", "純烈さん")
                    .replace("順列さん", "純烈さん");
        }

        return s.replaceAll("[ \\t]{2,}", " ").trim();
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

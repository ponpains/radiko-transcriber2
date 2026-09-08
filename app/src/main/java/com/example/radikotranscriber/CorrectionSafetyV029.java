package com.example.radikotranscriber;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Conservative rollback guard for post-correction.
 * Keeps corrections only when they do not collapse the original utterance or replace a known-good
 * phrase with a lower-quality unknown form. Formatting-only changes remain allowed.
 */
public final class CorrectionSafetyV029 {
    public static final String VERSION = "correction-safety-v029-2026-09-08";

    private CorrectionSafetyV029() {}

    public static String choose(String original, String corrected, String program) {
        String src = safe(original).trim();
        String dst = safe(corrected).trim();
        if (src.isEmpty()) return dst;
        if (dst.isEmpty()) return src;
        if (src.equals(dst)) return dst;

        String srcCompact = compact(src);
        String dstCompact = compact(dst);
        if (srcCompact.equals(dstCompact)) return dst;

        // Concrete regressions observed in the latest v0.28 diagnostics.
        if (src.contains("私の曲") && dst.contains("私の客")) return src;
        if (containsAny(src, "ケレケレポスター", "けれけれポスター", "ケレケレ ポスター")
                && containsAny(dst, "ケレスポスター", "ケレス ポスター")) return src;
        if (containsAny(src, "ラジオ ネコです", "ラジオ猫です")
                && !containsEquivalent(dst, "ラジオ猫です")) return src;

        // Known-good programme vocabulary may change spacing/script, but must not disappear.
        String[] protectedTerms = {
                "私の曲", "永田詩央里", "ノットイコールミー", "永田ラジオ",
                "ラジオ猫", "けれけれポスター", "けれけれ"
        };
        for (String term : protectedTerms) {
            if (containsEquivalent(src, term) && !containsEquivalent(dst, term)) return src;
        }

        // Reject suspicious shortening. The correction layer should repair words, not erase speech.
        int srcLen = contentLength(src);
        int dstLen = contentLength(dst);
        if (srcLen >= 12 && dstLen < Math.max(4, (int)Math.floor(srcLen * 0.78))) return src;

        ArrayList<String> srcTokens = meaningfulTokens(src);
        if (srcTokens.size() >= 4) {
            int retained = 0;
            for (String token : srcTokens) {
                if (containsEquivalent(dst, token)) retained++;
            }
            // If more than half of meaningful source tokens vanished, prefer the auditable ASR text.
            if (retained * 2 < srcTokens.size() && dstLen <= srcLen) return src;
        }

        return dst;
    }

    private static boolean containsEquivalent(String text, String term) {
        return canonical(text).contains(canonical(term));
    }

    private static String canonical(String s) {
        String x = safe(s).toLowerCase(Locale.ROOT)
                .replace(" ", "")
                .replace("\t", "")
                .replace("\n", "")
                .replace("\r", "");
        // Script/spacing variants that are known to be the same programme vocabulary.
        x = x.replace("ケレケレ", "けれけれ")
                .replace("ラジオネコ", "ラジオ猫")
                .replace("レディオネコ", "ラジオ猫");
        return x;
    }

    private static String compact(String s) {
        return canonical(s).replaceAll("[、。！？!?・,.;:：；（）()「」『』\\[\\]{}]", "");
    }

    private static int contentLength(String s) {
        return compact(s).length();
    }

    private static ArrayList<String> meaningfulTokens(String s) {
        ArrayList<String> out = new ArrayList<>();
        String[] xs = safe(s).replaceAll("[、。！？!?・,.;:：；（）()「」『』\\[\\]{}]", " ")
                .split("\\s+");
        for (String x : xs) {
            String t = canonical(x);
            if (t.length() >= 2 && !out.contains(t)) out.add(t);
        }
        return out;
    }

    private static boolean containsAny(String s, String... terms) {
        for (String t : terms) if (safe(s).contains(t)) return true;
        return false;
    }

    private static String safe(String s) { return s == null ? "" : s; }
}

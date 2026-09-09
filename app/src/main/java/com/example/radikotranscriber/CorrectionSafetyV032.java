package com.example.radikotranscriber;

/**
 * v0.32 correction safety based on the v0.31 diagnostic.
 *
 * The existing v0.29 guard catches large destructive rewrites. This layer covers the opposite
 * failure mode: very short utterances where a show-specific scorer can turn a plausible primary
 * recognition into a different short phrase (for example ケレケレ -> ケラケレ). It deliberately
 * allows a replacement when it introduces a strong canonical programme term.
 */
public final class CorrectionSafetyV032 {
    public static final String VERSION = "correction-safety-v032-2026-09-09";

    private CorrectionSafetyV032() {}

    public static String choose(String original, String candidate, String program) {
        String o = safe(original).trim();
        String c = safe(candidate).trim();
        if (o.isEmpty() || c.isEmpty() || o.equals(c)) return c.isEmpty() ? o : c;
        if (!KerekereContextProfile.applies(program)) return c;

        String os = semantic(o);
        String cs = semantic(c);
        if (os.isEmpty() || cs.isEmpty() || os.equals(cs)) return c;

        // Once the recognizer already has the programme title, never let a later local rule turn it
        // into a near-looking non-word. Canonical normalization (spaces/case) is still allowed.
        if (hasKerekere(os) && !hasKerekere(cs)) return o;

        // Regression observed in the v0.31 pack: a plausible phrase was changed to this longer but
        // unsupported form. Keep the acoustic wording unless the correction reaches the exact
        // programme phrase instead.
        if (os.contains("聞いてくれ") && cs.contains("聞いていける") && !strongCanonical(c)) return o;

        // A short utterance has too little context for a wholesale semantic substitution. This is
        // intentionally conservative: canonical host/show terms are allowed through, ordinary
        // unrelated replacements are rolled back.
        if (os.length() <= 18 && !strongCanonical(c)) {
            double sim = similarity(os, cs);
            if (sim < 0.48) return o;
            if (cs.length() + 2 <= os.length() && (os.startsWith(cs) || os.contains(cs))) return o;
        }

        return c;
    }

    private static boolean strongCanonical(String s) {
        String x = semantic(s);
        return x.contains("永田詩央里")
                || x.contains("ノットイコールミー")
                || hasKerekere(x)
                || x.contains("ラジオ猫")
                || x.contains("永田ラジオ")
                || x.contains("しおりん聞いてけれ");
    }

    private static boolean hasKerekere(String semantic) {
        String x = safe(semantic);
        return x.contains("けれけれ") || x.contains("ケレケレ");
    }

    private static double similarity(String a, String b) {
        if (a.equals(b)) return 1.0;
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = cur; cur = tmp;
        }
        return Math.max(0.0, 1.0 - prev[b.length()] / (double)Math.max(a.length(), b.length()));
    }

    private static String semantic(String s) {
        return safe(s).replaceAll("[\\s、。！？!?，,.・･：:；;\\\"'’「」『』()（）]+", "");
    }

    private static String safe(String s) { return s == null ? "" : s; }
}

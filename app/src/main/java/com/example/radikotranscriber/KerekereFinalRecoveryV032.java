package com.example.radikotranscriber;

/** Final-only repairs for highly specific split programme/corner identifiers seen in v0.31. */
public final class KerekereFinalRecoveryV032 {
    public static final String VERSION = "kerekere-final-recovery-v032-2026-09-09";

    private KerekereFinalRecoveryV032() {}

    public static String refine(String program, String text) {
        String s = safe(text).replace("\r\n", "\n").replace('\r', '\n');
        if (!KerekereContextProfile.applies(program) || s.isEmpty()) return s;

        // The v0.31 run split the programme identification into two tiny callbacks:
        // "中田詩織の" + "ケレケレ". Only repair this when the two pieces are adjacent, so an
        // unrelated person's name elsewhere in ordinary talk is never rewritten.
        s = s.replaceAll("(?m)^(?:中田詩織|中田しおり|長田詩織|長田しおり|私より)の[。]?[ \\t]*\\n+[ \\t]*(?:ケレケレ|ケラケレ)[。]?$",
                "永田詩央里のけれけれ");

        // Exact isolated programme/corner labels are safe to normalize; embedded uses are left to
        // the normal language/candidate pipeline.
        s = s.replaceAll("(?m)^(?:ケレケレ|ケラケレ)[。]?$", "けれけれ");
        s = s.replaceAll("(?m)^おり[ \\t]*聞いて(?:くれ|いける)[。]?$", "しおりん聞いてけれ");

        return s.replaceAll("\\n{3,}", "\n\n").trim();
    }

    private static String safe(String s) { return s == null ? "" : s; }
}

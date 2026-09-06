package com.example.radikotranscriber;

import java.util.ArrayList;

/** High-value spellings for the current diagnostic; all are program-scoped. */
public final class KerekereV023Bias {
    public static final String VERSION = "kerekere-bias-v023-2026-09-06";

    private KerekereV023Bias() {}

    public static ArrayList<String> biasTerms(String program) {
        ArrayList<String> out = new ArrayList<>();
        if (!KerekereContextProfile.applies(program)) return out;
        out.add("ななお");
        out.add("ガンバレないわ");
        out.add("しゃかかな");
        out.add("ナガハマコーヒー");
        out.add("外旭川店");
        out.add("テレビ大陸音頭");
        out.add("夜について");
        out.add("≠ME THE MOVIE -約束の歌-");
        out.add("イコノイジョイ大感謝祭 2025");
        out.add("We want to find \"カフェ樂園\"");
        out.add("LaLa arena TOKYO-BAY");
        out.add("男鹿半島");
        out.add("けれけれ係");
        return out;
    }
}

package com.example.radikotranscriber;

/**
 * Merges cumulative SpeechRecognizer results conservatively.
 *
 * Android segmented recognition often returns an almost-complete previous block again with a few
 * changed characters and a short continuation. Exact suffix matching misses that pattern and can
 * duplicate hundreds of characters. This helper looks only at the recent transcript tail and the
 * candidate prefix, so it cannot delete an unrelated repeated phrase from far earlier in a show.
 */
public final class RecognitionTextMerger {
    private RecognitionTextMerger() {}

    public static final class MergeResult {
        public final String append;
        public final int overlapCompactChars;
        public final double similarity;
        public final boolean duplicate;
        public final String reason;

        MergeResult(String append, int overlapCompactChars, double similarity,
                    boolean duplicate, String reason) {
            this.append = append == null ? "" : append;
            this.overlapCompactChars = overlapCompactChars;
            this.similarity = similarity;
            this.duplicate = duplicate;
            this.reason = reason == null ? "" : reason;
        }
    }

    public static MergeResult merge(String existingText, String candidate) {
        String original = candidate == null ? "" : candidate.trim();
        if (original.isEmpty()) return new MergeResult("", 0, 1.0, true, "empty");

        String a = compact(tail(existingText, 1800));
        String b = compact(original);
        if (b.isEmpty()) return new MergeResult("", 0, 1.0, true, "empty_compact");
        if (a.isEmpty()) return new MergeResult(original, 0, 0.0, false, "first");

        // Exact recent containment first.
        int exactIndex = a.lastIndexOf(b);
        if (exactIndex >= 0 && exactIndex + b.length() >= a.length() - 6) {
            return new MergeResult("", b.length(), 1.0, true, "exact_recent");
        }

        int exactOverlap = exactSuffixPrefix(a, b, 700);
        if (exactOverlap >= 8) {
            if (exactOverlap >= b.length() - 2) {
                return new MergeResult("", exactOverlap, 1.0, true, "exact_overlap");
            }
            int cut = approximateCut(original, exactOverlap);
            return new MergeResult(original.substring(Math.min(cut, original.length())).trim(),
                    exactOverlap, 1.0, false, "exact_delta");
        }

        // Fuzzy cumulative-result detection. Require a meaningful overlap and a strong match.
        int max = Math.min(Math.min(a.length(), b.length()), 700);
        int min = Math.min(max, Math.max(24, Math.min(90, b.length() / 3)));
        int bestLen = 0;
        double bestSimilarity = 0.0;
        for (int len = max; len >= min; len -= len > 220 ? 8 : 4) {
            String left = a.substring(a.length() - len);
            String right = b.substring(0, len);
            int allowed = Math.max(2, Math.min(18, (int)Math.ceil(len * 0.10)));
            int d = levenshteinWithin(left, right, allowed);
            if (d > allowed) continue;
            double sim = 1.0 - (d / (double)Math.max(1, len));
            if (sim > bestSimilarity || (Math.abs(sim - bestSimilarity) < 0.0001 && len > bestLen)) {
                bestSimilarity = sim;
                bestLen = len;
            }
            // A very long 96%+ match is enough; no need to scan every smaller prefix.
            if (len >= 120 && sim >= 0.96) break;
        }

        if (bestLen >= min && bestSimilarity >= 0.90) {
            int novel = b.length() - bestLen;
            if (novel <= 2) {
                return new MergeResult("", bestLen, bestSimilarity, true, "fuzzy_duplicate");
            }
            // Avoid treating a generic short repeated phrase as cumulative recognition.
            if (bestLen >= 40 || bestLen >= Math.round(b.length() * 0.55)) {
                int cut = approximateCut(original, bestLen);
                String delta = original.substring(Math.min(cut, original.length())).trim();
                if (compact(delta).length() <= 1) {
                    return new MergeResult("", bestLen, bestSimilarity, true, "fuzzy_no_novelty");
                }
                return new MergeResult(delta, bestLen, bestSimilarity, false, "fuzzy_delta");
            }
        }

        return new MergeResult(original, 0, 0.0, false, "independent");
    }

    private static int exactSuffixPrefix(String a, String b, int limit) {
        int max = Math.min(Math.min(a.length(), b.length()), limit);
        for (int k = max; k >= 8; k--) {
            if (a.regionMatches(a.length() - k, b, 0, k)) return k;
        }
        return 0;
    }

    private static int approximateCut(String original, int compactChars) {
        int seen = 0;
        for (int i = 0; i < original.length(); i++) {
            char c = original.charAt(i);
            if (!Character.isWhitespace(c) && "、。！？!?，,.・：；「」『』（）()".indexOf(c) < 0) seen++;
            if (seen >= compactChars) return i + 1;
        }
        return original.length();
    }

    private static String compact(String s) {
        return s == null ? "" : s.replaceAll("[\\s、。！？!?，,.・：；「」『』（）()]+", "");
    }

    private static String tail(String s, int max) {
        String x = s == null ? "" : s;
        return x.length() <= max ? x : x.substring(x.length() - max);
    }

    /** Returns maxDistance+1 as soon as it is clear the strings are too different. */
    private static int levenshteinWithin(String a, String b, int maxDistance) {
        if (Math.abs(a.length() - b.length()) > maxDistance) return maxDistance + 1;
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            int rowMin = cur[0];
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                rowMin = Math.min(rowMin, cur[j]);
            }
            if (rowMin > maxDistance) return maxDistance + 1;
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }
}

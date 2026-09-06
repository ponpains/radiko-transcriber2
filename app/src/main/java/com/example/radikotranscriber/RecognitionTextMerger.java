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

    public static final String VERSION = "elastic-boundary-v024-2026-09-06";

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

        // Existing equal-length fuzzy pass: useful for long cumulative recognizer returns.
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
            if (len >= 120 && sim >= 0.96) break;
        }
        if (bestLen >= min && bestSimilarity >= 0.90) {
            MergeResult r = fuzzyResult(original, b.length(), bestLen,
                    bestSimilarity, "fuzzy", false);
            if (r != null) return r;
        }

        // v0.24: a replay boundary can insert/delete a couple of characters, so the repeated suffix
        // and prefix are not necessarily the same length. A semiglobal edit-distance pass compares
        // the final 144 chars to the first 144 chars in O(n^2), rather than running hundreds of full
        // Levenshtein comparisons.
        ElasticMatch elastic = elasticSuffixPrefix(a, b);
        if (elastic != null) {
            MergeResult r = fuzzyResult(original, b.length(), elastic.prefixChars,
                    elastic.similarity, "elastic_fuzzy", true);
            if (r != null) return r;
        }

        // Sometimes the new chunk begins with a genuine bridge word ("あと", "そして"...) and
        // then repeats the preceding sentence. Preserve the bridge word while removing only the
        // repeated material after it.
        BridgeMatch bridge = bridgeOverlap(a, b);
        if (bridge != null) {
            int cut = approximateCut(original, bridge.markerChars + bridge.match.prefixChars);
            String delta = original.substring(Math.min(cut, original.length())).trim();
            String append = bridge.marker + (delta.isEmpty() ? "" : " " + delta);
            return new MergeResult(append, bridge.match.prefixChars, bridge.match.similarity,
                    false, "elastic_bridge_delta");
        }

        return new MergeResult(original, 0, 0.0, false, "independent");
    }

    private static MergeResult fuzzyResult(String original, int candidateCompactLength,
                                           int prefixChars, double similarity,
                                           String reasonPrefix, boolean elastic) {
        int novelty = candidateCompactLength - prefixChars;
        boolean strongEnough = prefixChars >= 24 ? similarity >= 0.91
                : prefixChars >= 18 && similarity >= (elastic ? 0.94 : 0.96);
        if (!strongEnough) return null;

        double coverage = prefixChars / (double)Math.max(1, candidateCompactLength);
        if (!elastic && prefixChars < 40 && coverage < 0.52) return null;

        if (novelty <= 2) {
            return new MergeResult("", prefixChars, similarity, true,
                    reasonPrefix + "_duplicate");
        }
        if (elastic || prefixChars >= 40 || coverage >= 0.55) {
            int cut = approximateCut(original, prefixChars);
            String delta = original.substring(Math.min(cut, original.length())).trim();
            if (compact(delta).length() <= 1) {
                return new MergeResult("", prefixChars, similarity, true,
                        reasonPrefix + "_no_novelty");
            }
            return new MergeResult(delta, prefixChars, similarity, false,
                    reasonPrefix + "_delta");
        }
        return null;
    }

    /** Minimum edit alignment between any suffix of A and any >=18-char prefix of B. */
    private static ElasticMatch elasticSuffixPrefix(String a, String b) {
        String left = a.substring(Math.max(0, a.length() - 144));
        String right = b.substring(0, Math.min(144, b.length()));
        int m = left.length(), n = right.length();
        if (m < 18 || n < 18) return null;

        int[][] cost = new int[m + 1][n + 1];
        int[][] start = new int[m + 1][n + 1];
        // Prefix of A is free: an alignment may start anywhere, but must end at A's final char.
        for (int i = 0; i <= m; i++) { cost[i][0] = 0; start[i][0] = i; }
        for (int j = 1; j <= n; j++) { cost[0][j] = j; start[0][j] = 0; }

        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                int bestCost = cost[i - 1][j - 1] + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1);
                int bestStart = start[i - 1][j - 1];

                int del = cost[i - 1][j] + 1;
                int delStart = start[i - 1][j];
                if (del < bestCost || (del == bestCost && delStart < bestStart)) {
                    bestCost = del; bestStart = delStart;
                }
                int ins = cost[i][j - 1] + 1;
                int insStart = start[i][j - 1];
                if (ins < bestCost || (ins == bestCost && insStart < bestStart)) {
                    bestCost = ins; bestStart = insStart;
                }
                cost[i][j] = bestCost;
                start[i][j] = bestStart;
            }
        }

        ElasticMatch best = null;
        for (int prefix = 18; prefix <= n; prefix++) {
            int suffix = m - start[m][prefix];
            if (suffix < 18) continue;
            int scale = Math.max(suffix, prefix);
            double sim = 1.0 - cost[m][prefix] / (double)Math.max(1, scale);
            double threshold = prefix < 24 ? 0.94 : 0.91;
            if (sim < threshold) continue;
            double score = sim + Math.min(0.08, prefix / 1800.0);
            if (best == null || score > best.score
                    || (Math.abs(score - best.score) < 0.0001 && prefix > best.prefixChars)) {
                best = new ElasticMatch(suffix, prefix, sim, score);
            }
        }
        return best;
    }

    private static BridgeMatch bridgeOverlap(String a, String b) {
        String[] markers = {"ちなみに", "そして", "それで", "なので", "だから", "でも", "あと"};
        for (String marker : markers) {
            String mc = compact(marker);
            if (!b.startsWith(mc) || b.length() <= mc.length() + 18) continue;
            ElasticMatch match = elasticSuffixPrefix(a, b.substring(mc.length()));
            if (match == null || match.prefixChars < 18 || match.similarity < 0.94) continue;
            return new BridgeMatch(marker, mc.length(), match);
        }
        return null;
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

    private static int levenshteinWithin(String a, String b, int maxDistance) {
        if (Math.abs(a.length() - b.length()) > maxDistance) return maxDistance + 1;
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            int rowMin = cur[0];
            for (int j = 1; j <= b.length(); j++) {
                int x = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + x);
                rowMin = Math.min(rowMin, cur[j]);
            }
            if (rowMin > maxDistance) return maxDistance + 1;
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    private static final class ElasticMatch {
        final int suffixChars;
        final int prefixChars;
        final double similarity;
        final double score;
        ElasticMatch(int suffixChars, int prefixChars, double similarity, double score) {
            this.suffixChars = suffixChars;
            this.prefixChars = prefixChars;
            this.similarity = similarity;
            this.score = score;
        }
    }

    private static final class BridgeMatch {
        final String marker;
        final int markerChars;
        final ElasticMatch match;
        BridgeMatch(String marker, int markerChars, ElasticMatch match) {
            this.marker = marker;
            this.markerChars = markerChars;
            this.match = match;
        }
    }
}

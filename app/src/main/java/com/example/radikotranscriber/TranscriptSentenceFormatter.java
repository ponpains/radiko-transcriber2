package com.example.radikotranscriber;

import java.util.ArrayList;

/**
 * Turns raw ASR blocks into a human-readable radio transcript.
 *
 * Goal: roughly one spoken sentence per line, not one recognizer callback per line. The recognizer
 * frequently returns several sentences in one long block, so we infer conservative Japanese
 * sentence boundaries from punctuation, strong sentence endings and discourse starts.
 */
public final class TranscriptSentenceFormatter {
    private TranscriptSentenceFormatter() {}

    private static final String[] STRONG_ENDINGS = {
            "ありがとうございました", "ありがとうございます", "よろしくお願いします", "お願いします",
            "いただきます", "ごちそうさまでした", "聞いてください", "お待ちしています",
            "と思います", "と思いました", "と思っています", "と思ってます", "と思うんです",
            "だったんです", "なんです", "なんですよ", "なんですよね", "なんですね",
            "でした", "ました", "ませんでした", "ません", "でしょう", "でしょうか",
            "ですよ", "ですよね", "ですね", "ですか", "ますか", "ましたか",
            "なんですか", "なんでしょうか", "いいですね", "いいと思います"
    };

    private static final String[] SOFT_ENDINGS = {
            "んですけど", "なんですけど", "ですけど", "ましたけど", "ますけど",
            "けれど", "けれども", "なんですが", "んですが", "ですが", "ので", "から"
    };

    private static final String[] NEW_SENTENCE_STARTERS = {
            "はい", "そう", "でも", "そして", "それで", "なので", "だから", "ただ", "あと",
            "あとは", "ちなみに", "今回", "最近", "私", "これ", "それ", "あの", "なんか",
            "やっぱり", "やっぱ", "まず", "続いて", "続きまして", "次に", "ここから",
            "ということで", "というわけで", "ありがとうございます", "皆さん", "今回も",
            "では", "それでは", "さて", "ところで", "本当に", "もう", "今度", "今日",
            "昨日", "明日", "来週", "ちなみにですね"
    };

    private static final String[] TOPIC_STARTERS = {
            "ラジオネーム", "続いて", "続きまして", "次のお便り", "続いてのお便り",
            "こちらのコーナー", "それではこちらのコーナー", "ここからは", "ここで一曲",
            "ここで1曲", "聞いていただいたのは", "お知らせ", "エンディングです",
            "以上 秋田のしおり", "以上秋田のしおり", "ということで最後まで"
    };

    public static String formatBlock(String text, boolean utteranceBoundary) {
        String s = normalize(text);
        if (s.isEmpty()) return s;

        s = insertExplicitMarkerStops(s);
        ArrayList<String> lines = splitExistingPunctuation(s);
        ArrayList<String> out = new ArrayList<>();
        for (String line : lines) splitInferred(line, out);

        StringBuilder result = new StringBuilder();
        for (String line : out) {
            String x = normalize(line);
            if (x.isEmpty()) continue;
            if (utteranceBoundary && !endsWithPunctuation(x) && looksComplete(x)) x += "。";
            if (result.length() > 0) result.append('\n');
            result.append(x);
        }
        return result.toString().trim();
    }

    public static boolean isTopicStarter(String line) {
        String s = trimLeadingSymbols(normalize(line));
        for (String m : TOPIC_STARTERS) if (s.startsWith(m)) return true;
        return false;
    }

    private static void splitInferred(String source, ArrayList<String> out) {
        String s = normalize(source);
        if (s.isEmpty()) return;

        int start = 0;
        for (int i = 1; i < s.length(); i++) {
            if (!Character.isWhitespace(s.charAt(i))) continue;
            String left = s.substring(start, i).trim();
            if (left.length() < 8) continue;
            int next = skipSpaces(s, i);
            if (next >= s.length()) break;
            String right = s.substring(next);

            boolean strong = endsWithAny(left, STRONG_ENDINGS);
            boolean soft = endsWithAny(left, SOFT_ENDINGS);
            boolean newStart = startsWithAny(trimLeadingSymbols(right), NEW_SENTENCE_STARTERS)
                    || startsWithAny(trimLeadingSymbols(right), TOPIC_STARTERS);
            boolean longClause = compactLength(left) >= 30;

            if (strong || (soft && newStart && longClause)) {
                String sentence = left;
                if (!endsWithPunctuation(sentence)) sentence += sentenceLooksQuestion(sentence) ? "？" : "。";
                out.add(sentence);
                start = next;
                i = Math.max(start, i);
            }
        }

        String rest = s.substring(Math.min(start, s.length())).trim();
        if (!rest.isEmpty()) {
            // A single long recognition block may contain sentence endings with no spaces around
            // them. Do a second conservative pass over the remaining text.
            splitByEmbeddedEndings(rest, out);
        }
    }

    private static void splitByEmbeddedEndings(String s, ArrayList<String> out) {
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            int boundary = -1;
            for (String ending : STRONG_ENDINGS) {
                if (!s.startsWith(ending, i)) continue;
                int end = i + ending.length();
                if (end >= s.length()) continue;
                int next = skipSpaces(s, end);
                if (next >= s.length()) continue;
                String left = s.substring(start, end).trim();
                String right = trimLeadingSymbols(s.substring(next));
                if (compactLength(left) < 12) continue;
                if (startsWithAny(right, NEW_SENTENCE_STARTERS) || startsWithAny(right, TOPIC_STARTERS)) {
                    boundary = end;
                    break;
                }
            }
            if (boundary > 0) {
                String x = s.substring(start, boundary).trim();
                if (!endsWithPunctuation(x)) x += sentenceLooksQuestion(x) ? "？" : "。";
                out.add(x);
                start = skipSpaces(s, boundary);
                i = Math.max(start - 1, i);
            }
        }
        String tail = s.substring(Math.min(start, s.length())).trim();
        if (!tail.isEmpty()) out.add(tail);
    }

    private static ArrayList<String> splitExistingPunctuation(String s) {
        ArrayList<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isSentencePunctuation(c)) {
                out.add(s.substring(start, i + 1).trim());
                start = i + 1;
            }
        }
        if (start < s.length()) out.add(s.substring(start).trim());
        return out;
    }

    private static String insertExplicitMarkerStops(String s) {
        String[] markers = {
                "ということで", "というわけで", "続いて", "続きまして", "それでは",
                "ここからは", "次のお便り", "続いてのお便り", "ラジオネーム",
                "ここで1曲", "ここで一曲", "聞いていただいたのは", "お知らせ"
        };
        String out = s;
        for (String marker : markers) {
            int from = 0;
            while (true) {
                int i = out.indexOf(marker, from);
                if (i <= 0) break;
                String before = out.substring(Math.max(0, i - 80), i).trim();
                if (compactLength(before) >= 18) {
                    int p = i - 1;
                    while (p >= 0 && Character.isWhitespace(out.charAt(p))) p--;
                    if (p >= 0 && !isSentencePunctuation(out.charAt(p))) {
                        out = out.substring(0, p + 1) + "。" + out.substring(p + 1);
                        i++;
                    }
                }
                from = i + marker.length();
                if (from >= out.length()) break;
            }
        }
        return out;
    }

    private static boolean looksComplete(String s) {
        String x = normalize(s);
        if (x.length() < 7) return false;
        if (endsWithAny(x, new String[]{"て", "で", "し", "と", "に", "を", "が", "は", "も", "の",
                "けど", "けれど", "ので", "から", "たり", "とか", "って", "という", "あの", "その"})) {
            return false;
        }
        if (endsWithAny(x, STRONG_ENDINGS)) return true;
        if (endsWithAny(x, SOFT_ENDINGS)) return compactLength(x) >= 28;
        return x.endsWith("ね") || x.endsWith("よ") || x.endsWith("な") || x.endsWith("かな")
                || x.endsWith("と思う") || x.endsWith("気がする") || x.endsWith("わけです")
                || x.endsWith("ことです") || x.endsWith("でしたね") || x.endsWith("ますね");
    }

    private static boolean sentenceLooksQuestion(String s) {
        String x = normalize(s);
        return x.endsWith("ですか") || x.endsWith("ますか") || x.endsWith("でしょうか")
                || x.endsWith("なんですか") || x.endsWith("かな") || x.endsWith("ですかね");
    }

    private static boolean startsWithAny(String s, String[] values) {
        for (String v : values) if (s.startsWith(v)) return true;
        return false;
    }

    private static boolean endsWithAny(String s, String[] values) {
        for (String v : values) if (s.endsWith(v)) return true;
        return false;
    }

    private static String trimLeadingSymbols(String s) {
        return s.replaceFirst("^[\\s、。！？!?「『（(]+", "");
    }

    private static int skipSpaces(String s, int i) {
        int p = i;
        while (p < s.length() && Character.isWhitespace(s.charAt(p))) p++;
        return p;
    }

    private static int compactLength(String s) {
        return s.replaceAll("[\\s、。！？!?，,.・：；]+", "").length();
    }

    private static boolean isSentencePunctuation(char c) {
        return c == '。' || c == '！' || c == '？' || c == '!' || c == '?';
    }

    private static boolean endsWithPunctuation(String s) {
        int i = s.length() - 1;
        while (i >= 0 && Character.isWhitespace(s.charAt(i))) i--;
        return i >= 0 && isSentencePunctuation(s.charAt(i));
    }

    private static String normalize(String s) {
        if (s == null) return "";
        return s.replace('，', '、').replace('．', '。')
                .replaceAll("[ \\t]+", " ")
                .replaceAll(" *\\n+ *", " ")
                .trim();
    }
}

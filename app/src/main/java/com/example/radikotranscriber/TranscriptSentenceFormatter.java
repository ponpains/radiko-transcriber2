package com.example.radikotranscriber;

import java.util.ArrayList;

/**
 * Japanese radio-transcript formatter.
 * Goal: one human sentence per line, with blank lines only for real section changes.
 */
public final class TranscriptSentenceFormatter {
    private TranscriptSentenceFormatter() {}

    private static final String[] STRONG_ENDINGS = {
            "ありがとうございました", "ありがとうございます", "よろしくお願いします", "お願いします",
            "おはようございます", "こんばんは", "こんにちは", "いただきます", "ごちそうさまでした",
            "聞いてください", "お待ちしています", "届いております", "届いています",
            "と思います", "と思いました", "と思っています", "と思ってます", "と思うんです",
            "気がします", "気がしました", "気がするんです", "だったんです", "なんです",
            "なんですよ", "なんですよね", "なんですね", "でした", "ました", "ませんでした",
            "ません", "でしょう", "でしょうか", "ですよ", "ですよね", "ですね", "ですか",
            "ますか", "ましたか", "なんですか", "なんでしょうか", "いいですね",
            "いいと思います", "していきます", "していきたいと思います", "やりたいと思います",
            "始めていきましょう", "始めて行きましょう", "です", "ます"
    };

    private static final String[] SOFT_ENDINGS = {
            "んですけど", "なんですけど", "ですけど", "ましたけど", "ますけど",
            "けれど", "けれども", "なんですが", "んですが", "ですが", "ので", "から",
            "と思って", "と思いまして", "ということで"
    };

    private static final String[] NEW_SENTENCE_STARTERS = {
            "はい", "そう", "でも", "そして", "それで", "なので", "だから", "ただ", "あと",
            "あとは", "ちなみに", "今回", "最近", "私", "これ", "それ", "この", "その",
            "あの", "なんか", "やっぱり", "やっぱ", "まず", "続いて", "続きまして", "次に",
            "ここから", "ということで", "というわけで", "ありがとうございます", "皆さん",
            "今回も", "では", "それでは", "さて", "ところで", "本当に", "もう", "今度",
            "今日", "昨日", "明日", "来週", "例えば", "実際", "なんと", "えー", "えっと"
    };

    private static final String[] TOPIC_STARTERS = {
            "ラジオネーム", "続いて", "続きまして", "次のお便り", "続いてのお便り",
            "こちらのコーナー", "それではこちらのコーナー", "ここからは", "ここで一曲",
            "ここで1曲", "聞いていただいたのは", "お知らせ", "エンディングです",
            "以上 秋田のしおり", "以上秋田のしおり", "ということで最後まで",
            "それではそろそろ始めていきましょう", "それではそろそろ始めて行きましょう"
    };

    public static String formatBlock(String text, boolean utteranceBoundary) {
        String s = normalize(text);
        if (s.isEmpty()) return s;

        s = insertExplicitMarkerStops(s);
        ArrayList<String> pieces = splitExistingPunctuation(s);
        ArrayList<String> out = new ArrayList<>();
        for (String piece : pieces) splitInferred(piece, out);

        StringBuilder result = new StringBuilder();
        for (String line : out) {
            String x = normalize(line);
            if (x.isEmpty()) continue;
            if (!endsWithPunctuation(x) && shouldCloseLine(x, utteranceBoundary)) {
                x += sentenceLooksQuestion(x) ? "？" : "。";
            }
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
            if (compactLength(left) < 6) continue;
            int next = skipSpaces(s, i);
            if (next >= s.length()) break;
            String right = trimLeadingSymbols(s.substring(next));

            boolean strong = endsWithAny(left, STRONG_ENDINGS);
            boolean soft = endsWithAny(left, SOFT_ENDINGS);
            boolean newStart = startsWithAny(right, NEW_SENTENCE_STARTERS)
                    || startsWithAny(right, TOPIC_STARTERS);
            boolean continuation = startsWithAny(right,
                    new String[]{"ね", "よ", "けど", "けれど", "けれども", "が", "し", "ので",
                            "から", "って", "とか", "のでね", "からね", "んですけど"});
            boolean barePolite = left.endsWith("です") || left.endsWith("ます");
            boolean strongBoundary = strong && !continuation
                    && (!barePolite || newStart || compactLength(left) >= 22);
            boolean softBoundary = soft && newStart && compactLength(left) >= 18;

            if (strongBoundary || softBoundary) {
                String sentence = left;
                if (!endsWithPunctuation(sentence)) {
                    sentence += sentenceLooksQuestion(sentence) ? "？" : "。";
                }
                out.add(sentence);
                start = next;
                i = Math.max(start, i);
            }
        }

        String rest = s.substring(Math.min(start, s.length())).trim();
        if (!rest.isEmpty()) splitByEmbeddedEndings(rest, out);
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
                if (compactLength(left) < 10) continue;
                boolean likelyNew = startsWithAny(right, NEW_SENTENCE_STARTERS)
                        || startsWithAny(right, TOPIC_STARTERS);
                if (likelyNew) {
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
                "ここで1曲", "ここで一曲", "聞いていただいたのは", "お知らせ",
                "こちらのコーナー"
        };
        String out = s;
        for (String marker : markers) {
            int from = 0;
            while (from < out.length()) {
                int i = out.indexOf(marker, from);
                if (i <= 0) break;
                String before = out.substring(Math.max(0, i - 90), i).trim();
                if (compactLength(before) >= 16) {
                    int p = i - 1;
                    while (p >= 0 && Character.isWhitespace(out.charAt(p))) p--;
                    if (p >= 0 && !isSentencePunctuation(out.charAt(p))) {
                        out = out.substring(0, p + 1) + "。" + out.substring(p + 1);
                        i++;
                    }
                }
                from = i + marker.length();
            }
        }
        return out;
    }

    private static boolean shouldCloseLine(String s, boolean utteranceBoundary) {
        String x = normalize(s);
        if (x.length() < 2) return false;
        if (looksOpenEnded(x)) return false;
        if (endsWithAny(x, STRONG_ENDINGS)) return true;
        if (sentenceLooksQuestion(x)) return true;
        if (x.endsWith("ね") || x.endsWith("よ") || x.endsWith("かな") || x.endsWith("かも")
                || x.endsWith("と思う") || x.endsWith("気がする") || x.endsWith("わけです")
                || x.endsWith("ことです") || x.endsWith("でしたね") || x.endsWith("ますね")
                || x.endsWith("ですよね") || x.endsWith("なんですよね")) return true;
        return utteranceBoundary && compactLength(x) >= 12;
    }

    private static boolean looksOpenEnded(String x) {
        return endsWithAny(x, new String[]{
                "て", "で", "し", "と", "に", "を", "が", "は", "も", "の", "へ", "や",
                "けど", "けれど", "けれども", "ので", "から", "たり", "とか", "って", "という",
                "あの", "その", "この", "なんか", "えっと", "まあ", "ながら", "つつ",
                "と思って", "と思いまして", "なんですけど", "んですけど", "ですが"
        });
    }

    private static boolean sentenceLooksQuestion(String s) {
        String x = normalize(s);
        return x.endsWith("ですか") || x.endsWith("ますか") || x.endsWith("でしょうか")
                || x.endsWith("なんですか") || x.endsWith("かな") || x.endsWith("ですかね")
                || x.endsWith("ますかね") || x.endsWith("なんでしょう");
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

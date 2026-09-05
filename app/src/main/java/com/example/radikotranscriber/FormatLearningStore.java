package com.example.radikotranscriber;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Learns only layout tendencies (paragraph size / lines per paragraph / recurring section starts).
 * Word corrections stay in EpisodeStore. Keeping the two systems separate prevents a manual
 * newline from becoming a long, brittle text-replacement rule.
 */
public final class FormatLearningStore {
    private static final String PREFS = "transcript_format_learning_v1";
    private static final int DEFAULT_PARAGRAPH_CHARS = 180;
    private static final int DEFAULT_LINES_PER_PARAGRAPH = 4;

    private static final String[] SAFE_MARKERS = {
            "ラジオネーム", "続いて", "続きまして", "次のお便り", "続いてのお便り",
            "ここで1曲", "ここで一曲", "聞いていただいたのは", "ということで",
            "というわけで", "それでは", "さて", "ここから", "ここからは",
            "最後に", "お知らせ", "メールが来", "メール来", "エンディング"
    };

    public static class Profile {
        public int paragraphChars = DEFAULT_PARAGRAPH_CHARS;
        public int linesPerParagraph = DEFAULT_LINES_PER_PARAGRAPH;
        public int samples = 0;
        public Set<String> markers = new HashSet<>();
    }

    private FormatLearningStore() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String k(String prefix, String program) {
        return prefix + "\u0001" + (program == null ? "" : program.trim());
    }

    public static Profile get(Context context, String program) {
        SharedPreferences p = prefs(context);
        Profile out = new Profile();
        out.paragraphChars = clamp(p.getInt(k("paragraphChars", program), DEFAULT_PARAGRAPH_CHARS), 80, 320);
        out.linesPerParagraph = clamp(p.getInt(k("linesPerParagraph", program), DEFAULT_LINES_PER_PARAGRAPH), 2, 7);
        out.samples = Math.max(0, p.getInt(k("samples", program), 0));
        out.markers = new HashSet<>(p.getStringSet(k("markers", program), Collections.emptySet()));
        return out;
    }

    /** Returns true when the edit contained enough information to update the style profile. */
    public static boolean learn(Context context, String program, String autoText, String editedText) {
        String before = safe(autoText).replace("\r\n", "\n").replace('\r', '\n');
        String after = safe(editedText).replace("\r\n", "\n").replace('\r', '\n');
        String beforeSemantic = semantic(before);
        String afterSemantic = semantic(after);
        if (beforeSemantic.length() < 200 || afterSemantic.length() < 200) return false;
        double ratio = afterSemantic.length() / (double)Math.max(1, beforeSemantic.length());
        if (ratio < 0.70 || ratio > 1.30) return false;

        ArrayList<Integer> paragraphLengths = new ArrayList<>();
        ArrayList<Integer> paragraphLines = new ArrayList<>();
        HashSet<String> learnedMarkers = new HashSet<>();

        String[] paragraphs = after.split("\\n[ \\t]*\\n+");
        for (int pi = 0; pi < paragraphs.length; pi++) {
            String paragraph = paragraphs[pi].trim();
            if (paragraph.isEmpty()) continue;
            int chars = semantic(paragraph).length();
            if (chars >= 20 && chars <= 900) paragraphLengths.add(chars);

            int lines = 0;
            for (String line : paragraph.split("\\n")) if (!line.trim().isEmpty()) lines++;
            if (lines > 0 && lines <= 12) paragraphLines.add(lines);

            if (pi > 0) {
                String first = firstNonEmptyLine(paragraph);
                for (String marker : SAFE_MARKERS) {
                    if (first.startsWith(marker)) {
                        learnedMarkers.add(marker);
                        break;
                    }
                }
            }
        }

        if (paragraphLengths.isEmpty()) return false;
        int learnedChars = clamp(median(paragraphLengths), 80, 320);
        int learnedLines = paragraphLines.isEmpty()
                ? DEFAULT_LINES_PER_PARAGRAPH : clamp(median(paragraphLines), 2, 7);

        Profile old = get(context, program);
        int weight = Math.min(old.samples, 5);
        int newChars = weight <= 0 ? learnedChars
                : Math.round((old.paragraphChars * weight + learnedChars) / (float)(weight + 1));
        int newLines = weight <= 0 ? learnedLines
                : Math.round((old.linesPerParagraph * weight + learnedLines) / (float)(weight + 1));
        HashSet<String> markers = new HashSet<>(old.markers);
        markers.addAll(learnedMarkers);

        prefs(context).edit()
                .putInt(k("paragraphChars", program), clamp(newChars, 80, 320))
                .putInt(k("linesPerParagraph", program), clamp(newLines, 2, 7))
                .putInt(k("samples", program), Math.min(99, old.samples + 1))
                .putStringSet(k("markers", program), markers)
                .apply();
        return true;
    }

    /**
     * Applies the learned paragraph density without changing any recognized words.
     * Existing blank lines are respected; learning only adds sensible section/paragraph breaks.
     */
    public static String apply(Context context, String program, String text) {
        String src = safe(text).replace("\r\n", "\n").replace('\r', '\n').trim();
        Profile profile = get(context, program);
        if (src.isEmpty() || profile.samples <= 0) return src;

        StringBuilder out = new StringBuilder();
        int paragraphChars = 0;
        int paragraphLines = 0;
        boolean explicitBlank = false;

        for (String rawLine : src.split("\\n", -1)) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                explicitBlank = true;
                continue;
            }

            ArrayList<String> pieces = sentencePieces(line);
            if (pieces.isEmpty()) pieces.add(line);
            for (String piece : pieces) {
                piece = piece.trim();
                if (piece.isEmpty()) continue;
                boolean marker = startsWithMarker(piece, profile.markers);
                boolean learnedBreak = paragraphLines > 0
                        && (paragraphChars >= profile.paragraphChars
                        || paragraphLines >= profile.linesPerParagraph);
                boolean blank = out.length() > 0 && (explicitBlank || marker || learnedBreak);
                if (out.length() > 0) {
                    if (blank) {
                        if (!endsWith(out, "\n\n")) {
                            if (endsWith(out, "\n")) out.append('\n');
                            else out.append("\n\n");
                        }
                        paragraphChars = 0;
                        paragraphLines = 0;
                    } else if (!endsWith(out, "\n")) {
                        out.append('\n');
                    }
                }
                out.append(piece);
                paragraphChars += semantic(piece).length();
                paragraphLines++;
                explicitBlank = false;
            }
        }
        return out.toString().trim();
    }

    private static ArrayList<String> sentencePieces(String line) {
        ArrayList<String> out = new ArrayList<>();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            b.append(c);
            if (c == '。' || c == '！' || c == '？' || c == '!' || c == '?') {
                out.add(b.toString());
                b.setLength(0);
            }
        }
        if (b.length() > 0) out.add(b.toString());
        return out;
    }

    private static boolean startsWithMarker(String text, Set<String> learned) {
        for (String s : SAFE_MARKERS) if (text.startsWith(s)) return true;
        if (learned != null) for (String s : learned) if (!s.isEmpty() && text.startsWith(s)) return true;
        return false;
    }

    public static JSONObject toJson(Context context, String program) {
        JSONObject o = new JSONObject();
        try {
            Profile p = get(context, program);
            o.put("program", safe(program));
            o.put("paragraphChars", p.paragraphChars);
            o.put("linesPerParagraph", p.linesPerParagraph);
            o.put("samples", p.samples);
            JSONArray a = new JSONArray();
            for (String m : p.markers) a.put(m);
            o.put("markers", a);
        } catch (Exception ignored) {}
        return o;
    }

    public static void fromJson(Context context, JSONObject o) {
        if (o == null) return;
        try {
            String program = o.optString("program", "");
            HashSet<String> markers = new HashSet<>();
            JSONArray a = o.optJSONArray("markers");
            if (a != null) for (int i = 0; i < a.length(); i++) {
                String m = a.optString(i, "").trim();
                if (!m.isEmpty()) markers.add(m);
            }
            prefs(context).edit()
                    .putInt(k("paragraphChars", program), clamp(o.optInt("paragraphChars", DEFAULT_PARAGRAPH_CHARS), 80, 320))
                    .putInt(k("linesPerParagraph", program), clamp(o.optInt("linesPerParagraph", DEFAULT_LINES_PER_PARAGRAPH), 2, 7))
                    .putInt(k("samples", program), Math.max(0, o.optInt("samples", 0)))
                    .putStringSet(k("markers", program), markers)
                    .apply();
        } catch (Exception ignored) {}
    }

    public static String describe(Context context, String program) {
        Profile p = get(context, program);
        return "paragraphChars=" + p.paragraphChars
                + ";linesPerParagraph=" + p.linesPerParagraph
                + ";samples=" + p.samples
                + ";markers=" + p.markers;
    }

    private static String firstNonEmptyLine(String p) {
        for (String line : p.split("\\n")) if (!line.trim().isEmpty()) return line.trim();
        return "";
    }

    private static int median(ArrayList<Integer> values) {
        if (values.isEmpty()) return 0;
        Collections.sort(values);
        int n = values.size();
        if ((n & 1) == 1) return values.get(n / 2);
        return Math.round((values.get(n / 2 - 1) + values.get(n / 2)) / 2f);
    }

    private static boolean endsWith(StringBuilder b, String suffix) {
        if (b.length() < suffix.length()) return false;
        for (int i = 0; i < suffix.length(); i++)
            if (b.charAt(b.length() - suffix.length() + i) != suffix.charAt(i)) return false;
        return true;
    }

    private static String semantic(String s) {
        return safe(s).replaceAll("[\\s、。！？!?，,.・：；\\\"'「」『』（）()]+", "");
    }

    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
    private static String safe(String s) { return s == null ? "" : s; }
}

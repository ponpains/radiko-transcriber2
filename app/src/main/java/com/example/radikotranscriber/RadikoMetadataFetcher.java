package com.example.radikotranscriber;

import android.os.Handler;
import android.os.Looper;
import android.text.Html;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads only the normal public radiko web page and extracts display/recognition metadata.
 * No stream URL/token/private API extraction is used here. Public HTML and text embedded in that
 * same page are used only as language hints for the recognizer.
 */
public final class RadikoMetadataFetcher {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int MAX_HTML_CHARS = 2_000_000;

    public interface Callback { void onResult(Result result); }

    public static final class Result {
        public String sourceUrl = "";
        public String program = "";
        public String episodeNumber = "";
        public String episodeTitle = "";
        public String broadcastDate = "";
        public String description = "";
        public String error = "";

        public boolean hasEpisodeIdentity() {
            return !episodeNumber.isEmpty() || !episodeTitle.isEmpty() || !broadcastDate.isEmpty();
        }

        public String displayEpisode() {
            StringBuilder b = new StringBuilder();
            if (!broadcastDate.isEmpty()) b.append(broadcastDate).append(' ');
            if (!episodeNumber.isEmpty()) b.append(episodeNumber);
            if (!episodeTitle.isEmpty()) {
                if (b.length() > 0 && b.charAt(b.length() - 1) != ' ') b.append(' ');
                b.append('「').append(episodeTitle).append('」');
            }
            return b.toString().trim();
        }
    }

    private RadikoMetadataFetcher() {}

    public static boolean looksLikeRadikoEpisode(String url) {
        String u = safe(url).toLowerCase(Locale.ROOT);
        return (u.startsWith("https://") || u.startsWith("http://"))
                && u.contains("radiko.jp/") && u.contains("/podcast/");
    }

    public static void fetchAsync(String url, Callback callback) {
        final String requested = safe(url).trim();
        EXECUTOR.execute(() -> {
            Result r = fetch(requested);
            MAIN.post(() -> {
                if (callback != null) callback.onResult(r);
            });
        });
    }

    private static Result fetch(String url) {
        Result r = new Result();
        r.sourceUrl = url;
        if (!looksLikeRadikoEpisode(url)) {
            r.error = "radiko Podcast URLではありません";
            return r;
        }

        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection)new URL(url).openConnection();
            c.setConnectTimeout(9000);
            c.setReadTimeout(12000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/140 Mobile Safari/537.36");
            c.setRequestProperty("Accept-Language", "ja-JP,ja;q=0.9,en;q=0.5");
            c.setRequestProperty("Accept", "text/html,application/xhtml+xml");
            int code = c.getResponseCode();
            InputStream in = code >= 200 && code < 400 ? c.getInputStream() : c.getErrorStream();
            if (in == null) {
                r.error = "HTTP " + code;
                return r;
            }
            String html = readUtf8(in);
            if (html.isEmpty()) {
                r.error = "ページ内容を取得できませんでした";
                return r;
            }

            String ogTitle = firstMeta(html, "og:title");
            String twitterTitle = firstMeta(html, "twitter:title");
            String titleTag = first(html, Pattern.compile("(?is)<title[^>]*>(.*?)</title>"), 1);
            String ogDescription = firstMeta(html, "og:description");
            String metaDescription = !ogDescription.isEmpty() ? ogDescription : firstMeta(html, "description");

            ogTitle = cleanHtmlText(ogTitle);
            twitterTitle = cleanHtmlText(twitterTitle);
            titleTag = cleanHtmlText(titleTag);
            metaDescription = cleanHtmlText(metaDescription);

            String visible = html
                    .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                    .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                    .replaceAll("(?is)<[^>]+>", " ");
            visible = cleanHtmlText(visible);
            if (visible.length() > 120000) visible = visible.substring(0, 120000);

            String combined = joinNonEmpty(ogTitle, twitterTitle, titleTag, metaDescription, visible);
            extractEpisodeIdentity(combined, r);
            extractDate(combined, r);
            extractProgram(combined, r);

            String embedded = bestEmbeddedDescription(html, r.episodeTitle);
            String nearby = visibleContext(visible, r.episodeTitle);
            r.description = compactContext(joinNonEmpty(r.episodeTitle, metaDescription, embedded, nearby), 1200);

            if (!r.hasEpisodeIdentity()) {
                r.error = "放送回・タイトルをページから判別できませんでした";
            }
        } catch (Exception e) {
            r.error = e.getClass().getSimpleName();
        } finally {
            if (c != null) c.disconnect();
        }
        return r;
    }

    private static void extractEpisodeIdentity(String text, Result r) {
        Pattern quoted = Pattern.compile("[＃#]\\s*(\\d{1,4})\\s*[「『\\\"]\\s*([^」』\\\"\\r\\n]{1,100})\\s*[」』\\\"]");
        Matcher m = quoted.matcher(text);
        if (m.find()) {
            r.episodeNumber = "#" + m.group(1);
            r.episodeTitle = cleanupTitle(m.group(2));
            return;
        }

        Pattern number = Pattern.compile("[＃#]\\s*(\\d{1,4})");
        m = number.matcher(text);
        if (m.find()) r.episodeNumber = "#" + m.group(1);

        Pattern titleOnly = Pattern.compile("[「『\\\"]\\s*([^」』\\\"\\r\\n]{2,100})\\s*[」』\\\"]");
        m = titleOnly.matcher(text);
        if (m.find()) r.episodeTitle = cleanupTitle(m.group(1));
    }

    private static void extractDate(String text, Result r) {
        Pattern p = Pattern.compile("(20\\d{2})[./\\-年]\\s*(\\d{1,2})[./\\-月]\\s*(\\d{1,2})(?:日)?(?:放送分)?");
        Matcher m = p.matcher(text);
        if (!m.find()) return;
        try {
            int y = Integer.parseInt(m.group(1));
            int mo = Integer.parseInt(m.group(2));
            int d = Integer.parseInt(m.group(3));
            if (mo >= 1 && mo <= 12 && d >= 1 && d <= 31) {
                r.broadcastDate = String.format(Locale.JAPAN, "%04d/%02d/%02d", y, mo, d);
            }
        } catch (Exception ignored) {}
    }

    private static void extractProgram(String text, Result r) {
        String compact = text.replace(" ", "");
        if (compact.contains("≠ME永田詩央里のけれけれ")
                || (compact.contains("永田詩央里") && compact.contains("けれけれ"))) {
            r.program = "≠ME 永田詩央里のけれけれ";
        }
    }

    /** Pull useful human-readable text from JSON/JSON-LD already embedded in the public page. */
    private static String bestEmbeddedDescription(String html, String episodeTitle) {
        Pattern p = Pattern.compile("(?is)\\\"(?:description|summary|episodeDescription)\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\]){20,1800})\\\"");
        Matcher m = p.matcher(html);
        String best = "";
        int bestScore = Integer.MIN_VALUE;
        int checked = 0;
        while (m.find() && checked++ < 80) {
            String x = decodeJsonString(m.group(1));
            x = cleanHtmlText(x);
            if (x.length() < 20 || x.length() > 1800) continue;
            int score = japaneseCount(x);
            if (!safe(episodeTitle).isEmpty() && x.contains(episodeTitle)) score += 60;
            if (containsAny(x, "今回", "ラジオネーム", "しおりん", "永田詩央里", "秋田")) score += 25;
            if (containsAny(x, "radikoなら", "ラジオが聴ける", "ログイン", "会員登録")) score -= 80;
            if (score > bestScore) { bestScore = score; best = x; }
        }
        return bestScore >= 25 ? best : "";
    }

    private static String visibleContext(String visible, String episodeTitle) {
        String v = safe(visible);
        String title = safe(episodeTitle).trim();
        if (v.isEmpty() || title.isEmpty()) return "";
        int i = v.indexOf(title);
        if (i < 0) return "";
        int from = Math.max(0, i - 180);
        int to = Math.min(v.length(), i + title.length() + 760);
        String x = cleanHtmlText(v.substring(from, to));
        return japaneseCount(x) >= 20 ? x : "";
    }

    private static String decodeJsonString(String s) {
        String x = safe(s);
        x = x.replace("\\n", " ").replace("\\r", " ").replace("\\t", " ")
                .replace("\\\"", "\"").replace("\\/", "/");
        Matcher u = Pattern.compile("\\\\u([0-9a-fA-F]{4})").matcher(x);
        StringBuffer b = new StringBuffer();
        while (u.find()) {
            try {
                char c = (char)Integer.parseInt(u.group(1), 16);
                u.appendReplacement(b, Matcher.quoteReplacement(String.valueOf(c)));
            } catch (Exception ignored) {}
        }
        u.appendTail(b);
        return b.toString().replace("\\\\", "\\");
    }

    private static int japaneseCount(String s) {
        int n = 0;
        for (int i = 0; i < safe(s).length(); i++) {
            char c = s.charAt(i);
            if ((c >= '\u3040' && c <= '\u30ff') || (c >= '\u3400' && c <= '\u9fff')) n++;
        }
        return n;
    }

    private static String compactContext(String s, int max) {
        String x = cleanHtmlText(s);
        if (x.length() <= max) return x;
        return x.substring(0, max).trim();
    }

    private static boolean containsAny(String s, String... terms) {
        String x = safe(s);
        for (String t : terms) if (x.contains(t)) return true;
        return false;
    }

    private static String firstMeta(String html, String name) {
        String escaped = Pattern.quote(name);
        Pattern p1 = Pattern.compile("(?is)<meta[^>]+(?:property|name)\\s*=\\s*[\\\"']" + escaped
                + "[\\\"'][^>]+content\\s*=\\s*[\\\"'](.*?)[\\\"'][^>]*>");
        String x = first(html, p1, 1);
        if (!x.isEmpty()) return x;
        Pattern p2 = Pattern.compile("(?is)<meta[^>]+content\\s*=\\s*[\\\"'](.*?)[\\\"'][^>]+(?:property|name)\\s*=\\s*[\\\"']"
                + escaped + "[\\\"'][^>]*>");
        return first(html, p2, 1);
    }

    private static String first(String s, Pattern p, int group) {
        Matcher m = p.matcher(s);
        return m.find() ? safe(m.group(group)) : "";
    }

    private static String readUtf8(InputStream in) throws Exception {
        StringBuilder b = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) > 0 && b.length() < MAX_HTML_CHARS) {
                b.append(buf, 0, Math.min(n, MAX_HTML_CHARS - b.length()));
            }
        }
        return b.toString();
    }

    private static String cleanHtmlText(String s) {
        String x = safe(s);
        if (x.isEmpty()) return x;
        try { x = Html.fromHtml(x, Html.FROM_HTML_MODE_LEGACY).toString(); }
        catch (Exception ignored) {}
        return x.replace('\u00a0', ' ')
                .replaceAll("[\\t\\r\\n ]+", " ")
                .trim();
    }

    private static String cleanupTitle(String s) {
        String x = cleanHtmlText(s);
        x = x.replaceAll("\\s*[|｜]\\s*radiko.*$", "").trim();
        return x;
    }

    private static String joinNonEmpty(String... values) {
        StringBuilder b = new StringBuilder();
        for (String v : values) {
            String x = safe(v).trim();
            if (x.isEmpty()) continue;
            if (b.length() > 0) b.append("  ");
            b.append(x);
        }
        return b.toString();
    }

    private static String safe(String s) { return s == null ? "" : s; }
}

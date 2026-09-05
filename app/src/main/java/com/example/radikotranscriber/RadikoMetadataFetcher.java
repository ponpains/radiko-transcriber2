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
 * Reads only the normal public radiko web page and extracts display metadata.
 * No stream URL/token/API reverse engineering is used here.
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
            String description = !ogDescription.isEmpty() ? ogDescription
                    : firstMeta(html, "description");

            ogTitle = cleanHtmlText(ogTitle);
            twitterTitle = cleanHtmlText(twitterTitle);
            titleTag = cleanHtmlText(titleTag);
            description = cleanHtmlText(description);
            r.description = description;

            String visible = html
                    .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                    .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                    .replaceAll("(?is)<[^>]+>", " ");
            visible = cleanHtmlText(visible);
            if (visible.length() > 120000) visible = visible.substring(0, 120000);

            String combined = joinNonEmpty(ogTitle, twitterTitle, titleTag, description, visible);
            extractEpisodeIdentity(combined, r);
            extractDate(combined, r);
            extractProgram(combined, r);

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
        // Current radiko podcast pages use forms such as #58「家に喫茶店を作ります」.
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

        // Fallback for pages where the number and quoted title are separated by tags.
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

package com.example.radikotranscriber;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Future-facing official context for ≠ME / 永田詩央里 / けれけれ.
 *
 * The bundled snapshot makes the current app useful offline.  When the user transcribes public
 * radiko audio, this class also refreshes a tiny vocabulary snapshot from the normal public ≠ME
 * schedule/news web pages.  It never uses a private endpoint, login, cookie, or protected media
 * URL, and network failure never blocks capture or SpeechRecognizer.
 */
public final class KerekereFutureScheduleContext {
    public static final String VERSION = "kerekere-future-schedule-v025-2026-09-06";

    public interface Logger { void log(String kind, String detail); }

    private static final Object LOCK = new Object();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final String PREFS = "kerekere_future_schedule_v1";
    private static final String KEY_TERMS = "terms";
    private static final String KEY_FETCHED_AT = "fetched_at";
    private static final long REFRESH_MS = 12L * 60L * 60L * 1000L;
    private static final int MAX_TERMS = 72;
    private static final int MAX_BIAS_TERMS = 18;
    private static final String[] OFFICIAL_URLS = {
            "https://not-equal-me.jp/schedule/",
            "https://not-equal-me.jp/news/1/"
    };
    private static final String USER_AGENT =
            "RadikoTranscriber/0.25 (public schedule vocabulary refresh; https://github.com/ponpains/radiko-transcriber2)";

    private static Context appContext;
    private static Logger logger;
    private static boolean enabled;
    private static boolean refreshRunning;
    private static long fetchedAt;
    private static int refreshSuccesses;
    private static int refreshErrors;
    private static final ArrayList<String> currentTerms = new ArrayList<>();

    private KerekereFutureScheduleContext() {}

    public static void beginEpisode(Context context, String program, boolean allowOnline,
                                    Logger diagnosticLogger) {
        synchronized (LOCK) {
            appContext = context == null ? null : context.getApplicationContext();
            logger = diagnosticLogger;
            enabled = allowOnline && appContext != null && KerekereContextProfile.applies(program);
            currentTerms.clear();
            addBundledTerms(currentTerms);
            loadCacheLocked();
            logLocked("future_schedule_start", "enabled=" + enabled
                    + ";terms=" + currentTerms.size()
                    + ";cacheAgeMs=" + (fetchedAt <= 0 ? -1 : Math.max(0L, System.currentTimeMillis() - fetchedAt))
                    + ";version=" + VERSION);
            if (!enabled || refreshRunning) return;
            if (fetchedAt > 0 && System.currentTimeMillis() - fetchedAt < REFRESH_MS) return;
            refreshRunning = true;
        }
        EXECUTOR.execute(KerekereFutureScheduleContext::refreshOfficialPages);
    }

    /** Terms are intentionally ordered: current/future high-value names come before scraped extras. */
    public static ArrayList<String> biasTerms(String program) {
        ArrayList<String> out = new ArrayList<>();
        if (!KerekereContextProfile.applies(program)) return out;
        synchronized (LOCK) {
            for (String term : currentTerms) {
                if (out.size() >= MAX_BIAS_TERMS) break;
                if (usefulBias(term) && !out.contains(term)) out.add(term);
            }
        }
        return out;
    }

    /** Small N-best nudge only. Audio confidence remains the primary signal. */
    public static double scoreCandidate(String program, String candidate, String previousText) {
        if (!KerekereContextProfile.applies(program)) return 0.0;
        String c = safe(candidate);
        String context = tail(previousText, 800) + " " + c;
        double score = 0.0;
        synchronized (LOCK) {
            for (String term : currentTerms) {
                if (term.length() < 3 || term.length() > 48) continue;
                if (c.contains(term)) score += Math.min(4.5, 1.6 + term.length() * 0.09);
            }
        }
        if (containsAny(context, "12thシングル", "12枚目", "愛ください", "ファーストキッス")) {
            if (c.contains("愛くださいませ/ここでファーストキッス")) score += 9.0;
        }
        if (containsAny(context, "9月19日", "東京ビッグ", "個別お話")) {
            if (c.contains("東京ビッグサイト")) score += 6.0;
            if (c.contains("個別お話し会")) score += 4.0;
            if (c.contains("ツーショット撮影会")) score += 4.0;
        }
        if (containsAny(context, "9月23日", "9月27日", "10月4日", "オンライン")) {
            if (c.contains("オンライン個別お話し会")) score += 5.0;
            if (c.contains("オンライン2ショット写真会")) score += 5.0;
        }
        return Math.max(-4.0, Math.min(22.0, score));
    }

    /** Conservative spelling repair for current official schedule vocabulary. */
    public static String refine(String program, String previousText, String segment) {
        String s = safe(segment);
        if (!KerekereContextProfile.applies(program) || s.isEmpty()) return s;
        String context = tail(previousText, 1100) + " " + s;

        if (containsAny(context, "12thシングル", "12枚目", "愛ください", "ファーストキッス")) {
            s = s.replace("愛くださいま瀬", "愛くださいませ")
                    .replace("愛くださいません", "愛くださいませ")
                    .replace("ここでファーストキス", "ここでファーストキッス")
                    .replace("ここで ファーストキス", "ここでファーストキッス")
                    .replace("愛くださいませ ここでファーストキッス", "愛くださいませ/ここでファーストキッス");
        }
        if (containsAny(context, "9月19日", "東京ビック", "東京ビッグ", "お話し会", "撮影会")) {
            s = s.replace("東京ビックサイト", "東京ビッグサイト")
                    .replace("東京 ビックサイト", "東京ビッグサイト")
                    .replace("個別お話会", "個別お話し会")
                    .replace("個別 お話会", "個別お話し会")
                    .replace("ツーショット写真会", "ツーショット撮影会");
        }
        if (containsAny(context, "9月23日", "9月27日", "10月4日", "オンライン")) {
            s = s.replace("オンライン個別お話会", "オンライン個別お話し会")
                    .replace("オンライン 個別お話会", "オンライン個別お話し会")
                    .replace("オンライン2ショット撮影会", "オンライン2ショット写真会")
                    .replace("オンライン ツーショット写真会", "オンライン2ショット写真会");
        }
        return s.replaceAll("[ \\t]{2,}", " ").trim();
    }

    public static String stats() {
        synchronized (LOCK) {
            return "terms=" + currentTerms.size()
                    + ";fetchedAt=" + fetchedAt
                    + ";refreshSuccesses=" + refreshSuccesses
                    + ";refreshErrors=" + refreshErrors
                    + ";refreshRunning=" + refreshRunning;
        }
    }

    private static void refreshOfficialPages() {
        LinkedHashSet<String> found = new LinkedHashSet<>();
        ArrayList<String> bundled = new ArrayList<>();
        addBundledTerms(bundled);
        found.addAll(bundled);
        int success = 0;
        int errors = 0;
        for (String address : OFFICIAL_URLS) {
            try {
                String html = fetchHtml(address);
                if (!html.isEmpty()) {
                    collectTermsFromHtml(html, found);
                    success++;
                }
            } catch (Exception ex) {
                errors++;
                synchronized (LOCK) {
                    logLocked("future_schedule_source_error", "source=" + sourceName(address)
                            + ";error=" + ex.getClass().getSimpleName());
                }
            }
        }

        synchronized (LOCK) {
            refreshRunning = false;
            refreshSuccesses += success;
            refreshErrors += errors;
            if (success <= 0) {
                logLocked("future_schedule_refresh", "success=0;errors=" + errors
                        + ";keptTerms=" + currentTerms.size());
                return;
            }
            currentTerms.clear();
            for (String s : found) {
                if (currentTerms.size() >= MAX_TERMS) break;
                if (usefulStoredTerm(s) && !currentTerms.contains(s)) currentTerms.add(s);
            }
            fetchedAt = System.currentTimeMillis();
            saveCacheLocked();
            logLocked("future_schedule_refresh", "success=" + success + ";errors=" + errors
                    + ";terms=" + currentTerms.size());
        }
    }

    private static String fetchHtml(String address) throws Exception {
        URL url = new URL(address);
        if (!"https".equalsIgnoreCase(url.getProtocol())
                || !"not-equal-me.jp".equalsIgnoreCase(url.getHost())) {
            throw new java.io.IOException("host_not_allowed");
        }
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection)url.openConnection();
            c.setConnectTimeout(3500);
            c.setReadTimeout(5000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", USER_AGENT);
            c.setRequestProperty("Accept", "text/html,application/xhtml+xml");
            c.setRequestProperty("Accept-Language", "ja,en;q=0.4");
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new java.io.IOException("HTTP_" + code);
            InputStream in = c.getInputStream();
            StringBuilder b = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
                char[] buf = new char[4096];
                int n;
                while ((n = r.read(buf)) > 0 && b.length() < 600000) {
                    b.append(buf, 0, Math.min(n, 600000 - b.length()));
                }
            }
            return b.toString();
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static void collectTermsFromHtml(String html, LinkedHashSet<String> out) {
        String text = htmlToText(html);

        // Titles inside Japanese quotation marks are the highest-value dynamic vocabulary.
        Matcher quoted = Pattern.compile("[「『]([^」』]{2,70})[」』]").matcher(text);
        while (quoted.find() && out.size() < MAX_TERMS) {
            String x = clean(quoted.group(1));
            if (x.length() >= 3 && x.length() <= 48 && relevant(x)) out.add(x);
        }

        // Keep future dates for the next ~150 days. They help announcement candidate selection.
        Matcher dates = Pattern.compile("(2026年|2027年)([0-9]{1,2})月([0-9]{1,2})日").matcher(text);
        while (dates.find() && out.size() < MAX_TERMS) {
            String x = dates.group();
            if (isNearFutureDate(x)) out.add(x);
        }

        String[] knownDynamic = {
                "愛くださいませ/ここでファーストキッス", "愛くださいませ", "ここでファーストキッス",
                "東京ビッグサイト", "パシフィコ横浜", "幕張メッセ", "ATCホール",
                "オンライン個別お話し会", "オンライン2ショット写真会",
                "個別お話し会", "ツーショット撮影会", "スペシャルツーショット撮影会",
                "≠MEオリジナルポストカードサイン会", "ヒューリックホール東京",
                "≠ME サンリオキャラクタースペシャルライブ", "ソロポスターサイン会"
        };
        for (String x : knownDynamic) if (text.contains(x)) out.add(x);
    }

    private static String htmlToText(String html) {
        String x = safe(html)
                .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?is)<[^>]+>", " ");
        x = x.replace("&quot;", "\"").replace("&#34;", "\"")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&nbsp;", " ").replace("&#x27;", "'");
        return x.replaceAll("[\\s\\u00a0]+", " ").trim();
    }

    private static boolean isNearFutureDate(String text) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy年M月d日", Locale.JAPAN);
            f.setLenient(false);
            f.setTimeZone(TimeZone.getTimeZone("Asia/Tokyo"));
            Date date = f.parse(text);
            if (date == null) return false;
            long now = System.currentTimeMillis() - 24L * 60L * 60L * 1000L;
            long latest = System.currentTimeMillis() + 150L * 24L * 60L * 60L * 1000L;
            return date.getTime() >= now && date.getTime() <= latest;
        } catch (Exception ignored) { return false; }
    }

    private static void addBundledTerms(List<String> out) {
        // Official snapshot checked 2026-09-06. Keep only likely spoken names/dates, not long prose.
        add(out, "≠ME 12thシングル「愛くださいませ/ここでファーストキッス」");
        add(out, "愛くださいませ/ここでファーストキッス");
        add(out, "愛くださいませ");
        add(out, "ここでファーストキッス");
        add(out, "2026年9月18日");
        add(out, "≠ME サンリオキャラクタースペシャルライブ");
        add(out, "ヒューリックホール東京");
        add(out, "2026年9月19日");
        add(out, "東京ビッグサイト");
        add(out, "個別お話し会");
        add(out, "ツーショット撮影会");
        add(out, "2026年9月23日");
        add(out, "2026年9月27日");
        add(out, "2026年10月4日");
        add(out, "オンライン個別お話し会");
        add(out, "オンライン2ショット写真会");
        add(out, "2026年10月3日");
        add(out, "≠MEソロポスターサイン会");
    }

    private static void loadCacheLocked() {
        if (appContext == null) return;
        SharedPreferences p = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        fetchedAt = p.getLong(KEY_FETCHED_AT, 0L);
        String raw = p.getString(KEY_TERMS, "");
        if (raw == null || raw.isEmpty()) return;
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length() && currentTerms.size() < MAX_TERMS; i++) {
                String x = clean(a.optString(i, ""));
                if (usefulStoredTerm(x) && !currentTerms.contains(x)) currentTerms.add(x);
            }
        } catch (Exception ignored) {}
    }

    private static void saveCacheLocked() {
        if (appContext == null) return;
        try {
            JSONArray a = new JSONArray();
            for (String x : currentTerms) a.put(x);
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_TERMS, a.toString())
                    .putLong(KEY_FETCHED_AT, fetchedAt)
                    .apply();
        } catch (Exception ignored) {}
    }

    private static boolean usefulBias(String s) {
        String x = clean(s);
        return x.length() >= 3 && x.length() <= 48;
    }

    private static boolean usefulStoredTerm(String s) {
        String x = clean(s);
        if (x.length() < 3 || x.length() > 60) return false;
        return relevant(x) || x.matches("20(?:26|27)年[0-9]{1,2}月[0-9]{1,2}日");
    }

    private static boolean relevant(String s) {
        return containsAny(s, "≠ME", "ノットイコールミー", "永田詩央里", "けれけれ",
                "愛ください", "ファーストキッス", "お話し会", "ショット", "サイン会", "ライブ",
                "ビッグサイト", "ホール", "アリーナ", "メッセ", "サンリオ", "ポスター");
    }

    private static void add(List<String> out, String term) {
        if (!out.contains(term)) out.add(term);
    }

    private static String sourceName(String url) {
        return url.contains("/schedule") ? "official_schedule" : "official_news";
    }

    private static String clean(String s) {
        return safe(s).replaceAll("[\\s\\u00a0]+", " ").trim();
    }

    private static String tail(String s, int max) {
        String x = safe(s);
        return x.length() <= max ? x : x.substring(x.length() - max);
    }

    private static boolean containsAny(String s, String... values) {
        String x = safe(s);
        for (String v : values) if (x.contains(v)) return true;
        return false;
    }

    private static void logLocked(String kind, String detail) {
        Logger l = logger;
        if (l != null) {
            try { l.log(kind, detail); } catch (Exception ignored) {}
        }
    }

    private static String safe(String s) { return s == null ? "" : s; }
}

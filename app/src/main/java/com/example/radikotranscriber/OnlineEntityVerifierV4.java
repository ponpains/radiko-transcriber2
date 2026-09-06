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
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v0.25 music-name verifier.
 *
 * Only explicit radio song cues are searched. Apple/iTunes search is tried first because one query
 * returns track + artist together and is more forgiving than the exact MusicBrainz path observed in
 * v0.24. If it cannot verify a pair, the cleaned pair is handed to the existing V3 verifier as a
 * fail-open fallback. All network work stays off capture/recognizer threads.
 */
public final class OnlineEntityVerifierV4 {
    public static final String VERSION = "online-entity-verifier-v025-2026-09-06";

    public interface Logger { void log(String kind, String detail); }

    private static final Object LOCK = new Object();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final String PREFS = "online_entity_cache_v4";
    private static final long CACHE_MS = 60L * 24L * 60L * 60L * 1000L;
    private static final long REQUEST_GAP_MS = 1300L;
    private static final int MAX_HTTP_REQUESTS = 6;
    private static final String USER_AGENT =
            "RadikoTranscriber/0.25 (https://github.com/ponpains/radiko-transcriber2)";

    private static Context appContext;
    private static String program = "";
    private static boolean enabled;
    private static int generation;
    private static int httpRequests;
    private static int extractedPairs;
    private static int verifiedPairs;
    private static int fallbackPairs;
    private static int errors;
    private static int cacheHits;
    private static long lastRequestAt;
    private static Logger logger;
    private static final Set<String> seen = new HashSet<>();
    private static final Map<String, String> aliases = new HashMap<>();

    private OnlineEntityVerifierV4() {}

    public static void beginEpisode(Context context, String currentProgram, String publicEpisodeContext,
                                    boolean allowOnline, Logger diagnosticLogger) {
        synchronized (LOCK) {
            appContext = context == null ? null : context.getApplicationContext();
            program = safe(currentProgram);
            enabled = allowOnline && appContext != null;
            logger = diagnosticLogger;
            generation++;
            httpRequests = extractedPairs = verifiedPairs = fallbackPairs = errors = cacheHits = 0;
            lastRequestAt = 0L;
            seen.clear();
            aliases.clear();
            logLocked("online_v4_start", "enabled=" + enabled + ";strategy=itunes-pair-first;version=" + VERSION);
        }
        // Keep the proven MusicBrainz/Wikidata resolver as a secondary path, but V4 decides when it
        // is worth invoking it and supplies a cleaned cue string.
        OnlineEntityVerifierV3.beginEpisode(context, currentProgram, publicEpisodeContext,
                allowOnline, diagnosticLogger == null ? null : diagnosticLogger::log);
    }

    public static void observe(String currentProgram, String previousText, String segment,
                               Logger diagnosticLogger) {
        if (diagnosticLogger != null) synchronized (LOCK) { logger = diagnosticLogger; }
        final Pair pair = extractBestPair(segment);
        if (pair == null) return;

        final int g;
        synchronized (LOCK) {
            if (!enabled || appContext == null || !sameProgram(currentProgram)) return;
            String key = normalize(pair.artist) + "|" + normalize(pair.title);
            if (seen.contains(key)) return;
            seen.add(key);
            extractedPairs++;
            g = generation;
            logLocked("online_v4_pair_extracted", "artist=" + brief(pair.artist)
                    + ";title=" + brief(pair.title) + ";cue=" + pair.cue);

            boolean ac = applyCacheLocked(pair.artist, "artist");
            boolean tc = applyCacheLocked(pair.title, "title");
            if (ac && tc) return;
            if (httpRequests >= MAX_HTTP_REQUESTS) {
                fallbackPairs++;
                fallbackToV3(currentProgram, previousText, pair, diagnosticLogger);
                return;
            }
        }

        EXECUTOR.execute(() -> resolveItunes(g, currentProgram, previousText, pair, diagnosticLogger));
    }

    public static String refineKnown(String currentProgram, String text) {
        String out = safe(text);
        synchronized (LOCK) {
            if (sameProgram(currentProgram) && !aliases.isEmpty()) {
                ArrayList<Map.Entry<String, String>> entries = new ArrayList<>(aliases.entrySet());
                entries.sort((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()));
                for (Map.Entry<String, String> e : entries) {
                    if (e.getKey().length() >= 2 && !e.getKey().equals(e.getValue()))
                        out = out.replace(e.getKey(), e.getValue());
                }
            }
        }
        return OnlineEntityVerifierV3.refineKnown(currentProgram, out);
    }

    public static double scoreCandidate(String currentProgram, String candidate) {
        double score = 0.0;
        synchronized (LOCK) {
            if (sameProgram(currentProgram)) {
                for (Map.Entry<String, String> e : aliases.entrySet()) {
                    if (safe(candidate).contains(e.getValue())) score += 4.5;
                    if (!e.getKey().equals(e.getValue()) && safe(candidate).contains(e.getKey())) score -= 2.5;
                }
            }
        }
        score += OnlineEntityVerifierV3.scoreCandidate(currentProgram, candidate);
        return Math.max(-10.0, Math.min(12.0, score));
    }

    public static String stats() {
        synchronized (LOCK) {
            return "v4HttpRequests=" + httpRequests
                    + ";v4ExtractedPairs=" + extractedPairs
                    + ";v4VerifiedPairs=" + verifiedPairs
                    + ";v4FallbackPairs=" + fallbackPairs
                    + ";v4CacheHits=" + cacheHits
                    + ";v4Errors=" + errors
                    + ";v3={" + OnlineEntityVerifierV3.stats() + "}";
        }
    }

    private static void resolveItunes(int g, String currentProgram, String previousText, Pair pair,
                                      Logger diagnosticLogger) {
        if (!current(g)) return;
        ItunesHit hit = null;
        try {
            if (reserveRequest(pair)) hit = searchItunes(pair);
        } catch (Exception ex) {
            synchronized (LOCK) {
                errors++;
                logLocked("online_v4_source_error", "source=itunes;artist=" + brief(pair.artist)
                        + ";title=" + brief(pair.title) + ";error=" + ex.getClass().getSimpleName());
            }
        }
        if (!current(g)) return;

        boolean verified = false;
        if (hit != null) {
            double combined = hit.titleSimilarity * 0.68 + hit.artistSimilarity * 0.32;
            boolean titleOk = hit.titleSimilarity >= 0.78
                    && safeReplacement(pair.title, hit.title, 0.66);
            boolean artistOk = hit.artistSimilarity >= 0.68
                    && safeReplacement(pair.artist, hit.artist, 0.60);
            // When both fields jointly agree very strongly, allow one field to be slightly more
            // garbled. This is still much safer than independent word searching.
            if (combined >= 0.88) {
                titleOk = titleOk || (hit.titleSimilarity >= 0.70
                        && safeReplacement(pair.title, hit.title, 0.58));
                artistOk = artistOk || (hit.artistSimilarity >= 0.60
                        && safeReplacement(pair.artist, hit.artist, 0.55));
            }
            synchronized (LOCK) {
                if (titleOk) registerAliasLocked(pair.title, hit.title, "itunes-track");
                if (artistOk) registerAliasLocked(pair.artist, hit.artist, "itunes-artist");
                verified = titleOk || artistOk || (pair.title.equals(hit.title) && pair.artist.equals(hit.artist));
                if (verified) verifiedPairs++;
                logLocked(verified ? "online_v4_pair_verified" : "online_v4_pair_hint",
                        "artistQuery=" + brief(pair.artist)
                                + ";titleQuery=" + brief(pair.title)
                                + ";artist=" + brief(hit.artist)
                                + ";title=" + brief(hit.title)
                                + ";artistSimilarity=" + f3(hit.artistSimilarity)
                                + ";titleSimilarity=" + f3(hit.titleSimilarity)
                                + ";combined=" + f3(combined));
                if (titleOk) saveCacheLocked(pair.title, hit.title, "itunes");
                if (artistOk) saveCacheLocked(pair.artist, hit.artist, "itunes");
            }
        }

        if (!verified && current(g)) {
            synchronized (LOCK) { fallbackPairs++; }
            fallbackToV3(currentProgram, previousText, pair, diagnosticLogger);
        }
    }

    private static ItunesHit searchItunes(Pair pair) throws Exception {
        String term = pair.artist + " " + pair.title;
        String address = "https://itunes.apple.com/search?country=JP&media=music&entity=song&limit=10&term="
                + URLEncoder.encode(term, "UTF-8");
        throttle();
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection)new URL(address).openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(5500);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", USER_AGENT);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("Accept-Language", "ja,en;q=0.5");
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new java.io.IOException("HTTP_" + code);
            StringBuilder b = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
                char[] buf = new char[4096];
                int n;
                while ((n = r.read(buf)) > 0 && b.length() < 350000)
                    b.append(buf, 0, Math.min(n, 350000 - b.length()));
            }
            JSONObject root = new JSONObject(b.toString());
            JSONArray results = root.optJSONArray("results");
            if (results == null) return null;
            ItunesHit best = null;
            double bestScore = -1.0;
            for (int i = 0; i < Math.min(10, results.length()); i++) {
                JSONObject o = results.optJSONObject(i);
                if (o == null) continue;
                String artist = clean(o.optString("artistName", ""));
                String title = clean(o.optString("trackName", ""));
                if (artist.isEmpty() || title.isEmpty()) continue;
                double as = similarity(pair.artist, artist);
                double ts = similarity(pair.title, title);
                double score = ts * 0.68 + as * 0.32;
                if (score > bestScore) {
                    bestScore = score;
                    best = new ItunesHit(artist, title, as, ts);
                }
            }
            return best;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static void fallbackToV3(String currentProgram, String previousText, Pair pair,
                                     Logger diagnosticLogger) {
        // Build a clean canonical cue so V3 cannot accidentally absorb unrelated surrounding prose.
        String cleanCue = "ここで1曲お聞きください " + pair.artist + "で" + pair.title + "です";
        OnlineEntityVerifierV3.observe(currentProgram, previousText, cleanCue,
                diagnosticLogger == null ? null : diagnosticLogger::log);
    }

    private static Pair extractBestPair(String segment) {
        String s = safe(segment).replace('\n', ' ').replace('\r', ' ').replaceAll("[ \\t]{2,}", " ").trim();
        if (s.isEmpty()) return null;
        Pattern[] patterns = {
                Pattern.compile("(?:ここで(?:1|一)曲(?:お)?聞きください|それでは(?:ここで)?(?:1|一)曲)[ \\t、]*([^。！？!?]{2,28}?)(?:さん)?[ \\t]*(?:で|の)[ \\t]*([^。！？!?]{2,60}?)(?=聞いていただ|こちら|です|でした|どうぞ|$)"),
                Pattern.compile("(?:聞いていただいたのは|聞いていただいているのは)[ \\t、]*([^。！？!?]{2,28}?)(?:さん)?[ \\t]*(?:で|の)[ \\t]*([^。！？!?]{2,60}?)(?=です|でした|こちら|続いて|$)"),
                Pattern.compile("(?:私の好きなラジオの曲は|僕の好きなラジオの曲は|私が好きな曲は)[ \\t、]*([^。！？!?]{2,28}?)(?:さん)?の[ \\t]*([^。！？!?]{2,60}?)(?=です|でした|を聞|を聴|$)")
        };
        String[] cues = {"play_request", "now_playing", "mail_song"};
        for (int p = 0; p < patterns.length; p++) {
            Matcher m = patterns[p].matcher(s);
            if (!m.find()) continue;
            String artist = cleanArtist(m.group(1));
            String title = cleanTitle(m.group(2), artist);
            if (validPair(artist, title)) return new Pair(artist, title, cues[p]);
        }
        return null;
    }

    private static String cleanArtist(String s) {
        return clean(s).replaceFirst("^(?:はい|それでは|では|続いて)[ \\t、]*", "").trim();
    }

    private static String cleanTitle(String raw, String artist) {
        String title = clean(raw);
        // A single ASR callback can contain "artist + title" twice. If the artist name reappears
        // inside the title field, prefer the prefix before that replay boundary.
        int p = title.indexOf(artist);
        if (p >= 2) title = title.substring(0, p).trim();
        title = title.replaceAll("^(?:曲は|楽曲は)[ \\t]*", "");
        return title.trim();
    }

    private static boolean validPair(String artist, String title) {
        if (artist.length() < 2 || artist.length() > 28 || title.length() < 2 || title.length() > 48) return false;
        String bad = "ありがとうございます|この番組|ラジオネーム|皆さん|初めて聞く|耳なじみ|幸せでした";
        if (artist.matches(".*(?:" + bad + ").*") || title.matches(".*(?:" + bad + ").*")) return false;
        return true;
    }

    private static boolean reserveRequest(Pair pair) {
        synchronized (LOCK) {
            if (!enabled || httpRequests >= MAX_HTTP_REQUESTS) return false;
            httpRequests++;
            logLocked("online_v4_query", "source=itunes;request=" + httpRequests
                    + ";artist=" + brief(pair.artist) + ";title=" + brief(pair.title));
            return true;
        }
    }

    private static void throttle() {
        long wait;
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            wait = Math.max(0L, REQUEST_GAP_MS - (now - lastRequestAt));
            lastRequestAt = now + wait;
        }
        if (wait > 0) try { Thread.sleep(wait); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static boolean applyCacheLocked(String wrong, String kind) {
        Cache c = loadCacheLocked(wrong);
        if (c == null) return false;
        cacheHits++;
        if (!wrong.equals(c.canonical) && safeReplacement(wrong, c.canonical, 0.58)) aliases.put(wrong, c.canonical);
        logLocked("online_v4_cache_hit", "kind=" + kind + ";query=" + brief(wrong)
                + ";canonical=" + brief(c.canonical));
        return true;
    }

    private static void registerAliasLocked(String wrong, String correct, String source) {
        if (wrong.equals(correct) || !safeReplacement(wrong, correct, 0.55)) return;
        aliases.put(wrong, correct);
        logLocked("online_v4_alias_ready", "wrong=" + brief(wrong) + ";correct=" + brief(correct)
                + ";source=" + source);
    }

    private static Cache loadCacheLocked(String key) {
        if (appContext == null) return null;
        String raw = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(hash(normalize(key)), "");
        if (raw == null || raw.isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(raw);
            if (o.optLong("expires", 0L) < System.currentTimeMillis()) return null;
            String canonical = o.optString("canonical", "");
            return canonical.isEmpty() ? null : new Cache(canonical);
        } catch (Exception ignored) { return null; }
    }

    private static void saveCacheLocked(String key, String canonical, String source) {
        if (appContext == null || key.isEmpty() || canonical.isEmpty()) return;
        try {
            JSONObject o = new JSONObject();
            o.put("canonical", canonical);
            o.put("source", source);
            o.put("expires", System.currentTimeMillis() + CACHE_MS);
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(hash(normalize(key)), o.toString()).apply();
        } catch (Exception ignored) {}
    }

    private static boolean safeReplacement(String wrong, String correct, double minimumSimilarity) {
        String a = normalize(wrong), b = normalize(correct);
        if (a.length() < 2 || b.length() < 2) return false;
        double lengthRatio = Math.min(a.length(), b.length()) / (double)Math.max(a.length(), b.length());
        return lengthRatio >= 0.48 && similarity(wrong, correct) >= minimumSimilarity;
    }

    private static double similarity(String a, String b) {
        String x = normalize(a), y = normalize(b);
        if (x.isEmpty() || y.isEmpty()) return 0.0;
        if (x.equals(y)) return 1.0;
        int[] prev = new int[y.length() + 1];
        int[] cur = new int[y.length() + 1];
        for (int j = 0; j <= y.length(); j++) prev[j] = j;
        for (int i = 1; i <= x.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= y.length(); j++) {
                int cost = x.charAt(i - 1) == y.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return Math.max(0.0, 1.0 - prev[y.length()] / (double)Math.max(x.length(), y.length()));
    }

    private static String normalize(String s) {
        return clean(s).toLowerCase(Locale.ROOT)
                .replaceAll("[\\s、。！？!?，,.・･:：;；'’\"「」『』()（）_\\-]+", "");
    }

    private static String hash(String s) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8"));
            StringBuilder b = new StringBuilder();
            for (byte x : bytes) b.append(String.format(Locale.US, "%02x", x & 0xff));
            return b.toString();
        } catch (Exception e) { return Integer.toHexString(s.hashCode()); }
    }

    private static boolean current(int g) {
        synchronized (LOCK) { return enabled && g == generation; }
    }

    private static boolean sameProgram(String currentProgram) {
        return safe(program).equals(safe(currentProgram));
    }

    private static String clean(String s) {
        return safe(s).replaceAll("[\\s\\u00a0]+", " ").trim();
    }

    private static String brief(String s) {
        String x = clean(s);
        return x.length() <= 70 ? x : x.substring(0, 70) + "…";
    }

    private static String f3(double x) { return String.format(Locale.US, "%.3f", x); }

    private static void logLocked(String kind, String detail) {
        Logger l = logger;
        if (l != null) try { l.log(kind, detail); } catch (Exception ignored) {}
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static final class Pair {
        final String artist, title, cue;
        Pair(String artist, String title, String cue) { this.artist = artist; this.title = title; this.cue = cue; }
    }
    private static final class ItunesHit {
        final String artist, title;
        final double artistSimilarity, titleSimilarity;
        ItunesHit(String artist, String title, double artistSimilarity, double titleSimilarity) {
            this.artist = artist; this.title = title;
            this.artistSimilarity = artistSimilarity; this.titleSimilarity = titleSimilarity;
        }
    }
    private static final class Cache {
        final String canonical;
        Cache(String canonical) { this.canonical = canonical; }
    }
}

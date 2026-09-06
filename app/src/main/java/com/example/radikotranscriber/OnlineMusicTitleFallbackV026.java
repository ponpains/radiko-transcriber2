package com.example.radikotranscriber;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
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
 * Title-first rescue path for music cues.
 *
 * v0.25 proved the cue extractor works, but a slightly wrong artist spelling (e.g. hiragana vs
 * Latin letters) can make an artist+title query miss completely.  This fallback searches the track
 * title alone.  It only adopts an artist when one near-exact title maps to one unique artist, so a
 * common song title cannot silently overwrite the transcript.
 */
public final class OnlineMusicTitleFallbackV026 {
    public static final String VERSION = "online-music-title-fallback-v026-2026-09-06";

    public interface Logger { void log(String kind, String detail); }

    private static final Object LOCK = new Object();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final String PREFS = "online_music_title_v026";
    private static final long CACHE_MS = 90L * 24L * 60L * 60L * 1000L;
    private static final long REQUEST_GAP_MS = 1400L;
    private static final int MAX_REQUESTS = 5;
    private static final String USER_AGENT =
            "RadikoTranscriber/0.26 (title-first music verification; https://github.com/ponpains/radiko-transcriber2)";

    private static Context appContext;
    private static String program = "";
    private static boolean enabled;
    private static int generation;
    private static int requests;
    private static int extracted;
    private static int verified;
    private static int ambiguous;
    private static int errors;
    private static long lastRequestAt;
    private static Logger logger;
    private static final Set<String> seen = new HashSet<>();
    private static final Map<String, String> aliases = new HashMap<>();

    private OnlineMusicTitleFallbackV026() {}

    public static void beginEpisode(Context context, String currentProgram, boolean allowOnline,
                                    Logger diagnosticLogger) {
        synchronized (LOCK) {
            appContext = context == null ? null : context.getApplicationContext();
            program = safe(currentProgram);
            enabled = allowOnline && appContext != null;
            logger = diagnosticLogger;
            generation++;
            requests = extracted = verified = ambiguous = errors = 0;
            lastRequestAt = 0L;
            seen.clear();
            aliases.clear();
            logLocked("music_title_search_start", "enabled=" + enabled + ";version=" + VERSION);
        }
    }

    public static void observe(String currentProgram, String segment, Logger diagnosticLogger) {
        Pair p = extractPair(segment);
        if (p == null) return;
        final int g;
        synchronized (LOCK) {
            if (diagnosticLogger != null) logger = diagnosticLogger;
            if (!enabled || !safe(program).equals(safe(currentProgram))) return;
            String key = normalize(p.title);
            if (seen.contains(key)) return;
            seen.add(key);
            extracted++;
            g = generation;
            if (applyCacheLocked(p)) return;
            if (requests >= MAX_REQUESTS) return;
            requests++;
            logLocked("music_title_search_query", "request=" + requests
                    + ";artist=" + brief(p.artist) + ";title=" + brief(p.title));
        }
        EXECUTOR.execute(() -> lookup(g, p));
    }

    public static String refineKnown(String currentProgram, String text) {
        String out = safe(text);
        synchronized (LOCK) {
            if (!safe(program).equals(safe(currentProgram))) return out;
            ArrayList<Map.Entry<String, String>> entries = new ArrayList<>(aliases.entrySet());
            entries.sort((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()));
            for (Map.Entry<String, String> e : entries) {
                if (!e.getKey().equals(e.getValue()) && e.getKey().length() >= 2)
                    out = out.replace(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    public static double scoreCandidate(String currentProgram, String candidate) {
        double score = 0.0;
        synchronized (LOCK) {
            if (!safe(program).equals(safe(currentProgram))) return 0.0;
            for (Map.Entry<String, String> e : aliases.entrySet()) {
                if (safe(candidate).contains(e.getValue())) score += 5.0;
                if (!e.getKey().equals(e.getValue()) && safe(candidate).contains(e.getKey())) score -= 2.5;
            }
        }
        return Math.max(-8.0, Math.min(10.0, score));
    }

    public static String stats() {
        synchronized (LOCK) {
            return "requests=" + requests + ";extracted=" + extracted + ";verified=" + verified
                    + ";ambiguous=" + ambiguous + ";errors=" + errors + ";aliases=" + aliases.size();
        }
    }

    private static void lookup(int g, Pair p) {
        if (!current(g)) return;
        HttpURLConnection c = null;
        try {
            throttle();
            String address = "https://itunes.apple.com/search?country=JP&media=music&entity=song&limit=25&term="
                    + URLEncoder.encode(p.title, "UTF-8");
            c = (HttpURLConnection)new URL(address).openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(6000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", USER_AGENT);
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new java.io.IOException("HTTP_" + code);
            StringBuilder b = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
                char[] buf = new char[4096];
                int n;
                while ((n = r.read(buf)) > 0 && b.length() < 420000)
                    b.append(buf, 0, Math.min(n, 420000 - b.length()));
            }
            JSONObject root = new JSONObject(b.toString());
            JSONArray arr = root.optJSONArray("results");
            if (arr == null) return;

            ArrayList<Hit> nearExact = new ArrayList<>();
            for (int i = 0; i < Math.min(25, arr.length()); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String title = clean(o.optString("trackName", ""));
                String artist = clean(o.optString("artistName", ""));
                if (title.isEmpty() || artist.isEmpty()) continue;
                double ts = similarity(p.title, title);
                if (ts >= 0.86) nearExact.add(new Hit(artist, title, ts, similarity(p.artist, artist)));
            }
            if (!current(g)) return;

            Hit chosen = chooseUnique(nearExact, p);
            synchronized (LOCK) {
                if (chosen == null) {
                    ambiguous++;
                    logLocked("music_title_search_ambiguous", "artist=" + brief(p.artist)
                            + ";title=" + brief(p.title) + ";nearExact=" + nearExact.size());
                    return;
                }
                if (chosen.titleSimilarity >= 0.90) {
                    if (!normalize(p.title).equals(normalize(chosen.title)))
                        aliases.put(p.title, chosen.title);
                    // Cross-script artist correction is allowed only because the title itself is
                    // near-exact and the result set resolved to one artist.
                    if (!normalize(p.artist).equals(normalize(chosen.artist)))
                        aliases.put(p.artist, chosen.artist);
                    verified++;
                    saveCacheLocked(p, chosen);
                    logLocked("music_title_search_verified", "artistQuery=" + brief(p.artist)
                            + ";titleQuery=" + brief(p.title)
                            + ";artist=" + brief(chosen.artist)
                            + ";title=" + brief(chosen.title)
                            + ";titleSimilarity=" + f3(chosen.titleSimilarity)
                            + ";artistSimilarity=" + f3(chosen.artistSimilarity));
                }
            }
        } catch (Exception ex) {
            synchronized (LOCK) {
                errors++;
                logLocked("music_title_search_error", "artist=" + brief(p.artist)
                        + ";title=" + brief(p.title) + ";error=" + ex.getClass().getSimpleName());
            }
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static Hit chooseUnique(ArrayList<Hit> hits, Pair p) {
        if (hits.isEmpty()) return null;
        Hit best = null;
        double bestScore = -1.0;
        HashSet<String> strongArtists = new HashSet<>();
        for (Hit h : hits) {
            if (h.titleSimilarity >= 0.90) strongArtists.add(normalize(h.artist));
            double score = h.titleSimilarity * 0.86 + h.artistSimilarity * 0.14;
            if (score > bestScore) { bestScore = score; best = h; }
        }
        if (best == null || best.titleSimilarity < 0.90) return null;
        // If several artists share the same near-exact title, only accept when the original artist
        // already points clearly to one of them.
        if (strongArtists.size() > 1 && best.artistSimilarity < 0.62) return null;
        return best;
    }

    private static Pair extractPair(String source) {
        String s = safe(source).replace('\n', ' ').replace('\r', ' ').replaceAll("[ \\t]{2,}", " ").trim();
        Pattern[] patterns = {
                Pattern.compile("(?:ここで(?:1|一)曲(?:お)?聞きください|それでは(?:ここで)?(?:1|一)曲)[ \\t、]*([^。！？!?]{2,28}?)(?:さん)?[ \\t]*(?:で|の)[ \\t]*([^。！？!?]{2,60}?)(?=聞いていただ|こちら|です|でした|どうぞ|$)"),
                Pattern.compile("(?:聞いていただいたのは|聞いていただいているのは)[ \\t、]*([^。！？!?]{2,28}?)(?:さん)?[ \\t]*(?:で|の)[ \\t]*([^。！？!?]{2,60}?)(?=です|でした|こちら|続いて|$)"),
                Pattern.compile("(?:ということで|曲ということで)[ \\t、]*([^。！？!?]{2,28}?)(?:さん)?の[ \\t]*([^。！？!?]{2,50}?)(?=を聞|を聴|聞いて|聴いて|$)")
        };
        for (Pattern pattern : patterns) {
            Matcher m = pattern.matcher(s);
            if (!m.find()) continue;
            String artist = clean(m.group(1)).replaceFirst("^(?:はい|では|それでは)[ \\t、]*", "").trim();
            String title = clean(m.group(2));
            int replay = title.indexOf(artist);
            if (replay >= 2) title = title.substring(0, replay).trim();
            if (valid(artist, title)) return new Pair(artist, title);
        }
        return null;
    }

    private static boolean valid(String artist, String title) {
        if (artist.length() < 2 || artist.length() > 28 || title.length() < 3 || title.length() > 55) return false;
        String bad = "ありがとうございます|この番組|ラジオネーム|皆さん|耳なじみ|初めて聞く";
        return !artist.matches(".*(?:" + bad + ").*") && !title.matches(".*(?:" + bad + ").*");
    }

    private static boolean applyCacheLocked(Pair p) {
        if (appContext == null) return false;
        String raw = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(hash(normalize(p.title)), "");
        if (raw == null || raw.isEmpty()) return false;
        try {
            JSONObject o = new JSONObject(raw);
            if (o.optLong("expires", 0L) < System.currentTimeMillis()) return false;
            String artist = o.optString("artist", "");
            String title = o.optString("title", "");
            if (artist.isEmpty() || title.isEmpty()) return false;
            if (!normalize(p.artist).equals(normalize(artist))) aliases.put(p.artist, artist);
            if (!normalize(p.title).equals(normalize(title))) aliases.put(p.title, title);
            logLocked("music_title_search_cache_hit", "artist=" + brief(artist) + ";title=" + brief(title));
            return true;
        } catch (Exception ignored) { return false; }
    }

    private static void saveCacheLocked(Pair p, Hit h) {
        if (appContext == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("artist", h.artist);
            o.put("title", h.title);
            o.put("expires", System.currentTimeMillis() + CACHE_MS);
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(hash(normalize(p.title)), o.toString()).apply();
        } catch (Exception ignored) {}
    }

    private static void throttle() {
        long wait;
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            wait = Math.max(0L, REQUEST_GAP_MS - (now - lastRequestAt));
            lastRequestAt = now + wait;
        }
        if (wait > 0L) try { Thread.sleep(wait); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static boolean current(int g) {
        synchronized (LOCK) { return enabled && g == generation; }
    }

    private static double similarity(String a, String b) {
        String x = normalize(a), y = normalize(b);
        if (x.isEmpty() || y.isEmpty()) return 0.0;
        if (x.equals(y)) return 1.0;
        int[] prev = new int[y.length() + 1], cur = new int[y.length() + 1];
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

    private static String clean(String s) { return safe(s).replaceAll("[\\s\\u00a0]+", " ").trim(); }
    private static String brief(String s) { String x = clean(s); return x.length() <= 70 ? x : x.substring(0, 70) + "…"; }
    private static String f3(double x) { return String.format(Locale.US, "%.3f", x); }
    private static String safe(String s) { return s == null ? "" : s; }

    private static void logLocked(String kind, String detail) {
        Logger l = logger;
        if (l != null) try { l.log(kind, detail); } catch (Exception ignored) {}
    }

    private static final class Pair {
        final String artist, title;
        Pair(String artist, String title) { this.artist = artist; this.title = title; }
    }
    private static final class Hit {
        final String artist, title;
        final double titleSimilarity, artistSimilarity;
        Hit(String artist, String title, double titleSimilarity, double artistSimilarity) {
            this.artist = artist; this.title = title;
            this.titleSimilarity = titleSimilarity; this.artistSimilarity = artistSimilarity;
        }
    }
}

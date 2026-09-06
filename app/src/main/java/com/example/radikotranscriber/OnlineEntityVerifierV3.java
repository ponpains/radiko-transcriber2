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
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cue-anchored online verifier for public-radio proper nouns.
 *
 * v0.23 still treated ordinary prose as artist/title pairs because the mere presence of the word
 * "曲" enabled broad "AのB" patterns. v0.24 does the opposite: no explicit radio/music cue, no
 * search. The online layer is optional; bundled official knowledge is the primary correction path.
 * All HTTP work stays on one background executor and failures never touch capture/recognizer state.
 */
public final class OnlineEntityVerifierV3 {
    public static final String VERSION = "online-entity-verifier-v024-2026-09-06";

    public interface Logger { void log(String kind, String detail); }

    private static final Object LOCK = new Object();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final String PREFS = "online_entity_cache_v3";
    private static final long CACHE_MS = 45L * 24L * 60L * 60L * 1000L;
    private static final long REQUEST_GAP_MS = 1200L;
    private static final long SOURCE_COOLDOWN_MS = 10L * 60L * 1000L;
    private static final int MAX_HTTP_REQUESTS = 10;
    private static final String USER_AGENT =
            "RadikoTranscriber/0.24 (https://github.com/ponpains/radiko-transcriber2)";

    private static Context appContext;
    private static boolean enabled;
    private static int generation;
    private static int httpRequests;
    private static int extractedPairs;
    private static int rejectedPairs;
    private static int cacheHits;
    private static int verifiedCount;
    private static int sourceErrors;
    private static long lastRequestAt;
    private static String program = "";
    private static Logger logger;

    private static final Set<String> seen = new HashSet<>();
    private static final Map<String, String> aliases = new HashMap<>();
    private static final ArrayList<String> pageTerms = new ArrayList<>();
    private static final Map<String, Integer> sourceFailures = new HashMap<>();
    private static final Map<String, Long> sourceCooldown = new HashMap<>();

    private OnlineEntityVerifierV3() {}

    public static void beginEpisode(Context context, String currentProgram, String publicEpisodeContext,
                                    boolean allowOnline, Logger diagnosticLogger) {
        synchronized (LOCK) {
            appContext = context == null ? null : context.getApplicationContext();
            program = safe(currentProgram);
            enabled = allowOnline && appContext != null;
            logger = diagnosticLogger;
            generation++;
            httpRequests = 0;
            extractedPairs = 0;
            rejectedPairs = 0;
            cacheHits = 0;
            verifiedCount = 0;
            sourceErrors = 0;
            lastRequestAt = 0L;
            seen.clear();
            aliases.clear();
            pageTerms.clear();
            sourceFailures.clear();
            sourceCooldown.clear();
            collectPageTerms(publicEpisodeContext, pageTerms);
            logLocked("online_entity_start", "enabled=" + enabled
                    + ";pageTerms=" + pageTerms.size()
                    + ";strategy=cue-anchored-pairs-only"
                    + ";version=" + VERSION);
        }
    }

    public static void observe(String currentProgram, String previousText, String segment,
                               Logger diagnosticLogger) {
        final ArrayList<Pair> pairs;
        final int g;
        synchronized (LOCK) {
            if (diagnosticLogger != null) logger = diagnosticLogger;
            if (!enabled || appContext == null || !sameProgram(currentProgram)) return;
            g = generation;
        }

        pairs = extractCueAnchoredPairs(segment);
        for (Pair pair : pairs) {
            if (!validPair(pair)) {
                synchronized (LOCK) {
                    rejectedPairs++;
                    logLocked("online_query_rejected", "artist=" + brief(pair.artist)
                            + ";title=" + brief(pair.title) + ";reason=pair_validation");
                }
                continue;
            }
            final String key = normalize(pair.artist) + "|" + normalize(pair.title);
            synchronized (LOCK) {
                if (g != generation || seen.contains(key)) continue;
                seen.add(key);
                extractedPairs++;
                logLocked("online_pair_extracted", "artist=" + brief(pair.artist)
                        + ";title=" + brief(pair.title) + ";cue=" + pair.cue);

                boolean ac = applyCachedLocked(pair.artist, "artist");
                boolean tc = applyCachedLocked(pair.title, "title");
                if (ac && tc) continue;

                applyPageMatchLocked(pair.artist, "artist");
                applyPageMatchLocked(pair.title, "title");
                if (httpRequests >= MAX_HTTP_REQUESTS) continue;
            }
            EXECUTOR.execute(() -> resolvePair(g, pair));
            // One explicit song identification per recognizer chunk is enough. Repeated cue text is
            // common around song playback and would otherwise waste network requests.
            break;
        }
    }

    public static String refineKnown(String currentProgram, String text) {
        String out = safe(text);
        synchronized (LOCK) {
            if (!sameProgram(currentProgram) || aliases.isEmpty()) return out;
            ArrayList<Map.Entry<String, String>> entries = new ArrayList<>(aliases.entrySet());
            entries.sort((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()));
            for (Map.Entry<String, String> e : entries) {
                String wrong = e.getKey(), correct = e.getValue();
                if (wrong.length() >= 3 && !wrong.equals(correct) && out.contains(wrong))
                    out = out.replace(wrong, correct);
            }
        }
        return out;
    }

    public static double scoreCandidate(String currentProgram, String candidate) {
        double score = 0.0;
        synchronized (LOCK) {
            if (!sameProgram(currentProgram)) return 0.0;
            for (Map.Entry<String, String> e : aliases.entrySet()) {
                if (safe(candidate).contains(e.getValue())) score += 4.0;
                if (!e.getKey().equals(e.getValue()) && safe(candidate).contains(e.getKey())) score -= 2.5;
            }
        }
        return Math.max(-8.0, Math.min(8.0, score));
    }

    public static String stats() {
        synchronized (LOCK) {
            return "httpRequests=" + httpRequests
                    + ";extractedPairs=" + extractedPairs
                    + ";rejectedPairs=" + rejectedPairs
                    + ";cacheHits=" + cacheHits
                    + ";verified=" + verifiedCount
                    + ";sourceErrors=" + sourceErrors
                    + ";musicbrainzCooldownMs=" + cooldownRemainingLocked("musicbrainz")
                    + ";wikidataCooldownMs=" + cooldownRemainingLocked("wikidata");
        }
    }

    private static void resolvePair(int g, Pair pair) {
        if (!current(g)) return;

        MusicHit music = searchMusicBrainz(pair.title, pair.artist);
        if (!current(g)) return;
        if (music != null) {
            boolean titleOk = music.titleSimilarity >= 0.80
                    && safeReplacement(pair.title, music.title, 0.80);
            boolean artistOk = music.titleSimilarity >= 0.88
                    && music.artistSimilarity >= 0.64
                    && safeReplacement(pair.artist, music.artist, 0.64);
            synchronized (LOCK) {
                if (titleOk) registerAliasLocked(pair.title, music.title, "musicbrainz-title");
                if (artistOk) registerAliasLocked(pair.artist, music.artist, "musicbrainz-artist");
                logLocked((titleOk || artistOk) ? "online_pair_verified" : "online_pair_hint",
                        "artistQuery=" + brief(pair.artist)
                                + ";titleQuery=" + brief(pair.title)
                                + ";artist=" + brief(music.artist)
                                + ";title=" + brief(music.title)
                                + ";titleSimilarity=" + f3(music.titleSimilarity)
                                + ";artistSimilarity=" + f3(music.artistSimilarity)
                                + ";titleReplace=" + titleOk
                                + ";artistReplace=" + artistOk);
                if (titleOk) saveCacheLocked(pair.title, music.title, "musicbrainz");
                if (artistOk) saveCacheLocked(pair.artist, music.artist, "musicbrainz");
            }
            if (titleOk || artistOk) return;
        }

        // Japanese Wikimedia is a fallback only for the two explicit fields. We never search the
        // surrounding sentence, which was the source of v0.22/v0.23 garbage queries.
        SearchHit artistHit = searchWikidata(pair.artist);
        SearchHit titleHit = searchWikidata(pair.title);
        if (!current(g)) return;
        synchronized (LOCK) {
            boolean a = artistHit != null && artistHit.similarity >= 0.86
                    && safeReplacement(pair.artist, artistHit.canonical, 0.86);
            boolean t = titleHit != null && titleHit.similarity >= 0.86
                    && safeReplacement(pair.title, titleHit.canonical, 0.86);
            if (a) {
                registerAliasLocked(pair.artist, artistHit.canonical, "wikidata-artist");
                saveCacheLocked(pair.artist, artistHit.canonical, "wikidata");
            }
            if (t) {
                registerAliasLocked(pair.title, titleHit.canonical, "wikidata-title");
                saveCacheLocked(pair.title, titleHit.canonical, "wikidata");
            }
            if (!a && !t) {
                logLocked("online_pair_miss", "artist=" + brief(pair.artist)
                        + ";title=" + brief(pair.title));
            }
        }
    }

    private static MusicHit searchMusicBrainz(String titleQuery, String artistQuery) {
        if (!sourceAvailable("musicbrainz") || !reserveRequest("musicbrainz", titleQuery)) return null;
        try {
            String address = "https://musicbrainz.org/ws/2/recording/?fmt=json&limit=6&query="
                    + enc("recording:\"" + titleQuery + "\"");
            JSONObject root = readJson(address, "musicbrainz");
            if (root == null) return null;
            JSONArray arr = root.optJSONArray("recordings");
            if (arr == null) return null;
            MusicHit best = null;
            double bestScore = -1.0;
            for (int i = 0; i < Math.min(6, arr.length()); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item == null) continue;
                String title = clean(item.optString("title", ""));
                String artist = firstArtist(item.optJSONArray("artist-credit"));
                if (title.isEmpty() || artist.isEmpty()) continue;
                double ts = similarity(titleQuery, title);
                double as = similarity(artistQuery, artist);
                double remote = Math.max(0.0, Math.min(1.0, item.optDouble("score", 0.0) / 100.0));
                double score = ts * 0.72 + as * 0.20 + remote * 0.08;
                if (score > bestScore) {
                    bestScore = score;
                    best = new MusicHit(artist, title, ts, as);
                }
            }
            sourceSucceeded("musicbrainz");
            return best;
        } catch (Exception ex) {
            sourceFailed("musicbrainz", ex, titleQuery);
            return null;
        }
    }

    private static SearchHit searchWikidata(String query) {
        if (!sourceAvailable("wikidata") || !reserveRequest("wikidata", query)) return null;
        try {
            String address = "https://www.wikidata.org/w/api.php?action=wbsearchentities&format=json"
                    + "&language=ja&uselang=ja&type=item&limit=5&search=" + enc(query);
            JSONObject root = readJson(address, "wikidata");
            if (root == null) return null;
            JSONArray arr = root.optJSONArray("search");
            if (arr == null) return null;
            SearchHit best = null;
            for (int i = 0; i < Math.min(5, arr.length()); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item == null) continue;
                LinkedHashSet<String> names = new LinkedHashSet<>();
                addName(names, item.optString("label", ""));
                JSONObject match = item.optJSONObject("match");
                if (match != null) addName(names, match.optString("text", ""));
                JSONArray aa = item.optJSONArray("aliases");
                if (aa != null) for (int j = 0; j < Math.min(8, aa.length()); j++) addName(names, aa.optString(j, ""));
                for (String name : names) {
                    SearchHit hit = new SearchHit(name, similarity(query, name));
                    if (best == null || hit.similarity > best.similarity) best = hit;
                }
            }
            sourceSucceeded("wikidata");
            return best;
        } catch (Exception ex) {
            sourceFailed("wikidata", ex, query);
            return null;
        }
    }

    private static JSONObject readJson(String address, String source) throws Exception {
        throttle();
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection)new URL(address).openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(5500);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", USER_AGENT);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("Accept-Language", "ja,en;q=0.6");
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new java.io.IOException("HTTP_" + code);
            InputStream in = c.getInputStream();
            StringBuilder b = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
                char[] buf = new char[4096];
                int n;
                while ((n = r.read(buf)) > 0 && b.length() < 300000)
                    b.append(buf, 0, Math.min(n, 300000 - b.length()));
            }
            return new JSONObject(b.toString());
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static ArrayList<Pair> extractCueAnchoredPairs(String source) {
        ArrayList<Pair> out = new ArrayList<>();
        String s = scrubRadioNames(safe(source).replace('\n', ' ').replace('\r', ' '));
        if (s.isEmpty()) return out;

        collect(out, s, "play_request", Pattern.compile(
                "(?:ここで(?:1|一)曲(?:お)?聞きください|それでは(?:ここで)?(?:1|一)曲)[ \\t、]*"
                        + "([^。！？!?]{2,24}?)(?:さん)?[ \\t]*(?:で|の)[ \\t]*"
                        + "([^。！？!?]{2,38}?)(?=聞いていただ|こちら|です|でした|どうぞ|$)"));
        collect(out, s, "now_playing", Pattern.compile(
                "(?:聞いていただいたのは|聞いていただいているのは)[ \\t、]*"
                        + "([^。！？!?]{2,24}?)(?:さん)?[ \\t]*(?:で|の)[ \\t]*"
                        + "([^。！？!?]{2,38}?)(?=です|でした|こちら|続いて|$)"));
        collect(out, s, "mail_song", Pattern.compile(
                "(?:私の好きなラジオの曲は|僕の好きなラジオの曲は|私が好きな曲は)[ \\t、]*"
                        + "([^。！？!?]{2,24}?)(?:さん)?の[ \\t]*"
                        + "([^。！？!?]{2,38}?)(?=です|でした|を聞|を聴|$)"));
        return out;
    }

    private static void collect(ArrayList<Pair> out, String s, String cue, Pattern pattern) {
        Matcher m = pattern.matcher(s);
        while (m.find() && out.size() < 3) {
            String artist = cleanArtist(m.group(1));
            String title = cleanTitle(m.group(2));
            Pair p = new Pair(artist, title, cue);
            boolean duplicate = false;
            for (Pair e : out) if (normalize(e.artist).equals(normalize(artist))
                    && normalize(e.title).equals(normalize(title))) duplicate = true;
            if (!duplicate) out.add(p);
        }
    }

    private static boolean validPair(Pair p) {
        String a = p.artist, t = p.title;
        if (a.length() < 2 || a.length() > 24 || t.length() < 2 || t.length() > 38) return false;
        if (wordCount(a) > 5 || wordCount(t) > 8) return false;
        String[] badArtist = {"ない", "しょうか", "みんな", "この番組", "初めて", "耳なじみ", "幸せ",
                "毎回", "時間", "と思", "ありがとうございます", "続いて", "ラジオネーム", "聞いて"};
        String[] badTitle = {"初めて聞く", "耳なじみ", "ラジオ局を", "幸せ", "触れることで", "この番組",
                "みんなもう", "毎回続", "と思います", "ありがとうございます", "ラジオネーム"};
        if (containsAny(a, badArtist) || containsAny(t, badTitle)) return false;
        if (endsWithAny(a, new String[]{"ない", "ので", "けど", "から", "まで", "こと", "もの", "時間"})) return false;
        return distinctive(a) >= 2 && distinctive(t) >= 2;
    }

    private static String cleanArtist(String s) {
        return clean(s).replaceFirst("^(?:はい|それでは|では|続いて)[ \\t、]*", "").trim();
    }

    private static String cleanTitle(String s) {
        String x = clean(s);
        String[] stop = {" 聞いていただ", " こちら", " 続いて", " という曲", " という楽曲", " お願いします"};
        for (String p : stop) {
            int i = x.indexOf(p);
            if (i >= 2) x = x.substring(0, i);
        }
        return x.trim();
    }

    private static String scrubRadioNames(String s) {
        Matcher m = Pattern.compile("ラジオネーム[ \\t]*[^。！？!?]{1,42}?さん").matcher(s);
        return m.replaceAll("ラジオネーム [投稿者名]");
    }

    private static boolean applyCachedLocked(String wrong, String kind) {
        Cache c = loadCacheLocked(wrong);
        if (c == null) return false;
        cacheHits++;
        if (!wrong.equals(c.canonical) && safeReplacement(wrong, c.canonical, 0.78)) aliases.put(wrong, c.canonical);
        logLocked("online_entity_cache_hit", "kind=" + kind + ";query=" + brief(wrong)
                + ";canonical=" + brief(c.canonical));
        return true;
    }

    private static void applyPageMatchLocked(String wrong, String kind) {
        String best = "";
        double score = 0.0;
        for (String term : pageTerms) {
            double s = similarity(wrong, term);
            if (s > score) { score = s; best = term; }
        }
        if (score >= 0.88 && safeReplacement(wrong, best, 0.88)) {
            registerAliasLocked(wrong, best, "radiko-page-" + kind);
            saveCacheLocked(wrong, best, "radiko-page");
        }
    }

    private static void collectPageTerms(String source, ArrayList<String> out) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        Matcher q = Pattern.compile("[「『]([^」』]{2,60})[」』]").matcher(safe(source));
        while (q.find()) terms.add(clean(q.group(1)));
        for (String token : safe(source).split("[、。！？!?\\n/／|｜:：]+")) {
            String x = clean(token);
            if (x.length() >= 3 && x.length() <= 55) terms.add(x);
            if (terms.size() >= 60) break;
        }
        out.addAll(terms);
    }

    private static boolean reserveRequest(String source, String query) {
        synchronized (LOCK) {
            if (!enabled || httpRequests >= MAX_HTTP_REQUESTS || !sourceAvailableLocked(source)) return false;
            httpRequests++;
            logLocked("online_entity_query", "source=" + source + ";request=" + httpRequests
                    + ";query=" + brief(query));
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

    private static void sourceFailed(String source, Exception ex, String query) {
        synchronized (LOCK) {
            sourceErrors++;
            int n = sourceFailures.containsKey(source) ? sourceFailures.get(source) + 1 : 1;
            sourceFailures.put(source, n);
            if (n >= 2) sourceCooldown.put(source, System.currentTimeMillis() + SOURCE_COOLDOWN_MS);
            logLocked("online_entity_source_error", "source=" + source + ";query=" + brief(query)
                    + ";error=" + ex.getClass().getSimpleName() + ";sourceFailures=" + n
                    + ";cooldownMs=" + cooldownRemainingLocked(source));
        }
    }

    private static void sourceSucceeded(String source) {
        synchronized (LOCK) {
            sourceFailures.put(source, 0);
            sourceCooldown.put(source, 0L);
        }
    }

    private static boolean sourceAvailable(String source) {
        synchronized (LOCK) { return sourceAvailableLocked(source); }
    }

    private static boolean sourceAvailableLocked(String source) {
        return System.currentTimeMillis() >= (sourceCooldown.containsKey(source) ? sourceCooldown.get(source) : 0L);
    }

    private static long cooldownRemainingLocked(String source) {
        return Math.max(0L, (sourceCooldown.containsKey(source) ? sourceCooldown.get(source) : 0L) - System.currentTimeMillis());
    }

    private static void registerAliasLocked(String wrong, String correct, String source) {
        if (wrong.equals(correct) || !safeReplacement(wrong, correct, 0.64)) return;
        aliases.put(wrong, correct);
        verifiedCount++;
        logLocked("online_alias_ready", "wrong=" + brief(wrong) + ";correct=" + brief(correct)
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
        } catch (Exception e) { return null; }
    }

    private static void saveCacheLocked(String key, String canonical, String source) {
        if (appContext == null || key.isEmpty() || canonical.isEmpty()) return;
        try {
            JSONObject o = new JSONObject();
            o.put("canonical", canonical);
            o.put("source", source);
            o.put("expires", System.currentTimeMillis() + CACHE_MS);
            SharedPreferences.Editor e = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
            e.putString(hash(normalize(key)), o.toString()).apply();
        } catch (Exception ignored) {}
    }

    private static String firstArtist(JSONArray credit) {
        if (credit == null || credit.length() == 0) return "";
        JSONObject first = credit.optJSONObject(0);
        if (first == null) return "";
        JSONObject artist = first.optJSONObject("artist");
        return clean(artist == null ? first.optString("name", "") : artist.optString("name", ""));
    }

    private static void addName(Set<String> set, String value) {
        String x = clean(value);
        if (x.length() >= 2 && x.length() <= 70) set.add(x);
    }

    private static boolean safeReplacement(String wrong, String correct, double minimum) {
        String a = clean(wrong), b = clean(correct);
        if (a.length() < 2 || b.length() < 2) return false;
        double sim = similarity(a, b);
        if (sim < minimum) return false;
        double ratio = Math.min(normalize(a).length(), normalize(b).length())
                / (double)Math.max(1, Math.max(normalize(a).length(), normalize(b).length()));
        return ratio >= 0.55;
    }

    private static double similarity(String a, String b) {
        String x = normalize(a), y = normalize(b);
        if (x.isEmpty() || y.isEmpty()) return 0.0;
        if (x.equals(y)) return 1.0;
        int d = levenshtein(x, y);
        return Math.max(0.0, 1.0 - d / (double)Math.max(x.length(), y.length()));
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    private static int wordCount(String s) {
        String x = clean(s);
        return x.isEmpty() ? 0 : x.split("[ \\t]+").length;
    }

    private static int distinctive(String s) {
        int n = 0;
        for (int i = 0; i < safe(s).length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) n++;
        }
        return n;
    }

    private static boolean current(int g) {
        synchronized (LOCK) { return enabled && g == generation; }
    }

    private static boolean sameProgram(String p) { return safe(program).equals(safe(p)); }

    private static boolean containsAny(String s, String... values) {
        String x = safe(s);
        for (String v : values) if (x.contains(v)) return true;
        return false;
    }

    private static boolean endsWithAny(String s, String[] values) {
        for (String v : values) if (s.endsWith(v)) return true;
        return false;
    }

    private static String clean(String s) {
        return safe(s).replaceAll("^[\\s、。！？!?「『（(]+", "")
                .replaceAll("[\\s、。！？!?」』）)]+$", "")
                .replaceAll("[ \\t]{2,}", " ").trim();
    }

    private static String normalize(String s) {
        return clean(s).toLowerCase(Locale.ROOT)
                .replaceAll("[\\s、。！？!?，,.・･:：;；'’\"「」『』()（）_\\-]+", "");
    }

    private static String enc(String s) throws Exception { return URLEncoder.encode(safe(s), "UTF-8"); }

    private static String hash(String s) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8"));
            StringBuilder b = new StringBuilder();
            for (byte x : bytes) b.append(String.format(Locale.US, "%02x", x & 0xff));
            return b.toString();
        } catch (Exception e) { return Integer.toHexString(s.hashCode()); }
    }

    private static String brief(String s) {
        String x = safe(s).replace('\n', ' ').replace('\r', ' ');
        return x.length() <= 72 ? x : x.substring(0, 72) + "…";
    }

    private static String f3(double v) { return String.format(Locale.US, "%.3f", v); }

    private static void logLocked(String kind, String detail) {
        if (logger != null) try { logger.log(kind, detail); } catch (Exception ignored) {}
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static final class Pair {
        final String artist, title, cue;
        Pair(String artist, String title, String cue) { this.artist = artist; this.title = title; this.cue = cue; }
    }

    private static final class MusicHit {
        final String artist, title;
        final double titleSimilarity, artistSimilarity;
        MusicHit(String artist, String title, double titleSimilarity, double artistSimilarity) {
            this.artist = artist; this.title = title;
            this.titleSimilarity = titleSimilarity; this.artistSimilarity = artistSimilarity;
        }
    }

    private static final class SearchHit {
        final String canonical;
        final double similarity;
        SearchHit(String canonical, double similarity) { this.canonical = canonical; this.similarity = similarity; }
    }

    private static final class Cache {
        final String canonical;
        Cache(String canonical) { this.canonical = canonical; }
    }
}

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
 * Stability-first online verifier for public-radio proper nouns.
 *
 * v0.22 proved the transport isolation was safe, but its extractor queried ordinary phrases such as
 * "この番組を通して" and "本当に皆".  v0.23 therefore removes generic token searching entirely.
 * It searches only strong structures such as "artist + song", named venues/shops, and page-context
 * candidates.  Network work stays on one background worker and can never block audio capture/ASR.
 */
public final class OnlineEntityVerifierV2 {
    public static final String VERSION = "online-entity-verifier-v023-2026-09-06";

    public interface Logger { void log(String kind, String detail); }

    private static final Object LOCK = new Object();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final String PREFS = "online_entity_cache_v2";
    private static final long CACHE_MS = 45L * 24L * 60L * 60L * 1000L;
    private static final long REQUEST_GAP_MS = 1100L;
    private static final long SOURCE_COOLDOWN_MS = 10L * 60L * 1000L;
    private static final int MAX_HTTP_REQUESTS = 18;
    private static final String USER_AGENT =
            "RadikoTranscriber/0.23 (https://github.com/ponpains/radiko-transcriber2)";

    private static Context appContext;
    private static boolean enabled;
    private static int sessionGeneration;
    private static int httpRequests;
    private static int pairQueries;
    private static int entityQueries;
    private static int cacheHits;
    private static int verifiedCount;
    private static int sourceErrors;
    private static long lastRequestAt;
    private static String program = "";
    private static String episodeContext = "";
    private static Logger logger;

    private static final Set<String> seen = new HashSet<>();
    private static final Map<String, String> aliases = new HashMap<>();
    private static final ArrayList<String> contextTerms = new ArrayList<>();
    private static final Map<String, Integer> sourceFailureCount = new HashMap<>();
    private static final Map<String, Long> sourceCooldownUntil = new HashMap<>();

    private OnlineEntityVerifierV2() {}

    public static void beginEpisode(Context context, String currentProgram, String publicEpisodeContext,
                                    boolean allowOnline, Logger diagnosticLogger) {
        synchronized (LOCK) {
            appContext = context == null ? null : context.getApplicationContext();
            program = safe(currentProgram);
            episodeContext = safe(publicEpisodeContext);
            enabled = allowOnline && appContext != null;
            logger = diagnosticLogger;
            sessionGeneration++;
            httpRequests = 0;
            pairQueries = 0;
            entityQueries = 0;
            cacheHits = 0;
            verifiedCount = 0;
            sourceErrors = 0;
            lastRequestAt = 0L;
            seen.clear();
            aliases.clear();
            contextTerms.clear();
            sourceFailureCount.clear();
            sourceCooldownUntil.clear();
            collectContextTerms(episodeContext, contextTerms);
            logLocked("online_entity_start", "enabled=" + enabled
                    + ";contextChars=" + episodeContext.length()
                    + ";contextTerms=" + contextTerms.size()
                    + ";strategy=strong-pair-only"
                    + ";version=" + VERSION);
        }
    }

    /** Queue at most two strong lookups from one recognized chunk. Returns immediately. */
    public static void observe(String currentProgram, String previousText, String segment,
                               Logger diagnosticLogger) {
        final ArrayList<Query> queries;
        final int generation;
        synchronized (LOCK) {
            if (diagnosticLogger != null) logger = diagnosticLogger;
            if (!enabled || appContext == null || !sameProgram(currentProgram)) return;
            if (httpRequests >= MAX_HTTP_REQUESTS) return;
            generation = sessionGeneration;
            queries = extractStrongQueries(previousText, segment);
        }

        int queued = 0;
        for (Query q : queries) {
            if (queued >= 2) break;
            final String key = q.key();
            synchronized (LOCK) {
                if (generation != sessionGeneration || seen.contains(key)) continue;
                seen.add(key);

                boolean artistCached = q.artist.isEmpty() || applyCachedLocked(q.artist, q.kind + ":artist");
                boolean titleCached = q.title.isEmpty() || applyCachedLocked(q.title, q.kind + ":title");
                boolean entityCached = q.entity.isEmpty() || applyCachedLocked(q.entity, q.kind);
                if (artistCached && titleCached && entityCached) continue;

                // The public radiko episode page is the safest source.  Use it before any network
                // lookup when a close spelling already appears in the episode description.
                if (!q.artist.isEmpty()) applyPageMatchLocked(q.artist, q.kind + ":artist");
                if (!q.title.isEmpty()) applyPageMatchLocked(q.title, q.kind + ":title");
                if (!q.entity.isEmpty()) applyPageMatchLocked(q.entity, q.kind);
            }

            // A page match may have completed every field.
            synchronized (LOCK) {
                boolean done = (q.artist.isEmpty() || aliases.containsKey(q.artist))
                        && (q.title.isEmpty() || aliases.containsKey(q.title))
                        && (q.entity.isEmpty() || aliases.containsKey(q.entity));
                if (done) continue;
                if (httpRequests >= MAX_HTTP_REQUESTS) break;
                if (q.isPair()) pairQueries++; else entityQueries++;
            }
            queued++;
            EXECUTOR.execute(() -> {
                if (!sessionCurrent(generation)) return;
                if (q.isPair()) resolveMusicPair(generation, q);
                else resolveEntity(generation, q);
            });
        }
    }

    /** Apply only aliases that were already verified with high confidence. */
    public static String refineKnown(String currentProgram, String text) {
        String out = safe(text);
        synchronized (LOCK) {
            if (!sameProgram(currentProgram) || aliases.isEmpty()) return out;
            // Longest wrong form first avoids a short alias rewriting part of a longer one.
            ArrayList<Map.Entry<String, String>> entries = new ArrayList<>(aliases.entrySet());
            entries.sort((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()));
            for (Map.Entry<String, String> e : entries) {
                String wrong = e.getKey(), correct = e.getValue();
                if (wrong.length() >= 3 && !wrong.equals(correct) && out.contains(wrong)) {
                    out = out.replace(wrong, correct);
                }
            }
        }
        return out;
    }

    public static double scoreCandidate(String currentProgram, String candidate) {
        String c = safe(candidate);
        double score = 0.0;
        synchronized (LOCK) {
            if (!sameProgram(currentProgram)) return 0.0;
            for (Map.Entry<String, String> e : aliases.entrySet()) {
                if (c.contains(e.getValue())) score += 4.0;
                if (!e.getKey().equals(e.getValue()) && c.contains(e.getKey())) score -= 2.8;
            }
        }
        return Math.max(-10.0, Math.min(10.0, score));
    }

    public static String stats() {
        synchronized (LOCK) {
            return "httpRequests=" + httpRequests + ";pairQueries=" + pairQueries
                    + ";entityQueries=" + entityQueries + ";cacheHits=" + cacheHits
                    + ";verified=" + verifiedCount + ";sourceErrors=" + sourceErrors
                    + ";musicbrainzCooldownMs=" + cooldownRemainingLocked("musicbrainz")
                    + ";wikidataCooldownMs=" + cooldownRemainingLocked("wikidata")
                    + ";wikipediaCooldownMs=" + cooldownRemainingLocked("wikipedia");
        }
    }

    private static void resolveMusicPair(int generation, Query q) {
        if (!sessionCurrent(generation)) return;
        MusicPairHit pair = searchMusicBrainzRecording(q.title, q.artist);
        if (!sessionCurrent(generation)) return;

        if (pair != null && pair.titleSimilarity >= 0.76) {
            boolean titleReplace = canStrictReplace(q.title, pair.title)
                    || (pair.titleSimilarity >= 0.93 && sameScriptFamily(q.title, pair.title));
            boolean artistReplace = pair.titleSimilarity >= 0.88
                    && strongPairArtistEvidence(q.artist, pair.artist, pair.titleSimilarity);

            synchronized (LOCK) {
                if (titleReplace) registerAliasLocked(q.title, pair.title,
                        pair.titleSimilarity, "musicbrainz_pair_title");
                if (artistReplace) registerAliasLocked(q.artist, pair.artist,
                        similarity(q.artist, pair.artist), "musicbrainz_pair_artist");
                logLocked((titleReplace || artistReplace) ? "online_pair_verified" : "online_pair_hint",
                        "artistQuery=" + brief(q.artist)
                                + ";titleQuery=" + brief(q.title)
                                + ";artist=" + brief(pair.artist)
                                + ";title=" + brief(pair.title)
                                + ";titleSimilarity=" + f3(pair.titleSimilarity)
                                + ";artistSimilarity=" + f3(similarity(q.artist, pair.artist))
                                + ";remoteScore=" + f2(pair.remoteScore)
                                + ";titleReplace=" + titleReplace
                                + ";artistReplace=" + artistReplace);
                if (titleReplace) saveCacheLocked(q.title, pair.title, "musicbrainz_pair");
                if (artistReplace) saveCacheLocked(q.artist, pair.artist, "musicbrainz_pair");
            }
            return;
        }

        // MusicBrainz can be slow or sparse for Japanese indie/local names.  Fall back to the two
        // Wikimedia search APIs, but only for the explicit artist/title pair rather than prose.
        SearchHit a = bestGeneralHit(q.artist);
        SearchHit t = bestGeneralHit(q.title);
        synchronized (LOCK) {
            boolean ar = a != null && a.similarity >= 0.86 && canStrictReplace(q.artist, a.canonical);
            boolean tr = t != null && t.similarity >= 0.86 && canStrictReplace(q.title, t.canonical);
            if (ar) {
                registerAliasLocked(q.artist, a.canonical, a.similarity, a.source);
                saveCacheLocked(q.artist, a.canonical, a.source);
            }
            if (tr) {
                registerAliasLocked(q.title, t.canonical, t.similarity, t.source);
                saveCacheLocked(q.title, t.canonical, t.source);
            }
            if (!ar && !tr) {
                logLocked("online_pair_miss", "artist=" + brief(q.artist)
                        + ";title=" + brief(q.title));
            }
        }
    }

    private static void resolveEntity(int generation, Query q) {
        if (!sessionCurrent(generation)) return;
        SearchHit hit = bestGeneralHit(q.entity);
        if (!sessionCurrent(generation)) return;
        synchronized (LOCK) {
            if (hit != null && hit.similarity >= 0.84 && canStrictReplace(q.entity, hit.canonical)) {
                registerAliasLocked(q.entity, hit.canonical, hit.similarity, hit.source);
                saveCacheLocked(q.entity, hit.canonical, hit.source);
                logLocked("online_entity_verified", "kind=" + q.kind
                        + ";query=" + brief(q.entity)
                        + ";canonical=" + brief(hit.canonical)
                        + ";similarity=" + f3(hit.similarity)
                        + ";source=" + hit.source);
            } else if (hit != null) {
                logLocked("online_entity_hint", "kind=" + q.kind
                        + ";query=" + brief(q.entity)
                        + ";canonical=" + brief(hit.canonical)
                        + ";similarity=" + f3(hit.similarity)
                        + ";source=" + hit.source);
            } else {
                logLocked("online_entity_miss", "kind=" + q.kind + ";query=" + brief(q.entity));
            }
        }
    }

    /** Search recordings by title; exact artist spelling is not required. */
    private static MusicPairHit searchMusicBrainzRecording(String titleQuery, String artistQuery) {
        if (titleQuery.isEmpty() || !sourceAvailable("musicbrainz")) return null;
        try {
            String q = "recording:" + titleQuery;
            JSONObject root = readJson("https://musicbrainz.org/ws/2/recording/?fmt=json&limit=8&query="
                    + enc(q), "musicbrainz", titleQuery);
            if (root == null) return null;
            JSONArray arr = root.optJSONArray("recordings");
            if (arr == null) return null;
            MusicPairHit best = null;
            double bestScore = -1.0;
            for (int i = 0; i < Math.min(8, arr.length()); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item == null) continue;
                String title = cleanCandidate(item.optString("title", ""));
                String artist = firstArtistCredit(item.optJSONArray("artist-credit"));
                if (title.isEmpty() || artist.isEmpty()) continue;
                double titleSim = similarity(titleQuery, title);
                double artistSim = similarity(artistQuery, artist);
                double prefix = commonPrefixRatio(artistQuery, artist);
                double remote = Math.max(0.0, Math.min(1.0, item.optDouble("score", 0.0) / 100.0));
                double score = titleSim * 0.70 + Math.max(artistSim, prefix) * 0.22 + remote * 0.08;
                if (score > bestScore) {
                    bestScore = score;
                    best = new MusicPairHit(artist, title, titleSim, remote);
                }
            }
            return best;
        } catch (Exception ex) {
            sourceFailed("musicbrainz", ex, titleQuery);
            return null;
        }
    }

    private static SearchHit bestGeneralHit(String query) {
        SearchHit a = searchWikidata(query);
        SearchHit b = searchWikipedia(query);
        return better(b, a) ? b : a;
    }

    private static SearchHit searchWikidata(String query) {
        if (query.isEmpty() || !sourceAvailable("wikidata")) return null;
        try {
            JSONObject root = readJson(
                    "https://www.wikidata.org/w/api.php?action=wbsearchentities&format=json&language=ja"
                            + "&uselang=ja&type=item&limit=6&search=" + enc(query),
                    "wikidata", query);
            if (root == null) return null;
            JSONArray arr = root.optJSONArray("search");
            if (arr == null) return null;
            SearchHit best = null;
            for (int i = 0; i < Math.min(6, arr.length()); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item == null) continue;
                LinkedHashSet<String> names = new LinkedHashSet<>();
                addName(names, item.optString("label", ""));
                JSONObject match = item.optJSONObject("match");
                if (match != null) addName(names, match.optString("text", ""));
                JSONArray aliases = item.optJSONArray("aliases");
                if (aliases != null) for (int j = 0; j < Math.min(8, aliases.length()); j++)
                    addName(names, aliases.optString(j, ""));
                for (String name : names) {
                    SearchHit h = new SearchHit(name, similarity(query, name),
                            Math.max(0.70, 0.96 - i * 0.05), "wikidata");
                    if (better(h, best)) best = h;
                }
            }
            return best;
        } catch (Exception ex) {
            sourceFailed("wikidata", ex, query);
            return null;
        }
    }

    private static SearchHit searchWikipedia(String query) {
        if (query.isEmpty() || !sourceAvailable("wikipedia")) return null;
        try {
            JSONObject root = readJson(
                    "https://ja.wikipedia.org/w/api.php?action=query&list=search&format=json&utf8=1&srlimit=6&srsearch="
                            + enc(query), "wikipedia", query);
            if (root == null) return null;
            JSONObject q = root.optJSONObject("query");
            JSONArray arr = q == null ? null : q.optJSONArray("search");
            if (arr == null) return null;
            SearchHit best = null;
            for (int i = 0; i < Math.min(6, arr.length()); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item == null) continue;
                String title = cleanCandidate(item.optString("title", ""));
                if (title.isEmpty()) continue;
                SearchHit h = new SearchHit(title, similarity(query, title),
                        Math.max(0.68, 0.94 - i * 0.05), "wikipedia");
                if (better(h, best)) best = h;
            }
            return best;
        } catch (Exception ex) {
            sourceFailed("wikipedia", ex, query);
            return null;
        }
    }

    private static JSONObject readJson(String address, String source, String query) throws Exception {
        if (!reserveHttpRequest(source, query)) return null;
        throttle();
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection)new URL(address).openConnection();
            c.setConnectTimeout(4500);
            c.setReadTimeout(6500);
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
                while ((n = r.read(buf)) > 0 && b.length() < 400000) {
                    b.append(buf, 0, Math.min(n, 400000 - b.length()));
                }
            }
            sourceSucceeded(source);
            return new JSONObject(b.toString());
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static boolean reserveHttpRequest(String source, String query) {
        synchronized (LOCK) {
            if (!enabled || httpRequests >= MAX_HTTP_REQUESTS || !sourceAvailableLocked(source)) return false;
            httpRequests++;
            logLocked("online_entity_query", "source=" + source
                    + ";request=" + httpRequests + ";query=" + brief(query));
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
        if (wait > 0L) {
            try { Thread.sleep(wait); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
    }

    private static void sourceFailed(String source, Exception ex, String query) {
        synchronized (LOCK) {
            sourceErrors++;
            int n = sourceFailureCount.containsKey(source) ? sourceFailureCount.get(source) + 1 : 1;
            sourceFailureCount.put(source, n);
            if (n >= 2) sourceCooldownUntil.put(source, System.currentTimeMillis() + SOURCE_COOLDOWN_MS);
            logLocked("online_entity_source_error", "source=" + source
                    + ";query=" + brief(query)
                    + ";error=" + ex.getClass().getSimpleName()
                    + ";sourceFailures=" + n
                    + ";cooldownMs=" + cooldownRemainingLocked(source));
        }
    }

    private static void sourceSucceeded(String source) {
        synchronized (LOCK) {
            sourceFailureCount.put(source, 0);
            sourceCooldownUntil.put(source, 0L);
        }
    }

    private static boolean sourceAvailable(String source) {
        synchronized (LOCK) { return sourceAvailableLocked(source); }
    }

    private static boolean sourceAvailableLocked(String source) {
        long until = sourceCooldownUntil.containsKey(source) ? sourceCooldownUntil.get(source) : 0L;
        return System.currentTimeMillis() >= until;
    }

    private static long cooldownRemainingLocked(String source) {
        long until = sourceCooldownUntil.containsKey(source) ? sourceCooldownUntil.get(source) : 0L;
        return Math.max(0L, until - System.currentTimeMillis());
    }

    private static ArrayList<Query> extractStrongQueries(String previousText, String segment) {
        ArrayList<Query> out = new ArrayList<>();
        String s = scrubRadioNames(safe(segment).replace('\n', ' ').replace('\r', ' '));
        if (s.trim().isEmpty()) return out;
        boolean music = containsAny(s, "曲", "楽曲", "お聞きください", "聞いていただいた",
                "聞いていただいている", "歌", "アルバム", "バンド", "ギター");

        if (music) {
            // "聞いていただいているのは テレビ大陸音頭で夜について でした"
            Matcher played = Pattern.compile(
                    "(?:聞いていただいたのは|聞いていただいているのは|聞いていただいているのは|それでは|では)[ \\t、]*"
                            + "([^。！？!?]{2,30}?)(?:さん)?[ \\t]*(?:で|の)[ \\t]*"
                            + "([^。！？!?]{2,42}?)(?=です|でした|をお聞き|を聞き|お願いします|どうぞ|$)")
                    .matcher(s);
            while (played.find() && out.size() < 3) {
                addPair(out, played.group(1), played.group(2));
            }

            // "赤い公園の NOW ON AIR", "佐野元春さんの悲しきレイディオ"
            Matcher artistSong = Pattern.compile(
                    "([一-龠々ぁ-んァ-ヶーA-Za-z0-9・･.'’＆& \\t]{2,26}?)(?:さん)?の[ \\t]*"
                            + "([^。！？!?]{2,36}?)(?=です|でした|という曲|という楽曲|を聞|を聴|お願いします|$)")
                    .matcher(s);
            while (artistSong.find() && out.size() < 3) {
                addPair(out, artistSong.group(1), artistSong.group(2));
            }

            // "純烈さんで君が涙をくれる時"
            Matcher artistDeSong = Pattern.compile(
                    "([一-龠々ぁ-んァ-ヶーA-Za-z0-9・･.'’＆& \\t]{2,24}?)(?:さん)?[ \\t]*で[ \\t]*"
                            + "([^。！？!?]{2,36}?)(?=でした|です|お願いします|どうぞ|$)")
                    .matcher(s);
            while (artistDeSong.find() && out.size() < 3) {
                addPair(out, artistDeSong.group(1), artistDeSong.group(2));
            }
        }

        // Named venues and stores are useful even outside music talk.
        Matcher venue = Pattern.compile("([^、。！？!?\\n]{2,34}?(?:アリーナ|ドーム|ホール|シアター|劇場|スタジアム)(?:[ A-Za-z0-9・･ー-]{0,20})?)")
                .matcher(s);
        while (venue.find() && out.size() < 4) addEntity(out, venue.group(1), "venue");

        Matcher coffee = Pattern.compile("([^、。！？!?\\n]{2,24}?(?:コーヒー|珈琲)(?:店)?)")
                .matcher(s);
        while (coffee.find() && out.size() < 4) addEntity(out, coffee.group(1), "shop");

        return out;
    }

    private static void addPair(ArrayList<Query> out, String artist, String title) {
        String a = cleanArtist(artist);
        String t = cleanTitle(title);
        if (!likelyArtist(a) || !likelyTitle(t)) return;
        for (Query q : out) if (q.isPair()
                && normalize(q.artist).equals(normalize(a))
                && normalize(q.title).equals(normalize(t))) return;
        out.add(Query.pair(a, t));
    }

    private static void addEntity(ArrayList<Query> out, String entity, String kind) {
        String x = cleanEntity(entity);
        if (x.length() < 3 || x.length() > 45 || isGeneric(x)) return;
        for (Query q : out) if (!q.entity.isEmpty() && normalize(q.entity).equals(normalize(x))) return;
        out.add(Query.entity(x, kind));
    }

    private static String cleanArtist(String s) {
        String x = cleanCandidate(s);
        x = x.replaceFirst("^(?:はい|それでは|では|続いて|ここで1曲|ここで一曲|私の好きなラジオの曲は)[ \\t、]*", "");
        x = x.replaceFirst("^(?:聞いていただいたのは|聞いていただいているのは)[ \\t、]*", "");
        x = x.replaceFirst("^(?:この|その|あの)[ \\t]+", "");
        return x.trim();
    }

    private static String cleanTitle(String s) {
        String x = cleanCandidate(s);
        String[] stops = {" とのこと", " 恋人", " 主人公", " という", " この曲", " なんですけど",
                " お願いします", " どうぞ", " をお聞き", " を聞き", " を聴き"};
        for (String stop : stops) {
            int p = x.indexOf(stop);
            if (p >= 2) x = x.substring(0, p);
        }
        return x.replaceFirst("^(?:曲は|楽曲は)[ \\t]*", "").trim();
    }

    private static String cleanEntity(String s) {
        String x = cleanCandidate(s);
        // Keep only the last reasonable phrase before the entity suffix, preventing an entire
        // sentence from becoming a venue query.
        if (x.length() > 30) {
            String[] parts = x.split("[ \\t]+");
            StringBuilder b = new StringBuilder();
            for (int i = Math.max(0, parts.length - 5); i < parts.length; i++) {
                if (b.length() > 0) b.append(' ');
                b.append(parts[i]);
            }
            x = b.toString();
        }
        return x.trim();
    }

    private static boolean likelyArtist(String s) {
        String x = cleanCandidate(s);
        if (x.length() < 2 || x.length() > 28 || isGeneric(x)) return false;
        if (containsAny(x, "本当に", "皆さん", "リスナー", "スタッフ", "この番組", "ラジオネーム",
                "ありがとうございます", "続いて", "今回", "先週", "来週", "メール", "お便り")) return false;
        if (x.endsWith("さん") && x.length() <= 5) return false;
        return distinctiveChars(x) >= 2;
    }

    private static boolean likelyTitle(String s) {
        String x = cleanCandidate(s);
        if (x.length() < 2 || x.length() > 42 || isGeneric(x)) return false;
        if (containsAny(x, "ありがとうございます", "よろしくお願いします", "聞いてください",
                "この番組", "ラジオネーム")) return false;
        return distinctiveChars(x) >= 2;
    }

    private static int distinctiveChars(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c) && !Character.isWhitespace(c)) n++;
        }
        return n;
    }

    private static boolean isGeneric(String s) {
        String n = normalize(s);
        String[] generic = {
                "この番組", "ラジオ", "音楽", "楽曲", "この曲", "今回", "今日", "先週", "来週",
                "ありがとうございます", "よろしくお願いします", "皆さん", "本当に", "続いて",
                "メール", "お便り", "ラジオネーム", "永田詩央里", "しおりん", "スタッフさん",
                "神奈川県", "東京都", "大阪府", "秋田県", "北海道"
        };
        for (String g : generic) if (n.equals(normalize(g))) return true;
        return false;
    }

    private static String scrubRadioNames(String s) {
        Matcher m = Pattern.compile("ラジオネーム[ \\t]*[^。！？!?\\n]{1,40}?さん").matcher(s);
        StringBuffer b = new StringBuffer();
        while (m.find()) m.appendReplacement(b, "ラジオネーム [投稿者名]");
        m.appendTail(b);
        return b.toString();
    }

    private static void collectContextTerms(String source, ArrayList<String> out) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        Matcher quoted = Pattern.compile("[「『]([^」』]{2,70})[」』]").matcher(safe(source));
        while (quoted.find()) {
            String x = cleanCandidate(quoted.group(1));
            if (x.length() >= 3 && x.length() <= 70) set.add(x);
        }
        for (String token : safe(source).split("[\\s、。！？!?「」『』（）()/:：|｜]+")) {
            String x = cleanCandidate(token);
            if (x.length() >= 3 && x.length() <= 40 && !isGeneric(x)) set.add(x);
            if (set.size() >= 90) break;
        }
        out.addAll(set);
    }

    private static void applyPageMatchLocked(String wrong, String label) {
        if (wrong.isEmpty() || aliases.containsKey(wrong)) return;
        String best = "";
        double bestSim = 0.0;
        for (String term : contextTerms) {
            if (!sameScriptFamily(wrong, term)) continue;
            double sim = similarity(wrong, term);
            if (sim > bestSim) { bestSim = sim; best = term; }
        }
        if (bestSim >= 0.86 && canStrictReplace(wrong, best)) {
            registerAliasLocked(wrong, best, bestSim, "radiko_page");
            saveCacheLocked(wrong, best, "radiko_page");
            logLocked("online_page_verified", "field=" + label
                    + ";query=" + brief(wrong) + ";canonical=" + brief(best)
                    + ";similarity=" + f3(bestSim));
        }
    }

    private static boolean applyCachedLocked(String wrong, String label) {
        if (wrong.isEmpty()) return true;
        CacheEntry e = loadCacheLocked(wrong);
        if (e == null) return false;
        cacheHits++;
        if (!wrong.equals(e.canonical)) aliases.put(wrong, e.canonical);
        logLocked("online_entity_cache_hit", "field=" + label
                + ";query=" + brief(wrong) + ";canonical=" + brief(e.canonical)
                + ";source=" + e.source);
        return true;
    }

    private static boolean strongPairArtistEvidence(String wrong, String correct, double titleSimilarity) {
        if (wrong.isEmpty() || correct.isEmpty() || wrong.equals(correct)) return false;
        if (!sameScriptFamily(wrong, correct)) return false;
        double sim = similarity(wrong, correct);
        if (sim >= 0.72) return true;
        String a = normalize(wrong), b = normalize(correct);
        if (a.length() >= 3 && b.length() >= 3 && (a.contains(b) || b.contains(a))) return true;
        return titleSimilarity >= 0.92 && commonPrefixChars(wrong, correct) >= 3;
    }

    private static boolean canStrictReplace(String wrong, String correct) {
        String a = cleanCandidate(wrong), b = cleanCandidate(correct);
        if (a.length() < 3 || b.length() < 2 || a.equals(b)) return false;
        if (!sameScriptFamily(a, b)) return false;
        double sim = similarity(a, b);
        if (sim < 0.80) return false;
        double ratio = (double)Math.min(normalize(a).length(), normalize(b).length())
                / Math.max(1, Math.max(normalize(a).length(), normalize(b).length()));
        return ratio >= 0.58;
    }

    private static double commonPrefixRatio(String a, String b) {
        String x = normalize(a), y = normalize(b);
        if (x.isEmpty() || y.isEmpty()) return 0.0;
        int n = 0, max = Math.min(x.length(), y.length());
        while (n < max && x.charAt(n) == y.charAt(n)) n++;
        return (double)n / Math.max(1, Math.min(x.length(), y.length()));
    }

    private static int commonPrefixChars(String a, String b) {
        String x = normalize(a), y = normalize(b);
        int n = 0, max = Math.min(x.length(), y.length());
        while (n < max && x.charAt(n) == y.charAt(n)) n++;
        return n;
    }

    private static boolean sameScriptFamily(String a, String b) {
        int[] x = scriptCounts(a), y = scriptCounts(b);
        boolean aLatin = x[0] >= Math.max(2, x[1] + x[2]);
        boolean bLatin = y[0] >= Math.max(2, y[1] + y[2]);
        return aLatin == bLatin;
    }

    private static int[] scriptCounts(String s) {
        int latin = 0, kana = 0, kanji = 0;
        for (int i = 0; i < safe(s).length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) latin++;
            else if (c >= '\u3040' && c <= '\u30ff') kana++;
            else if (c >= '\u3400' && c <= '\u9fff') kanji++;
        }
        return new int[]{latin, kana, kanji};
    }

    private static String firstArtistCredit(JSONArray credits) {
        if (credits == null || credits.length() == 0) return "";
        JSONObject first = credits.optJSONObject(0);
        if (first == null) return "";
        String name = cleanCandidate(first.optString("name", ""));
        if (!name.isEmpty()) return name;
        JSONObject artist = first.optJSONObject("artist");
        return artist == null ? "" : cleanCandidate(artist.optString("name", ""));
    }

    private static boolean better(SearchHit a, SearchHit b) {
        if (a == null) return false;
        if (b == null) return true;
        return a.similarity * 0.84 + a.remoteScore * 0.16
                > b.similarity * 0.84 + b.remoteScore * 0.16;
    }

    private static void registerAliasLocked(String wrong, String correct, double sim, String source) {
        String a = cleanCandidate(wrong), b = cleanCandidate(correct);
        if (a.length() < 3 || b.length() < 2 || a.equals(b)) return;
        aliases.put(a, b);
        verifiedCount++;
        logLocked("online_entity_alias_ready", "wrong=" + brief(a)
                + ";correct=" + brief(b) + ";similarity=" + f3(sim)
                + ";source=" + source);
    }

    private static CacheEntry loadCacheLocked(String wrong) {
        if (appContext == null) return null;
        String key = hash(normalize(wrong));
        String raw = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, "");
        if (raw == null || raw.isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(raw);
            if (o.optLong("expires", 0L) < System.currentTimeMillis()) return null;
            String canonical = o.optString("canonical", "");
            if (canonical.isEmpty()) return null;
            return new CacheEntry(canonical, o.optString("source", "cache"));
        } catch (Exception ignored) { return null; }
    }

    private static void saveCacheLocked(String wrong, String canonical, String source) {
        if (appContext == null || wrong.isEmpty() || canonical.isEmpty()) return;
        try {
            JSONObject o = new JSONObject();
            o.put("canonical", canonical);
            o.put("source", source);
            o.put("expires", System.currentTimeMillis() + CACHE_MS);
            SharedPreferences.Editor edit = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
            edit.putString(hash(normalize(wrong)), o.toString()).apply();
        } catch (Exception ignored) {}
    }

    private static String hash(String s) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8"));
            StringBuilder b = new StringBuilder();
            for (byte x : bytes) b.append(String.format(Locale.US, "%02x", x & 0xff));
            return b.toString();
        } catch (Exception e) { return Integer.toHexString(s.hashCode()); }
    }

    private static boolean sessionCurrent(int generation) {
        synchronized (LOCK) { return enabled && generation == sessionGeneration; }
    }

    private static boolean sameProgram(String currentProgram) {
        return safe(program).equals(safe(currentProgram));
    }

    private static void addName(Set<String> out, String name) {
        String x = cleanCandidate(name);
        if (x.length() >= 2 && x.length() <= 80) out.add(x);
    }

    private static String cleanCandidate(String s) {
        return safe(s).replaceAll("^[\\s、。！？!?『「（(]+", "")
                .replaceAll("[\\s、。！？!?』」）)]+$", "")
                .replaceAll("[ \\t]{2,}", " ").trim();
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(safe(s), "UTF-8");
    }

    private static double similarity(String a, String b) {
        String x = normalize(a), y = normalize(b);
        if (x.isEmpty() || y.isEmpty()) return 0.0;
        if (x.equals(y)) return 1.0;
        int d = levenshtein(x, y);
        return Math.max(0.0, 1.0 - (double)d / Math.max(x.length(), y.length()));
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
            int[] tmp = prev; prev = cur; cur = tmp;
        }
        return prev[b.length()];
    }

    private static String normalize(String s) {
        return safe(s).toLowerCase(Locale.ROOT)
                .replace('ヶ', 'ケ')
                .replaceAll("[\\s、。！？!?，,.・･:：;；'’\"「」『』()（）_\\-]+", "")
                .trim();
    }

    private static boolean containsAny(String s, String... values) {
        String x = safe(s);
        for (String v : values) if (x.contains(v)) return true;
        return false;
    }

    private static String brief(String s) {
        String x = safe(s).replace('\n', ' ').replace('\r', ' ');
        return x.length() <= 70 ? x : x.substring(0, 70) + "…";
    }

    private static String f3(double v) { return String.format(Locale.US, "%.3f", v); }
    private static String f2(double v) { return String.format(Locale.US, "%.2f", v); }

    private static void logLocked(String kind, String detail) {
        Logger l = logger;
        if (l != null) try { l.log(kind, detail); } catch (Exception ignored) {}
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static final class Query {
        final String artist, title, entity, kind;
        private Query(String artist, String title, String entity, String kind) {
            this.artist = artist; this.title = title; this.entity = entity; this.kind = kind;
        }
        static Query pair(String artist, String title) { return new Query(artist, title, "", "music_pair"); }
        static Query entity(String entity, String kind) { return new Query("", "", entity, kind); }
        boolean isPair() { return !artist.isEmpty() && !title.isEmpty(); }
        String key() { return kind + "|" + normalize(artist) + "|" + normalize(title) + "|" + normalize(entity); }
    }

    private static final class MusicPairHit {
        final String artist, title;
        final double titleSimilarity, remoteScore;
        MusicPairHit(String artist, String title, double titleSimilarity, double remoteScore) {
            this.artist = artist; this.title = title;
            this.titleSimilarity = titleSimilarity; this.remoteScore = remoteScore;
        }
    }

    private static final class SearchHit {
        final String canonical, source;
        final double similarity, remoteScore;
        SearchHit(String canonical, double similarity, double remoteScore, String source) {
            this.canonical = canonical; this.similarity = similarity;
            this.remoteScore = remoteScore; this.source = source;
        }
    }

    private static final class CacheEntry {
        final String canonical, source;
        CacheEntry(String canonical, String source) { this.canonical = canonical; this.source = source; }
    }
}

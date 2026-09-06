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
 * Best-effort, fail-open proper-noun verifier for public radio audio.
 *
 * Important stability/privacy rules:
 * - It never blocks the recognizer/capture threads; all network I/O is on one background worker.
 * - It is disabled for microphone mode.  Only proper-noun-sized fragments from public radio audio
 *   are queried; radio-name slots are scrubbed before extraction.
 * - Failure, timeout or rate limiting simply leaves the transcript untouched.
 * - Automatic replacement requires a close same-script match.  Cross-script search hits may be
 *   logged as hints, but are not blindly substituted.
 * - MusicBrainz is throttled together with all resolver traffic to <= 1 request / 1.1 seconds.
 */
public final class OnlineEntityVerifier {
    public static final String VERSION = "online-entity-verifier-v022-2026-09-06";

    public interface Logger { void log(String kind, String detail); }

    private static final Object LOCK = new Object();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final String PREFS = "online_entity_cache_v1";
    private static final long CACHE_MS = 30L * 24L * 60L * 60L * 1000L;
    private static final long REQUEST_GAP_MS = 1100L;
    private static final int MAX_REMOTE_REQUESTS = 14;
    private static final String USER_AGENT =
            "RadikoTranscriber/0.22 (https://github.com/ponpains/radiko-transcriber2)";

    private static Context appContext;
    private static boolean enabled = false;
    private static int sessionGeneration = 0;
    private static int remoteRequests = 0;
    private static int cacheHits = 0;
    private static int verifiedCount = 0;
    private static int failures = 0;
    private static long disabledUntil = 0L;
    private static long lastRequestAt = 0L;
    private static String program = "";
    private static String episodeContext = "";
    private static Logger logger;
    private static final Set<String> seen = new HashSet<>();
    private static final Map<String, String> verifiedAliases = new HashMap<>();
    private static final ArrayList<String> contextTerms = new ArrayList<>();

    private OnlineEntityVerifier() {}

    public static void beginEpisode(Context context, String currentProgram, String publicEpisodeContext,
                                    boolean allowOnline, Logger diagnosticLogger) {
        synchronized (LOCK) {
            appContext = context == null ? null : context.getApplicationContext();
            program = safe(currentProgram);
            episodeContext = safe(publicEpisodeContext);
            enabled = allowOnline && appContext != null;
            logger = diagnosticLogger;
            sessionGeneration++;
            remoteRequests = 0;
            cacheHits = 0;
            verifiedCount = 0;
            failures = 0;
            disabledUntil = 0L;
            seen.clear();
            verifiedAliases.clear();
            contextTerms.clear();
            collectContextTerms(episodeContext, contextTerms);
            logLocked("online_entity_start", "enabled=" + enabled
                    + ";contextChars=" + episodeContext.length()
                    + ";contextTerms=" + contextTerms.size()
                    + ";version=" + VERSION);
        }
    }

    /** Observes one recognizer result and schedules at most two tiny background lookups. */
    public static void observe(String currentProgram, String previousText, String segment,
                               Logger diagnosticLogger) {
        final ArrayList<EntityCandidate> candidates;
        final int generation;
        synchronized (LOCK) {
            if (diagnosticLogger != null) logger = diagnosticLogger;
            if (!enabled || appContext == null || System.currentTimeMillis() < disabledUntil) return;
            if (!sameProgram(currentProgram)) return;
            candidates = extractCandidates(previousText, segment);
            generation = sessionGeneration;
        }
        int scheduled = 0;
        for (EntityCandidate candidate : candidates) {
            if (scheduled >= 2) break;
            final String key = normalize(candidate.text);
            if (key.length() < 3) continue;
            synchronized (LOCK) {
                if (generation != sessionGeneration || seen.contains(key)) continue;
                seen.add(key);

                String pageMatch = closestContextTerm(candidate.text);
                if (!pageMatch.isEmpty()) {
                    double sim = similarity(candidate.text, pageMatch);
                    if (sim >= 0.84 && canAutoReplace(candidate.text, pageMatch)) {
                        registerAliasLocked(candidate.text, pageMatch, sim, "radiko_page");
                        continue;
                    }
                }

                CacheEntry cached = loadCacheLocked(key);
                if (cached != null) {
                    cacheHits++;
                    if (!cached.canonical.equals(candidate.text)
                            && canAutoReplace(candidate.text, cached.canonical)) {
                        verifiedAliases.put(candidate.text, cached.canonical);
                    }
                    logLocked("online_entity_cache_hit", "kind=" + candidate.kind
                            + ";query=" + brief(candidate.text)
                            + ";canonical=" + brief(cached.canonical)
                            + ";source=" + cached.source);
                    continue;
                }
                if (remoteRequests >= MAX_REMOTE_REQUESTS) continue;
                remoteRequests++;
            }
            scheduled++;
            EXECUTOR.execute(() -> resolveCandidate(generation, candidate));
        }
    }

    /** Applies only aliases that have already passed a high-confidence verifier. */
    public static String refineKnown(String currentProgram, String text) {
        String out = safe(text);
        synchronized (LOCK) {
            if (!sameProgram(currentProgram) || verifiedAliases.isEmpty()) return out;
            for (Map.Entry<String, String> e : verifiedAliases.entrySet()) {
                String wrong = e.getKey(), correct = e.getValue();
                if (wrong.length() >= 3 && !wrong.equals(correct) && out.contains(wrong)) {
                    out = out.replace(wrong, correct);
                }
            }
        }
        return out;
    }

    /** A small N-best score nudge; the audio model remains the primary decision maker. */
    public static double scoreCandidate(String currentProgram, String candidate) {
        String c = safe(candidate);
        double score = 0.0;
        synchronized (LOCK) {
            if (!sameProgram(currentProgram)) return 0.0;
            for (Map.Entry<String, String> e : verifiedAliases.entrySet()) {
                if (c.contains(e.getValue())) score += 3.5;
                if (c.contains(e.getKey()) && !e.getKey().equals(e.getValue())) score -= 2.5;
            }
        }
        return Math.max(-8.0, Math.min(8.0, score));
    }

    public static String stats() {
        synchronized (LOCK) {
            return "requests=" + remoteRequests + ";cacheHits=" + cacheHits
                    + ";verified=" + verifiedCount + ";failures=" + failures;
        }
    }

    private static void resolveCandidate(int generation, EntityCandidate candidate) {
        if (!sessionCurrent(generation)) return;
        try {
            SearchHit hit;
            if ("artist".equals(candidate.kind) || "recording".equals(candidate.kind)) {
                hit = searchMusicBrainz(candidate.text, candidate.kind);
                if (hit == null || hit.similarity < 0.76) {
                    SearchHit wiki = searchWikidata(candidate.text);
                    if (better(wiki, hit)) hit = wiki;
                }
            } else {
                hit = searchWikidata(candidate.text);
            }
            if (!sessionCurrent(generation)) return;

            synchronized (LOCK) {
                if (hit != null) {
                    boolean replace = hit.similarity >= 0.80
                            && hit.remoteScore >= 0.70
                            && canAutoReplace(candidate.text, hit.canonical);
                    logLocked(replace ? "online_entity_verified" : "online_entity_hint",
                            "kind=" + candidate.kind
                                    + ";query=" + brief(candidate.text)
                                    + ";canonical=" + brief(hit.canonical)
                                    + ";similarity=" + String.format(Locale.US, "%.3f", hit.similarity)
                                    + ";remoteScore=" + String.format(Locale.US, "%.2f", hit.remoteScore)
                                    + ";source=" + hit.source
                                    + ";autoReplace=" + replace);
                    saveCacheLocked(normalize(candidate.text), hit.canonical,
                            hit.similarity, hit.source, replace);
                    if (replace) registerAliasLocked(candidate.text, hit.canonical,
                            hit.similarity, hit.source);
                } else {
                    logLocked("online_entity_miss", "kind=" + candidate.kind
                            + ";query=" + brief(candidate.text));
                }
            }
        } catch (Exception ex) {
            synchronized (LOCK) {
                failures++;
                if (failures >= 3) disabledUntil = System.currentTimeMillis() + 5L * 60L * 1000L;
                logLocked("online_entity_error", "kind=" + candidate.kind
                        + ";query=" + brief(candidate.text)
                        + ";error=" + ex.getClass().getSimpleName()
                        + ";failures=" + failures
                        + ";disabledMs=" + Math.max(0L, disabledUntil - System.currentTimeMillis()));
            }
        }
    }

    private static SearchHit searchMusicBrainz(String query, String kind) throws Exception {
        String resource = "artist".equals(kind) ? "artist" : "recording";
        String field = "artist".equals(kind) ? "artist" : "recording";
        String url = "https://musicbrainz.org/ws/2/" + resource + "/?fmt=json&limit=5&query="
                + enc(field + ":\"" + query + "\"");
        JSONObject root = readJson(url, "musicbrainz");
        JSONArray arr = root.optJSONArray("artist".equals(kind) ? "artists" : "recordings");
        if (arr == null) return null;
        SearchHit best = null;
        for (int i = 0; i < Math.min(5, arr.length()); i++) {
            JSONObject item = arr.optJSONObject(i);
            if (item == null) continue;
            double remote = Math.max(0.0, Math.min(1.0, item.optDouble("score", 0.0) / 100.0));
            LinkedHashSet<String> names = new LinkedHashSet<>();
            if ("artist".equals(kind)) {
                addName(names, item.optString("name", ""));
                addName(names, item.optString("sort-name", ""));
                JSONArray aliases = item.optJSONArray("aliases");
                if (aliases != null) {
                    for (int a = 0; a < Math.min(12, aliases.length()); a++) {
                        JSONObject alias = aliases.optJSONObject(a);
                        if (alias != null) addName(names, alias.optString("name", ""));
                    }
                }
            } else {
                addName(names, item.optString("title", ""));
            }
            for (String name : names) {
                double sim = similarity(query, name);
                SearchHit h = new SearchHit(name, sim, remote, "musicbrainz");
                if (better(h, best)) best = h;
            }
        }
        return best;
    }

    private static SearchHit searchWikidata(String query) throws Exception {
        String url = "https://www.wikidata.org/w/api.php?action=wbsearchentities&format=json&language=ja"
                + "&uselang=ja&type=item&limit=7&search=" + enc(query);
        JSONObject root = readJson(url, "wikidata");
        JSONArray arr = root.optJSONArray("search");
        if (arr == null) return null;
        SearchHit best = null;
        for (int i = 0; i < Math.min(7, arr.length()); i++) {
            JSONObject item = arr.optJSONObject(i);
            if (item == null) continue;
            LinkedHashSet<String> names = new LinkedHashSet<>();
            addName(names, item.optString("label", ""));
            JSONObject match = item.optJSONObject("match");
            if (match != null) addName(names, match.optString("text", ""));
            JSONArray aliases = item.optJSONArray("aliases");
            if (aliases != null) {
                for (int a = 0; a < Math.min(10, aliases.length()); a++) addName(names, aliases.optString(a, ""));
            }
            // Search order is a useful but deliberately weak confidence signal.
            double remote = Math.max(0.72, 0.96 - i * 0.05);
            for (String name : names) {
                double sim = similarity(query, name);
                SearchHit h = new SearchHit(name, sim, remote, "wikidata");
                if (better(h, best)) best = h;
            }
        }
        return best;
    }

    private static JSONObject readJson(String address, String source) throws Exception {
        throttle();
        synchronized (LOCK) {
            logLocked("online_entity_query", "source=" + source + ";request=" + remoteRequests);
        }
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection)new URL(address).openConnection();
            c.setConnectTimeout(2800);
            c.setReadTimeout(3800);
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
                while ((n = r.read(buf)) > 0 && b.length() < 350000) {
                    b.append(buf, 0, Math.min(n, 350000 - b.length()));
                }
            }
            return new JSONObject(b.toString());
        } finally {
            if (c != null) c.disconnect();
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
            try { Thread.sleep(wait); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
    }

    private static ArrayList<EntityCandidate> extractCandidates(String previousText, String segment) {
        ArrayList<EntityCandidate> out = new ArrayList<>();
        String s = scrubRadioNames(safe(segment).replace('\n', ' ').replace('\r', ' '));
        if (s.trim().isEmpty()) return out;
        boolean music = containsAny(s, "曲", "楽曲", "音楽", "歌", "アルバム", "ギター", "バンド", "レディオ", "ラジオ");

        Matcher artistSong = Pattern.compile("([一-龠々ぁ-んァ-ヶーA-Za-z0-9・･.'’＆&]{2,28})さんの[ \\t]*([^。！？!?]{2,34}?)(?=です|でした|という|を|お願いします|聞き|聴き|$)").matcher(s);
        if (artistSong.find()) {
            addCandidate(out, artistSong.group(1), "artist");
            addCandidate(out, trimSongTail(artistSong.group(2)), "recording");
        }

        Matcher played = Pattern.compile("(?:それでは|では|聞いていただいたのは|聞いていただいているのは|はい)[ \\t]*([^。！？!?]{2,34}?)[ \\t]*で[ \\t]*([^。！？!?]{2,38}?)(?=です|でした|。|！|？|$)").matcher(s);
        if (played.find()) {
            addCandidate(out, trimArtistLead(played.group(1)), "artist");
            addCandidate(out, trimSongTail(played.group(2)), "recording");
        }

        Matcher person = Pattern.compile("([一-龠々ぁ-んァ-ヶーA-Za-z・･.'’]{2,22})さん").matcher(s);
        while (person.find() && out.size() < 5) addCandidate(out, person.group(1), music ? "artist" : "entity");

        Matcher venue = Pattern.compile("([^\\s、。！？!?]{2,32}(?:アリーナ|ドーム|ホール|劇場|シアター|スタジアム)[^\\s、。！？!?]{0,18})").matcher(s);
        if (venue.find()) addCandidate(out, venue.group(1), "entity");

        if (music && out.size() < 5) {
            for (String token : s.split("[\\s、。！？!?「」『』（）()]+")) {
                String t = cleanCandidate(token);
                if (t.length() < 4 || t.length() > 28) continue;
                if (!looksDistinctive(t) || isStopCandidate(t)) continue;
                addCandidate(out, t, "recording");
                if (out.size() >= 5) break;
            }
        }
        return out;
    }

    private static String scrubRadioNames(String s) {
        String out = s;
        Pattern p = Pattern.compile("ラジオネーム[ \\t]*[^。！？!?]{1,35}?さん");
        Matcher m = p.matcher(out);
        StringBuffer b = new StringBuffer();
        while (m.find()) m.appendReplacement(b, "ラジオネーム [投稿者名]");
        m.appendTail(b);
        return b.toString();
    }

    private static void addCandidate(ArrayList<EntityCandidate> out, String text, String kind) {
        String t = cleanCandidate(text);
        if (t.length() < 3 || t.length() > 36 || isStopCandidate(t)) return;
        String n = normalize(t);
        for (EntityCandidate e : out) if (normalize(e.text).equals(n)) return;
        out.add(new EntityCandidate(t, kind));
    }

    private static String trimArtistLead(String s) {
        String x = cleanCandidate(s);
        x = x.replaceFirst("^(?:はい|それでは|では)[ \\t]*", "");
        return x.trim();
    }

    private static String trimSongTail(String s) {
        String x = cleanCandidate(s);
        String[] stops = {" 恋人", " 主人公", " という", " を聞", " お願い", " とのこと", " この曲", " という曲"};
        for (String stop : stops) {
            int p = x.indexOf(stop);
            if (p >= 2) x = x.substring(0, p);
        }
        return x.trim();
    }

    private static String cleanCandidate(String s) {
        String x = safe(s).replaceAll("^[\\s、。！？!?『「（(]+", "")
                .replaceAll("[\\s、。！？!?』」）)]+$", "")
                .replaceAll("[ \\t]{2,}", " ").trim();
        x = x.replaceFirst("^(?:東京都|大阪府|京都府|北海道|.{2,4}県)[ \\t]+", "");
        return x;
    }

    private static boolean isStopCandidate(String s) {
        String n = normalize(s);
        if (n.isEmpty() || n.matches("[0-9０-９]+")) return true;
        String[] stops = {
                "ありがとうございます", "ありがとうございました", "ラジオネーム", "投稿者名", "お願いします",
                "聞いてください", "聞いてみてください", "よろしくお願いします", "この曲", "楽曲",
                "ラジオ", "音楽", "皆さん", "しおりん", "永田さん", "今日", "今回", "続いて",
                "本当に", "めちゃくちゃ", "という", "みたい", "なんか", "ちょっと"
        };
        for (String stop : stops) if (n.equals(normalize(stop))) return true;
        return false;
    }

    private static boolean looksDistinctive(String s) {
        int katakana = 0, latin = 0, kanji = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '\u30a0' && c <= '\u30ff') katakana++;
            else if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) latin++;
            else if (c >= '\u3400' && c <= '\u9fff') kanji++;
        }
        return katakana >= 4 || latin >= 3 || s.indexOf('・') >= 0 || (kanji >= 2 && s.length() <= 8);
    }

    private static void collectContextTerms(String source, ArrayList<String> out) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        Matcher quoted = Pattern.compile("[「『]([^」』]{2,60})[」』]").matcher(safe(source));
        while (quoted.find()) {
            String x = cleanCandidate(quoted.group(1));
            if (x.length() >= 3 && x.length() <= 60) set.add(x);
        }
        for (String token : safe(source).split("[\\s、。！？!?「」『』（）()/:：|｜]+")) {
            String x = cleanCandidate(token);
            if (x.length() >= 3 && x.length() <= 36 && looksDistinctive(x)) set.add(x);
            if (set.size() >= 80) break;
        }
        out.addAll(set);
    }

    private static String closestContextTerm(String candidate) {
        String best = "";
        double bestScore = 0.0;
        for (String term : contextTerms) {
            if (!sameScriptFamily(candidate, term)) continue;
            double sim = similarity(candidate, term);
            if (sim > bestScore) { bestScore = sim; best = term; }
        }
        return bestScore >= 0.84 ? best : "";
    }

    private static boolean canAutoReplace(String wrong, String correct) {
        String a = cleanCandidate(wrong), b = cleanCandidate(correct);
        if (a.length() < 3 || b.length() < 2 || a.equals(b)) return false;
        if (!sameScriptFamily(a, b)) return false;
        double sim = similarity(a, b);
        if (sim < 0.80) return false;
        double ratio = (double)Math.min(normalize(a).length(), normalize(b).length())
                / Math.max(1, Math.max(normalize(a).length(), normalize(b).length()));
        return ratio >= 0.58;
    }

    private static boolean sameScriptFamily(String a, String b) {
        int[] x = scriptCounts(a), y = scriptCounts(b);
        boolean aLatin = x[0] >= Math.max(2, x[1] + x[2]);
        boolean bLatin = y[0] >= Math.max(2, y[1] + y[2]);
        if (aLatin != bLatin) return false;
        return true;
    }

    private static int[] scriptCounts(String s) {
        int latin = 0, kana = 0, kanji = 0;
        for (int i = 0; i < safe(s).length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) latin++;
            else if ((c >= '\u3040' && c <= '\u30ff')) kana++;
            else if (c >= '\u3400' && c <= '\u9fff') kanji++;
        }
        return new int[]{latin, kana, kanji};
    }

    private static void registerAliasLocked(String wrong, String correct, double similarity, String source) {
        if (!canAutoReplace(wrong, correct)) return;
        verifiedAliases.put(wrong, correct);
        verifiedCount++;
        logLocked("online_entity_alias_ready", "wrong=" + brief(wrong)
                + ";correct=" + brief(correct)
                + ";similarity=" + String.format(Locale.US, "%.3f", similarity)
                + ";source=" + source);
    }

    private static CacheEntry loadCacheLocked(String normalizedKey) {
        if (appContext == null) return null;
        String raw = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(hash(normalizedKey), "");
        if (raw == null || raw.isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(raw);
            long expires = o.optLong("expires", 0L);
            if (expires < System.currentTimeMillis()) return null;
            if (!o.optBoolean("replace", false)) return null;
            String canonical = o.optString("canonical", "");
            if (canonical.isEmpty()) return null;
            return new CacheEntry(canonical, o.optString("source", "cache"));
        } catch (Exception ignored) { return null; }
    }

    private static void saveCacheLocked(String normalizedKey, String canonical,
                                        double similarity, String source, boolean replace) {
        if (appContext == null || normalizedKey.isEmpty() || canonical.isEmpty()) return;
        try {
            JSONObject o = new JSONObject();
            o.put("canonical", canonical);
            o.put("similarity", similarity);
            o.put("source", source);
            o.put("replace", replace);
            o.put("expires", System.currentTimeMillis() + CACHE_MS);
            SharedPreferences.Editor edit = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
            edit.putString(hash(normalizedKey), o.toString()).apply();
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
        synchronized (LOCK) { return generation == sessionGeneration && enabled; }
    }

    private static boolean sameProgram(String currentProgram) {
        return safe(program).equals(safe(currentProgram));
    }

    private static boolean better(SearchHit a, SearchHit b) {
        if (a == null) return false;
        if (b == null) return true;
        double as = a.similarity * 0.78 + a.remoteScore * 0.22;
        double bs = b.similarity * 0.78 + b.remoteScore * 0.22;
        return as > bs;
    }

    private static void addName(Set<String> out, String name) {
        String x = cleanCandidate(name);
        if (x.length() >= 2 && x.length() <= 80) out.add(x);
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(safe(s), "UTF-8");
    }

    private static double similarity(String a, String b) {
        String x = normalize(a), y = normalize(b);
        if (x.isEmpty() || y.isEmpty()) return 0.0;
        if (x.equals(y)) return 1.0;
        int dist = levenshtein(x, y);
        return Math.max(0.0, 1.0 - (double)dist / Math.max(x.length(), y.length()));
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
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

    private static void logLocked(String kind, String detail) {
        Logger l = logger;
        if (l != null) {
            try { l.log(kind, detail); } catch (Exception ignored) {}
        }
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static final class EntityCandidate {
        final String text;
        final String kind;
        EntityCandidate(String text, String kind) { this.text = text; this.kind = kind; }
    }

    private static final class SearchHit {
        final String canonical;
        final double similarity;
        final double remoteScore;
        final String source;
        SearchHit(String canonical, double similarity, double remoteScore, String source) {
            this.canonical = canonical;
            this.similarity = similarity;
            this.remoteScore = remoteScore;
            this.source = source;
        }
    }

    private static final class CacheEntry {
        final String canonical;
        final String source;
        CacheEntry(String canonical, String source) { this.canonical = canonical; this.source = source; }
    }
}

package com.ponpains.shioriarchive;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class ArchiveEvent implements Comparable<ArchiveEvent> {
    public final String id;
    public final LocalDate date;
    public final String time;
    public final String type;
    public final String title;
    public final String summary;
    public final String excerpt;
    public final String sourceName;
    public final String sourceUrl;
    public final String confidence;
    public final List<String> tags;
    public final List<String> people;

    private ArchiveEvent(String id, LocalDate date, String time, String type, String title,
                         String summary, String excerpt, String sourceName, String sourceUrl,
                         String confidence, List<String> tags, List<String> people) {
        this.id = id;
        this.date = date;
        this.time = time;
        this.type = type;
        this.title = title;
        this.summary = summary;
        this.excerpt = excerpt;
        this.sourceName = sourceName;
        this.sourceUrl = sourceUrl;
        this.confidence = confidence;
        this.tags = Collections.unmodifiableList(tags);
        this.people = Collections.unmodifiableList(people);
    }

    public static ArchiveEvent fromJson(JSONObject o) throws JSONException {
        String id = o.getString("id");
        LocalDate date = LocalDate.parse(o.getString("date"));
        String type = o.optString("type", "その他");
        String sourceUrl = sanitizeSourceUrl(type, o.optString("sourceUrl", ""));
        return new ArchiveEvent(
                id,
                date,
                o.optString("time", ""),
                type,
                o.optString("title", ""),
                o.optString("summary", ""),
                o.optString("excerpt", ""),
                o.optString("sourceName", "公開情報"),
                sourceUrl,
                o.optString("confidence", "確認済み"),
                readStrings(o.optJSONArray("tags")),
                readStrings(o.optJSONArray("people"))
        );
    }

    private static String sanitizeSourceUrl(String type, String raw) {
        String url = raw == null ? "" : raw.trim();
        if (!url.startsWith("https://") && !url.startsWith("http://")) return "";
        String lower = url.toLowerCase(Locale.ROOT);

        // X: never call an account/profile page a "source post". Only an individual status is clickable.
        if ("X".equalsIgnoreCase(type)) {
            if (!lower.matches("https?://(www\\.)?(x|twitter)\\.com/nagata_shiori_/status/\\d+.*")) return "";
            return url;
        }

        // Instagram: only individual post / reel / TV URLs are treated as direct source records.
        if ("Instagram".equalsIgnoreCase(type)) {
            if (!lower.matches("https?://(www\\.)?instagram\\.com/(p|reel|tv)/[^/?#]+.*")) return "";
            return url;
        }

        // These are useful discovery/index/channel/profile pages, but not individual-record destinations.
        if (lower.equals("https://nagata-shiohigari.github.io/shiorin/") ||
                lower.startsWith("https://www.showroom-live.com/room/profile") ||
                lower.equals("https://www.instagram.com/nagata__shiori/") ||
                lower.startsWith("https://radiko.jp/podcast/channels/") ||
                lower.equals("https://not-equal-me.jp/schedule/") ||
                lower.equals("https://www.youtube.com/@notequalme_official")) {
            return "";
        }
        return url;
    }

    private static List<String> readStrings(JSONArray a) throws JSONException {
        List<String> out = new ArrayList<>();
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        return out;
    }

    public boolean isX() {
        return "X".equalsIgnoreCase(type);
    }

    public String bodyText() {
        return isX() ? excerpt : summary;
    }

    public boolean matches(String rawQuery) {
        String q = rawQuery == null ? "" : rawQuery.trim().toLowerCase(Locale.JAPANESE);
        if (q.isEmpty()) return true;
        String joined = (date + " " + type + " " + title + " " + summary + " " + excerpt + " " +
                sourceName + " " + String.join(" ", tags) + " " + String.join(" ", people))
                .toLowerCase(Locale.JAPANESE);
        return joined.contains(q);
    }

    @Override
    public int compareTo(ArchiveEvent other) {
        int byDate = other.date.compareTo(this.date);
        if (byDate != 0) return byDate;
        return other.time.compareTo(this.time);
    }
}

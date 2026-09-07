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
    public final String sourceName;
    public final String sourceUrl;
    public final String confidence;
    public final List<String> tags;
    public final List<String> people;

    private ArchiveEvent(String id, LocalDate date, String time, String type, String title,
                         String summary, String sourceName, String sourceUrl, String confidence,
                         List<String> tags, List<String> people) {
        this.id = id;
        this.date = date;
        this.time = time;
        this.type = type;
        this.title = title;
        this.summary = summary;
        this.sourceName = sourceName;
        this.sourceUrl = sourceUrl;
        this.confidence = confidence;
        this.tags = Collections.unmodifiableList(tags);
        this.people = Collections.unmodifiableList(people);
    }

    public static ArchiveEvent fromJson(JSONObject o) throws JSONException {
        String id = o.getString("id");
        LocalDate date = LocalDate.parse(o.getString("date"));
        return new ArchiveEvent(
                id,
                date,
                o.optString("time", ""),
                o.optString("type", "その他"),
                o.getString("title"),
                o.optString("summary", ""),
                o.optString("sourceName", "公開情報"),
                o.optString("sourceUrl", ""),
                o.optString("confidence", "確認済み"),
                readStrings(o.optJSONArray("tags")),
                readStrings(o.optJSONArray("people"))
        );
    }

    private static List<String> readStrings(JSONArray a) throws JSONException {
        List<String> out = new ArrayList<>();
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        return out;
    }

    public boolean matches(String rawQuery) {
        String q = rawQuery == null ? "" : rawQuery.trim().toLowerCase(Locale.JAPANESE);
        if (q.isEmpty()) return true;
        String joined = (date + " " + type + " " + title + " " + summary + " " +
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

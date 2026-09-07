package com.example.radikotranscriber;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Isolates radio-mail addressee lines without separating a lead-in such as "続いて". */
public final class RadioMailLayoutV028 {
    public static final String VERSION = "radio-mail-layout-v028-2026-09-07";

    private RadioMailLayoutV028() {}

    private static final String PREFECTURE =
            "北海道|青森県|岩手県|宮城県|秋田県|山形県|福島県|茨城県|栃木県|群馬県|埼玉県|千葉県|東京都|神奈川県|" +
            "新潟県|富山県|石川県|福井県|山梨県|長野県|岐阜県|静岡県|愛知県|三重県|滋賀県|京都府|大阪府|兵庫県|" +
            "奈良県|和歌山県|鳥取県|島根県|岡山県|広島県|山口県|徳島県|香川県|愛媛県|高知県|福岡県|佐賀県|" +
            "長崎県|熊本県|大分県|宮崎県|鹿児島県|沖縄県";

    private static final String LEAD_IN =
            "続いて|続きまして|それでは続いて|それでは|では続いて|では|次に|次は|はい続いて|はい|さあ続いて|さあ";

    private static final Pattern HEADER = Pattern.compile(
            "(?:(?:" + LEAD_IN + ")[、,]?[ \\t]*)?" +
            "(?:" + PREFECTURE + ")[ \\t]*ラジオネーム[ \\t]*" +
            "[^\\n。！？!?]{1,48}?さん[。！？!?]?"
    );

    public static String apply(String text) {
        String s = normalize(text);
        if (s.isEmpty() || !s.contains("ラジオネーム")) return s;

        Matcher m = HEADER.matcher(s);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String header = tidyHeader(m.group());
            m.appendReplacement(out, Matcher.quoteReplacement("\n" + header + "\n"));
        }
        m.appendTail(out);
        return cleanupLines(out.toString());
    }

    private static String tidyHeader(String header) {
        String x = normalize(header).replaceAll("[。！？!?]+$", "").trim();
        x = x.replaceAll("[ \\t]+", " ");
        x = x.replaceAll("(" + PREFECTURE + ")[ \\t]*ラジオネーム[ \\t]*", "$1 ラジオネーム ");
        x = x.replaceAll("^(" + LEAD_IN + ")[、,]?[ \\t]*", "$1 ");
        return x.trim();
    }

    private static String cleanupLines(String text) {
        String[] lines = normalize(text).split("\\n", -1);
        StringBuilder out = new StringBuilder();
        boolean lastBlank = true;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) {
                if (!lastBlank && out.length() > 0) out.append('\n');
                lastBlank = true;
                continue;
            }
            if (out.length() > 0) out.append('\n');
            out.append(line);
            lastBlank = false;
        }
        return out.toString().replaceAll("\\n{3,}", "\n\n").trim();
    }

    private static String normalize(String s) {
        if (s == null) return "";
        return s.replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("[ \\t]+", " ")
                .replaceAll(" *\\n *", "\n")
                .trim();
    }
}

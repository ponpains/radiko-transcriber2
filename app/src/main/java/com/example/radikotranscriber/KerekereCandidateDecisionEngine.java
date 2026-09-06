package com.example.radikotranscriber;

import java.util.ArrayList;
import java.util.Locale;

/**
 * One decision layer for SpeechRecognizer N-best candidates.
 *
 * Older revisions accumulated many useful scorers in TranscribeService.  v0.26 centralizes them so
 * confidence, show state, current/future official context, learned corrections and online-verified
 * names are compared on the same scale.  It does not invent text: it only ranks candidates the
 * recognizer actually returned.
 */
public final class KerekereCandidateDecisionEngine {
    public static final String VERSION = "candidate-decision-v026-2026-09-06";

    public enum State {
        COLD_OPEN, OPENING, FREE_TALK, MAIL, CORNER, SONG, ANNOUNCEMENT, MAIL_INFO, ENDING
    }

    public static final class Decision {
        public String selected = "";
        public State state = State.FREE_TALK;
        public double bestScore = -1e18;
        public double runnerUpScore = -1e18;
        public double margin = 999.0;
        public float selectedConfidence = -1f;
        public boolean suspicious;
        public String suspiciousReason = "";
        public int selectedIndex = -1;

        public String detail() {
            return "state=" + state
                    + ";score=" + f3(bestScore)
                    + ";runnerUp=" + f3(runnerUpScore)
                    + ";margin=" + f3(margin)
                    + ";confidence=" + (selectedConfidence < 0 ? "unknown" : f3(selectedConfidence))
                    + ";suspicious=" + suspicious
                    + ";reason=" + suspiciousReason;
        }
    }

    private KerekereCandidateDecisionEngine() {}

    public static Decision choose(String program, String previousText, String episodeContext,
                                  ArrayList<String> candidates, float[] confidence,
                                  ArrayList<EpisodeStore.Correction> corrections,
                                  ArrayList<String> biasHints) {
        Decision out = new Decision();
        if (candidates == null || candidates.isEmpty()) return out;

        State state = detectState(previousText, candidates.get(0));
        out.state = state;
        double best = -1e18, second = -1e18;
        int bestIndex = -1;
        String bestText = "";
        float bestConfidence = -1f;

        int limit = Math.min(12, candidates.size());
        for (int i = 0; i < limit; i++) {
            String c = safe(candidates.get(i)).trim();
            if (c.isEmpty()) continue;
            float conf = confidence != null && i < confidence.length ? confidence[i] : -1f;
            double score = conf >= 0 ? conf * 8.0 : 0.0;
            score -= i * 0.07;

            if (corrections != null) {
                for (EpisodeStore.Correction r : corrections) {
                    if (r == null) continue;
                    if (!safe(r.correct).isEmpty() && c.contains(r.correct))
                        score += 2.8 + Math.min(Math.max(0, r.uses), 7) * 0.55;
                    if (!safe(r.wrong).isEmpty() && c.contains(r.wrong))
                        score -= 2.4 + Math.min(Math.max(0, r.uses), 7) * 0.50;
                }
            }
            if (biasHints != null) {
                for (String h0 : biasHints) {
                    String h = safe(h0).trim();
                    if (h.length() < 2 || h.length() > 48) continue;
                    if (c.contains(h)) score += Math.min(2.8, 0.55 + h.length() * 0.08);
                }
            }

            // Existing program knowledge, now on one comparable scale.
            score += KerekereContextProfile.scoreCandidate(program, c, previousText) * 0.75;
            score += KerekereContextBoost.scoreCandidate(program, c, previousText) * 0.72;
            score += KerekereProgramStructure.scoreCandidate(program, c, previousText, episodeContext) * 0.78;
            score += KerekereVerifiedLexicon.scoreCandidate(program, c, previousText) * 0.78;
            score += KerekereObservedCorrectionsV023.scoreCandidate(program, c, previousText) * 0.75;
            score += KerekereObservedCorrectionsV024.scoreCandidate(program, c, previousText) * 0.78;
            score += KerekereOfficialKnowledge.scoreCandidate(program, c, previousText) * 0.85;
            score += KerekereObservedCorrectionsV025.scoreCandidate(program, c, previousText) * 0.82;
            score += KerekereFutureScheduleContext.scoreCandidate(program, c, previousText) * 0.90;
            score += OnlineEntityVerifierV4.scoreCandidate(program, c) * 0.70;
            score += OnlineMusicTitleFallbackV026.scoreCandidate(program, c) * 0.78;
            score += stateScore(state, program, previousText, c);
            score += languagePlausibility(c, state);
            score += Math.min(c.length(), 180) * 0.0015;

            if (score > best) {
                second = best;
                best = score;
                bestText = c;
                bestIndex = i;
                bestConfidence = conf;
            } else if (score > second) {
                second = score;
            }
        }

        if (bestIndex < 0) {
            bestIndex = 0;
            bestText = safe(candidates.get(0)).trim();
        }
        out.selected = bestText;
        out.selectedIndex = bestIndex;
        out.bestScore = best;
        out.runnerUpScore = second;
        out.margin = second <= -1e17 ? 999.0 : best - second;
        out.selectedConfidence = bestConfidence;

        String reason = suspiciousReason(state, previousText, bestText, out.margin, bestConfidence);
        out.suspicious = !reason.isEmpty();
        out.suspiciousReason = reason;
        return out;
    }

    public static State detectState(String previousText, String candidate) {
        String prev = safe(previousText);
        String c = safe(candidate);
        String tail = tail(prev, 1500);
        String x = tail + " " + c;

        if (prev.length() < 220 && containsAny(x, "こんばんは", "こんばんハタハタ", "永田詩央里"))
            return State.COLD_OPEN;
        if (containsAny(c, "この番組はアイドルグループ", "パーソナリティを務める", "金曜の夜")
                || containsAny(tail, "そろそろ始めていきましょう", "そろそろ始めて行きましょう"))
            return State.OPENING;
        if (containsAny(x, "ここまでのお相手", "最後まで聞いていただき", "来週も聞いてけれ"))
            return State.ENDING;
        if (containsAny(tail, "番組公式ハッシュタグ", "郵便番号010", "郵便番号 010", "過去回は",
                "ABSラジオ公式サイト", "専用メールフォーム", "けれけれ係"))
            return State.MAIL_INFO;
        if (containsAny(tail, "お知らせです", "お知らせがあります", "開催が決定", "出演します")
                || (containsAny(x, "2026年", "2027年", "月")
                    && containsAny(x, "ライブ", "イベント", "公演", "会場", "発売", "シングル")))
            return State.ANNOUNCEMENT;
        if (containsAny(x, "ここで1曲", "ここで一曲", "聞いていただいたのは", "聞いていただいているのは",
                "ラジオにまつわる曲", "楽曲", "曲を聞いて", "曲を聴いて"))
            return State.SONG;
        if (containsAny(x, "こちらのコーナー", "コーナーでした", "しおりん聞いてけれ", "秋田のしおり"))
            return State.CORNER;
        if (containsAny(c, "ラジオネーム", "都 ラジオネーム", "県 ラジオネーム", "府 ラジオネーム",
                "道 ラジオネーム") || recentRadioNameSlot(tail))
            return State.MAIL;
        return State.FREE_TALK;
    }

    public static boolean looksSuspiciousText(State state, String text) {
        return !suspiciousReason(state, "", safe(text), 999.0, 1.0f).isEmpty();
    }

    private static double stateScore(State state, String program, String previous, String c) {
        if (!KerekereContextProfile.applies(program)) return 0.0;
        double s = 0.0;
        switch (state) {
            case COLD_OPEN:
                if (c.contains("永田詩央里")) s += 8.0;
                if (c.contains("ノットイコールミー")) s += 6.0;
                if (c.contains("こんばんハタハタ")) s += 7.0;
                if (containsAny(c, "のて私より", "のてしおり", "ノッテコール")) s -= 7.0;
                break;
            case OPENING:
                if (c.contains("ノットイコールミー")) s += 7.0;
                if (c.contains("永田詩央里")) s += 8.0;
                if (c.contains("けれけれ")) s += 7.0;
                if (c.contains("永田ラジオ")) s += 5.0;
                break;
            case MAIL:
                if (c.contains("ラジオネーム")) s += 2.5;
                if (containsAny(c, "ななお", "ガンバレないわ", "しゃかかな")) s += 9.0;
                if (containsAny(c, "七尾", "頑張れないわ", "頑張らないわ", "釈迦かな", "釈迦 かな")) s -= 6.0;
                break;
            case CORNER:
                if (c.contains("しおりん聞いてけれ")) s += 6.0;
                if (c.contains("秋田のしおり")) s += 6.0;
                if (containsAny(c, "しおり 聞いてけ", "しおりん 聞いてくれ", "キレキレ")) s -= 4.0;
                break;
            case SONG:
                if (containsAny(c, "HALCALI", "今日の私はキゲンがいい", "THE MODS", "ごきげんRADIO",
                        "ミッシェル・ガン・エレファント", "Go Go Round This World!")) s += 6.0;
                if (containsAny(c, "はるかり", "ザモッツァ", "ミシェルガ エレファント")) s -= 4.0;
                break;
            case ANNOUNCEMENT:
                // Official/current terms should dominate generic homophones in announcement slots.
                for (String term : KerekereFutureScheduleContext.biasTerms(program)) {
                    if (term.length() >= 3 && c.contains(term)) s += Math.min(5.0, 1.5 + term.length() * 0.08);
                }
                if (containsAny(c, "Kアリーナ横浜", "LaLa arena TOKYO-BAY", "東京ビッグサイト")) s += 5.0;
                if (containsAny(c, "キアアリーナ", "県アリーナ", "ニコラブ", "にゃちゃん")) s -= 4.0;
                break;
            case MAIL_INFO:
                if (c.contains("永田ラジオ")) s += 5.0;
                if (c.contains("010-8611")) s += 5.0;
                if (c.contains("けれけれ係")) s += 6.0;
                if (c.contains("radikoポッドキャスト")) s += 5.0;
                break;
            case ENDING:
                if (c.contains("ここまでのお相手")) s += 6.0;
                if (c.contains("永田詩央里")) s += 7.0;
                if (c.contains("来週も聞いてけれ")) s += 8.0;
                if (containsAny(c, "持っていこう しおり", "聞いてけです", "ポケモン 聞いて")) s -= 8.0;
                break;
            case FREE_TALK:
            default:
                break;
        }
        return s;
    }

    private static double languagePlausibility(String c, State state) {
        double s = 0.0;
        String x = safe(c);
        if (x.matches(".*[ぁ-んァ-ヶ一-龠々][ ]+[ぁ-んァ-ヶ一-龠々][ ]+[ぁ-んァ-ヶ一-龠々].*")) s -= 0.6;
        if (x.matches(".*(?:ということで){2,}.*")) s -= 1.0;
        if (x.matches(".*(.{4,18})[ \\t]+\\1.*")) s -= 1.2;
        if (x.length() == 1 && !containsAny(x, "あ", "え", "ん")) s -= 3.5;
        if (containsAny(x, "乗ってくるみ", "長田詩織", "中田詩織", "持っていこう しおり")) s -= 4.5;
        if (state == State.SONG && containsAny(x, "修理は", "修理が")) s -= 2.0;
        return s;
    }

    private static String suspiciousReason(State state, String previous, String c,
                                           double margin, float confidence) {
        ArrayList<String> reasons = new ArrayList<>();
        if (confidence >= 0 && confidence < 0.78f) reasons.add("low_confidence");
        if (margin < 0.12) reasons.add("candidate_tie");
        if (c.length() <= 2 && !containsAny(c, "はい", "ね", "あ", "え", "うん")) reasons.add("tiny_fragment");
        if (containsAny(c, "乗ってくるみ", "長田詩織", "中田詩織", "のて私より", "持っていこう しおり"))
            reasons.add("known_bad_form");
        if (state == State.SONG && containsAny(c, "はるかり", "ザモッツァ", "ミシェルガ エレファント", "修理は"))
            reasons.add("music_entity");
        if (state == State.ANNOUNCEMENT && containsAny(c, "キアアリーナ", "県アリーナ", "ニコラブ", "にゃちゃん"))
            reasons.add("announcement_entity");
        if (state == State.ENDING && containsAny(c, "聞いてけです", "ポケモン 聞いて", "持っていこう"))
            reasons.add("ending_mismatch");
        if (c.matches(".*[一-龠々]$" ) && c.length() >= 8) reasons.add("clipped_kanji_end");
        return join(reasons, ",");
    }

    private static boolean recentRadioNameSlot(String tail) {
        int i = tail.lastIndexOf("ラジオネーム");
        return i >= 0 && tail.length() - i <= 110;
    }

    private static String join(ArrayList<String> items, String sep) {
        StringBuilder b = new StringBuilder();
        for (String x : items) {
            if (b.length() > 0) b.append(sep);
            b.append(x);
        }
        return b.toString();
    }

    private static String tail(String s, int max) {
        String x = safe(s);
        return x.length() <= max ? x : x.substring(x.length() - max);
    }

    private static boolean containsAny(String s, String... values) {
        String x = safe(s);
        for (String v : values) if (v != null && !v.isEmpty() && x.contains(v)) return true;
        return false;
    }

    private static String f3(double x) { return String.format(Locale.US, "%.3f", x); }
    private static String safe(String s) { return s == null ? "" : s; }
}

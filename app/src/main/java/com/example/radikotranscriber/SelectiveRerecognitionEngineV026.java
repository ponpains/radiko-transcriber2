package com.example.radikotranscriber;

import android.content.ContentValues;
import android.content.Context;
import android.media.AudioFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.io.File;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;

/**
 * Re-recognizes only a few suspicious short intervals after the main PCM spool has completely
 * drained.  It never competes with the live recognizer and never changes capture/replay state.
 */
public final class SelectiveRerecognitionEngineV026 {
    public static final String VERSION = "selective-rerecognition-v026-2026-09-06";

    public interface Logger { void log(String kind, String detail); }
    public interface Completion { void onComplete(int attempted, int applied); }

    private static final int SAMPLE_RATE = 16000;
    private static final int MAX_ATTEMPTS = 3;
    private static final long MAX_MEDIA_INTERVAL_MS = 12500L;
    private static final long MAX_TOTAL_RAW_MS = 22000L;
    private static final long PAD_MS = 550L;
    private static final double FEED_RATE = 1.28;

    private final ArrayList<Suspect> suspects = new ArrayList<>();

    public void reset() { suspects.clear(); }

    public void noteSegment(long segmentId, long startMs, long endMs, String committedText,
                            KerekereCandidateDecisionEngine.Decision decision) {
        if (segmentId <= 0 || decision == null) return;
        long duration = Math.max(0L, endMs - startMs);
        String text = safe(committedText).trim();
        if (text.isEmpty() || duration <= 350L || duration > MAX_MEDIA_INTERVAL_MS) return;

        boolean suspicious = decision.suspicious
                || KerekereCandidateDecisionEngine.looksSuspiciousText(decision.state, text);
        if (!suspicious) return;

        int priority = 0;
        if (decision.suspiciousReason.contains("known_bad_form")) priority += 8;
        if (decision.suspiciousReason.contains("music_entity")) priority += 7;
        if (decision.suspiciousReason.contains("announcement_entity")) priority += 7;
        if (decision.suspiciousReason.contains("ending_mismatch")) priority += 6;
        if (decision.suspiciousReason.contains("clipped_kanji_end")) priority += 4;
        if (decision.suspiciousReason.contains("candidate_tie")) priority += 2;
        if (decision.suspiciousReason.contains("low_confidence")) priority += 3;
        if (decision.state == KerekereCandidateDecisionEngine.State.SONG
                || decision.state == KerekereCandidateDecisionEngine.State.ANNOUNCEMENT) priority += 3;
        priority += Math.max(0, 5 - (int)Math.round(Math.min(5.0, decision.margin)));

        for (Suspect old : suspects) {
            if (old.segmentId == segmentId) {
                if (priority > old.priority) old.priority = priority;
                return;
            }
        }
        Suspect s = new Suspect();
        s.segmentId = segmentId;
        s.startMs = startMs;
        s.endMs = endMs;
        s.original = text;
        s.state = decision.state;
        s.primaryScore = decision.bestScore;
        s.reason = decision.suspiciousReason;
        s.priority = priority;
        suspects.add(s);
        if (suspects.size() > 18) {
            suspects.sort((a, b) -> Integer.compare(b.priority, a.priority));
            while (suspects.size() > 18) suspects.remove(suspects.size() - 1);
        }
    }

    public int queuedCount() { return suspects.size(); }

    public void run(Context context, File spoolFile, long mediaStartMs, float playbackSpeed,
                    String program, String episodeContext, EpisodeStore store, long episodeId,
                    Logger logger, Completion completion) {
        final Handler main = new Handler(Looper.getMainLooper());
        if (context == null || spoolFile == null || !spoolFile.exists() || store == null || episodeId <= 0) {
            main.post(() -> completion.onComplete(0, 0));
            return;
        }

        ArrayList<Suspect> selected = chooseSuspects(playbackSpeed);
        if (selected.isEmpty()) {
            if (logger != null) logger.log("rerecognition_plan", "queued=" + suspects.size() + ";selected=0");
            main.post(() -> completion.onComplete(0, 0));
            return;
        }
        if (logger != null) logger.log("rerecognition_plan", "queued=" + suspects.size()
                + ";selected=" + selected.size() + ";feedRate=" + FEED_RATE);

        Runner runner = new Runner(context.getApplicationContext(), spoolFile, mediaStartMs,
                Math.max(1.0f, playbackSpeed), program, episodeContext, store, episodeId,
                selected, logger, completion, main);
        main.post(runner::next);
    }

    /** Rebuilds text from post-rerecognition segments before the full-text proofreader runs. */
    public static String rebuildFromSegments(EpisodeStore store, long episodeId, String program) {
        ArrayList<EpisodeStore.Segment> segments = store.listSegments(episodeId);
        StringBuilder out = new StringBuilder();
        for (EpisodeStore.Segment seg : segments) {
            String text = ContextCorrectionEngine.refine(program, tail(out.toString(), 1400), safe(seg.text));
            text = TranscriptSentenceFormatter.formatBlock(text, true);
            if (text.isEmpty()) continue;
            if (out.length() > 0) {
                if (seg.topicBreak) {
                    if (!endsWith(out, "\n\n")) out.append(out.toString().endsWith("\n") ? "\n" : "\n\n");
                } else if (!out.toString().endsWith("\n")) out.append('\n');
            }
            out.append(text);
        }
        return out.toString().trim();
    }

    private ArrayList<Suspect> chooseSuspects(float playbackSpeed) {
        ArrayList<Suspect> sorted = new ArrayList<>(suspects);
        sorted.sort((a, b) -> {
            int p = Integer.compare(b.priority, a.priority);
            if (p != 0) return p;
            return Long.compare(a.endMs - a.startMs, b.endMs - b.startMs);
        });
        ArrayList<Suspect> out = new ArrayList<>();
        long totalRaw = 0L;
        for (Suspect s : sorted) {
            if (out.size() >= MAX_ATTEMPTS) break;
            boolean overlaps = false;
            for (Suspect chosen : out) {
                if (Math.max(s.startMs, chosen.startMs) < Math.min(s.endMs, chosen.endMs)) {
                    overlaps = true; break;
                }
            }
            if (overlaps) continue;
            long rawMs = Math.round((s.endMs - s.startMs) / Math.max(1.0, playbackSpeed));
            if (totalRaw + rawMs > MAX_TOTAL_RAW_MS) continue;
            totalRaw += rawMs;
            out.add(s);
        }
        return out;
    }

    private static final class Runner {
        final Context context;
        final File spoolFile;
        final long mediaStartMs;
        final float playbackSpeed;
        final String program;
        final String episodeContext;
        final EpisodeStore store;
        final long episodeId;
        final ArrayList<Suspect> list;
        final Logger logger;
        final Completion completion;
        final Handler main;
        int index = 0, attempted = 0, applied = 0;
        SpeechRecognizer recognizer;
        ParcelFileDescriptor readFd;
        OutputStream writer;
        Runnable timeout;
        boolean finishedCurrent;

        Runner(Context context, File spoolFile, long mediaStartMs, float playbackSpeed,
               String program, String episodeContext, EpisodeStore store, long episodeId,
               ArrayList<Suspect> list, Logger logger, Completion completion, Handler main) {
            this.context = context; this.spoolFile = spoolFile; this.mediaStartMs = mediaStartMs;
            this.playbackSpeed = playbackSpeed; this.program = safe(program);
            this.episodeContext = safe(episodeContext); this.store = store; this.episodeId = episodeId;
            this.list = list; this.logger = logger; this.completion = completion; this.main = main;
        }

        void next() {
            cleanup();
            if (index >= list.size()) {
                if (logger != null) logger.log("rerecognition_complete", "attempted=" + attempted + ";applied=" + applied);
                completion.onComplete(attempted, applied);
                return;
            }
            Suspect s = list.get(index++);
            attempted++;
            finishedCurrent = false;
            try {
                ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
                readFd = pipe[0];
                writer = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]);
                recognizer = SpeechRecognizer.createSpeechRecognizer(context);
                recognizer.setRecognitionListener(listenerFor(s));

                android.content.Intent ri = new android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                ri.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                ri.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP");
                ri.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
                ri.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 12);
                ri.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readFd);
                ri.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1);
                ri.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
                ri.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE);
                ri.putExtra(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, false);
                ri.putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS,
                        store.getBiasStrings(program, episodeContext + " " + KerekereFutureScheduleContext.biasTerms(program)));
                ri.putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY);

                if (logger != null) logger.log("rerecognition_start", "segmentId=" + s.segmentId
                        + ";startMs=" + s.startMs + ";endMs=" + s.endMs
                        + ";state=" + s.state + ";reason=" + s.reason);
                recognizer.startListening(ri);
                timeout = () -> finishOne(s, null, "timeout");
                main.postDelayed(timeout, 17000L);
            } catch (Exception ex) {
                if (logger != null) logger.log("rerecognition_error", "segmentId=" + s.segmentId
                        + ";stage=start;error=" + ex.getClass().getSimpleName());
                main.postDelayed(this::next, 250L);
            }
        }

        RecognitionListener listenerFor(final Suspect s) {
            return new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) { feedRange(s); }
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float rmsdB) {}
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onEndOfSpeech() {}
                @Override public void onError(int error) { finishOne(s, null, "error_" + error); }
                @Override public void onResults(Bundle results) { finishOne(s, results, "results"); }
                @Override public void onPartialResults(Bundle partialResults) {}
                @Override public void onEvent(int eventType, Bundle params) {}
                @Override public void onSegmentResults(Bundle segmentResults) { finishOne(s, segmentResults, "segment"); }
                @Override public void onEndOfSegmentedSession() {}
                @Override public void onLanguageDetection(Bundle results) {}
            };
        }

        void feedRange(final Suspect s) {
            final OutputStream target = writer;
            new Thread(() -> {
                if (target == null) return;
                long rawStartMs = Math.max(0L, Math.round((s.startMs - mediaStartMs) / playbackSpeed) - PAD_MS);
                long rawEndMs = Math.max(rawStartMs + 400L,
                        Math.round((s.endMs - mediaStartMs) / playbackSpeed) + PAD_MS);
                long startByte = bytesForMs(rawStartMs);
                long endByte = Math.min(spoolFile.length(), bytesForMs(rawEndMs));
                if (endByte <= startByte) {
                    try { target.close(); } catch (Exception ignored) {}
                    return;
                }
                byte[] buf = new byte[4096];
                try (RandomAccessFile raf = new RandomAccessFile(spoolFile, "r")) {
                    raf.seek(startByte);
                    long remain = endByte - startByte;
                    while (remain > 0 && !finishedCurrent) {
                        int n = raf.read(buf, 0, (int)Math.min(buf.length, remain));
                        if (n <= 0) break;
                        target.write(buf, 0, n);
                        remain -= n;
                        double audioMs = n * 1000.0 / (SAMPLE_RATE * 2.0);
                        long pause = Math.round(audioMs / FEED_RATE);
                        if (pause > 0) Thread.sleep(Math.min(150L, pause));
                    }
                    if (!finishedCurrent) {
                        byte[] silence = new byte[(int)bytesForMs(280L)];
                        target.write(silence);
                        target.flush();
                    }
                } catch (Exception ex) {
                    if (logger != null) logger.log("rerecognition_error", "segmentId=" + s.segmentId
                            + ";stage=feed;error=" + ex.getClass().getSimpleName());
                } finally {
                    try { target.close(); } catch (Exception ignored) {}
                }
            }, "SelectiveRerecognitionFeed").start();
        }

        void finishOne(Suspect s, Bundle results, String source) {
            if (finishedCurrent) return;
            finishedCurrent = true;
            if (timeout != null) main.removeCallbacks(timeout);

            boolean accepted = false;
            String chosen = "";
            if (results != null) {
                ArrayList<String> candidates = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                float[] confidence = results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES);
                if (candidates != null && !candidates.isEmpty()) {
                    String previous = contextBefore(store, episodeId, s.startMs);
                    KerekereCandidateDecisionEngine.Decision d = KerekereCandidateDecisionEngine.choose(
                            program, previous, episodeContext, candidates, confidence,
                            store.getCorrections(program), store.getBiasStrings(program, episodeContext));
                    chosen = ContextCorrectionEngine.refine(program, previous, d.selected);
                    chosen = OnlineMusicTitleFallbackV026.refineKnown(program, chosen);
                    double similarity = similarity(s.original, chosen);
                    double oldQuality = quality(s.original, s.state);
                    double newQuality = quality(chosen, d.state);
                    boolean related = similarity >= (s.original.length() <= 35 ? 0.38 : 0.50);
                    boolean better = newQuality >= oldQuality + 1.0
                            || (!d.suspicious && d.bestScore >= s.primaryScore + 0.35);
                    accepted = related && better && chosen.length() >= 2 && chosen.length() <= 220;
                    if (logger != null) logger.log("rerecognition_result", "segmentId=" + s.segmentId
                            + ";source=" + source + ";similarity=" + f3(similarity)
                            + ";oldQuality=" + f3(oldQuality) + ";newQuality=" + f3(newQuality)
                            + ";decision=" + d.detail() + ";accepted=" + accepted
                            + ";text=" + brief(chosen));
                }
            } else if (logger != null) {
                logger.log("rerecognition_result", "segmentId=" + s.segmentId + ";source=" + source + ";accepted=false");
            }

            if (accepted) {
                ContentValues v = new ContentValues();
                v.put("text", chosen.trim());
                int n = store.getWritableDatabase().update("segments", v, "id=? AND episode_id=?",
                        new String[]{String.valueOf(s.segmentId), String.valueOf(episodeId)});
                if (n > 0) applied++;
            }
            cleanup();
            main.postDelayed(this::next, 280L);
        }

        void cleanup() {
            if (timeout != null) { main.removeCallbacks(timeout); timeout = null; }
            try { if (writer != null) writer.close(); } catch (Exception ignored) {}
            writer = null;
            try { if (readFd != null) readFd.close(); } catch (Exception ignored) {}
            readFd = null;
            if (recognizer != null) {
                try { recognizer.cancel(); } catch (Exception ignored) {}
                try { recognizer.destroy(); } catch (Exception ignored) {}
            }
            recognizer = null;
        }
    }

    private static double quality(String text, KerekereCandidateDecisionEngine.State state) {
        String x = safe(text);
        double q = Math.min(2.0, x.length() / 30.0);
        if (containsAny(x, "ノットイコールミー", "永田詩央里", "けれけれ", "HALCALI",
                "THE MODS", "ごきげんRADIO", "Kアリーナ横浜", "010-8611")) q += 2.0;
        if (containsAny(x, "乗ってくるみ", "のて私より", "持っていこう しおり", "はるかり",
                "ザモッツァ", "キアアリーナ", "ニコラブ", "にゃちゃん", "ポケモン 聞いて")) q -= 3.0;
        if (KerekereCandidateDecisionEngine.looksSuspiciousText(state, x)) q -= 1.4;
        if (x.length() <= 2) q -= 1.0;
        return q;
    }

    private static String contextBefore(EpisodeStore store, long episodeId, long startMs) {
        StringBuilder b = new StringBuilder();
        for (EpisodeStore.Segment seg : store.listSegments(episodeId)) {
            if (seg.startMs >= startMs) break;
            if (b.length() > 0) b.append('\n');
            b.append(safe(seg.text));
            if (b.length() > 1800) b.delete(0, b.length() - 1800);
        }
        return b.toString();
    }

    private static long bytesForMs(long ms) {
        long b = Math.round(Math.max(0L, ms) * SAMPLE_RATE * 2.0 / 1000.0);
        return b - (b & 1L);
    }

    private static boolean endsWith(StringBuilder b, String suffix) {
        if (b.length() < suffix.length()) return false;
        for (int i = 0; i < suffix.length(); i++)
            if (b.charAt(b.length() - suffix.length() + i) != suffix.charAt(i)) return false;
        return true;
    }

    private static double similarity(String a, String b) {
        String x = compact(a), y = compact(b);
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

    private static String compact(String s) {
        return safe(s).replaceAll("[\\s、。！？!?，,.・･:：;；'’\"「」『』()（）_\\-]+", "");
    }

    private static boolean containsAny(String s, String... values) {
        String x = safe(s);
        for (String v : values) if (x.contains(v)) return true;
        return false;
    }

    private static String tail(String s, int max) {
        String x = safe(s);
        return x.length() <= max ? x : x.substring(x.length() - max);
    }

    private static String brief(String s) {
        String x = safe(s).replace('\n', ' ').replace('\r', ' ');
        return x.length() <= 120 ? x : x.substring(0, 120) + "…";
    }

    private static String f3(double x) { return String.format(Locale.US, "%.3f", x); }
    private static String safe(String s) { return s == null ? "" : s; }

    private static final class Suspect {
        long segmentId, startMs, endMs;
        String original = "", reason = "";
        KerekereCandidateDecisionEngine.State state = KerekereCandidateDecisionEngine.State.FREE_TALK;
        double primaryScore;
        int priority;
    }
}

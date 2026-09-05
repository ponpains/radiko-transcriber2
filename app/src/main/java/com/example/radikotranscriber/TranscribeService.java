package com.example.radikotranscriber;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.media.projection.*;
import android.os.*;
import android.speech.*;

import androidx.core.app.NotificationCompat;

import java.io.*;
import java.util.ArrayList;
import java.util.Locale;

public class TranscribeService extends Service {
    public static final String ACTION_START_INTERNAL = "com.example.radikotranscriber.START_INTERNAL";
    public static final String ACTION_START_MIC = "com.example.radikotranscriber.START_MIC";
    public static final String ACTION_STOP = "com.example.radikotranscriber.STOP";
    public static final String ACTION_UPDATE = "com.example.radikotranscriber.UPDATE";

    private static final String CHANNEL = "transcribe";
    private static final int NOTIFY_ID = 606;
    private static final int SAMPLE_RATE = 16000;
    private static final long WATCHDOG_INTERVAL_MS = 2000L;
    private static final long AUTO_STOP_SILENCE_MS = 25000L;
    private static final long AUTO_STOP_MIN_CAPTURE_MS = 60000L;

    private final Object pipeLock = new Object();
    private final Object spoolLock = new Object();
    private final Object breakLock = new Object();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private MediaProjection projection;
    private AudioRecord recorder;
    private SpeechRecognizer recognizer;
    private ParcelFileDescriptor readFd, writeFd;
    private OutputStream pipeOut;
    private Thread captureThread;
    private Thread feederThread;
    private PowerManager.WakeLock wakeLock;
    private EpisodeStore store;
    private DiagnosticStore diagnostics;

    private volatile boolean running = false;
    private volatile boolean captureActive = false;
    private volatile boolean sourceFinished = false;
    private volatile boolean drainComplete = false;
    private volatile int peak = 0;
    private volatile long bytes = 0;
    private volatile long firstAudibleAt = 0;
    private volatile long lastAudibleAt = 0;
    private volatile long silenceStartedAt = 0;
    private volatile boolean pendingParagraphBreak = false;
    private volatile boolean pendingSentenceBreak = false;
    private volatile long spoolBytesWritten = 0L;
    private volatile long spoolBytesConsumed = 0L;
    private volatile long frozenDurationMs = 0L;
    private volatile long lastDiagnosticAudioAt = 0L;
    private volatile long broadcastSequence = 0L;

    private File spoolFile;
    private OutputStream spoolOut;

    private static class BreakMarker {
        long rawByteOffset;
        boolean paragraph;
        BreakMarker(long rawByteOffset, boolean paragraph) {
            this.rawByteOffset = rawByteOffset;
            this.paragraph = paragraph;
        }
    }
    private final ArrayList<BreakMarker> breakMarkers = new ArrayList<>();
    private int nextBreakMarker = 0;

    private String rawText = "";
    private String finalText = "";
    private String partialText = "";
    private String lastCommitted = "";
    private String mode = "internal";
    private String program = "";
    private String episodeTitle = "";
    private String episodeHints = "";
    private String episodeLabel = "文字起こし中";
    private long currentEpisodeId = -1L;
    private long mediaStartMs = 0L;
    private long lastSegmentEndMs = 0L;
    private long lastCommitWallAt = 0L;
    private FormatLearningStore.Profile formatProfile = new FormatLearningStore.Profile();

    private float playbackSpeed = 1.0f;
    private boolean autoStopEnabled = true;
    private int recognizerGeneration = 0;
    private int reconnectCount = 0;
    private int permissionErrorRetries = 0;
    private boolean restartScheduled = false;
    private boolean finishScheduled = false;
    private long recognizerSessionStartedAt = 0L;
    private long lastRecognizerCallbackAt = 0L;

    private final Runnable finishAfterDrain = new Runnable() {
        @Override public void run() {
            finishScheduled = false;
            if (!running || !drainComplete) return;
            flushPartialForRecovery();
            stopEverything("残りの音声まで文字起こしし、保存しました。",
                    "complete", "buffer_drain_complete");
        }
    };

    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long now = System.currentTimeMillis();

            if (captureActive && autoStopEnabled && firstAudibleAt > 0) {
                long captureElapsed = now - firstAudibleAt;
                long silence = now - lastAudibleAt;
                // Capture ending is intentionally independent from recognizer progress. At 1.5/2x
                // recognition can be far behind while the actual playback has already ended.
                if (captureElapsed >= AUTO_STOP_MIN_CAPTURE_MS
                        && silence >= AUTO_STOP_SILENCE_MS && peak < 24) {
                    finishCaptureAndDrain("再生終了を検知しました。残りの音声を文字起こししています…", "auto_end");
                    return;
                } else if (captureElapsed >= AUTO_STOP_MIN_CAPTURE_MS
                        && silence >= 15000L && peak < 24) {
                    long remain = Math.max(0L, (AUTO_STOP_SILENCE_MS - silence + 999L) / 1000L);
                    broadcast("無音が続いています。再生が戻らなければ約" + remain + "秒で取り込みを終了します。", true);
                }
            }

            long stallTimeout = 24000L;
            long rollover = 65000L;
            boolean audioWaiting = spoolBytesConsumed < spoolBytesWritten;
            boolean recognizerStalled = lastRecognizerCallbackAt > 0
                    && now - lastRecognizerCallbackAt > stallTimeout;
            boolean sessionTooLong = recognizerSessionStartedAt > 0
                    && now - recognizerSessionStartedAt > rollover;

            if (drainComplete) {
                scheduleFinishAfterDrain(1800L);
            } else if (!restartScheduled && (sessionTooLong || (audioWaiting && recognizerStalled))) {
                diag("recognizer_watchdog", "stalled=" + recognizerStalled
                        + ";sessionTooLong=" + sessionTooLong
                        + ";backlogMs=" + bufferedBacklogMs());
                flushPartialForRecovery();
                // v0.13 diagnostics showed repeated server-disconnected errors when a new session
                // was created only ~180ms after a planned rollover. The audio spool lets us wait.
                requestRecognizerRestart("認識器を安全に更新しています…", 850L);
            }

            if (running) mainHandler.postDelayed(this, WATCHDOG_INTERVAL_MS);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        store = new EpisodeStore(this);
        diagnostics = new DiagnosticStore(this);
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_START_INTERNAL.equals(action)) {
            if (running) return START_NOT_STICKY;
            mode = "internal";
            playbackSpeed = clampSpeed(intent.getFloatExtra("playbackSpeed", 1.0f));
            autoStopEnabled = intent.getBooleanExtra("autoStop", true);
            loadEpisode(intent.getLongExtra("episodeId", -1L));
            startForegroundForMode(false, "文字起こしを準備中");
            int resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED);
            Intent data = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra("projectionData", Intent.class)
                    : intent.getParcelableExtra("projectionData");
            beginInternal(resultCode, data);
        } else if (ACTION_START_MIC.equals(action)) {
            if (running) return START_NOT_STICKY;
            mode = "mic";
            playbackSpeed = clampSpeed(intent.getFloatExtra("playbackSpeed", 1.0f));
            autoStopEnabled = intent.getBooleanExtra("autoStop", true);
            loadEpisode(intent.getLongExtra("episodeId", -1L));
            startForegroundForMode(true, "マイク文字起こしを準備中");
            beginMic();
        } else if (ACTION_STOP.equals(action)) {
            if (running && captureActive) {
                finishCaptureAndDrain("取り込みを停止しました。残りの音声を文字起こしして保存します…", "manual_stop");
            } else if (running) {
                stopEverything("文字起こしを停止しました。途中まで保存済みです。",
                        "complete", "manual_stop_during_drain");
            }
        }
        return START_NOT_STICKY;
    }

    private float clampSpeed(float speed) {
        if (speed >= 1.9f) return 2.0f;
        if (speed >= 1.4f) return 1.5f;
        return 1.0f;
    }

    private boolean isHighSpeed() { return playbackSpeed >= 1.4f; }

    private void loadEpisode(long id) {
        currentEpisodeId = id;
        EpisodeStore.Episode e = store.getEpisode(id);
        if (e == null) {
            rawText = finalText = program = episodeTitle = episodeHints = "";
            mediaStartMs = lastSegmentEndMs = 0L;
            episodeLabel = "文字起こし中";
            formatProfile = FormatLearningStore.get(this, "");
            return;
        }
        rawText = e.rawTranscript == null ? "" : e.rawTranscript;
        finalText = e.transcript == null ? "" : e.transcript;
        program = e.program == null ? "" : e.program;
        episodeTitle = e.title == null ? "" : e.title;
        episodeHints = (e.tags == null ? "" : e.tags) + " "
                + (e.keyPoints == null ? "" : e.keyPoints);
        mediaStartMs = e.mediaStartMs;
        formatProfile = FormatLearningStore.get(this, program);
        ArrayList<EpisodeStore.Segment> segments = store.listSegments(id);
        lastSegmentEndMs = segments.isEmpty()
                ? mediaStartMs : segments.get(segments.size() - 1).endMs;
        String name = !episodeTitle.isEmpty() ? episodeTitle : program;
        episodeLabel = name.isEmpty() ? "文字起こし中" : name;
        lastCommitted = "";
    }

    private void startForegroundForMode(boolean micOnly, String text) {
        Notification n = notification(text);
        if (Build.VERSION.SDK_INT >= 29) {
            int type = micOnly ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    : ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            startForeground(NOTIFY_ID, n, type);
        } else startForeground(NOTIFY_ID, n);
    }

    private AudioFormat audioFormat() {
        return new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build();
    }

    private int bufferBytes() {
        int min = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        return Math.max(min * 8, 65536);
    }

    private void beginInternal(int resultCode, Intent data) {
        if (data == null) {
            diag("media_projection", "permission_data_missing");
            stopEverything("共有許可を取得できませんでした。", "error", "projection_missing");
            return;
        }
        try {
            MediaProjectionManager m = (MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = m.getMediaProjection(resultCode, data);
            AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build();
            recorder = new AudioRecord.Builder()
                    .setAudioFormat(audioFormat())
                    .setBufferSizeInBytes(bufferBytes())
                    .setAudioPlaybackCaptureConfig(config)
                    .build();
            diag("media_projection", "capture_ready");
            beginRecorder(statusForRunning());
        } catch (Exception e) {
            diag("media_projection_error", e.getClass().getSimpleName() + ":" + e.getMessage());
            stopEverything("内部音声を開始できませんでした: " + e.getClass().getSimpleName(),
                    "error", "capture_start_error");
        }
    }

    private void beginMic() {
        try {
            recorder = buildMicRecorder();
            beginRecorder("バックグラウンドでマイク文字起こし中。スピーカーで再生してください。");
        } catch (Exception e) {
            diag("mic_start_error", e.getClass().getSimpleName() + ":" + e.getMessage());
            stopEverything("マイク文字起こしを開始できませんでした: " + e.getClass().getSimpleName(),
                    "error", "mic_start_error");
        }
    }

    private AudioRecord buildMicRecorder() {
        try {
            AudioRecord r = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.UNPROCESSED)
                    .setAudioFormat(audioFormat())
                    .setBufferSizeInBytes(bufferBytes())
                    .build();
            if (r.getState() == AudioRecord.STATE_INITIALIZED) return r;
            r.release();
        } catch (Exception ignored) {}
        return new AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(audioFormat())
                .setBufferSizeInBytes(bufferBytes())
                .build();
    }

    private String statusForRunning() {
        if (playbackSpeed >= 1.9f) return "2倍速を取り込み中。音声認識は一時バッファから追いかけています。";
        if (playbackSpeed >= 1.4f) return "1.5倍速を取り込み中。音声認識は一時バッファから追いかけています。";
        return "バックグラウンドで文字起こし中。音声は一時バッファで保護しています。";
    }

    private void beginRecorder(String message) throws Exception {
        peak = 0;
        bytes = 0;
        reconnectCount = 0;
        permissionErrorRetries = 0;
        partialText = "";
        firstAudibleAt = 0;
        lastAudibleAt = 0;
        silenceStartedAt = 0;
        pendingParagraphBreak = false;
        pendingSentenceBreak = false;
        lastCommitWallAt = 0;
        spoolBytesWritten = 0L;
        spoolBytesConsumed = 0L;
        frozenDurationMs = 0L;
        lastDiagnosticAudioAt = 0L;
        sourceFinished = false;
        drainComplete = false;
        finishScheduled = false;
        captureActive = true;
        running = true;
        synchronized (breakLock) {
            breakMarkers.clear();
            nextBreakMarker = 0;
        }

        prepareSpool();
        acquireWakeLock();
        diag("session_start", "mode=" + mode + ";speed=" + playbackSpeed
                + ";autoStop=" + autoStopEnabled + ";bufferedCapture=true"
                + ";format=" + FormatLearningStore.describe(this, program));
        if (currentEpisodeId > 0) {
            store.updateRecognition(currentEpisodeId, rawText, finalText,
                    "recording", playbackSpeed, currentDurationMs());
        }
        recorder.startRecording();
        startCaptureThread();
        startRecognizerSession(false);
        startBufferedFeeder();
        mainHandler.removeCallbacks(watchdog);
        mainHandler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS);
        broadcast(message, true);
        updateNotification(message);
    }

    private void prepareSpool() throws IOException {
        File dir = getCacheDir();
        File[] old = dir.listFiles((d, name) ->
                (name.startsWith("radiko_buf_") || name.startsWith("radiko_hs_")) && name.endsWith(".pcm"));
        if (old != null) {
            for (File f : old) {
                if (System.currentTimeMillis() - f.lastModified() > 12L * 60L * 60L * 1000L) f.delete();
            }
        }
        spoolFile = new File(getCacheDir(),
                "radiko_buf_" + currentEpisodeId + "_" + System.currentTimeMillis() + ".pcm");
        spoolOut = new FileOutputStream(spoolFile, false);
        diag("audio_spool", "created=" + spoolFile.getName());
    }

    private void startRecognizerSession(boolean recovery) {
        mainHandler.post(() -> {
            if (!running || drainComplete) return;
            restartScheduled = false;
            recognizerGeneration++;
            final int generation = recognizerGeneration;
            if (recovery) reconnectCount++;
            destroyRecognizerOnly();
            closePipe();
            try {
                ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
                synchronized (pipeLock) {
                    readFd = pipe[0];
                    writeFd = pipe[1];
                    pipeOut = new ParcelFileDescriptor.AutoCloseOutputStream(writeFd);
                }

                recognizer = SpeechRecognizer.createSpeechRecognizer(this);
                recognizer.setRecognitionListener(new RecognitionListener() {
                    private boolean current() { return running && generation == recognizerGeneration; }
                    private void touch() { if (current()) lastRecognizerCallbackAt = System.currentTimeMillis(); }

                    @Override public void onReadyForSpeech(Bundle params) {
                        touch();
                        diag("recognizer_ready", "generation=" + generation);
                    }
                    @Override public void onBeginningOfSpeech() { touch(); }
                    @Override public void onRmsChanged(float rmsdB) {}
                    @Override public void onBufferReceived(byte[] buffer) { touch(); }
                    @Override public void onEndOfSpeech() { touch(); }

                    @Override public void onError(int error) {
                        if (!current()) return;
                        touch();
                        diag("recognizer_error", "generation=" + generation + ";code=" + error
                                + ";name=" + errorName(error) + ";sourceFinished=" + sourceFinished
                                + ";drainComplete=" + drainComplete + ";backlogMs=" + bufferedBacklogMs());
                        if (drainComplete) {
                            scheduleFinishAfterDrain(900L);
                            return;
                        }
                        if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
                                || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
                            stopEverything("日本語の音声認識を利用できません。", "error", "language_error");
                            return;
                        }
                        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                            permissionErrorRetries++;
                            if (permissionErrorRetries <= 5) {
                                flushPartialForRecovery();
                                requestRecognizerRestart("権限状態を再確認して自動復旧しています…", 1400L);
                            } else {
                                stopEverything("音声認識の権限を維持できませんでした。アプリを開いて再開してください。",
                                        "error", "recognizer_permission_error");
                            }
                            return;
                        }
                        permissionErrorRetries = 0;
                        flushPartialForRecovery();
                        long delay;
                        if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) delay = 1300L;
                        else if (error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED
                                || error == SpeechRecognizer.ERROR_SERVER) delay = 1000L;
                        else if (error == SpeechRecognizer.ERROR_NO_MATCH
                                || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) delay = 550L;
                        else delay = 800L;
                        requestRecognizerRestart("認識が途切れたため一時バッファから自動復旧しています…", delay);
                    }

                    @Override public void onResults(Bundle results) {
                        if (!current()) return;
                        touch();
                        permissionErrorRetries = 0;
                        appendBest(results, "final");
                        if (drainComplete) scheduleFinishAfterDrain(900L);
                        else requestRecognizerRestart("次の認識区間へ接続中…", 600L);
                    }

                    @Override public void onPartialResults(Bundle partialResults) {
                        if (!current()) return;
                        touch();
                        ArrayList<String> list = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                        if (list != null && !list.isEmpty()) {
                            partialText = chooseBestCandidateWithContext(list, null);
                            broadcast(bufferedStatusText(), true);
                        }
                    }

                    @Override public void onEvent(int eventType, Bundle params) {}
                    @Override public void onSegmentResults(Bundle segmentResults) {
                        if (current()) {
                            touch();
                            permissionErrorRetries = 0;
                            appendBest(segmentResults, "segment");
                        }
                    }
                    @Override public void onEndOfSegmentedSession() {
                        if (!current()) return;
                        touch();
                        flushPartialForRecovery();
                        if (drainComplete) scheduleFinishAfterDrain(900L);
                        else requestRecognizerRestart("認識区間を更新しています…", 600L);
                    }
                    @Override public void onLanguageDetection(Bundle results) {}
                });

                Intent ri = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                ri.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                ri.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP");
                ri.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
                ri.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 8);
                ri.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readFd);
                ri.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1);
                ri.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
                ri.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE);
                ri.putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE);
                ri.putExtra(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, false);
                ri.putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS,
                        store.getBiasStrings(program, episodeTitle + " " + episodeHints));
                ri.putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING,
                        RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY);
                ri.putExtra(RecognizerIntent.EXTRA_HIDE_PARTIAL_TRAILING_PUNCTUATION, true);

                recognizerSessionStartedAt = System.currentTimeMillis();
                lastRecognizerCallbackAt = recognizerSessionStartedAt;
                diag("recognizer_session_start", "generation=" + generation
                        + ";recovery=" + recovery + ";backlogMs=" + bufferedBacklogMs());
                recognizer.startListening(ri);
                if (recovery) broadcast(bufferedStatusText(), true);
            } catch (Exception e) {
                diag("recognizer_session_exception", e.getClass().getSimpleName() + ":" + e.getMessage());
                if (running && !drainComplete) requestRecognizerRestart("認識器を再接続しています…", 1500L);
            }
        });
    }

    private void requestRecognizerRestart(String statusText, long delayMs) {
        if (!running || restartScheduled || drainComplete) return;
        restartScheduled = true;
        broadcast(statusText, true);
        int generation = recognizerGeneration;
        mainHandler.postDelayed(() -> {
            if (!running || drainComplete) return;
            if (generation != recognizerGeneration) {
                restartScheduled = false;
                return;
            }
            startRecognizerSession(true);
        }, delayMs);
    }

    private void appendBest(Bundle b, String source) {
        ArrayList<String> list = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (list == null || list.isEmpty()) return;
        float[] confidence = b.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES);
        String rawCandidate = list.get(0) == null ? "" : list.get(0).trim();
        String selected = chooseBestCandidateWithContext(list, confidence);
        diag("recognition_candidates", candidateDetail(source, list, confidence, selected));
        partialText = "";
        commitText(rawCandidate, selected);
    }

    private String candidateDetail(String source, ArrayList<String> list, float[] confidence, String selected) {
        StringBuilder b = new StringBuilder();
        b.append("source=").append(source).append(";selected=").append(selected).append(";candidates=");
        int n = Math.min(8, list.size());
        for (int i = 0; i < n; i++) {
            if (i > 0) b.append(" || ");
            String c = list.get(i) == null ? "" : list.get(i).replace('\n', ' ').replace('\r', ' ');
            b.append(i + 1).append(":").append(c);
            if (confidence != null && i < confidence.length) {
                b.append("@").append(String.format(Locale.US, "%.3f", confidence[i]));
            }
        }
        return b.toString();
    }

    private String chooseBestCandidateWithContext(ArrayList<String> candidates, float[] confidence) {
        if (candidates == null || candidates.isEmpty()) return "";
        ArrayList<EpisodeStore.Correction> corrections = store.getCorrections(program);
        ArrayList<String> hints = store.getBiasStrings(program, episodeTitle + " " + episodeHints);
        double bestScore = -1e18;
        String best = candidates.get(0) == null ? "" : candidates.get(0);

        for (int i = 0; i < candidates.size(); i++) {
            String c = candidates.get(i) == null ? "" : candidates.get(i).trim();
            if (c.isEmpty()) continue;
            double score = confidence != null && i < confidence.length && confidence[i] >= 0
                    ? confidence[i] * 4.5 : 0.0;
            for (EpisodeStore.Correction r : corrections) {
                if (!r.correct.isEmpty() && c.contains(r.correct)) score += 6.0 + Math.min(r.uses, 9);
                if (!r.wrong.isEmpty() && c.contains(r.wrong)) score -= 5.0 + Math.min(r.uses, 9);
            }
            for (String hint : hints) {
                String h = hint == null ? "" : hint.trim();
                if (h.length() < 2 || h.length() > 30) continue;
                if (c.contains(h)) score += Math.min(4.5, 0.9 + h.length() * 0.24);
            }
            score += Math.min(c.length(), 140) * 0.002;
            if (score > bestScore) {
                bestScore = score;
                best = c;
            }
        }
        return store.applyCorrections(program, best).trim();
    }

    private void flushPartialForRecovery() {
        String p = partialText == null ? "" : partialText.trim();
        partialText = "";
        if (p.length() >= 2) commitText(p, p);
    }

    private void commitText(String rawSegment, String selected) {
        if (selected == null) return;
        String corrected = store.applyCorrections(program, selected.trim()).trim();
        rawSegment = rawSegment == null ? corrected : rawSegment.trim();
        if (corrected.isEmpty() || corrected.equals(lastCommitted)) return;

        String tail = compact(finalText.length() > 1200
                ? finalText.substring(finalText.length() - 1200) : finalText);
        String compactCorrected = compact(corrected);
        if (!compactCorrected.isEmpty() && tail.contains(compactCorrected)) {
            lastCommitted = corrected;
            diag("dedupe_skip", "exact_tail;chars=" + corrected.length());
            return;
        }

        int overlap = findOverlap(compact(finalText), compactCorrected);
        String append = corrected;
        if (overlap >= 8 && overlap < compactCorrected.length()) {
            int cut = approximateCut(corrected, overlap);
            if (cut > 0 && cut < corrected.length()) append = corrected.substring(cut).trim();
        } else if (overlap >= compactCorrected.length()) {
            lastCommitted = corrected;
            diag("dedupe_skip", "overlap;chars=" + corrected.length());
            return;
        }
        if (append.isEmpty()) return;

        long now = System.currentTimeMillis();
        boolean paragraphFromSilence = pendingParagraphBreak;
        boolean sentenceFromSilence = pendingSentenceBreak;
        pendingParagraphBreak = false;
        pendingSentenceBreak = false;

        boolean topicBreak = paragraphFromSilence || shouldTopicBreak(append);
        appendRaw(rawSegment);
        appendReadable(append, topicBreak, sentenceFromSilence);

        long mediaEnd = currentRecognitionMediaPositionMs();
        long estimated = Math.max(1200L, Math.min(12000L, append.length() * 150L));
        long segmentStart = lastSegmentEndMs > 0
                ? lastSegmentEndMs : Math.max(mediaStartMs, mediaEnd - estimated);
        if (segmentStart > mediaEnd) segmentStart = Math.max(mediaStartMs, mediaEnd - estimated);
        if (currentEpisodeId > 0) {
            store.addSegment(currentEpisodeId, segmentStart, mediaEnd,
                    rawSegment, append, topicBreak);
        }
        lastSegmentEndMs = mediaEnd;
        lastCommitWallAt = now;
        lastCommitted = corrected;
        if (!rawSegment.equals(corrected)) {
            diag("correction_applied", "raw=" + rawSegment + ";final=" + corrected);
        }
        diag("commit", "startMs=" + segmentStart + ";endMs=" + mediaEnd
                + ";chars=" + append.length() + ";topicBreak=" + topicBreak
                + ";paragraphBreakFromSilence=" + paragraphFromSilence);
        if (currentEpisodeId > 0) {
            store.updateRecognition(currentEpisodeId, rawText, finalText,
                    "recording", playbackSpeed, currentDurationMs());
        }
        broadcast(bufferedStatusText(), true);
    }

    private boolean shouldTopicBreak(String text) {
        String s = text.replaceAll("^[「『（(\\s]+", "");
        String[] markers = {
                "ラジオネーム", "さて", "続いて", "続きまして", "次に", "ここで",
                "ということで", "というわけで", "では", "それでは", "話は変わ",
                "ところで", "改めて", "一方で", "ちなみに", "ここから", "最後に",
                "まずは", "ここからは", "次のお便り", "続いてのお便り",
                "ここで一曲", "ここで1曲", "聞いていただいたのは", "お知らせ"
        };
        for (String m : markers) if (s.startsWith(m)) return true;
        if (formatProfile != null && formatProfile.markers != null) {
            for (String m : formatProfile.markers) if (!m.isEmpty() && s.startsWith(m)) return true;
        }
        int charLimit = formatProfile == null ? 180 : formatProfile.paragraphChars;
        int lineLimit = formatProfile == null ? 4 : formatProfile.linesPerParagraph;
        return currentParagraphLength() >= charLimit || currentParagraphSentenceCount() >= lineLimit;
    }

    private void appendRaw(String segment) {
        if (!rawText.isEmpty() && !rawText.endsWith("\n")) rawText += "\n";
        rawText += segment;
    }

    private void appendReadable(String text, boolean topicBreak, boolean sentenceFromSilence) {
        text = formatRecognizedSegment(text);
        if (text.isEmpty()) return;

        if (sentenceFromSilence && !finalText.isEmpty()
                && !endsWithSentencePunctuation(finalText)
                && !finalText.endsWith("\n")) {
            finalText += "。";
        }

        String[] pieces = text.split("(?<=[。！？!?])");
        boolean firstPiece = true;
        int charLimit = formatProfile == null ? 180 : formatProfile.paragraphChars;
        int lineLimit = formatProfile == null ? 4 : formatProfile.linesPerParagraph;
        for (String piece : pieces) {
            piece = piece.trim();
            if (piece.isEmpty()) continue;
            if (!endsWithSentencePunctuation(piece) && piece.length() >= 10) piece += "。";

            boolean newParagraph = !finalText.isEmpty()
                    && ((firstPiece && topicBreak)
                    || currentParagraphLength() >= charLimit
                    || currentParagraphSentenceCount() >= lineLimit);

            if (!finalText.isEmpty()) {
                if (newParagraph) {
                    if (!finalText.endsWith("\n\n")) {
                        if (finalText.endsWith("\n")) finalText += "\n";
                        else finalText += "\n\n";
                    }
                } else if (!finalText.endsWith("\n")) {
                    finalText += "\n";
                }
            }
            finalText += piece;
            firstPiece = false;
        }
    }

    private String formatRecognizedSegment(String text) {
        String s = text == null ? "" : text
                .replace('，', '、')
                .replace('．', '。')
                .replaceAll("[ \\t]+", " ")
                .trim();
        if (s.isEmpty()) return s;
        s = insertStopsBeforeMarkers(s);
        if (!endsWithSentencePunctuation(s) && s.length() >= 10) s += "。";
        return s;
    }

    private String insertStopsBeforeMarkers(String s) {
        String[] markers = {
                "ということで", "というわけで", "ちなみに", "続いて", "続きまして",
                "それでは", "さて", "ここから", "ここからは", "最後に", "一方で",
                "まずは", "次のお便り", "続いてのお便り", "ラジオネーム",
                "ここで1曲", "ここで一曲", "聞いていただいたのは", "お知らせ",
                "ところで"
        };
        StringBuilder out = new StringBuilder();
        int sinceBoundary = 0;
        for (int i = 0; i < s.length();) {
            String found = null;
            for (String m : markers) {
                if (s.startsWith(m, i) && safeMarkerBoundary(s, i)) {
                    found = m;
                    break;
                }
            }
            if (found != null) {
                if (sinceBoundary >= 22 && out.length() > 0
                        && !isSentencePunctuation(out.charAt(out.length() - 1))) {
                    out.append('。');
                    sinceBoundary = 0;
                }
                out.append(found);
                sinceBoundary += found.length();
                i += found.length();
                continue;
            }
            char c = s.charAt(i++);
            out.append(c);
            if (isSentencePunctuation(c)) sinceBoundary = 0;
            else sinceBoundary++;
        }
        return out.toString();
    }

    private boolean safeMarkerBoundary(String s, int index) {
        if (index <= 0) return true;
        char p = s.charAt(index - 1);
        return Character.isWhitespace(p) || p == '。' || p == '！' || p == '？'
                || p == '!' || p == '?' || p == '、' || p == '：' || p == ':';
    }

    private int currentParagraphLength() {
        int i = finalText.lastIndexOf("\n\n");
        return i < 0 ? finalText.length() : finalText.length() - i - 2;
    }

    private int currentParagraphSentenceCount() {
        int i = finalText.lastIndexOf("\n\n");
        String p = i < 0 ? finalText : finalText.substring(i + 2);
        int n = 0;
        for (int k = 0; k < p.length(); k++) if (isSentencePunctuation(p.charAt(k))) n++;
        return n;
    }

    private boolean isSentencePunctuation(char c) {
        return c == '。' || c == '！' || c == '？' || c == '!' || c == '?' || c == '.';
    }

    private boolean endsWithSentencePunctuation(String s) {
        if (s == null || s.isEmpty()) return false;
        int i = s.length() - 1;
        while (i >= 0 && Character.isWhitespace(s.charAt(i))) i--;
        return i >= 0 && isSentencePunctuation(s.charAt(i));
    }

    private String compact(String s) {
        return s == null ? "" : s.replaceAll("[\\s、。！？!?，,.・：；]+", "");
    }

    private int findOverlap(String a, String b) {
        int max = Math.min(Math.min(a.length(), b.length()), 220);
        for (int k = max; k >= 8; k--) if (a.endsWith(b.substring(0, k))) return k;
        return 0;
    }

    private int approximateCut(String original, int compactChars) {
        int seen = 0;
        for (int i = 0; i < original.length(); i++) {
            char c = original.charAt(i);
            if (!Character.isWhitespace(c) && "、。！？!?，,.・：；".indexOf(c) < 0) seen++;
            if (seen >= compactChars) return i + 1;
        }
        return original.length();
    }

    private String displayText() {
        String p = partialText == null ? "" : partialText.trim();
        if (p.isEmpty()) return finalText;
        if (finalText.isEmpty()) return p;
        return finalText + "\n\n…" + p;
    }

    private long currentMediaPositionMs(long now) {
        if (firstAudibleAt <= 0) return Math.max(mediaStartMs, lastSegmentEndMs);
        if (sourceFinished && frozenDurationMs > 0) return mediaStartMs + frozenDurationMs;
        return mediaStartMs + Math.max(0L, Math.round((now - firstAudibleAt) * playbackSpeed));
    }

    private long currentRecognitionMediaPositionMs() {
        long rawAudioMs = Math.round(spoolBytesConsumed * 1000.0 / (SAMPLE_RATE * 2.0));
        return mediaStartMs + Math.max(0L, Math.round(rawAudioMs * playbackSpeed));
    }

    private long currentDurationMs() {
        if (sourceFinished && frozenDurationMs > 0) return frozenDurationMs;
        if (firstAudibleAt <= 0) return 0L;
        return Math.max(0L, Math.round((System.currentTimeMillis() - firstAudibleAt) * playbackSpeed));
    }

    private long bufferedBacklogMs() {
        long remain = Math.max(0L, spoolBytesWritten - spoolBytesConsumed);
        double capturedMs = remain * 1000.0 / (SAMPLE_RATE * 2.0);
        return Math.max(0L, Math.round(capturedMs * playbackSpeed));
    }

    private void startCaptureThread() {
        captureThread = new Thread(() -> {
            short[] buf = new short[4096];
            long lastStatus = 0L;
            while (running && captureActive) {
                try {
                    int n = recorder.read(buf, 0, buf.length);
                    if (n <= 0) continue;

                    int localPeak = 0;
                    for (int i = 0; i < n; i++) localPeak = Math.max(localPeak, Math.abs((int)buf[i]));
                    peak = localPeak;
                    bytes += n * 2L;
                    long now = System.currentTimeMillis();

                    if (localPeak >= 35) {
                        if (silenceStartedAt > 0) {
                            long mediaGap = Math.round((now - silenceStartedAt) * playbackSpeed);
                            if (mediaGap >= 550L) addBreakMarker(spoolBytesWritten, mediaGap >= 1400L);
                            silenceStartedAt = 0L;
                        }
                        if (firstAudibleAt == 0) firstAudibleAt = now;
                        lastAudibleAt = now;
                    } else if (firstAudibleAt > 0 && silenceStartedAt == 0) {
                        silenceStartedAt = now;
                    }

                    writeSpool(buf, n);

                    if (now - lastDiagnosticAudioAt >= 5000L) {
                        diag("audio_sample", "peak=" + peak + ";bytes=" + bytes
                                + ";written=" + spoolBytesWritten + ";consumed=" + spoolBytesConsumed
                                + ";backlogMs=" + bufferedBacklogMs() + ";captureActive=" + captureActive);
                        lastDiagnosticAudioAt = now;
                    }

                    if (now - lastStatus > 1000L) {
                        String s;
                        if (bytes > SAMPLE_RATE * 2L * 6L && peak < 20) {
                            s = mode.equals("mic") ? "マイク入力がほぼ無音です。"
                                    : "内部音声がほぼ無音です。再生を確認してください。";
                        } else if (restartScheduled) {
                            s = "音声取得は継続中です。認識器を安全に再接続しています…";
                        } else s = bufferedStatusText();
                        broadcast(s, true);
                        updateNotification(s);
                        lastStatus = now;
                    }
                } catch (Exception e) {
                    if (running && captureActive) {
                        diag("capture_loop_error", e.getClass().getSimpleName() + ":" + e.getMessage());
                        broadcast("音声入力を復旧しています…", true);
                        sleepQuietly(120L);
                    }
                }
            }
            diag("capture_thread_end", "sourceFinished=" + sourceFinished + ";bytes=" + bytes);
        }, "AudioCapture");
        captureThread.start();
    }

    private void addBreakMarker(long rawByteOffset, boolean paragraph) {
        synchronized (breakLock) {
            breakMarkers.add(new BreakMarker(rawByteOffset, paragraph));
        }
    }

    private void applyBreakMarkers(long consumed) {
        synchronized (breakLock) {
            while (nextBreakMarker < breakMarkers.size()) {
                BreakMarker m = breakMarkers.get(nextBreakMarker);
                if (m.rawByteOffset > consumed) break;
                if (m.paragraph) pendingParagraphBreak = true;
                else pendingSentenceBreak = true;
                nextBreakMarker++;
            }
        }
    }

    private void writeSpool(short[] input, int n) throws IOException {
        byte[] raw = pcmBytes(input, n);
        synchronized (spoolLock) {
            if (spoolOut == null) return;
            spoolOut.write(raw);
            spoolBytesWritten += raw.length;
        }
    }

    private byte[] pcmBytes(short[] input, int n) {
        byte[] out = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            out[i * 2] = (byte)(input[i] & 0xff);
            out[i * 2 + 1] = (byte)((input[i] >> 8) & 0xff);
        }
        return out;
    }

    private void startBufferedFeeder() {
        feederThread = new Thread(() -> {
            long pos = 0L;
            byte[] raw = new byte[16384];
            long lastStatus = 0L;
            try (RandomAccessFile raf = new RandomAccessFile(spoolFile, "r")) {
                while (running && !drainComplete) {
                    long available = spoolBytesWritten - pos;
                    if (available < 2L) {
                        if (sourceFinished) {
                            markDrainComplete();
                            break;
                        }
                        sleepQuietly(25L);
                        continue;
                    }

                    int want = (int)Math.min(raw.length, available);
                    if ((want & 1) != 0) want--;
                    if (want <= 0) {
                        sleepQuietly(20L);
                        continue;
                    }
                    raf.seek(pos);
                    int got = raf.read(raw, 0, want);
                    if (got <= 0) {
                        sleepQuietly(20L);
                        continue;
                    }
                    if ((got & 1) != 0) got--;
                    short[] samples = new short[got / 2];
                    for (int i = 0; i < samples.length; i++) {
                        samples[i] = (short)((raw[i * 2] & 0xff) | (raw[i * 2 + 1] << 8));
                    }
                    byte[] encoded = encodeForRecognizer(samples, samples.length, playbackSpeed);

                    boolean sent = false;
                    while (running && !sent && !drainComplete) {
                        OutputStream target;
                        synchronized (pipeLock) { target = pipeOut; }
                        if (target == null) {
                            sleepQuietly(35L);
                            continue;
                        }
                        try {
                            target.write(encoded);
                            sent = true;
                        } catch (IOException e) {
                            diag("feeder_pipe_retry", "generation=" + recognizerGeneration
                                    + ";pos=" + pos + ";error=" + e.getClass().getSimpleName());
                            sleepQuietly(100L);
                        }
                    }
                    if (!sent) continue;

                    pos += got;
                    spoolBytesConsumed = pos;
                    applyBreakMarkers(pos);

                    long now = System.currentTimeMillis();
                    if (now - lastStatus > 1500L) {
                        String s = bufferedStatusText();
                        broadcast(s, true);
                        updateNotification(s);
                        lastStatus = now;
                    }
                }
            } catch (Exception e) {
                diag("buffered_feeder_error", e.getClass().getSimpleName() + ":" + e.getMessage());
                if (running) mainHandler.post(() -> {
                    if (sourceFinished) stopEverything("音声の残り処理中にエラーが起きました。途中まで保存しました。",
                            "error", "buffered_feeder_error");
                });
            }
        }, "BufferedAudioFeeder");
        feederThread.start();
    }

    private String bufferedStatusText() {
        long backlog = bufferedBacklogMs();
        if (sourceFinished) {
            return "再生取り込み完了。認識待ち約" + formatShort(backlog) + "を処理中…";
        }
        if (isHighSpeed()) {
            return (playbackSpeed >= 1.9f ? "2倍速" : "1.5倍速")
                    + "を取り込み中。認識待ち約" + formatShort(backlog)
                    + "（音声取得は継続しています）。";
        }
        if (backlog > 2500L) {
            return "文字起こし中。認識待ち約" + formatShort(backlog)
                    + "（音声は一時バッファに保存済み）。";
        }
        return "バックグラウンドで文字起こし中。音声は一時バッファで保護しています。";
    }

    private String formatShort(long ms) {
        long sec = Math.max(0L, ms) / 1000L;
        long min = sec / 60L;
        long rem = sec % 60L;
        return min + ":" + String.format(Locale.JAPAN, "%02d", rem);
    }

    private void finishCaptureAndDrain(String message, String reason) {
        if (!running || sourceFinished) return;
        sourceFinished = true;
        captureActive = false;
        frozenDurationMs = currentDurationMs();
        diag("capture_finish", "reason=" + reason + ";durationMs=" + frozenDurationMs
                + ";written=" + spoolBytesWritten + ";consumed=" + spoolBytesConsumed);

        try { if (recorder != null) recorder.stop(); } catch (Exception ignored) {}
        try { if (recorder != null) recorder.release(); } catch (Exception ignored) {}
        recorder = null;
        try { if (projection != null) projection.stop(); } catch (Exception ignored) {}
        projection = null;

        synchronized (spoolLock) {
            try { if (spoolOut != null) spoolOut.close(); } catch (Exception ignored) {}
            spoolOut = null;
        }
        trimFinalSilenceFromSpool();
        broadcast(message, true);
        updateNotification(message);

        if (spoolBytesConsumed >= spoolBytesWritten) markDrainComplete();
        else mainHandler.postDelayed(() -> {
            if (running && sourceFinished) broadcast(bufferedStatusText(), true);
        }, 500L);
    }

    private void trimFinalSilenceFromSpool() {
        if (spoolFile == null || firstAudibleAt <= 0 || lastAudibleAt <= 0) return;
        long silenceMs = Math.max(0L, System.currentTimeMillis() - lastAudibleAt);
        long trim = Math.round(silenceMs * SAMPLE_RATE * 2.0 / 1000.0);
        trim -= trim & 1L;
        long target = Math.max(spoolBytesConsumed, Math.max(0L, spoolBytesWritten - trim));
        try (RandomAccessFile raf = new RandomAccessFile(spoolFile, "rw")) {
            raf.setLength(target);
            spoolBytesWritten = target;
            frozenDurationMs = Math.max(0L,
                    frozenDurationMs - Math.round(silenceMs * playbackSpeed));
            diag("spool_trim", "silenceMs=" + silenceMs + ";newBytes=" + target
                    + ";durationMs=" + frozenDurationMs);
        } catch (Exception e) {
            diag("spool_trim_error", e.getClass().getSimpleName());
        }
    }

    private void markDrainComplete() {
        if (drainComplete) return;
        drainComplete = true;
        diag("buffer_drain_complete", "written=" + spoolBytesWritten
                + ";consumed=" + spoolBytesConsumed);
        closePipeWriterOnly();
        broadcast("音声の送信が完了しました。最終認識を確定しています…", true);
        scheduleFinishAfterDrain(4500L);
    }

    private void scheduleFinishAfterDrain(long delayMs) {
        if (!running || !drainComplete) return;
        mainHandler.removeCallbacks(finishAfterDrain);
        finishScheduled = true;
        mainHandler.postDelayed(finishAfterDrain, Math.max(500L, delayMs));
    }

    private byte[] encodeForRecognizer(short[] input, int n, float factor) {
        if (factor < 1.4f || n < 800) return pcmBytes(input, n);

        int frame = 640;
        int analysisHop = 160;
        int synthHop = Math.max(analysisHop, Math.round(analysisHop * factor));
        int frames = 1 + Math.max(0, (n - frame) / analysisHop);
        int outSamples = (frames - 1) * synthHop + frame;
        double[] acc = new double[outSamples];
        double[] weight = new double[outSamples];
        for (int f = 0; f < frames; f++) {
            int inPos = f * analysisHop;
            int outPos = f * synthHop;
            for (int i = 0; i < frame && inPos + i < n; i++) {
                double w = 0.02 + 0.98 * (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (frame - 1.0)));
                acc[outPos + i] += input[inPos + i] * w;
                weight[outPos + i] += w;
            }
        }
        byte[] out = new byte[outSamples * 2];
        for (int i = 0; i < outSamples; i++) {
            int v = weight[i] > 0.0001 ? (int)Math.round(acc[i] / weight[i]) : 0;
            v = Math.max(-32768, Math.min(32767, v));
            out[i * 2] = (byte)(v & 0xff);
            out[i * 2 + 1] = (byte)((v >> 8) & 0xff);
        }
        return out;
    }

    private void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private void closePipeWriterOnly() {
        synchronized (pipeLock) {
            try { if (pipeOut != null) pipeOut.close(); } catch (Exception ignored) {}
            pipeOut = null;
            writeFd = null;
        }
    }

    private void closePipe() {
        synchronized (pipeLock) {
            try { if (pipeOut != null) pipeOut.close(); } catch (Exception ignored) {}
            pipeOut = null;
            try { if (readFd != null) readFd.close(); } catch (Exception ignored) {}
            readFd = null;
            writeFd = null;
        }
    }

    private void destroyRecognizerOnly() {
        SpeechRecognizer old = recognizer;
        recognizer = null;
        if (old != null) {
            try { old.cancel(); } catch (Exception ignored) {}
            try { old.destroy(); } catch (Exception ignored) {}
        }
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager)getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "radiko-transcriber:recognition");
            wakeLock.acquire(6 * 60 * 60 * 1000L);
        } catch (Exception ignored) {}
    }

    private void releaseWakeLock() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
        wakeLock = null;
    }

    private void stopEverything(String message, String statusValue, String reason) {
        if (running) flushPartialForRecovery();
        diag("session_stop", "reason=" + reason + ";status=" + statusValue
                + ";chars=" + finalText.length() + ";reconnects=" + reconnectCount
                + ";written=" + spoolBytesWritten + ";consumed=" + spoolBytesConsumed);
        running = false;
        captureActive = false;
        restartScheduled = false;
        finishScheduled = false;
        mainHandler.removeCallbacks(watchdog);
        mainHandler.removeCallbacks(finishAfterDrain);
        closePipe();

        try { if (recorder != null) recorder.stop(); } catch (Exception ignored) {}
        try { if (recorder != null) recorder.release(); } catch (Exception ignored) {}
        recorder = null;
        synchronized (spoolLock) {
            try { if (spoolOut != null) spoolOut.close(); } catch (Exception ignored) {}
            spoolOut = null;
        }
        mainHandler.post(this::destroyRecognizerOnly);
        try { if (projection != null) projection.stop(); } catch (Exception ignored) {}
        projection = null;
        releaseWakeLock();
        if (currentEpisodeId > 0) {
            store.updateRecognition(currentEpisodeId, rawText, finalText,
                    statusValue, playbackSpeed, currentDurationMs());
        }
        store.autoBackup(this);
        broadcast(message, false);
        try { if (spoolFile != null) spoolFile.delete(); } catch (Exception ignored) {}
        spoolFile = null;
        try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Exception ignored) {}
        stopSelf();
    }

    private void broadcast(String status, boolean isRunning) {
        broadcastSequence++;
        String text = displayText();
        long now = System.currentTimeMillis();
        long position = currentMediaPositionMs(now);
        long backlog = bufferedBacklogMs();
        String phase = sourceFinished ? (drainComplete ? "finalizing" : "draining") : "capturing";

        getSharedPreferences("runtime_state", MODE_PRIVATE).edit()
                .putBoolean("running", isRunning)
                .putLong("episodeId", currentEpisodeId)
                .putLong("heartbeatAt", now)
                .putString("status", status == null ? "" : status)
                .putLong("mediaPositionMs", position)
                .putLong("backlogMs", backlog)
                .putFloat("playbackSpeed", playbackSpeed)
                .putString("phase", phase)
                .apply();

        Intent i = new Intent(ACTION_UPDATE);
        i.setPackage(getPackageName());
        i.putExtra("status", status);
        i.putExtra("running", isRunning);
        i.putExtra("peak", peak);
        i.putExtra("mode", mode);
        i.putExtra("reconnects", reconnectCount);
        i.putExtra("episodeId", currentEpisodeId);
        i.putExtra("playbackSpeed", playbackSpeed);
        i.putExtra("text", text);
        i.putExtra("mediaPositionMs", position);
        i.putExtra("durationMs", currentDurationMs());
        i.putExtra("backlogMs", backlog);
        i.putExtra("phase", phase);
        i.putExtra("sequence", broadcastSequence);
        i.putExtra("lastCommitAt", lastCommitWallAt);
        sendBroadcast(i);
    }

    private void diag(String kind, String detail) {
        if (diagnostics != null) diagnostics.log(currentEpisodeId, kind, detail);
    }

    private String errorName(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO: return "AUDIO";
            case SpeechRecognizer.ERROR_CLIENT: return "CLIENT";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "INSUFFICIENT_PERMISSIONS";
            case SpeechRecognizer.ERROR_NETWORK: return "NETWORK";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "NETWORK_TIMEOUT";
            case SpeechRecognizer.ERROR_NO_MATCH: return "NO_MATCH";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "RECOGNIZER_BUSY";
            case SpeechRecognizer.ERROR_SERVER: return "SERVER";
            case SpeechRecognizer.ERROR_SERVER_DISCONNECTED: return "SERVER_DISCONNECTED";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "SPEECH_TIMEOUT";
            case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED: return "LANGUAGE_NOT_SUPPORTED";
            case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE: return "LANGUAGE_UNAVAILABLE";
            default: return "ERROR_" + error;
        }
    }

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(
                CHANNEL, "ラジオ文字起こし", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("バックグラウンド文字起こしの状態");
        nm.createNotificationChannel(ch);
    }

    private Notification notification(String text) {
        Intent stop = new Intent(this, TranscribeService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 2, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 3, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle(episodeLabel)
                .setContentText(text)
                .setContentIntent(openPi)
                .addAction(android.R.drawable.ic_media_pause,
                        captureActive ? "取り込み停止→残りを処理" : "停止して保存", stopPi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void updateNotification(String text) {
        getSystemService(NotificationManager.class).notify(NOTIFY_ID, notification(text));
    }

    @Override public void onDestroy() {
        mainHandler.removeCallbacks(watchdog);
        mainHandler.removeCallbacks(finishAfterDrain);
        if (running) stopEverything("サービスを終了しました。途中まで保存済みです。",
                "interrupted", "service_destroyed");
        releaseWakeLock();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}

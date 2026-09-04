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
    private static final long AUTO_STOP_NO_TEXT_MS = 15000L;

    private final Object pipeLock = new Object();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private MediaProjection projection;
    private AudioRecord recorder;
    private SpeechRecognizer recognizer;
    private ParcelFileDescriptor readFd, writeFd;
    private OutputStream pipeOut;
    private Thread captureThread;
    private PowerManager.WakeLock wakeLock;
    private EpisodeStore store;

    private volatile boolean running = false;
    private volatile int peak = 0;
    private volatile long bytes = 0;
    private volatile long firstAudibleAt = 0;
    private volatile long lastAudibleAt = 0;

    private String rawText = "";
    private String finalText = "";
    private String partialText = "";
    private String lastCommitted = "";
    private String mode = "internal";
    private String program = "";
    private String episodeTitle = "";
    private String episodeLabel = "文字起こし中";
    private long currentEpisodeId = -1L;
    private long mediaStartMs = 0L;
    private long lastSegmentEndMs = 0L;
    private long lastCommitWallAt = 0L;

    private float playbackSpeed = 1.0f;
    private boolean autoStopEnabled = true;
    private int recognizerGeneration = 0;
    private int reconnectCount = 0;
    private int permissionErrorRetries = 0;
    private boolean restartScheduled = false;
    private long recognizerSessionStartedAt = 0;
    private long lastRecognizerCallbackAt = 0;

    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long now = System.currentTimeMillis();

            if (autoStopEnabled && firstAudibleAt > 0) {
                long captureElapsed = now - firstAudibleAt;
                long silence = now - lastAudibleAt;
                long noText = lastCommitWallAt > 0 ? now - lastCommitWallAt : captureElapsed;
                if (captureElapsed >= AUTO_STOP_MIN_CAPTURE_MS && silence >= AUTO_STOP_SILENCE_MS
                        && noText >= AUTO_STOP_NO_TEXT_MS && peak < 24) {
                    flushPartialForRecovery();
                    stopEverything("再生終了を検知し、自動停止・保存しました。", "complete");
                    return;
                } else if (captureElapsed >= AUTO_STOP_MIN_CAPTURE_MS && silence >= 15000L && peak < 24) {
                    long remain = Math.max(0L, (AUTO_STOP_SILENCE_MS - silence + 999L) / 1000L);
                    broadcast("無音が続いています。再生が戻らなければ約" + remain + "秒で自動保存します。", true);
                }
            }

            long stallTimeout = playbackSpeed >= 1.9f ? 11000L : playbackSpeed >= 1.4f ? 15000L : 20000L;
            long rollover = playbackSpeed >= 1.9f ? 26000L : playbackSpeed >= 1.4f ? 40000L : 65000L;
            boolean heardAudioRecently = lastAudibleAt > 0 && now - lastAudibleAt < 7000L;
            boolean recognizerStalled = lastRecognizerCallbackAt > 0 && now - lastRecognizerCallbackAt > stallTimeout;
            boolean sessionTooLong = recognizerSessionStartedAt > 0 && now - recognizerSessionStartedAt > rollover;
            if (!restartScheduled && (sessionTooLong || (heardAudioRecently && recognizerStalled))) {
                flushPartialForRecovery();
                requestRecognizerRestart("認識器を自動更新しています…", 150L);
            }
            if (running) mainHandler.postDelayed(this, WATCHDOG_INTERVAL_MS);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        store = new EpisodeStore(this);
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
            Intent data = Build.VERSION.SDK_INT >= 33 ? intent.getParcelableExtra("projectionData", Intent.class) : intent.getParcelableExtra("projectionData");
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
            stopEverything("文字起こしを停止しました。自動保存済みです。", "complete");
        }
        return START_NOT_STICKY;
    }

    private float clampSpeed(float speed) {
        if (speed >= 1.9f) return 2.0f;
        if (speed >= 1.4f) return 1.5f;
        return 1.0f;
    }

    private void loadEpisode(long id) {
        currentEpisodeId = id;
        EpisodeStore.Episode e = store.getEpisode(id);
        if (e == null) {
            rawText = finalText = program = episodeTitle = "";
            mediaStartMs = lastSegmentEndMs = 0L;
            episodeLabel = "文字起こし中";
            return;
        }
        rawText = e.rawTranscript == null ? "" : e.rawTranscript;
        finalText = e.transcript == null ? "" : e.transcript;
        program = e.program == null ? "" : e.program;
        episodeTitle = e.title == null ? "" : e.title;
        mediaStartMs = e.mediaStartMs;
        ArrayList<EpisodeStore.Segment> segments = store.listSegments(id);
        lastSegmentEndMs = segments.isEmpty() ? mediaStartMs : segments.get(segments.size() - 1).endMs;
        String name = !episodeTitle.isEmpty() ? episodeTitle : program;
        episodeLabel = name.isEmpty() ? "文字起こし中" : name;
        lastCommitted = "";
    }

    private void startForegroundForMode(boolean micOnly, String text) {
        Notification n = notification(text);
        if (Build.VERSION.SDK_INT >= 29) {
            int type = micOnly ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    : ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            startForeground(NOTIFY_ID, n, type);
        } else startForeground(NOTIFY_ID, n);
    }

    private AudioFormat audioFormat() {
        return new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build();
    }

    private int bufferBytes() {
        int min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        return Math.max(min * 6, 49152);
    }

    private void beginInternal(int resultCode, Intent data) {
        if (data == null) { stopEverything("共有許可を取得できませんでした。", "error"); return; }
        try {
            MediaProjectionManager m = (MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = m.getMediaProjection(resultCode, data);
            AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build();
            recorder = new AudioRecord.Builder().setAudioFormat(audioFormat()).setBufferSizeInBytes(bufferBytes())
                    .setAudioPlaybackCaptureConfig(config).build();
            beginRecorder(statusForRunning());
        } catch (Exception e) {
            stopEverything("内部音声を開始できませんでした: " + e.getClass().getSimpleName(), "error");
        }
    }

    private void beginMic() {
        try { recorder = buildMicRecorder(); beginRecorder("バックグラウンドでマイク文字起こし中。スピーカーで再生してください。"); }
        catch (Exception e) { stopEverything("マイク文字起こしを開始できませんでした: " + e.getClass().getSimpleName(), "error"); }
    }

    private AudioRecord buildMicRecorder() {
        try {
            AudioRecord r = new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.UNPROCESSED)
                    .setAudioFormat(audioFormat()).setBufferSizeInBytes(bufferBytes()).build();
            if (r.getState() == AudioRecord.STATE_INITIALIZED) return r;
            r.release();
        } catch (Exception ignored) {}
        return new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(audioFormat()).setBufferSizeInBytes(bufferBytes()).build();
    }

    private String statusForRunning() {
        String speed = playbackSpeed >= 1.9f ? "2倍速・音声減速補助" : playbackSpeed >= 1.4f ? "1.5倍速・音声減速補助" : "標準";
        return "バックグラウンドで文字起こし中（" + speed + "）。";
    }

    private void beginRecorder(String message) throws Exception {
        peak = 0; bytes = 0; reconnectCount = 0; permissionErrorRetries = 0; partialText = "";
        firstAudibleAt = 0; lastAudibleAt = 0; lastCommitWallAt = 0; running = true;
        acquireWakeLock();
        if (currentEpisodeId > 0) store.updateRecognition(currentEpisodeId, rawText, finalText, "recording", playbackSpeed, currentDurationMs());
        recorder.startRecording();
        startCaptureThread();
        startRecognizerSession(false);
        mainHandler.removeCallbacks(watchdog);
        mainHandler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS);
        broadcast(message, true); updateNotification(message);
    }

    private void startRecognizerSession(boolean recovery) {
        mainHandler.post(() -> {
            if (!running) return;
            restartScheduled = false;
            recognizerGeneration++;
            final int generation = recognizerGeneration;
            if (recovery) reconnectCount++;
            destroyRecognizerOnly(); closePipe();
            try {
                ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
                synchronized (pipeLock) {
                    readFd = pipe[0]; writeFd = pipe[1]; pipeOut = new ParcelFileDescriptor.AutoCloseOutputStream(writeFd);
                }
                recognizer = SpeechRecognizer.createSpeechRecognizer(this);
                recognizer.setRecognitionListener(new RecognitionListener() {
                    private boolean current() { return running && generation == recognizerGeneration; }
                    private void touch() { if (current()) lastRecognizerCallbackAt = System.currentTimeMillis(); }
                    @Override public void onReadyForSpeech(Bundle params) { touch(); }
                    @Override public void onBeginningOfSpeech() { touch(); }
                    @Override public void onRmsChanged(float rmsdB) {}
                    @Override public void onBufferReceived(byte[] buffer) { touch(); }
                    @Override public void onEndOfSpeech() { touch(); }
                    @Override public void onError(int error) {
                        if (!current()) return; touch();
                        if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
                            stopEverything("日本語の音声認識を利用できません。", "error"); return;
                        }
                        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                            permissionErrorRetries++;
                            if (permissionErrorRetries <= 5) { flushPartialForRecovery(); requestRecognizerRestart("権限状態を再確認して自動復旧しています…", 1200L); }
                            else stopEverything("音声認識の権限を維持できませんでした。アプリを開いて再開してください。", "error");
                            return;
                        }
                        permissionErrorRetries = 0; flushPartialForRecovery();
                        requestRecognizerRestart("認識が途切れたため自動復旧しています…", error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ? 1100L : 400L);
                    }
                    @Override public void onResults(Bundle results) {
                        if (!current()) return; touch(); permissionErrorRetries = 0; appendBest(results);
                        requestRecognizerRestart("次の認識区間へ接続中…", 220L);
                    }
                    @Override public void onPartialResults(Bundle partialResults) {
                        if (!current()) return; touch();
                        ArrayList<String> list = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                        if (list != null && !list.isEmpty()) {
                            partialText = store.applyCorrections(program, list.get(0).trim());
                            broadcast("文字起こし中。バックグラウンドでも継続します。", true);
                        }
                    }
                    @Override public void onEvent(int eventType, Bundle params) {}
                    @Override public void onSegmentResults(Bundle segmentResults) { if (current()) { touch(); permissionErrorRetries = 0; appendBest(segmentResults); } }
                    @Override public void onEndOfSegmentedSession() { if (current()) { touch(); flushPartialForRecovery(); requestRecognizerRestart("認識区間を更新しています…", 220L); } }
                    @Override public void onLanguageDetection(Bundle results) {}
                });
                Intent ri = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                ri.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                ri.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP");
                ri.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
                ri.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
                ri.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readFd);
                ri.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1);
                ri.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
                ri.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE);
                ri.putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE);
                ri.putExtra(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, false);
                ri.putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, store.getBiasStrings(program, episodeTitle));
                ri.putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY);
                ri.putExtra(RecognizerIntent.EXTRA_HIDE_PARTIAL_TRAILING_PUNCTUATION, true);
                recognizerSessionStartedAt = System.currentTimeMillis(); lastRecognizerCallbackAt = recognizerSessionStartedAt;
                recognizer.startListening(ri);
                if (recovery) broadcast("自動復旧しました。文字起こしを継続中です。", true);
            } catch (Exception e) { if (running) requestRecognizerRestart("認識器を再接続しています…", 1400L); }
        });
    }

    private void requestRecognizerRestart(String statusText, long delayMs) {
        if (!running || restartScheduled) return;
        restartScheduled = true; broadcast(statusText, true); int generation = recognizerGeneration;
        mainHandler.postDelayed(() -> {
            if (!running) return;
            if (generation != recognizerGeneration) { restartScheduled = false; return; }
            startRecognizerSession(true);
        }, delayMs);
    }

    private void appendBest(Bundle b) {
        ArrayList<String> list = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (list == null || list.isEmpty()) return;
        float[] confidence = b.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES);
        String rawCandidate = list.get(0) == null ? "" : list.get(0).trim();
        String selected = store.chooseBestCandidate(program, list, confidence).trim();
        partialText = "";
        commitText(rawCandidate, selected);
    }

    private void flushPartialForRecovery() {
        String p = partialText == null ? "" : partialText.trim(); partialText = "";
        if (p.length() >= 2) commitText(p, p);
    }

    private void commitText(String rawSegment, String selected) {
        if (selected == null) return;
        String corrected = store.applyCorrections(program, selected.trim()).trim();
        rawSegment = rawSegment == null ? corrected : rawSegment.trim();
        if (corrected.isEmpty() || corrected.equals(lastCommitted)) return;
        String tail = compact(finalText.length() > 900 ? finalText.substring(finalText.length() - 900) : finalText);
        String compactCorrected = compact(corrected);
        if (!compactCorrected.isEmpty() && tail.contains(compactCorrected)) { lastCommitted = corrected; return; }

        int overlap = findOverlap(compact(finalText), compactCorrected);
        String append = corrected;
        if (overlap >= 8 && overlap < compactCorrected.length()) {
            int cut = approximateCut(corrected, overlap);
            if (cut > 0 && cut < corrected.length()) append = corrected.substring(cut).trim();
        } else if (overlap >= compactCorrected.length()) { lastCommitted = corrected; return; }
        if (append.isEmpty()) return;

        long now = System.currentTimeMillis();
        boolean topicBreak = shouldTopicBreak(append, now);
        appendRaw(rawSegment);
        appendReadable(append, topicBreak);
        long mediaEnd = currentMediaPositionMs(now);
        long estimated = Math.max(1200L, Math.min(12000L, append.length() * 150L));
        long segmentStart = lastSegmentEndMs > 0 ? lastSegmentEndMs : Math.max(mediaStartMs, mediaEnd - estimated);
        if (segmentStart > mediaEnd) segmentStart = Math.max(mediaStartMs, mediaEnd - estimated);
        if (currentEpisodeId > 0) store.addSegment(currentEpisodeId, segmentStart, mediaEnd, rawSegment, append, topicBreak);
        lastSegmentEndMs = mediaEnd; lastCommitWallAt = now; lastCommitted = corrected;
        if (currentEpisodeId > 0) store.updateRecognition(currentEpisodeId, rawText, finalText, "recording", playbackSpeed, currentDurationMs());
        broadcast("文字起こし中。タイムスタンプ付きで自動保存しています。", true);
    }

    private boolean shouldTopicBreak(String text, long now) {
        long gap = lastCommitWallAt <= 0 ? 0 : now - lastCommitWallAt;
        long gapThreshold = playbackSpeed >= 1.9f ? 1000L : playbackSpeed >= 1.4f ? 1500L : 2200L;
        if (gap >= gapThreshold) return true;
        String s = text.replaceAll("^[「『（(\\s]+", "");
        String[] markers = {"さて", "続いて", "次に", "ここで", "ということで", "では", "それでは", "話は変わ", "ところで", "改めて", "一方で"};
        for (String m : markers) if (s.startsWith(m)) return true;
        return currentParagraphLength() >= 150;
    }

    private void appendRaw(String segment) {
        if (!rawText.isEmpty() && !rawText.endsWith("\n")) rawText += "\n";
        rawText += segment;
    }

    private void appendReadable(String text, boolean topicBreak) {
        text = text.replaceAll("[ \\t]+", " ").trim();
        if (text.isEmpty()) return;
        if (!endsWithSentencePunctuation(text) && text.length() >= 8) text += "。";
        if (!finalText.isEmpty()) {
            if (topicBreak || currentParagraphLength() >= 150) {
                if (!finalText.endsWith("\n\n")) finalText += "\n\n";
            } else if (!finalText.endsWith("\n") && !finalText.endsWith("。") && !finalText.endsWith("！") && !finalText.endsWith("？")) finalText += " ";
        }
        finalText += text;
    }

    private int currentParagraphLength() { int i = finalText.lastIndexOf("\n\n"); return i < 0 ? finalText.length() : finalText.length() - i - 2; }
    private boolean endsWithSentencePunctuation(String s) { if (s.isEmpty()) return false; char c = s.charAt(s.length()-1); return c=='。'||c=='！'||c=='？'||c=='!'||c=='?'||c=='.'; }
    private String compact(String s) { return s == null ? "" : s.replaceAll("[\\s、。！？!?，,.・：；]+", ""); }
    private int findOverlap(String a, String b) { int max = Math.min(Math.min(a.length(), b.length()), 180); for (int k=max;k>=8;k--) if (a.endsWith(b.substring(0,k))) return k; return 0; }
    private int approximateCut(String original, int compactChars) { int seen=0; for (int i=0;i<original.length();i++) { char c=original.charAt(i); if (!Character.isWhitespace(c) && "、。！？!?，,.・：；".indexOf(c)<0) seen++; if (seen>=compactChars) return i+1; } return original.length(); }

    private String displayText() {
        String p = partialText == null ? "" : partialText.trim();
        if (p.isEmpty()) return finalText;
        if (finalText.isEmpty()) return p;
        return finalText + "\n\n…" + p;
    }

    private long currentMediaPositionMs(long now) {
        if (firstAudibleAt <= 0) return Math.max(mediaStartMs, lastSegmentEndMs);
        return mediaStartMs + Math.max(0L, Math.round((now - firstAudibleAt) * playbackSpeed));
    }

    private long currentDurationMs() {
        if (firstAudibleAt <= 0) return 0L;
        return Math.max(0L, Math.round((System.currentTimeMillis() - firstAudibleAt) * playbackSpeed));
    }

    private void startCaptureThread() {
        captureThread = new Thread(() -> {
            short[] buf = new short[4096]; long lastStatus = 0;
            while (running) {
                try {
                    int n = recorder.read(buf, 0, buf.length);
                    if (n <= 0) continue;
                    int localPeak = 0;
                    for (int i=0;i<n;i++) localPeak = Math.max(localPeak, Math.abs((int)buf[i]));
                    peak = localPeak; bytes += n * 2L; long now = System.currentTimeMillis();
                    if (localPeak >= 35) { if (firstAudibleAt == 0) firstAudibleAt = now; lastAudibleAt = now; }
                    byte[] out = encodeForRecognizer(buf, n, playbackSpeed);
                    OutputStream target; synchronized (pipeLock) { target = pipeOut; }
                    if (target != null) {
                        try { target.write(out); }
                        catch (IOException expectedDuringReconnect) { if (running) sleepQuietly(20L); }
                    } else sleepQuietly(20L);
                    if (now - lastStatus > 1000L) {
                        String s;
                        if (bytes > SAMPLE_RATE * 2L * 6L && peak < 20) s = mode.equals("mic") ? "マイク入力がほぼ無音です。" : "内部音声がほぼ無音です。ブラウザ再生を確認してください。";
                        else if (restartScheduled) s = "音声は取得中です。認識器を自動復旧しています…";
                        else s = statusForRunning();
                        broadcast(s, true); updateNotification(s); lastStatus = now;
                    }
                } catch (Exception e) { if (running) { broadcast("音声入力を復旧しています…", true); sleepQuietly(120L); } }
            }
        }, "AudioToSpeechPipe");
        captureThread.start();
    }

    private byte[] encodeForRecognizer(short[] input, int n, float factor) {
        if (factor < 1.4f || n < 640) {
            byte[] out = new byte[n * 2];
            for (int i=0;i<n;i++) { out[i*2]=(byte)(input[i]&0xff); out[i*2+1]=(byte)((input[i]>>8)&0xff); }
            return out;
        }
        int frame = 320, analysisHop = 160, synthHop = Math.max(analysisHop, Math.round(analysisHop * factor));
        int frames = 1 + Math.max(0, (n - frame) / analysisHop);
        int outSamples = (frames - 1) * synthHop + frame;
        double[] acc = new double[outSamples]; double[] weight = new double[outSamples];
        for (int f=0;f<frames;f++) {
            int inPos = f * analysisHop, outPos = f * synthHop;
            for (int i=0;i<frame && inPos+i<n;i++) {
                double w = 0.1 + 0.9 * (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (frame - 1.0)));
                acc[outPos+i] += input[inPos+i] * w; weight[outPos+i] += w;
            }
        }
        byte[] out = new byte[outSamples * 2];
        for (int i=0;i<outSamples;i++) {
            int v = weight[i] > 0.0001 ? (int)Math.round(acc[i] / weight[i]) : 0;
            v = Math.max(-32768, Math.min(32767, v));
            out[i*2]=(byte)(v&0xff); out[i*2+1]=(byte)((v>>8)&0xff);
        }
        return out;
    }

    private void sleepQuietly(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }

    private void closePipe() {
        synchronized (pipeLock) {
            try { if (pipeOut != null) pipeOut.close(); } catch (Exception ignored) {}
            pipeOut = null; try { if (readFd != null) readFd.close(); } catch (Exception ignored) {}
            readFd = null; writeFd = null;
        }
    }

    private void destroyRecognizerOnly() {
        SpeechRecognizer old = recognizer; recognizer = null;
        if (old != null) { try { old.cancel(); } catch (Exception ignored) {} try { old.destroy(); } catch (Exception ignored) {} }
    }

    private void acquireWakeLock() {
        try { PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE); wakeLock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"radiko-transcriber:recognition"); wakeLock.acquire(6*60*60*1000L); }
        catch (Exception ignored) {}
    }
    private void releaseWakeLock() { try { if (wakeLock!=null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {} wakeLock=null; }

    private void stopEverything(String message, String statusValue) {
        if (running) flushPartialForRecovery();
        running=false; restartScheduled=false; mainHandler.removeCallbacks(watchdog); closePipe();
        try { if (recorder!=null) { recorder.stop(); recorder.release(); } } catch (Exception ignored) {} recorder=null;
        mainHandler.post(this::destroyRecognizerOnly);
        try { if (projection!=null) projection.stop(); } catch (Exception ignored) {} projection=null; releaseWakeLock();
        if (currentEpisodeId>0) store.updateRecognition(currentEpisodeId, rawText, finalText, statusValue, playbackSpeed, currentDurationMs());
        store.autoBackup(this);
        broadcast(message,false); try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Exception ignored) {} stopSelf();
    }

    private void broadcast(String status, boolean isRunning) {
        Intent i=new Intent(ACTION_UPDATE); i.setPackage(getPackageName()); i.putExtra("status",status); i.putExtra("running",isRunning);
        i.putExtra("peak",peak); i.putExtra("mode",mode); i.putExtra("reconnects",reconnectCount); i.putExtra("episodeId",currentEpisodeId);
        i.putExtra("playbackSpeed",playbackSpeed); i.putExtra("text",displayText()); sendBroadcast(i);
    }

    private void createChannel() {
        NotificationManager nm=getSystemService(NotificationManager.class);
        NotificationChannel ch=new NotificationChannel(CHANNEL,"ラジオ文字起こし",NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("バックグラウンド文字起こしの状態"); nm.createNotificationChannel(ch);
    }

    private Notification notification(String text) {
        Intent stop=new Intent(this,TranscribeService.class); stop.setAction(ACTION_STOP);
        PendingIntent stopPi=PendingIntent.getService(this,2,stop,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Intent open=new Intent(this,MainActivity.class);
        PendingIntent openPi=PendingIntent.getActivity(this,3,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle(episodeLabel)
                .setContentText(text).setContentIntent(openPi).addAction(android.R.drawable.ic_media_pause,"停止して保存",stopPi)
                .setOngoing(true).setOnlyAlertOnce(true).build();
    }

    private void updateNotification(String text) { getSystemService(NotificationManager.class).notify(NOTIFY_ID,notification(text)); }

    @Override public void onDestroy() {
        mainHandler.removeCallbacks(watchdog);
        if (running) stopEverything("サービスを終了しました。保存済みです。","interrupted");
        releaseWakeLock(); super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}

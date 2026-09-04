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
    private static final long WATCHDOG_INTERVAL_MS = 4000L;
    private static final long STALL_TIMEOUT_MS = 20000L;
    private static final long SESSION_ROLLOVER_MS = 65000L;

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
    private volatile long lastAudibleAt = 0;

    private String finalText = "";
    private String partialText = "";
    private String lastCommitted = "";
    private String mode = "internal";
    private String episodeLabel = "文字起こし中";
    private long currentEpisodeId = -1L;

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
            boolean heardAudioRecently = lastAudibleAt > 0 && now - lastAudibleAt < 7000L;
            boolean recognizerStalled = lastRecognizerCallbackAt > 0
                    && now - lastRecognizerCallbackAt > STALL_TIMEOUT_MS;
            boolean sessionTooLong = recognizerSessionStartedAt > 0
                    && now - recognizerSessionStartedAt > SESSION_ROLLOVER_MS;

            if (!restartScheduled && (sessionTooLong || (heardAudioRecently && recognizerStalled))) {
                flushPartialForRecovery();
                requestRecognizerRestart("認識器を自動更新しています…", 120L);
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
            loadEpisode(intent.getLongExtra("episodeId", -1L));
            startForegroundForMode(false, "文字起こしを準備中");
            int resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED);
            Intent data;
            if (Build.VERSION.SDK_INT >= 33) data = intent.getParcelableExtra("projectionData", Intent.class);
            else data = intent.getParcelableExtra("projectionData");
            beginInternal(resultCode, data);
        } else if (ACTION_START_MIC.equals(action)) {
            if (running) return START_NOT_STICKY;
            mode = "mic";
            loadEpisode(intent.getLongExtra("episodeId", -1L));
            startForegroundForMode(true, "マイク文字起こしを準備中");
            beginMic();
        } else if (ACTION_STOP.equals(action)) {
            stopEverything("文字起こしを停止しました。自動保存済みです。", "complete");
        }
        return START_NOT_STICKY;
    }

    private void loadEpisode(long id) {
        currentEpisodeId = id;
        EpisodeStore.Episode e = store.getEpisode(id);
        finalText = e == null || e.transcript == null ? "" : e.transcript;
        lastCommitted = "";
        if (e != null) {
            String name = !e.title.isEmpty() ? e.title : e.program;
            episodeLabel = name.isEmpty() ? "文字起こし中" : name;
        } else {
            episodeLabel = "文字起こし中";
        }
    }

    private void startForegroundForMode(boolean micOnly, String text) {
        Notification n = notification(text);
        if (Build.VERSION.SDK_INT >= 29) {
            int type;
            if (micOnly) {
                type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            } else {
                // SpeechRecognizer itself requires RECORD_AUDIO. Keep microphone WIU capability
                // while the user switches from this app to the browser.
                type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                        | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            }
            startForeground(NOTIFY_ID, n, type);
        } else {
            startForeground(NOTIFY_ID, n);
        }
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
        return Math.max(min * 4, 32768);
    }

    private void beginInternal(int resultCode, Intent data) {
        if (data == null) {
            stopEverything("共有許可を取得できませんでした。", "error");
            return;
        }
        try {
            MediaProjectionManager m = (MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = m.getMediaProjection(resultCode, data);

            AudioPlaybackCaptureConfiguration config =
                    new AudioPlaybackCaptureConfiguration.Builder(projection)
                            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                            .build();

            recorder = new AudioRecord.Builder()
                    .setAudioFormat(audioFormat())
                    .setBufferSizeInBytes(bufferBytes())
                    .setAudioPlaybackCaptureConfig(config)
                    .build();

            beginRecorder("バックグラウンドで文字起こし中。ブラウザでradikoを再生してください。");
        } catch (Exception e) {
            stopEverything("内部音声を開始できませんでした: " + e.getClass().getSimpleName(), "error");
        }
    }

    private void beginMic() {
        try {
            recorder = buildMicRecorder();
            beginRecorder("バックグラウンドでマイク文字起こし中。スピーカーで再生してください。");
        } catch (Exception e) {
            stopEverything("マイク文字起こしを開始できませんでした: " + e.getClass().getSimpleName(), "error");
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

    private void beginRecorder(String message) throws Exception {
        peak = 0;
        bytes = 0;
        reconnectCount = 0;
        permissionErrorRetries = 0;
        partialText = "";
        lastAudibleAt = 0;
        running = true;

        acquireWakeLock();
        if (currentEpisodeId > 0) store.updateTranscript(currentEpisodeId, finalText, "recording");

        recorder.startRecording();
        startCaptureThread();
        startRecognizerSession(false);
        mainHandler.removeCallbacks(watchdog);
        mainHandler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS);

        broadcast(message, true);
        updateNotification(message);
    }

    private void startRecognizerSession(boolean recovery) {
        mainHandler.post(() -> {
            if (!running) return;
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
                    private boolean current() {
                        return running && generation == recognizerGeneration;
                    }
                    private void touch() {
                        if (current()) lastRecognizerCallbackAt = System.currentTimeMillis();
                    }

                    @Override public void onReadyForSpeech(Bundle params) { touch(); }
                    @Override public void onBeginningOfSpeech() { touch(); }
                    @Override public void onRmsChanged(float rmsdB) {}
                    @Override public void onBufferReceived(byte[] buffer) { touch(); }
                    @Override public void onEndOfSpeech() { touch(); }

                    @Override public void onError(int error) {
                        if (!current()) return;
                        touch();

                        if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
                                || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
                            stopEverything("日本語の音声認識を利用できません。", "error");
                            return;
                        }

                        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                            permissionErrorRetries++;
                            if (permissionErrorRetries <= 3) {
                                flushPartialForRecovery();
                                requestRecognizerRestart("権限状態を再確認して自動復旧しています…", 1000L);
                            } else {
                                stopEverything("音声認識の権限を維持できませんでした。アプリを開いて再開してください。", "error");
                            }
                            return;
                        }

                        permissionErrorRetries = 0;
                        flushPartialForRecovery();
                        long delay = error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ? 1000L : 350L;
                        requestRecognizerRestart("認識が途切れたため自動復旧しています…", delay);
                    }

                    @Override public void onResults(Bundle results) {
                        if (!current()) return;
                        touch();
                        permissionErrorRetries = 0;
                        appendBest(results);
                        requestRecognizerRestart("次の認識区間へ接続中…", 200L);
                    }

                    @Override public void onPartialResults(Bundle partialResults) {
                        if (!current()) return;
                        touch();
                        ArrayList<String> list = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                        if (list != null && !list.isEmpty()) {
                            partialText = list.get(0).trim();
                            broadcast("文字起こし中。バックグラウンドでも継続します。", true);
                        }
                    }

                    @Override public void onEvent(int eventType, Bundle params) {}

                    @Override public void onSegmentResults(Bundle segmentResults) {
                        if (!current()) return;
                        touch();
                        permissionErrorRetries = 0;
                        appendBest(segmentResults);
                    }

                    @Override public void onEndOfSegmentedSession() {
                        if (!current()) return;
                        touch();
                        flushPartialForRecovery();
                        requestRecognizerRestart("認識区間を更新しています…", 200L);
                    }

                    @Override public void onLanguageDetection(Bundle results) {}
                });

                Intent ri = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                ri.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                ri.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP");
                ri.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
                ri.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readFd);
                ri.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1);
                ri.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
                ri.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE);
                ri.putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE);
                ri.putExtra(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, false);

                recognizerSessionStartedAt = System.currentTimeMillis();
                lastRecognizerCallbackAt = recognizerSessionStartedAt;
                recognizer.startListening(ri);

                if (recovery) broadcast("自動復旧しました。文字起こしを継続中です。", true);
            } catch (Exception e) {
                if (running) requestRecognizerRestart("認識器を再接続しています…", 1300L);
            }
        });
    }

    private void requestRecognizerRestart(String statusText, long delayMs) {
        if (!running || restartScheduled) return;
        restartScheduled = true;
        broadcast(statusText, true);
        int generation = recognizerGeneration;
        mainHandler.postDelayed(() -> {
            if (!running) return;
            if (generation != recognizerGeneration) {
                restartScheduled = false;
                return;
            }
            startRecognizerSession(true);
        }, delayMs);
    }

    private void appendBest(Bundle b) {
        ArrayList<String> list = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (list == null || list.isEmpty()) return;
        partialText = "";
        commitText(list.get(0));
    }

    private void flushPartialForRecovery() {
        String p = partialText == null ? "" : partialText.trim();
        partialText = "";
        if (p.length() >= 2) commitText(p);
    }

    private void commitText(String raw) {
        if (raw == null) return;
        String s = raw.trim();
        if (s.isEmpty()) return;
        if (s.equals(lastCommitted)) return;

        // Avoid cumulative/overlapping results being appended twice across recognizer reconnects.
        String tail = finalText.length() > 600 ? finalText.substring(finalText.length() - 600) : finalText;
        if (tail.contains(s)) {
            lastCommitted = s;
            return;
        }

        int max = Math.min(Math.min(finalText.length(), s.length()), 160);
        int overlap = 0;
        for (int k = max; k >= 8; k--) {
            if (finalText.endsWith(s.substring(0, k))) {
                overlap = k;
                break;
            }
        }
        String append = s.substring(overlap).trim();
        if (append.isEmpty()) {
            lastCommitted = s;
            return;
        }

        if (!finalText.isEmpty() && !finalText.endsWith("\n")) finalText += "\n";
        finalText += append;
        lastCommitted = s;
        if (currentEpisodeId > 0) store.updateTranscript(currentEpisodeId, finalText, "recording");
        broadcast("文字起こし中。自動保存しています。", true);
    }

    private String displayText() {
        String p = partialText == null ? "" : partialText.trim();
        if (p.isEmpty()) return finalText;
        if (finalText.isEmpty()) return p;
        if (finalText.endsWith(p)) return finalText;
        return finalText + "\n" + p;
    }

    private void startCaptureThread() {
        captureThread = new Thread(() -> {
            short[] buf = new short[4096];
            byte[] out = new byte[buf.length * 2];
            long lastStatus = 0;

            while (running) {
                try {
                    int n = recorder.read(buf, 0, buf.length);
                    if (n <= 0) continue;

                    int localPeak = 0;
                    for (int i = 0; i < n; i++) {
                        int v = buf[i];
                        int a = Math.abs(v);
                        if (a > localPeak) localPeak = a;
                        out[i * 2] = (byte)(v & 0xff);
                        out[i * 2 + 1] = (byte)((v >> 8) & 0xff);
                    }

                    peak = localPeak;
                    bytes += n * 2L;
                    if (localPeak >= 40) lastAudibleAt = System.currentTimeMillis();

                    OutputStream target;
                    synchronized (pipeLock) { target = pipeOut; }
                    if (target != null) {
                        try { target.write(out, 0, n * 2); }
                        catch (IOException expectedDuringReconnect) {
                            if (running) try { Thread.sleep(20L); } catch (InterruptedException ignored) {}
                        }
                    }

                    long now = System.currentTimeMillis();
                    if (now - lastStatus > 1000L) {
                        String s;
                        if (bytes > SAMPLE_RATE * 2L * 6L && peak < 20) {
                            s = mode.equals("mic")
                                    ? "マイク入力がほぼ無音です。スピーカー音量を確認してください。"
                                    : "内部音声がほぼ無音です。ブラウザで再生しているか確認してください。";
                        } else if (restartScheduled) {
                            s = "音声取得は継続中。認識器を自動復旧しています…";
                        } else {
                            s = "バックグラウンドで文字起こし中。自動保存しています。";
                        }
                        broadcast(s, true);
                        updateNotification(episodeLabel + " • " + (restartScheduled ? "自動復旧中" : "文字起こし中"));
                        lastStatus = now;
                    }
                } catch (Exception e) {
                    if (running) {
                        broadcast("音声入力を復旧しています…", true);
                        try { Thread.sleep(150L); } catch (InterruptedException ignored) {}
                    }
                }
            }
        }, "AudioToSpeechPipe");
        captureThread.start();
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager)getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RadikoTranscriber:Transcribing");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        } catch (Exception ignored) {}
    }

    private void releaseWakeLock() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
        wakeLock = null;
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

    private void stopEverything(String message, String finalStatus) {
        if (running) flushPartialForRecovery();
        running = false;
        restartScheduled = false;
        mainHandler.removeCallbacks(watchdog);

        if (currentEpisodeId > 0) store.updateTranscript(currentEpisodeId, finalText, finalStatus);

        closePipe();
        try {
            if (recorder != null) {
                recorder.stop();
                recorder.release();
            }
        } catch (Exception ignored) {}
        recorder = null;

        mainHandler.post(this::destroyRecognizerOnly);
        try { if (projection != null) projection.stop(); } catch (Exception ignored) {}
        projection = null;
        releaseWakeLock();

        broadcast(message, false);
        try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Exception ignored) {}
        stopSelf();
    }

    private void broadcast(String status, boolean isRunning) {
        Intent i = new Intent(ACTION_UPDATE);
        i.setPackage(getPackageName());
        i.putExtra("status", status);
        i.putExtra("running", isRunning);
        i.putExtra("peak", peak);
        i.putExtra("mode", mode);
        i.putExtra("reconnects", reconnectCount);
        i.putExtra("episodeId", currentEpisodeId);
        i.putExtra("text", displayText());
        sendBroadcast(i);
    }

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(
                CHANNEL, "バックグラウンド文字起こし", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("radikoの文字起こし実行中に表示します");
        nm.createNotificationChannel(ch);
    }

    private Notification notification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stop = new Intent(this, TranscribeService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 2, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("radiko文字起こし • 実行中")
                .setContentText(text)
                .setContentIntent(openPi)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止して保存", stopPi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }

    private void updateNotification(String text) {
        getSystemService(NotificationManager.class).notify(NOTIFY_ID, notification(text));
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        if (running) updateNotification(episodeLabel + " • バックグラウンドで継続中");
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        mainHandler.removeCallbacks(watchdog);
        if (running) {
            running = false;
            if (currentEpisodeId > 0) store.updateTranscript(currentEpisodeId, finalText, "interrupted");
            closePipe();
            try { if (recorder != null) { recorder.stop(); recorder.release(); } } catch (Exception ignored) {}
            recorder = null;
            destroyRecognizerOnly();
            try { if (projection != null) projection.stop(); } catch (Exception ignored) {}
            projection = null;
            releaseWakeLock();
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}

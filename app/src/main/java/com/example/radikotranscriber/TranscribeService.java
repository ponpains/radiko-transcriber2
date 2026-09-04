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
    private static final long SESSION_ROLLOVER_MS = 70000L;

    private final Object pipeLock = new Object();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private MediaProjection projection;
    private AudioRecord recorder;
    private SpeechRecognizer recognizer;
    private ParcelFileDescriptor readFd, writeFd;
    private OutputStream pipeOut;
    private Thread captureThread;

    private volatile boolean running = false;
    private volatile int peak = 0;
    private volatile long bytes = 0;
    private volatile long lastAudibleAt = 0;

    private String finalText = "";
    private String partialText = "";
    private String lastCommitted = "";
    private String mode = "internal";

    private int recognizerGeneration = 0;
    private int reconnectCount = 0;
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
                requestRecognizerRestart("認識セッションを自動更新しています…", 100L);
            }

            if (running) mainHandler.postDelayed(this, WATCHDOG_INTERVAL_MS);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        finalText = getSharedPreferences("state", MODE_PRIVATE).getString("transcript", "");
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();

        if (ACTION_START_INTERNAL.equals(action)) {
            mode = "internal";
            startForegroundForMode(false, "内部音声を準備中");
            int resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED);
            Intent data;
            if (Build.VERSION.SDK_INT >= 33) data = intent.getParcelableExtra("projectionData", Intent.class);
            else data = intent.getParcelableExtra("projectionData");
            beginInternal(resultCode, data);
        } else if (ACTION_START_MIC.equals(action)) {
            mode = "mic";
            startForegroundForMode(true, "マイク文字起こし準備中");
            beginMic();
        } else if (ACTION_STOP.equals(action)) {
            stopEverything("停止しました。");
        }
        return START_NOT_STICKY;
    }

    private void startForegroundForMode(boolean mic, String text) {
        Notification n = notification(text);
        if (Build.VERSION.SDK_INT >= 29) {
            int type = mic ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    : ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
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
        if (data == null || running) return;
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

            beginRecorder("内部音声で文字起こし中。ブラウザでradikoを再生してください。");
        } catch (Exception e) {
            stopEverything("内部音声を開始できませんでした: " + e.getClass().getSimpleName() + " / " + e.getMessage());
        }
    }

    private void beginMic() {
        if (running) return;
        try {
            recorder = buildMicRecorder();
            beginRecorder("マイク経由で文字起こし中。radikoをスピーカーで再生してください。");
        } catch (Exception e) {
            stopEverything("マイク文字起こしを開始できませんでした: " + e.getClass().getSimpleName() + " / " + e.getMessage());
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
        partialText = "";
        lastCommitted = "";
        lastAudibleAt = 0;
        running = true;

        recorder.startRecording();
        startCaptureThread();
        startRecognizerSession(false);
        mainHandler.removeCallbacks(watchdog);
        mainHandler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS);

        broadcast(message, true);
        updateNotification(mode.equals("mic") ? "マイク経由で文字起こし中" : "ブラウザ内部音声で文字起こし中");
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

                        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                                || error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
                                || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
                            stopEverything("音声認識を継続できません: " + speechError(error));
                            return;
                        }

                        flushPartialForRecovery();
                        long delay = error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ? 900L : 300L;
                        requestRecognizerRestart("音声認識が途切れたため自動再接続します: " + speechError(error), delay);
                    }

                    @Override public void onResults(Bundle results) {
                        if (!current()) return;
                        touch();
                        appendBest(results);
                        requestRecognizerRestart("次の音声認識セッションへ接続中…", 180L);
                    }

                    @Override public void onPartialResults(Bundle partialResults) {
                        if (!current()) return;
                        touch();
                        ArrayList<String> list = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                        if (list != null && !list.isEmpty()) {
                            partialText = list.get(0).trim();
                            broadcast("認識中。途切れた場合は自動で再接続します。", true);
                        }
                    }

                    @Override public void onEvent(int eventType, Bundle params) {}

                    @Override public void onSegmentResults(Bundle segmentResults) {
                        if (!current()) return;
                        touch();
                        appendBest(segmentResults);
                    }

                    @Override public void onEndOfSegmentedSession() {
                        if (!current()) return;
                        touch();
                        flushPartialForRecovery();
                        requestRecognizerRestart("認識セッション終了。自動で続きを開始します…", 180L);
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

                if (recovery) {
                    broadcast("自動再接続しました。文字起こしを継続中です。", true);
                }
            } catch (Exception e) {
                if (running) {
                    requestRecognizerRestart("認識器の再接続に失敗しました。再試行します…", 1200L);
                }
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
        String s = list.get(0).trim();
        partialText = "";
        commitText(s);
    }

    private void flushPartialForRecovery() {
        String p = partialText == null ? "" : partialText.trim();
        partialText = "";
        if (p.length() >= 2) commitText(p);
    }

    private void commitText(String s) {
        if (s == null) return;
        s = s.trim();
        if (s.isEmpty()) return;

        if (s.equals(lastCommitted)) return;
        if (!finalText.isEmpty() && finalText.endsWith(s)) {
            lastCommitted = s;
            return;
        }

        if (!finalText.isEmpty() && !finalText.endsWith("\n")) finalText += "\n";
        finalText += s;
        lastCommitted = s;
        getSharedPreferences("state", MODE_PRIVATE).edit().putString("transcript", finalText).apply();
        broadcast("文字起こし中。", true);
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
                    synchronized (pipeLock) {
                        target = pipeOut;
                    }

                    if (target != null) {
                        try {
                            target.write(out, 0, n * 2);
                        } catch (IOException expectedDuringReconnect) {
                            if (running) {
                                try { Thread.sleep(20L); } catch (InterruptedException ignored) {}
                            }
                        }
                    } else {
                        try { Thread.sleep(20L); } catch (InterruptedException ignored) {}
                    }

                    long now = System.currentTimeMillis();
                    if (now - lastStatus > 1000L) {
                        String s;
                        if (bytes > SAMPLE_RATE * 2L * 6L && peak < 20) {
                            if (mode.equals("mic")) {
                                s = "マイク入力がほぼ無音です。端末のスピーカー音量を上げてください。";
                            } else {
                                s = "内部音声がほぼ無音です。radikoアプリではなくブラウザ再生を試してください。";
                            }
                        } else if (restartScheduled) {
                            s = "音声は取得中です。認識器を自動再接続しています…";
                        } else {
                            s = mode.equals("mic")
                                    ? "マイク経由で文字起こし中。"
                                    : "ブラウザ内部音声で文字起こし中。";
                        }
                        broadcast(s, true);
                        lastStatus = now;
                    }
                } catch (Exception e) {
                    if (running) {
                        broadcast("音声入力エラー: " + e.getMessage(), true);
                        try { Thread.sleep(100L); } catch (InterruptedException ignored) {}
                    }
                }
            }
        }, "AudioToSpeechPipe");
        captureThread.start();
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

    private String speechError(int e) {
        switch (e) {
            case SpeechRecognizer.ERROR_AUDIO: return "音声エラー";
            case SpeechRecognizer.ERROR_CLIENT: return "クライアントエラー";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "権限不足";
            case SpeechRecognizer.ERROR_NETWORK: return "ネットワークエラー";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "ネットワークタイムアウト";
            case SpeechRecognizer.ERROR_NO_MATCH: return "認識結果なし";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "認識器が使用中";
            case SpeechRecognizer.ERROR_SERVER: return "認識サービスエラー";
            case SpeechRecognizer.ERROR_SERVER_DISCONNECTED: return "認識サービス切断";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "音声待ちタイムアウト";
            case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED: return "日本語が未対応";
            case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE: return "日本語モデルが利用不可";
            default: return "コード " + e;
        }
    }

    private void stopEverything(String message) {
        if (running) flushPartialForRecovery();
        running = false;
        restartScheduled = false;
        mainHandler.removeCallbacks(watchdog);

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
        i.putExtra("text", displayText());
        sendBroadcast(i);
    }

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(
                CHANNEL, "文字起こし", NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(ch);
    }

    private Notification notification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("radiko文字起こし")
                .setContentText(text)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        getSystemService(NotificationManager.class).notify(NOTIFY_ID, notification(text));
    }

    @Override public void onDestroy() {
        mainHandler.removeCallbacks(watchdog);
        if (running) stopEverything("サービスを終了しました。");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}

package com.example.radikotranscriber;

import android.app.*;
import android.content.*;
import android.media.*;
import android.media.projection.*;
import android.os.*;
import android.speech.*;
import androidx.core.app.NotificationCompat;

import java.io.*;
import java.util.ArrayList;

public class TranscribeService extends Service {
    public static final String ACTION_START = "com.example.radikotranscriber.START";
    public static final String ACTION_STOP = "com.example.radikotranscriber.STOP";
    public static final String ACTION_UPDATE = "com.example.radikotranscriber.UPDATE";

    private static final String CHANNEL = "transcribe";
    private static final int NOTIFY_ID = 606;
    private static final int SAMPLE_RATE = 16000;

    private MediaProjection projection;
    private AudioRecord recorder;
    private SpeechRecognizer recognizer;
    private ParcelFileDescriptor readFd, writeFd;
    private OutputStream pipeOut;
    private Thread captureThread;
    private volatile boolean running = false;
    private int peak = 0;
    private long bytes = 0;
    private String finalText = "";

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        finalText = getSharedPreferences("state", MODE_PRIVATE).getString("transcript", "");
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (ACTION_START.equals(intent.getAction())) {
            startForeground(NOTIFY_ID, notification("文字起こし準備中"));
            int resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED);
            Intent data;
            if (Build.VERSION.SDK_INT >= 33) {
                data = intent.getParcelableExtra("projectionData", Intent.class);
            } else {
                data = intent.getParcelableExtra("projectionData");
            }
            begin(resultCode, data);
        } else if (ACTION_STOP.equals(intent.getAction())) {
            stopEverything("停止しました。");
        }
        return START_NOT_STICKY;
    }

    private void begin(int resultCode, Intent data) {
        if (data == null || running) return;
        try {
            MediaProjectionManager m = (MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = m.getMediaProjection(resultCode, data);

            AudioPlaybackCaptureConfiguration config =
                    new AudioPlaybackCaptureConfiguration.Builder(projection)
                            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                            .build();

            AudioFormat format = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build();

            int min = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);

            recorder = new AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(Math.max(min * 4, 32768))
                    .setAudioPlaybackCaptureConfig(config)
                    .build();

            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            readFd = pipe[0];
            writeFd = pipe[1];
            pipeOut = new ParcelFileDescriptor.AutoCloseOutputStream(writeFd);

            running = true;
            startRecognizer();
            recorder.startRecording();
            startCaptureThread();

            broadcast("文字起こし中。radikoでPodcastを再生してください。", true);
            updateNotification("文字起こし中");
        } catch (Exception e) {
            stopEverything("開始できませんでした: " + e.getClass().getSimpleName() + " / " + e.getMessage());
        }
    }

    private void startRecognizer() {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                recognizer = SpeechRecognizer.createSpeechRecognizer(this);
                recognizer.setRecognitionListener(new RecognitionListener() {
                    @Override public void onReadyForSpeech(Bundle params) {}
                    @Override public void onBeginningOfSpeech() {}
                    @Override public void onRmsChanged(float rmsdB) {}
                    @Override public void onBufferReceived(byte[] buffer) {}
                    @Override public void onEndOfSpeech() {}
                    @Override public void onError(int error) {
                        if (!running) return;
                        String msg = speechError(error);
                        broadcast("音声認識エラー: " + msg + "。再試行します…", true);
                        restartRecognizerDelayed();
                    }
                    @Override public void onResults(Bundle results) {
                        appendBest(results);
                        if (running) restartRecognizerDelayed();
                    }
                    @Override public void onPartialResults(Bundle partialResults) {
                        ArrayList<String> list = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                        if (list != null && !list.isEmpty()) {
                            broadcast("認識中: " + list.get(0), true);
                        }
                    }
                    @Override public void onEvent(int eventType, Bundle params) {}
                    @Override public void onSegmentResults(Bundle segmentResults) {
                        appendBest(segmentResults);
                    }
                    @Override public void onEndOfSegmentedSession() {}
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
                recognizer.startListening(ri);
            } catch (Exception e) {
                broadcast("認識器を開始できません: " + e.getMessage(), true);
            }
        });
    }

    private void restartRecognizerDelayed() {
        // EXTRA_AUDIO_SOURCE uses a pipe that belongs to the running session.
        // Some recognition providers keep the segmented session alive until the pipe closes.
        // If a provider ends early, avoid reopening the same fd aggressively.
    }

    private void appendBest(Bundle b) {
        ArrayList<String> list = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (list == null || list.isEmpty()) return;
        String s = list.get(0).trim();
        if (s.isEmpty()) return;
        if (!finalText.isEmpty() && !finalText.endsWith("\n")) finalText += "\n";
        finalText += s;
        getSharedPreferences("state", MODE_PRIVATE).edit().putString("transcript", finalText).apply();
        broadcast("文字起こし中。", true);
    }

    private void startCaptureThread() {
        captureThread = new Thread(() -> {
            short[] buf = new short[4096];
            byte[] out = new byte[buf.length * 2];
            long last = 0;
            try {
                while (running) {
                    int n = recorder.read(buf, 0, buf.length);
                    if (n <= 0) continue;

                    int localPeak = 0;
                    for (int i = 0; i < n; i++) {
                        int v = buf[i];
                        int a = Math.abs(v);
                        if (a > localPeak) localPeak = a;
                        out[i*2] = (byte)(v & 0xff);
                        out[i*2+1] = (byte)((v >> 8) & 0xff);
                    }
                    peak = localPeak;
                    bytes += n * 2L;
                    pipeOut.write(out, 0, n * 2);

                    long now = System.currentTimeMillis();
                    if (now - last > 1000) {
                        String s;
                        if (bytes > SAMPLE_RATE * 2L * 6L && peak < 20) {
                            s = "音声がほぼ無音です。radikoが再生中なら、再生側がキャプチャを許可していない可能性があります。";
                        } else {
                            s = "文字起こし中。radikoで再生してください。";
                        }
                        broadcast(s, true);
                        last = now;
                    }
                }
            } catch (Exception e) {
                if (running) broadcast("音声入力エラー: " + e.getMessage(), true);
            }
        }, "PlaybackToSpeechPipe");
        captureThread.start();
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
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "音声待ちタイムアウト";
            default: return "コード " + e;
        }
    }

    private void stopEverything(String message) {
        running = false;

        try { if (pipeOut != null) pipeOut.close(); } catch (Exception ignored) {}
        pipeOut = null;
        try { if (readFd != null) readFd.close(); } catch (Exception ignored) {}
        readFd = null;

        try {
            if (recorder != null) {
                recorder.stop();
                recorder.release();
            }
        } catch (Exception ignored) {}
        recorder = null;

        if (recognizer != null) {
            new Handler(Looper.getMainLooper()).post(() -> {
                try { recognizer.stopListening(); } catch (Exception ignored) {}
                try { recognizer.destroy(); } catch (Exception ignored) {}
                recognizer = null;
            });
        }

        try { if (projection != null) projection.stop(); } catch (Exception ignored) {}
        projection = null;

        broadcast(message, false);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void broadcast(String status, boolean isRunning) {
        Intent i = new Intent(ACTION_UPDATE);
        i.setPackage(getPackageName());
        i.putExtra("status", status);
        i.putExtra("running", isRunning);
        i.putExtra("peak", peak);
        i.putExtra("text", finalText);
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
        if (running) stopEverything("サービスを終了しました。");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}

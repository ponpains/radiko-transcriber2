package com.example.radikotranscriber;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.*;
import android.text.*;
import android.view.View;
import android.widget.*;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.io.OutputStreamWriter;
import java.util.ArrayList;

public class MainActivity extends AppCompatActivity {
    private static final int MODE_NONE = 0;
    private static final int MODE_INTERNAL = 1;
    private static final int MODE_MIC = 2;

    private EditText programInput, episodeInput, urlInput, transcript, librarySearch;
    private TextView status, meter, libraryCount, currentEpisodeLabel;
    private Button startButton, micStartButton, stopButton, newEpisodeButton;
    private LinearLayout libraryContainer;
    private MediaProjectionManager projectionManager;
    private EpisodeStore store;

    private int pendingMode = MODE_NONE;
    private long activeEpisodeId = -1L;
    private boolean serviceRunning = false;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!TranscribeService.ACTION_UPDATE.equals(intent.getAction())) return;
            String s = intent.getStringExtra("status");
            String text = intent.getStringExtra("text");
            int peak = intent.getIntExtra("peak", 0);
            int reconnects = intent.getIntExtra("reconnects", 0);
            boolean running = intent.getBooleanExtra("running", false);
            String mode = intent.getStringExtra("mode");
            long episodeId = intent.getLongExtra("episodeId", -1L);

            serviceRunning = running;
            if (episodeId > 0) activeEpisodeId = episodeId;
            if (s != null) status.setText(s);

            String meterText = ("mic".equals(mode) ? "マイク" : "内部音声") + "  " + peak;
            if (running) meterText += "   自動復旧 " + reconnects + "回";
            meter.setText(meterText);

            if (text != null && !text.contentEquals(transcript.getText())) {
                transcript.setText(text);
                transcript.setSelection(transcript.length());
            }

            updateRunningUi();
            updateCurrentLabel();
            if (!running) renderLibrary();
        }
    };

    private final ActivityResultLauncher<String> recordPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (!granted) {
                    status.setText("音声認識に必要なマイク権限がありません。");
                    pendingMode = MODE_NONE;
                    return;
                }
                continuePendingStart();
            });

    private final ActivityResultLauncher<String> notificationPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {});

    private final ActivityResultLauncher<Intent> projectionPermission =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
                    status.setText("画面共有の許可がキャンセルされました。");
                    pendingMode = MODE_NONE;
                    return;
                }

                long id = ensureEpisode();
                if (id <= 0) {
                    status.setText("回を保存できませんでした。");
                    pendingMode = MODE_NONE;
                    return;
                }

                Intent service = new Intent(this, TranscribeService.class);
                service.setAction(TranscribeService.ACTION_START_INTERNAL);
                service.putExtra("episodeId", id);
                service.putExtra("resultCode", result.getResultCode());
                service.putExtra("projectionData", result.getData());
                ContextCompat.startForegroundService(this, service);
                status.setText("文字起こしを開始しています… ブラウザを開きます。");
                pendingMode = MODE_NONE;

                new Handler(Looper.getMainLooper()).postDelayed(this::openInBrowser, 650L);
            });

    private final ActivityResultLauncher<String> exportJsonLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"), uri -> {
                if (uri == null) return;
                try (OutputStreamWriter w = new OutputStreamWriter(
                        getContentResolver().openOutputStream(uri), "UTF-8")) {
                    w.write(store.exportJson());
                    Toast.makeText(this, "全回をJSONで書き出しました", Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(this, "書き出しに失敗しました", Toast.LENGTH_LONG).show();
                }
            });

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        store = new EpisodeStore(this);
        store.migrateLegacyIfNeeded(this);
        projectionManager = (MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);

        programInput = findViewById(R.id.programInput);
        episodeInput = findViewById(R.id.episodeInput);
        urlInput = findViewById(R.id.urlInput);
        transcript = findViewById(R.id.transcript);
        librarySearch = findViewById(R.id.librarySearch);
        status = findViewById(R.id.status);
        meter = findViewById(R.id.meter);
        libraryCount = findViewById(R.id.libraryCount);
        currentEpisodeLabel = findViewById(R.id.currentEpisodeLabel);
        libraryContainer = findViewById(R.id.libraryContainer);
        startButton = findViewById(R.id.startButton);
        micStartButton = findViewById(R.id.micStartButton);
        stopButton = findViewById(R.id.stopButton);
        newEpisodeButton = findViewById(R.id.newEpisodeButton);

        startButton.setOnClickListener(v -> requestStart(MODE_INTERNAL));
        micStartButton.setOnClickListener(v -> requestStart(MODE_MIC));
        findViewById(R.id.openBrowserButton).setOnClickListener(v -> openInBrowser());
        stopButton.setOnClickListener(v -> stopServiceTranscription());
        newEpisodeButton.setOnClickListener(v -> newEpisode());
        findViewById(R.id.copyButton).setOnClickListener(v -> copyCurrent());
        findViewById(R.id.exportJsonButton).setOnClickListener(v ->
                exportJsonLauncher.launch("radiko-transcripts.json"));

        librarySearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { renderLibrary(); }
            @Override public void afterTextChanged(Editable s) {}
        });

        IntentFilter filter = new IntentFilter(TranscribeService.ACTION_UPDATE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, filter);

        renderLibrary();
        ArrayList<EpisodeStore.Episode> all = store.listEpisodes("");
        if (!all.isEmpty()) loadEpisode(all.get(0));
        else newEpisode();

        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            new Handler(Looper.getMainLooper()).postDelayed(() ->
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS), 700L);
        }
    }

    private void requestStart(int mode) {
        if (Build.VERSION.SDK_INT < 33) {
            status.setText("Android 13以上が必要です。");
            return;
        }
        if (serviceRunning) return;
        if (urlInput.getText().toString().trim().isEmpty()) {
            status.setText("先にradiko PodcastのURLを入力してください。");
            urlInput.requestFocus();
            return;
        }

        pendingMode = mode;
        ensureEpisode();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            recordPermission.launch(Manifest.permission.RECORD_AUDIO);
        } else {
            continuePendingStart();
        }
    }

    private void continuePendingStart() {
        if (pendingMode == MODE_INTERNAL) {
            projectionPermission.launch(projectionManager.createScreenCaptureIntent());
        } else if (pendingMode == MODE_MIC) {
            long id = ensureEpisode();
            Intent service = new Intent(this, TranscribeService.class);
            service.setAction(TranscribeService.ACTION_START_MIC);
            service.putExtra("episodeId", id);
            ContextCompat.startForegroundService(this, service);
            status.setText("マイク文字起こしを開始しています…");
            pendingMode = MODE_NONE;
            new Handler(Looper.getMainLooper()).postDelayed(this::openInBrowser, 650L);
        }
    }

    private long ensureEpisode() {
        String program = programInput.getText().toString().trim();
        String title = episodeInput.getText().toString().trim();
        String url = urlInput.getText().toString().trim();
        if (title.isEmpty()) title = "名称未入力の回";

        if (activeEpisodeId <= 0) {
            activeEpisodeId = store.createEpisode(program, title, url);
        } else {
            store.updateMeta(activeEpisodeId, program, title, url);
            if (!serviceRunning) store.updateTranscript(activeEpisodeId,
                    transcript.getText().toString(), null);
        }
        updateCurrentLabel();
        renderLibrary();
        return activeEpisodeId;
    }

    private void newEpisode() {
        if (serviceRunning) {
            Toast.makeText(this, "文字起こしを停止してから新しい回を作成してください", Toast.LENGTH_LONG).show();
            return;
        }
        saveCurrentEdits();
        activeEpisodeId = -1L;
        programInput.setText("");
        episodeInput.setText("");
        urlInput.setText("");
        transcript.setText("");
        status.setText("新しい回を準備中。URLを貼って開始してください。");
        meter.setText("待機中");
        updateCurrentLabel();
    }

    private void loadEpisode(EpisodeStore.Episode e) {
        if (e == null || serviceRunning) return;
        saveCurrentEdits();
        activeEpisodeId = e.id;
        programInput.setText(e.program);
        episodeInput.setText(e.title);
        urlInput.setText(e.url);
        transcript.setText(e.transcript);
        transcript.setSelection(transcript.length());
        status.setText("保存済みの文字起こしを表示しています。");
        meter.setText(e.transcript.length() + "文字");
        updateCurrentLabel();
    }

    private void renderLibrary() {
        if (store == null || libraryContainer == null) return;
        String q = librarySearch == null ? "" : librarySearch.getText().toString();
        ArrayList<EpisodeStore.Episode> list = store.listEpisodes(q);
        libraryContainer.removeAllViews();
        libraryCount.setText(store.count() + "回保存済み" + (q.trim().isEmpty() ? "" : " / " + list.size() + "件表示"));

        if (list.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("まだ保存された回はありません。");
            empty.setPadding(8, 16, 8, 16);
            libraryContainer.addView(empty);
            return;
        }

        for (EpisodeStore.Episode e : list) {
            Button b = new Button(this);
            String p = e.program.isEmpty() ? "番組名未入力" : e.program;
            String t = e.title.isEmpty() ? "名称未入力の回" : e.title;
            b.setText(p + "\n" + t + "\n" + EpisodeStore.displayDate(e.updatedAt)
                    + "   " + e.transcript.length() + "文字");
            b.setAllCaps(false);
            b.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
            b.setPadding(18, 10, 18, 10);
            b.setOnClickListener(v -> loadEpisode(store.getEpisode(e.id)));
            b.setOnLongClickListener(v -> {
                if (serviceRunning && activeEpisodeId == e.id) {
                    Toast.makeText(this, "文字起こし中の回は削除できません", Toast.LENGTH_SHORT).show();
                    return true;
                }
                new AlertDialog.Builder(this)
                        .setTitle("この回を削除しますか？")
                        .setMessage(p + " / " + t)
                        .setNegativeButton("キャンセル", null)
                        .setPositiveButton("削除", (d, w) -> {
                            store.deleteEpisode(e.id);
                            if (activeEpisodeId == e.id) {
                                activeEpisodeId = -1L;
                                transcript.setText("");
                            }
                            renderLibrary();
                            updateCurrentLabel();
                        }).show();
                return true;
            });
            libraryContainer.addView(b);
        }
    }

    private void stopServiceTranscription() {
        Intent i = new Intent(this, TranscribeService.class);
        i.setAction(TranscribeService.ACTION_STOP);
        startService(i);
    }

    private void copyCurrent() {
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        String header = programInput.getText().toString().trim() + " / "
                + episodeInput.getText().toString().trim();
        cm.setPrimaryClip(android.content.ClipData.newPlainText(
                "ラジオ文字起こし", header + "\n" + urlInput.getText() + "\n\n" + transcript.getText()));
        Toast.makeText(this, "この回をコピーしました", Toast.LENGTH_SHORT).show();
    }

    private Uri currentUri() {
        String s = urlInput.getText().toString().trim();
        return s.isEmpty() ? null : Uri.parse(s);
    }

    private void openInBrowser() {
        Uri uri = currentUri();
        if (uri == null) {
            status.setText("URLを入力してください。");
            return;
        }
        String[] packages = {"com.android.chrome", "com.sec.android.app.sbrowser"};
        for (String pkg : packages) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, uri);
                i.setPackage(pkg);
                startActivity(i);
                return;
            } catch (Exception ignored) {}
        }
        try {
            startActivity(Intent.createChooser(new Intent(Intent.ACTION_VIEW, uri), "ブラウザで開く"));
        } catch (Exception e) {
            status.setText("ブラウザを開けませんでした。");
        }
    }

    private void updateRunningUi() {
        startButton.setEnabled(!serviceRunning);
        micStartButton.setEnabled(!serviceRunning);
        newEpisodeButton.setEnabled(!serviceRunning);
        stopButton.setEnabled(serviceRunning);
        programInput.setEnabled(!serviceRunning);
        episodeInput.setEnabled(!serviceRunning);
        urlInput.setEnabled(!serviceRunning);
    }

    private void updateCurrentLabel() {
        if (activeEpisodeId <= 0) {
            currentEpisodeLabel.setText("新しい回");
            return;
        }
        String p = programInput.getText().toString().trim();
        String t = episodeInput.getText().toString().trim();
        String label = (p.isEmpty() ? "番組名未入力" : p) + " / "
                + (t.isEmpty() ? "名称未入力の回" : t);
        currentEpisodeLabel.setText(label + (serviceRunning ? "  ● 文字起こし中" : ""));
    }

    private void saveCurrentEdits() {
        if (store == null || activeEpisodeId <= 0 || serviceRunning) return;
        store.updateMeta(activeEpisodeId,
                programInput.getText().toString().trim(),
                episodeInput.getText().toString().trim(),
                urlInput.getText().toString().trim());
        store.updateTranscript(activeEpisodeId, transcript.getText().toString(), null);
    }

    @Override protected void onPause() {
        saveCurrentEdits();
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        renderLibrary();
    }

    @Override protected void onDestroy() {
        try { unregisterReceiver(receiver); } catch (Exception ignored) {}
        super.onDestroy();
    }
}

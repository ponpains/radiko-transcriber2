package com.example.radikotranscriber;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.*;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;

public class MainActivity extends AppCompatActivity {
    private static final int MODE_NONE = 0, MODE_INTERNAL = 1, MODE_MIC = 2;

    private EditText programInput, episodeInput, urlInput, transcript;
    private TextView status, meter, playbackPanel, libraryCount, currentEpisodeLabel, correctionHint;
    private Button startButton, micStartButton, stopButton, newEpisodeButton, learnButton;
    private LinearLayout libraryContainer;
    private Spinner playbackSpeedSpinner;
    private CheckBox autoStopCheck, inAppPlaybackCheck;
    private MediaProjectionManager projectionManager;
    private EpisodeStore store;
    private DiagnosticStore diagnostics;
    private int pendingMode = MODE_NONE;
    private long activeEpisodeId = -1L;
    private boolean serviceRunning = false;
    private boolean applyingServiceText = false;
    private boolean userModifiedDuringRun = false;
    private String lastServiceText = "";
    private long lastUiDiagnosticAt = 0L;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!TranscribeService.ACTION_UPDATE.equals(intent.getAction())) return;
            String s = intent.getStringExtra("status");
            String text = intent.getStringExtra("text");
            String mode = intent.getStringExtra("mode");
            int peak = intent.getIntExtra("peak", 0);
            int reconnects = intent.getIntExtra("reconnects", 0);
            boolean running = intent.getBooleanExtra("running", false);
            long episodeId = intent.getLongExtra("episodeId", -1L);
            float speed = intent.getFloatExtra("playbackSpeed", 1.0f);
            long mediaPositionMs = intent.getLongExtra("mediaPositionMs", 0L);
            long durationMs = intent.getLongExtra("durationMs", 0L);
            long backlogMs = intent.getLongExtra("backlogMs", 0L);
            String phase = intent.getStringExtra("phase");
            long sequence = intent.getLongExtra("sequence", 0L);

            serviceRunning = running;
            if (episodeId > 0) activeEpisodeId = episodeId;
            if (s != null) status.setText(s);

            String m = ("mic".equals(mode) ? "マイク" : "内部音声") + "  " + peak;
            if (running) m += "   自動復旧 " + reconnects + "回   " + speedLabel(speed);
            meter.setText(m);
            updatePlaybackPanel(running, phase, mediaPositionMs, durationMs, backlogMs, speed);

            if (running && text != null) {
                lastServiceText = text;
                if (!userModifiedDuringRun) {
                    applyServiceTextPreservingSelection(text);
                } else if (System.currentTimeMillis() - lastUiDiagnosticAt > 10000L) {
                    diagnostics.log(activeEpisodeId, "ui_live_sync_deferred",
                            "reason=user_edit;sequence=" + sequence + ";serviceChars=" + text.length()
                                    + ";visibleChars=" + transcript.length());
                    lastUiDiagnosticAt = System.currentTimeMillis();
                }
            }

            if (!running) {
                userModifiedDuringRun = false;
                if (activeEpisodeId > 0) refreshTranscriptFromStore(true, false);
            }

            updateRunningUi();
            updateCurrentLabel();
            if (!running) renderRecentEpisodes();
        }
    };

    private final ActivityResultLauncher<String> recordPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (!granted) {
                    status.setText("音声認識に必要なマイク権限がありません。");
                    pendingMode = MODE_NONE;
                } else continuePendingStart();
            });

    private final ActivityResultLauncher<String> notificationPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {});

    private final ActivityResultLauncher<Intent> projectionPermission = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
                    status.setText("画面共有の許可がキャンセルされました。");
                    pendingMode = MODE_NONE;
                    return;
                }
                long id = ensureEpisode();
                Intent service = new Intent(this, TranscribeService.class);
                service.setAction(TranscribeService.ACTION_START_INTERNAL);
                service.putExtra("episodeId", id);
                service.putExtra("resultCode", result.getResultCode());
                service.putExtra("projectionData", result.getData());
                service.putExtra("playbackSpeed", selectedPlaybackSpeed());
                service.putExtra("autoStop", autoStopCheck.isChecked());
                ContextCompat.startForegroundService(this, service);
                status.setText("文字起こしを開始しています… 再生画面を開きます。");
                pendingMode = MODE_NONE;
                userModifiedDuringRun = false;
                transcript.clearFocus();
                new Handler(Looper.getMainLooper()).postDelayed(this::openPlaybackTarget, 700L);
            });

    private final ActivityResultLauncher<String> backupLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/json"),
            uri -> writeText(uri, store.exportBackupJson(), "完全バックアップを書き出しました"));

    private final ActivityResultLauncher<String> csvLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("text/csv"),
            uri -> writeText(uri, store.exportCsv(), "CSVを書き出しました"));

    private final ActivityResultLauncher<String[]> restoreLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                try {
                    StringBuilder b = new StringBuilder();
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(
                            getContentResolver().openInputStream(uri), "UTF-8"))) {
                        String line;
                        while ((line = r.readLine()) != null) b.append(line).append('\n');
                    }
                    int n = store.importBackupJson(b.toString(), true);
                    activeEpisodeId = -1L;
                    renderRecentEpisodes();
                    ArrayList<EpisodeStore.Episode> all = store.listEpisodes("");
                    if (!all.isEmpty()) loadEpisode(all.get(0));
                    else newEpisode();
                    Toast.makeText(this, n + "回を復元しました", Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(this, "復元に失敗しました: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        store = new EpisodeStore(this);
        diagnostics = new DiagnosticStore(this);
        store.migrateLegacyIfNeeded(this);
        projectionManager = (MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);

        programInput = findViewById(R.id.programInput);
        episodeInput = findViewById(R.id.episodeInput);
        urlInput = findViewById(R.id.urlInput);
        transcript = findViewById(R.id.transcript);
        status = findViewById(R.id.status);
        meter = findViewById(R.id.meter);
        playbackPanel = findViewById(R.id.playbackPanel);
        libraryCount = findViewById(R.id.libraryCount);
        currentEpisodeLabel = findViewById(R.id.currentEpisodeLabel);
        correctionHint = findViewById(R.id.correctionHint);
        libraryContainer = findViewById(R.id.libraryContainer);
        startButton = findViewById(R.id.startButton);
        micStartButton = findViewById(R.id.micStartButton);
        stopButton = findViewById(R.id.stopButton);
        newEpisodeButton = findViewById(R.id.newEpisodeButton);
        learnButton = findViewById(R.id.learnButton);
        playbackSpeedSpinner = findViewById(R.id.playbackSpeedSpinner);
        autoStopCheck = findViewById(R.id.autoStopCheck);
        inAppPlaybackCheck = findViewById(R.id.inAppPlaybackCheck);

        setupSpeedSpinner();
        setupTranscriptEditor();
        inAppPlaybackCheck.setChecked(getSharedPreferences("settings", MODE_PRIVATE)
                .getBoolean("inAppPlayback", false));
        inAppPlaybackCheck.setOnCheckedChangeListener((button, checked) ->
                getSharedPreferences("settings", MODE_PRIVATE).edit()
                        .putBoolean("inAppPlayback", checked).apply());

        startButton.setOnClickListener(v -> requestStart(MODE_INTERNAL));
        micStartButton.setOnClickListener(v -> requestStart(MODE_MIC));
        findViewById(R.id.openBrowserButton).setOnClickListener(v -> openInBrowser());
        findViewById(R.id.openInAppButton).setOnClickListener(v -> openInAppBrowser());
        stopButton.setOnClickListener(v -> stopServiceTranscription());
        newEpisodeButton.setOnClickListener(v -> newEpisode());
        learnButton.setOnClickListener(v -> learnCurrentCorrections());
        findViewById(R.id.copyButton).setOnClickListener(v -> copyCurrent());
        findViewById(R.id.detailButton).setOnClickListener(v -> openCurrentDetail());
        findViewById(R.id.dictionaryButton).setOnClickListener(v -> openDictionary());
        findViewById(R.id.programSummaryButton).setOnClickListener(v -> openProgramSummary());
        findViewById(R.id.openArchiveButton).setOnClickListener(v ->
                startActivity(new Intent(this, ArchiveActivity.class)));
        findViewById(R.id.backupJsonButton).setOnClickListener(v ->
                backupLauncher.launch("radio-transcriber-backup.json"));
        findViewById(R.id.exportCsvButton).setOnClickListener(v ->
                csvLauncher.launch("radio-transcripts.csv"));
        findViewById(R.id.diagnosticShareButton).setOnClickListener(v -> shareDiagnostics());
        findViewById(R.id.restoreBackupButton).setOnClickListener(v -> confirmRestoreFile());
        findViewById(R.id.restoreAutoButton).setOnClickListener(v -> confirmRestoreAuto());

        IntentFilter filter = new IntentFilter(TranscribeService.ACTION_UPDATE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, filter);

        renderRecentEpisodes();
        ArrayList<EpisodeStore.Episode> all = store.listEpisodes("");
        if (!all.isEmpty()) loadEpisode(all.get(0));
        else newEpisode();
        syncRuntimeState();

        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            new Handler(Looper.getMainLooper()).postDelayed(() ->
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS), 700L);
        }
    }

    private void setupSpeedSpinner() {
        ArrayAdapter<String> a = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"1.0x（最も安定）", "1.5x（バッファ処理）", "2.0x（バッファ処理）"});
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        playbackSpeedSpinner.setAdapter(a);
    }

    private void setupTranscriptEditor() {
        transcript.setVerticalScrollBarEnabled(true);
        transcript.setNestedScrollingEnabled(true);
        transcript.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        transcript.setOnTouchListener((v, e) -> {
            ViewParent p = v.getParent();
            if (p != null) {
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) {
                    p.requestDisallowInterceptTouchEvent(true);
                } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    p.requestDisallowInterceptTouchEvent(false);
                }
            }
            return false;
        });
        transcript.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (serviceRunning && !applyingServiceText) {
                    userModifiedDuringRun = true;
                    diagnostics.log(activeEpisodeId, "ui_manual_edit_during_run",
                            "visibleChars=" + s.length() + ";serviceChars=" + lastServiceText.length());
                }
            }
        });
        transcript.setOnFocusChangeListener((v, hasFocus) -> {
            diagnostics.log(activeEpisodeId, "ui_transcript_focus", "focused=" + hasFocus
                    + ";running=" + serviceRunning + ";userModified=" + userModifiedDuringRun);
            if (!hasFocus && serviceRunning && activeEpisodeId > 0) {
                userModifiedDuringRun = false;
                refreshTranscriptFromStore(true, true);
            }
        });
    }

    private void applyServiceTextPreservingSelection(String text) {
        if (text == null || text.contentEquals(transcript.getText())) return;
        String old = transcript.getText().toString();
        int oldSelStart = Math.max(0, transcript.getSelectionStart());
        int oldSelEnd = Math.max(0, transcript.getSelectionEnd());
        int oldScrollY = transcript.getScrollY();
        boolean focused = transcript.hasFocus();
        boolean wasAtEnd = oldSelStart == old.length() && oldSelEnd == old.length();
        int prefix = commonPrefix(old, text);

        applyingServiceText = true;
        try {
            Editable editable = transcript.getText();
            editable.replace(prefix, editable.length(), text.substring(prefix));
            int newLen = editable.length();
            if (!focused || wasAtEnd) {
                transcript.setSelection(newLen);
            } else {
                int ns = Math.min(oldSelStart, newLen);
                int ne = Math.min(oldSelEnd, newLen);
                transcript.setSelection(Math.min(ns, ne), Math.max(ns, ne));
                transcript.post(() -> transcript.scrollTo(transcript.getScrollX(), oldScrollY));
            }
        } finally {
            applyingServiceText = false;
        }
    }

    private int commonPrefix(String a, String b) {
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) i++;
        return i;
    }

    private void refreshTranscriptFromStore(boolean moveToEnd, boolean evenIfFocused) {
        if (activeEpisodeId <= 0) return;
        if (!evenIfFocused && transcript.hasFocus()) return;
        EpisodeStore.Episode e = store.getEpisode(activeEpisodeId);
        if (e == null || e.transcript.contentEquals(transcript.getText())) return;
        if (serviceRunning) {
            applyServiceTextPreservingSelection(e.transcript);
        } else {
            applyingServiceText = true;
            try {
                transcript.setText(e.transcript);
                if (moveToEnd) transcript.setSelection(transcript.length());
            } finally { applyingServiceText = false; }
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
        } else continuePendingStart();
    }

    private void continuePendingStart() {
        if (pendingMode == MODE_INTERNAL) {
            projectionPermission.launch(projectionManager.createScreenCaptureIntent());
        } else if (pendingMode == MODE_MIC) {
            long id = ensureEpisode();
            Intent s = new Intent(this, TranscribeService.class);
            s.setAction(TranscribeService.ACTION_START_MIC);
            s.putExtra("episodeId", id);
            s.putExtra("playbackSpeed", selectedPlaybackSpeed());
            s.putExtra("autoStop", autoStopCheck.isChecked());
            ContextCompat.startForegroundService(this, s);
            pendingMode = MODE_NONE;
            userModifiedDuringRun = false;
            transcript.clearFocus();
            new Handler(Looper.getMainLooper()).postDelayed(this::openPlaybackTarget, 700L);
        }
    }

    private float selectedPlaybackSpeed() {
        int p = playbackSpeedSpinner.getSelectedItemPosition();
        return p == 2 ? 2.0f : p == 1 ? 1.5f : 1.0f;
    }

    private void setPlaybackSpeed(float s) {
        playbackSpeedSpinner.setSelection(s >= 1.9f ? 2 : s >= 1.4f ? 1 : 0);
    }

    private String speedLabel(float s) {
        return s >= 1.9f ? "2.0xバッファ" : s >= 1.4f ? "1.5xバッファ" : "1.0x";
    }

    private long ensureEpisode() {
        String p = programInput.getText().toString().trim();
        String t = episodeInput.getText().toString().trim();
        String u = urlInput.getText().toString().trim();
        if (t.isEmpty()) t = "名称未入力の回";
        if (activeEpisodeId <= 0) {
            activeEpisodeId = store.createEpisode(p, t, u);
        } else {
            store.updateMeta(activeEpisodeId, p, t, u);
            if (!serviceRunning) store.updateEditedTranscript(activeEpisodeId, transcript.getText().toString());
        }
        updateCurrentLabel();
        renderRecentEpisodes();
        return activeEpisodeId;
    }

    private void newEpisode() {
        if (serviceRunning) {
            Toast.makeText(this, "文字起こしを停止してから新しい回を作成してください", Toast.LENGTH_LONG).show();
            return;
        }
        String keep = programInput == null ? "" : programInput.getText().toString().trim();
        saveCurrentEdits();
        activeEpisodeId = -1L;
        programInput.setText(keep);
        episodeInput.setText("");
        urlInput.setText("");
        applyingServiceText = true;
        try { transcript.setText(""); } finally { applyingServiceText = false; }
        setPlaybackSpeed(1.0f);
        correctionHint.setText("停止後に固有名詞などを直して「この修正を学習」を押すと次回から補正します。");
        status.setText("新しい回を準備中。URLを貼って開始してください。");
        meter.setText("待機中");
        playbackPanel.setText("再生位置：—");
        updateCurrentLabel();
    }

    private void loadEpisode(EpisodeStore.Episode e) {
        if (e == null || serviceRunning) return;
        saveCurrentEdits();
        activeEpisodeId = e.id;
        programInput.setText(e.program);
        episodeInput.setText(e.title);
        urlInput.setText(e.url);
        applyingServiceText = true;
        try {
            transcript.setText(e.transcript);
            transcript.setSelection(transcript.length());
        } finally { applyingServiceText = false; }
        setPlaybackSpeed(e.playbackSpeed);
        status.setText("保存済みの回です。詳細画面でタイムライン・メモ・タグも見られます。");
        meter.setText(e.transcript.length() + "文字   " + speedLabel(e.playbackSpeed)
                + "   " + EpisodeStore.formatDuration(e.durationMs));
        playbackPanel.setText("収録時間：約" + EpisodeStore.formatDuration(e.durationMs));
        updateCurrentLabel();
    }

    private void learnCurrentCorrections() {
        if (serviceRunning || activeEpisodeId <= 0) return;
        int n = store.learnCorrectionsFromEdit(activeEpisodeId, transcript.getText().toString());
        store.autoBackup(this);
        correctionHint.setText(n > 0 ? n + "件の修正を学習しました。"
                : "学習できる小さな表記修正は見つかりませんでした。");
        Toast.makeText(this, n > 0 ? n + "件を学習" : "学習対象なし", Toast.LENGTH_LONG).show();
        renderRecentEpisodes();
    }

    private void renderRecentEpisodes() {
        if (store == null || libraryContainer == null) return;
        ArrayList<EpisodeStore.Episode> list = store.listEpisodes("");
        libraryContainer.removeAllViews();
        libraryCount.setText(store.count() + "回保存済み　最近の" + Math.min(5, list.size()) + "回を表示");
        if (list.isEmpty()) {
            TextView e = new TextView(this);
            e.setText("まだ保存された回はありません。");
            e.setPadding(dp(8), dp(18), dp(8), dp(18));
            libraryContainer.addView(e);
            return;
        }
        int n = Math.min(5, list.size());
        for (int i = 0; i < n; i++) addRecentRow(list.get(i));
    }

    private void addRecentRow(EpisodeStore.Episode e) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(e.id == activeEpisodeId ? "#ECFDF5" : "#F8FAFC"));
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), Color.parseColor("#E5E7EB"));
        row.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(4), 0, dp(4));
        row.setLayoutParams(lp);

        TextView title = new TextView(this);
        title.setText((e.program.isEmpty() ? "番組名未入力" : e.program) + "  /  "
                + (e.title.isEmpty() ? "名称未入力の回" : e.title));
        title.setTextSize(14);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        row.addView(title);

        TextView meta = new TextView(this);
        meta.setText(EpisodeStore.displayDate(e.updatedAt) + "   " + e.transcript.length()
                + "文字   " + EpisodeStore.formatDuration(e.durationMs));
        meta.setTextSize(11);
        meta.setTextColor(Color.parseColor("#6B7280"));
        row.addView(meta);

        row.setOnClickListener(v -> {
            Intent i = new Intent(this, EpisodeDetailActivity.class);
            i.putExtra("episodeId", e.id);
            startActivity(i);
        });
        libraryContainer.addView(row);
    }

    private void stopServiceTranscription() {
        Intent i = new Intent(this, TranscribeService.class);
        i.setAction(TranscribeService.ACTION_STOP);
        startService(i);
    }

    private void copyCurrent() {
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        String h = programInput.getText() + " / " + episodeInput.getText();
        String body = activeEpisodeId > 0
                ? store.transcriptWithTimestamps(activeEpisodeId)
                : transcript.getText().toString();
        cm.setPrimaryClip(android.content.ClipData.newPlainText(
                "ラジオ文字起こし", h + "\n" + urlInput.getText() + "\n\n" + body));
        Toast.makeText(this, "タイムスタンプ付きでコピーしました", Toast.LENGTH_SHORT).show();
    }

    private Uri currentUri() {
        String s = urlInput.getText().toString().trim();
        return s.isEmpty() ? null : Uri.parse(s);
    }

    private void openPlaybackTarget() {
        if (inAppPlaybackCheck != null && inAppPlaybackCheck.isChecked()) openInAppBrowser();
        else openInBrowser();
    }

    private void openInAppBrowser() {
        Uri u = currentUri();
        if (u == null) {
            status.setText("URLを入力してください。");
            return;
        }
        Intent i = new Intent(this, InAppBrowserActivity.class);
        i.putExtra("url", u.toString());
        try {
            startActivity(i);
        } catch (Exception e) {
            status.setText("アプリ内再生を開けなかったため、外部ブラウザを開きます。");
            openInBrowser();
        }
    }

    private void openInBrowser() {
        Uri u = currentUri();
        if (u == null) {
            status.setText("URLを入力してください。");
            return;
        }
        String[] pkgs = {"com.android.chrome", "com.sec.android.app.sbrowser"};
        for (String pkg : pkgs) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, u);
                i.setPackage(pkg);
                startActivity(i);
                return;
            } catch (Exception ignored) {}
        }
        try {
            startActivity(Intent.createChooser(new Intent(Intent.ACTION_VIEW, u), "ブラウザで開く"));
        } catch (Exception e) {
            status.setText("ブラウザを開けませんでした。");
        }
    }

    private void openCurrentDetail() {
        if (activeEpisodeId <= 0) {
            Toast.makeText(this, "まだ保存された回がありません", Toast.LENGTH_SHORT).show();
            return;
        }
        saveCurrentEdits();
        Intent i = new Intent(this, EpisodeDetailActivity.class);
        i.putExtra("episodeId", activeEpisodeId);
        startActivity(i);
    }

    private void openDictionary() {
        Intent i = new Intent(this, DictionaryActivity.class);
        i.putExtra("program", programInput.getText().toString().trim());
        startActivity(i);
    }

    private void openProgramSummary() {
        Intent i = new Intent(this, ProgramActivity.class);
        i.putExtra("program", programInput.getText().toString().trim());
        startActivity(i);
    }

    private void shareDiagnostics() {
        try {
            String json = diagnostics.exportPack(store, 3);
            File f = new File(getCacheDir(), "radiko-diagnostics-latest3.json");
            try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8")) {
                w.write(json);
            }
            Uri uri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", f);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("application/json");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "診断パックを共有"));
            diagnostics.log(activeEpisodeId, "diagnostic_pack_shared", "latestEpisodes=3");
        } catch (Exception e) {
            Toast.makeText(this, "診断パックを作れませんでした: " + e.getClass().getSimpleName(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void updatePlaybackPanel(boolean running, String phase, long positionMs,
                                     long durationMs, long backlogMs, float speed) {
        if (!running) {
            if (durationMs > 0) playbackPanel.setText("収録時間：約" + EpisodeStore.formatDuration(durationMs));
            else playbackPanel.setText("再生位置：—");
            return;
        }
        String label;
        if ("draining".equals(phase)) label = "取り込み完了・認識処理中";
        else if ("finalizing".equals(phase)) label = "最終結果を確定中";
        else label = "再生取り込み中";
        String text = label + "   推定位置 " + EpisodeStore.formatDuration(positionMs)
                + "   " + speedLabel(speed);
        if (backlogMs > 1000L) text += "   認識待ち 約" + EpisodeStore.formatDuration(backlogMs);
        playbackPanel.setText(text);
    }

    private void syncRuntimeState() {
        SharedPreferences p = getSharedPreferences("runtime_state", MODE_PRIVATE);
        long heartbeat = p.getLong("heartbeatAt", 0L);
        boolean alive = p.getBoolean("running", false)
                && System.currentTimeMillis() - heartbeat < 30000L;
        if (!alive) return;
        serviceRunning = true;
        long id = p.getLong("episodeId", -1L);
        if (id > 0) activeEpisodeId = id;
        String rs = p.getString("status", "バックグラウンドで文字起こし中です。");
        status.setText(rs);
        long pos = p.getLong("mediaPositionMs", 0L);
        long backlog = p.getLong("backlogMs", 0L);
        String phase = p.getString("phase", "capturing");
        updatePlaybackPanel(true, phase, pos, 0L, backlog, selectedPlaybackSpeed());
        userModifiedDuringRun = false;
        refreshTranscriptFromStore(false, true);
        diagnostics.log(activeEpisodeId, "ui_runtime_resync",
                "heartbeatAgeMs=" + (System.currentTimeMillis() - heartbeat) + ";phase=" + phase);
        updateRunningUi();
        updateCurrentLabel();
    }

    private void confirmRestoreFile() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("完全バックアップから復元")
                .setMessage("現在のアーカイブを置き換えます。先にバックアップを書き出すのがおすすめです。")
                .setNegativeButton("キャンセル", null)
                .setPositiveButton("ファイルを選ぶ", (d, w) ->
                        restoreLauncher.launch(new String[]{"application/json", "text/plain"}))
                .show();
    }

    private void confirmRestoreAuto() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("自動バックアップから復元")
                .setMessage("直近の自動バックアップで現在のデータを置き換えます。")
                .setNegativeButton("キャンセル", null)
                .setPositiveButton("復元", (d, w) -> {
                    try {
                        int n = store.restoreLatestAutoBackup(this);
                        activeEpisodeId = -1;
                        renderRecentEpisodes();
                        ArrayList<EpisodeStore.Episode> a = store.listEpisodes("");
                        if (!a.isEmpty()) loadEpisode(a.get(0));
                        Toast.makeText(this, n + "回を復元しました", Toast.LENGTH_LONG).show();
                    } catch (Exception e) {
                        Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }).show();
    }

    private void writeText(Uri uri, String text, String ok) {
        if (uri == null) return;
        try (OutputStreamWriter w = new OutputStreamWriter(
                getContentResolver().openOutputStream(uri), "UTF-8")) {
            w.write(text);
            Toast.makeText(this, ok, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "書き出しに失敗しました", Toast.LENGTH_LONG).show();
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
        playbackSpeedSpinner.setEnabled(!serviceRunning);
        learnButton.setEnabled(!serviceRunning);
    }

    private void updateCurrentLabel() {
        if (activeEpisodeId <= 0) {
            currentEpisodeLabel.setText("新しい回");
            return;
        }
        String p = programInput.getText().toString().trim();
        String t = episodeInput.getText().toString().trim();
        currentEpisodeLabel.setText((p.isEmpty() ? "番組名未入力" : p) + " / "
                + (t.isEmpty() ? "名称未入力の回" : t)
                + (serviceRunning ? "  ● 文字起こし動作中" : ""));
    }

    private void saveCurrentEdits() {
        if (store == null || activeEpisodeId <= 0 || serviceRunning) return;
        store.updateMeta(activeEpisodeId,
                programInput.getText().toString().trim(),
                episodeInput.getText().toString().trim(),
                urlInput.getText().toString().trim());
        store.updateEditedTranscript(activeEpisodeId, transcript.getText().toString());
    }

    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }

    @Override protected void onPause() {
        diagnostics.log(activeEpisodeId, "ui_activity_pause", "running=" + serviceRunning);
        saveCurrentEdits();
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        if (store != null) {
            renderRecentEpisodes();
            syncRuntimeState();
            if (activeEpisodeId > 0 && !serviceRunning) {
                refreshTranscriptFromStore(false, false);
            }
            diagnostics.log(activeEpisodeId, "ui_activity_resume", "running=" + serviceRunning);
        }
    }

    @Override protected void onDestroy() {
        try { unregisterReceiver(receiver); } catch (Exception ignored) {}
        super.onDestroy();
    }
}

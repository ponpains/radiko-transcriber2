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
import android.text.*;
import android.text.method.ScrollingMovementMethod;
import android.view.*;
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
    private TextView status, meter, libraryCount, currentEpisodeLabel, correctionHint;
    private Button startButton, micStartButton, stopButton, newEpisodeButton, learnButton;
    private LinearLayout libraryContainer;
    private Spinner playbackSpeedSpinner, libraryProgramFilter;
    private CheckBox autoStopCheck;

    private MediaProjectionManager projectionManager;
    private EpisodeStore store;
    private int pendingMode = MODE_NONE;
    private long activeEpisodeId = -1L;
    private boolean serviceRunning = false;
    private boolean refreshingFilter = false;
    private final ArrayList<String> programFilterValues = new ArrayList<>();

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
            float speed = intent.getFloatExtra("playbackSpeed", 1.0f);

            serviceRunning = running;
            if (episodeId > 0) activeEpisodeId = episodeId;
            if (s != null) status.setText(s);

            String meterText = ("mic".equals(mode) ? "マイク" : "内部音声") + "  " + peak;
            if (running) meterText += "   自動復旧 " + reconnects + "回   " + speedLabel(speed);
            meter.setText(meterText);

            if (running && text != null && !text.contentEquals(transcript.getText())) {
                transcript.setText(text);
                transcript.setSelection(transcript.length());
            }

            if (!running && activeEpisodeId > 0) {
                EpisodeStore.Episode e = store.getEpisode(activeEpisodeId);
                if (e != null && !e.transcript.contentEquals(transcript.getText())) {
                    transcript.setText(e.transcript);
                    transcript.setSelection(transcript.length());
                }
            }

            updateRunningUi();
            updateCurrentLabel();
            if (!running) {
                refreshProgramFilter();
                renderLibrary();
            }
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
                service.putExtra("playbackSpeed", selectedPlaybackSpeed());
                service.putExtra("autoStop", autoStopCheck.isChecked());
                ContextCompat.startForegroundService(this, service);
                status.setText("文字起こしを開始しています… ブラウザを開きます。");
                pendingMode = MODE_NONE;

                new Handler(Looper.getMainLooper()).postDelayed(this::openInBrowser, 700L);
            });

    private final ActivityResultLauncher<String> exportJsonLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"), uri -> {
                if (uri == null) return;
                try (OutputStreamWriter w = new OutputStreamWriter(
                        getContentResolver().openOutputStream(uri), "UTF-8")) {
                    w.write(store.exportJson());
                    Toast.makeText(this, "全回と修正辞書をJSONで書き出しました", Toast.LENGTH_LONG).show();
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
        correctionHint = findViewById(R.id.correctionHint);
        libraryContainer = findViewById(R.id.libraryContainer);
        startButton = findViewById(R.id.startButton);
        micStartButton = findViewById(R.id.micStartButton);
        stopButton = findViewById(R.id.stopButton);
        newEpisodeButton = findViewById(R.id.newEpisodeButton);
        learnButton = findViewById(R.id.learnButton);
        playbackSpeedSpinner = findViewById(R.id.playbackSpeedSpinner);
        libraryProgramFilter = findViewById(R.id.libraryProgramFilter);
        autoStopCheck = findViewById(R.id.autoStopCheck);

        setupSpeedSpinner();
        setupTranscriptScrolling();

        startButton.setOnClickListener(v -> requestStart(MODE_INTERNAL));
        micStartButton.setOnClickListener(v -> requestStart(MODE_MIC));
        findViewById(R.id.openBrowserButton).setOnClickListener(v -> openInBrowser());
        stopButton.setOnClickListener(v -> stopServiceTranscription());
        newEpisodeButton.setOnClickListener(v -> newEpisode());
        learnButton.setOnClickListener(v -> learnCurrentCorrections());
        findViewById(R.id.copyButton).setOnClickListener(v -> copyCurrent());
        findViewById(R.id.exportJsonButton).setOnClickListener(v ->
                exportJsonLauncher.launch("radio-transcripts.json"));

        librarySearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { renderLibrary(); }
            @Override public void afterTextChanged(Editable s) {}
        });

        libraryProgramFilter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!refreshingFilter) renderLibrary();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        IntentFilter filter = new IntentFilter(TranscribeService.ACTION_UPDATE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, filter);

        refreshProgramFilter();
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

    private void setupSpeedSpinner() {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"1.0x（最も安定）", "1.5x（高速補助）", "2.0x（高速補助）"});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        playbackSpeedSpinner.setAdapter(adapter);
        playbackSpeedSpinner.setSelection(0);
    }

    private void setupTranscriptScrolling() {
        transcript.setMovementMethod(ScrollingMovementMethod.getInstance());
        transcript.setVerticalScrollBarEnabled(true);
        transcript.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        transcript.setOnTouchListener((v, event) -> {
            ViewParent p = v.getParent();
            if (p != null) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                        || event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    p.requestDisallowInterceptTouchEvent(true);
                } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                        || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    p.requestDisallowInterceptTouchEvent(false);
                }
            }
            return false;
        });
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
            service.putExtra("playbackSpeed", selectedPlaybackSpeed());
            service.putExtra("autoStop", autoStopCheck.isChecked());
            ContextCompat.startForegroundService(this, service);
            status.setText("マイク文字起こしを開始しています…");
            pendingMode = MODE_NONE;
            new Handler(Looper.getMainLooper()).postDelayed(this::openInBrowser, 700L);
        }
    }

    private float selectedPlaybackSpeed() {
        int p = playbackSpeedSpinner.getSelectedItemPosition();
        if (p == 2) return 2.0f;
        if (p == 1) return 1.5f;
        return 1.0f;
    }

    private void setPlaybackSpeed(float speed) {
        playbackSpeedSpinner.setSelection(speed >= 1.9f ? 2 : speed >= 1.4f ? 1 : 0);
    }

    private String speedLabel(float speed) {
        return speed >= 1.9f ? "2.0x補助" : speed >= 1.4f ? "1.5x補助" : "1.0x";
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
            if (!serviceRunning) store.updateEditedTranscript(activeEpisodeId,
                    transcript.getText().toString());
        }
        updateCurrentLabel();
        refreshProgramFilter();
        renderLibrary();
        return activeEpisodeId;
    }

    private void newEpisode() {
        if (serviceRunning) {
            Toast.makeText(this, "文字起こしを停止してから新しい回を作成してください", Toast.LENGTH_LONG).show();
            return;
        }
        String keepProgram = programInput == null ? "" : programInput.getText().toString().trim();
        saveCurrentEdits();
        activeEpisodeId = -1L;
        programInput.setText(keepProgram);
        episodeInput.setText("");
        urlInput.setText("");
        transcript.setText("");
        setPlaybackSpeed(1.0f);
        correctionHint.setText("停止後に固有名詞などを直して「この修正を学習」を押すと、次回から番組別に補正します。");
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
        setPlaybackSpeed(e.playbackSpeed);
        status.setText("保存済みの文字起こしを表示しています。手直し後は修正を学習できます。");
        meter.setText(e.transcript.length() + "文字   " + speedLabel(e.playbackSpeed));
        correctionHint.setText("自動文字起こしの元データも保持しています。表記を直したら「この修正を学習」で番組別辞書に反映できます。");
        updateCurrentLabel();
    }

    private void learnCurrentCorrections() {
        if (serviceRunning || activeEpisodeId <= 0) return;
        String edited = transcript.getText().toString();
        int learned = store.learnCorrectionsFromEdit(activeEpisodeId, edited);
        if (learned > 0) {
            correctionHint.setText(learned + "件の修正を学習しました。次回の認識候補と自動補正に使います。");
            Toast.makeText(this, learned + "件の修正を学習しました", Toast.LENGTH_LONG).show();
        } else {
            correctionHint.setText("学習できる小さな表記修正は見つかりませんでした。改行・句読点だけの変更は学習対象にしません。");
            Toast.makeText(this, "学習対象の表記修正は見つかりませんでした", Toast.LENGTH_LONG).show();
        }
        renderLibrary();
    }

    private void refreshProgramFilter() {
        if (libraryProgramFilter == null || store == null) return;
        String selected = currentProgramFilter();
        ArrayList<String> programs = store.listPrograms();
        programFilterValues.clear();
        programFilterValues.add("すべての番組");
        programFilterValues.addAll(programs);
        refreshingFilter = true;
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, programFilterValues);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        libraryProgramFilter.setAdapter(adapter);
        int index = 0;
        if (!selected.isEmpty()) {
            int found = programFilterValues.indexOf(selected);
            if (found >= 0) index = found;
        }
        libraryProgramFilter.setSelection(index);
        refreshingFilter = false;
    }

    private String currentProgramFilter() {
        if (libraryProgramFilter == null || libraryProgramFilter.getSelectedItemPosition() <= 0) return "";
        Object item = libraryProgramFilter.getSelectedItem();
        return item == null ? "" : item.toString();
    }

    private void renderLibrary() {
        if (store == null || libraryContainer == null) return;
        String q = librarySearch == null ? "" : librarySearch.getText().toString();
        String pf = currentProgramFilter();
        ArrayList<EpisodeStore.Episode> list = store.listEpisodes(q, pf);
        libraryContainer.removeAllViews();
        libraryCount.setText(store.count() + "回保存済み / " + list.size() + "件表示");

        if (list.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("該当する文字起こしはありません。");
            empty.setTextColor(Color.parseColor("#6B7280"));
            empty.setPadding(dp(8), dp(18), dp(8), dp(18));
            libraryContainer.addView(empty);
            return;
        }

        for (EpisodeStore.Episode e : list) addEpisodeCard(e);
    }

    private void addEpisodeCard(EpisodeStore.Episode e) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(e.id == activeEpisodeId ? "#ECFDF5" : "#F8FAFC"));
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), Color.parseColor(e.id == activeEpisodeId ? "#99D5C9" : "#E5E7EB"));
        card.setBackground(bg);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cp.setMargins(0, dp(5), 0, dp(5));
        card.setLayoutParams(cp);

        TextView programView = new TextView(this);
        programView.setText(e.program.isEmpty() ? "番組名未入力" : e.program);
        programView.setTextSize(12);
        programView.setTextColor(Color.parseColor("#6B7280"));
        card.addView(programView);

        TextView titleView = new TextView(this);
        titleView.setText(e.title.isEmpty() ? "名称未入力の回" : e.title);
        titleView.setTextSize(16);
        titleView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titleView.setTextColor(Color.parseColor("#111827"));
        titleView.setPadding(0, dp(2), 0, 0);
        card.addView(titleView);

        TextView meta = new TextView(this);
        meta.setText(EpisodeStore.displayDate(e.updatedAt) + "   " + e.transcript.length() + "文字   "
                + speedLabel(e.playbackSpeed) + "   " + statusLabel(e.status));
        meta.setTextSize(11);
        meta.setTextColor(Color.parseColor("#6B7280"));
        meta.setPadding(0, dp(4), 0, 0);
        card.addView(meta);

        String preview = e.transcript == null ? "" : e.transcript.replaceAll("\\s+", " ").trim();
        TextView previewView = new TextView(this);
        previewView.setText(preview.isEmpty() ? "（文字起こしなし）" : preview);
        previewView.setTextSize(13);
        previewView.setTextColor(Color.parseColor("#374151"));
        previewView.setMaxLines(3);
        previewView.setEllipsize(TextUtils.TruncateAt.END);
        previewView.setPadding(0, dp(7), 0, 0);
        card.addView(previewView);

        card.setOnClickListener(v -> loadEpisode(store.getEpisode(e.id)));
        card.setOnLongClickListener(v -> {
            if (serviceRunning && activeEpisodeId == e.id) {
                Toast.makeText(this, "文字起こし中の回は削除できません", Toast.LENGTH_SHORT).show();
                return true;
            }
            new AlertDialog.Builder(this)
                    .setTitle("この回を削除しますか？")
                    .setMessage((e.program.isEmpty() ? "番組名未入力" : e.program) + "\n" + e.title)
                    .setNegativeButton("キャンセル", null)
                    .setPositiveButton("削除", (d, w) -> {
                        store.deleteEpisode(e.id);
                        if (activeEpisodeId == e.id) {
                            activeEpisodeId = -1L;
                            transcript.setText("");
                        }
                        refreshProgramFilter();
                        renderLibrary();
                        updateCurrentLabel();
                    }).show();
            return true;
        });
        libraryContainer.addView(card);
    }

    private String statusLabel(String status) {
        if ("complete".equals(status)) return "完了";
        if ("recording".equals(status)) return "文字起こし中";
        if ("error".equals(status)) return "エラー";
        if ("interrupted".equals(status)) return "中断";
        if ("imported".equals(status)) return "移行データ";
        return "保存済み";
    }

    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
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
        learnButton.setEnabled(!serviceRunning && activeEpisodeId > 0);
        stopButton.setEnabled(serviceRunning);
        programInput.setEnabled(!serviceRunning);
        episodeInput.setEnabled(!serviceRunning);
        urlInput.setEnabled(!serviceRunning);
        playbackSpeedSpinner.setEnabled(!serviceRunning);
        autoStopCheck.setEnabled(!serviceRunning);
        transcript.setEnabled(!serviceRunning);
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
        store.updateEditedTranscript(activeEpisodeId, transcript.getText().toString());
    }

    @Override protected void onPause() {
        saveCurrentEdits();
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        if (!serviceRunning && activeEpisodeId > 0) {
            EpisodeStore.Episode e = store.getEpisode(activeEpisodeId);
            if (e != null) {
                transcript.setText(e.transcript);
                transcript.setSelection(transcript.length());
            }
        }
        refreshProgramFilter();
        renderLibrary();
    }

    @Override protected void onDestroy() {
        try { unregisterReceiver(receiver); } catch (Exception ignored) {}
        super.onDestroy();
    }
}

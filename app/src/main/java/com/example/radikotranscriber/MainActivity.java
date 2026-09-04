package com.example.radikotranscriber;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.*;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {
    private static final int MODE_NONE = 0;
    private static final int MODE_INTERNAL = 1;
    private static final int MODE_MIC = 2;

    private EditText urlInput, transcript;
    private TextView status, meter;
    private Button internalStartButton, micStartButton, stopButton;
    private MediaProjectionManager projectionManager;
    private int pendingMode = MODE_NONE;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!TranscribeService.ACTION_UPDATE.equals(intent.getAction())) return;
            String s = intent.getStringExtra("status");
            String text = intent.getStringExtra("text");
            int peak = intent.getIntExtra("peak", 0);
            int reconnects = intent.getIntExtra("reconnects", 0);
            boolean running = intent.getBooleanExtra("running", false);
            String mode = intent.getStringExtra("mode");

            if (s != null) status.setText(s);
            String meterText = ("mic".equals(mode) ? "マイク入力レベル: " : "内部音声レベル: ") + peak;
            if (running) meterText += "　自動再接続: " + reconnects + "回";
            meter.setText(meterText);

            if (text != null && !text.contentEquals(transcript.getText())) {
                transcript.setText(text);
                transcript.setSelection(transcript.length());
            }

            internalStartButton.setEnabled(!running);
            micStartButton.setEnabled(!running);
            stopButton.setEnabled(running);
        }
    };

    private final ActivityResultLauncher<String> recordPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (!granted) {
                    status.setText("文字起こしにはマイク権限が必要です。");
                    pendingMode = MODE_NONE;
                    return;
                }
                continuePendingStart();
            });

    private final ActivityResultLauncher<Intent> projectionPermission =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
                    status.setText("共有許可がキャンセルされました。");
                    pendingMode = MODE_NONE;
                    return;
                }
                Intent service = new Intent(this, TranscribeService.class);
                service.setAction(TranscribeService.ACTION_START_INTERNAL);
                service.putExtra("resultCode", result.getResultCode());
                service.putExtra("projectionData", result.getData());
                ContextCompat.startForegroundService(this, service);
                status.setText("内部音声を準備中… 許可後にブラウザでradikoを再生してください。");
                pendingMode = MODE_NONE;
            });

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        urlInput = findViewById(R.id.urlInput);
        transcript = findViewById(R.id.transcript);
        status = findViewById(R.id.status);
        meter = findViewById(R.id.meter);
        internalStartButton = findViewById(R.id.internalStartButton);
        micStartButton = findViewById(R.id.micStartButton);
        stopButton = findViewById(R.id.stopButton);
        projectionManager = (MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);

        transcript.setText(getSharedPreferences("state", MODE_PRIVATE).getString("transcript", ""));

        findViewById(R.id.openRadiko).setOnClickListener(v -> openGeneric());
        findViewById(R.id.openBrowser).setOnClickListener(v -> openInBrowser());

        internalStartButton.setOnClickListener(v -> requestStart(MODE_INTERNAL));
        micStartButton.setOnClickListener(v -> requestStart(MODE_MIC));

        stopButton.setOnClickListener(v -> {
            Intent i = new Intent(this, TranscribeService.class);
            i.setAction(TranscribeService.ACTION_STOP);
            startService(i);
        });

        findViewById(R.id.copyButton).setOnClickListener(v -> {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("文字起こし", transcript.getText()));
            Toast.makeText(this, "コピーしました", Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.clearButton).setOnClickListener(v -> {
            transcript.setText("");
            getSharedPreferences("state", MODE_PRIVATE).edit().remove("transcript").apply();
        });

        IntentFilter filter = new IntentFilter(TranscribeService.ACTION_UPDATE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, filter);
    }

    private void requestStart(int mode) {
        if (Build.VERSION.SDK_INT < 33) {
            status.setText("この試作版はAndroid 13以上が必要です。");
            return;
        }
        pendingMode = mode;
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
            Intent service = new Intent(this, TranscribeService.class);
            service.setAction(TranscribeService.ACTION_START_MIC);
            ContextCompat.startForegroundService(this, service);
            status.setText("マイク文字起こしを準備中…");
            pendingMode = MODE_NONE;
        }
    }

    private Uri currentUri() {
        return Uri.parse(urlInput.getText().toString().trim());
    }

    private void openGeneric() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, currentUri()));
        } catch (Exception e) {
            status.setText("URLを開けませんでした: " + e.getMessage());
        }
    }

    private void openInBrowser() {
        Uri uri = currentUri();
        String[] packages = {"com.android.chrome", "com.sec.android.app.sbrowser"};
        for (String pkg : packages) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, uri);
                i.setPackage(pkg);
                startActivity(i);
                status.setText("ブラウザで開きました。再生後、内部音声レベルが動けば取得できています。");
                return;
            } catch (Exception ignored) {}
        }
        try {
            Intent i = Intent.createChooser(new Intent(Intent.ACTION_VIEW, uri), "ブラウザで開く");
            startActivity(i);
        } catch (Exception e) {
            status.setText("ブラウザを開けませんでした: " + e.getMessage());
        }
    }

    @Override protected void onDestroy() {
        try { unregisterReceiver(receiver); } catch (Exception ignored) {}
        super.onDestroy();
    }
}

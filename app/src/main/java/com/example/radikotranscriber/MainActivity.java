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
    private EditText urlInput, transcript;
    private TextView status, meter;
    private Button startButton, stopButton;
    private MediaProjectionManager projectionManager;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!TranscribeService.ACTION_UPDATE.equals(intent.getAction())) return;
            String s = intent.getStringExtra("status");
            String text = intent.getStringExtra("text");
            int peak = intent.getIntExtra("peak", 0);
            boolean running = intent.getBooleanExtra("running", false);

            if (s != null) status.setText(s);
            meter.setText("入力レベル: " + peak);
            if (text != null) transcript.setText(text);

            startButton.setEnabled(!running);
            stopButton.setEnabled(running);
        }
    };

    private final ActivityResultLauncher<String> recordPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) askProjection();
                else status.setText("音声キャプチャには録音権限が必要です。");
            });

    private final ActivityResultLauncher<Intent> projectionPermission =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
                    status.setText("共有許可がキャンセルされました。");
                    return;
                }
                Intent service = new Intent(this, TranscribeService.class);
                service.setAction(TranscribeService.ACTION_START);
                service.putExtra("resultCode", result.getResultCode());
                service.putExtra("projectionData", result.getData());
                ContextCompat.startForegroundService(this, service);
                status.setText("準備中…");
            });

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        urlInput = findViewById(R.id.urlInput);
        transcript = findViewById(R.id.transcript);
        status = findViewById(R.id.status);
        meter = findViewById(R.id.meter);
        startButton = findViewById(R.id.startButton);
        stopButton = findViewById(R.id.stopButton);
        projectionManager = (MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);

        transcript.setText(getSharedPreferences("state", MODE_PRIVATE).getString("transcript", ""));

        findViewById(R.id.openRadiko).setOnClickListener(v -> {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(urlInput.getText().toString().trim()));
                startActivity(i);
            } catch (Exception e) {
                status.setText("URLを開けませんでした: " + e.getMessage());
            }
        });

        startButton.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT < 33) {
                status.setText("この試作版はAndroid 13以上が必要です。");
                return;
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                recordPermission.launch(Manifest.permission.RECORD_AUDIO);
            } else askProjection();
        });

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

    private void askProjection() {
        projectionPermission.launch(projectionManager.createScreenCaptureIntent());
    }

    @Override protected void onDestroy() {
        unregisterReceiver(receiver);
        super.onDestroy();
    }
}

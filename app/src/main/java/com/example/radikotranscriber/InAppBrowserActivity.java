package com.example.radikotranscriber;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Experimental and deliberately isolated from transcription/storage logic.
 * The WebView shares this screen with live status/transcript, but TranscribeService is independent.
 */
public class InAppBrowserActivity extends AppCompatActivity {
    private WebView webView;
    private LinearLayout webHolder;
    private TextView statusView, positionView, transcriptView;
    private String initialUrl = "";
    private boolean webExpanded = true;
    private long episodeId = -1L;
    private EpisodeStore store;
    private DiagnosticStore diagnostics;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!TranscribeService.ACTION_UPDATE.equals(intent.getAction())) return;

            long incomingId = intent.getLongExtra("episodeId", -1L);
            boolean switchedEpisode = incomingId > 0 && incomingId != episodeId;
            if (incomingId > 0) episodeId = incomingId;
            if (switchedEpisode && transcriptView != null) transcriptView.setText("");

            String status = intent.getStringExtra("status");
            String text = intent.getStringExtra("text");
            long pos = intent.getLongExtra("mediaPositionMs", 0L);
            long backlog = intent.getLongExtra("backlogMs", 0L);
            float speed = intent.getFloatExtra("playbackSpeed", 1.0f);
            boolean running = intent.getBooleanExtra("running", false);
            String phase = intent.getStringExtra("phase");
            int peak = intent.getIntExtra("peak", 0);
            int reconnects = intent.getIntExtra("reconnects", 0);

            if (status != null) statusView.setText(status);
            String p = (running ? "● 動作中" : "■ 停止")
                    + "   推定 " + EpisodeStore.formatDuration(pos)
                    + "   " + speedLabel(speed)
                    + "   入力 " + peak;
            if (running && reconnects > 0) p += "   復旧 " + reconnects + "回";
            if ("draining".equals(phase)) p += "   残り処理中";
            if (backlog > 1000L) p += "   認識待ち約" + EpisodeStore.formatDuration(backlog);
            positionView.setText(p);

            if (text != null && !text.contentEquals(transcriptView.getText())) {
                boolean nearBottom = isNearBottom();
                transcriptView.setText(text.isEmpty() && running
                        ? "音声を取得中です。最初の認識結果を待っています…"
                        : text);
                if (nearBottom) transcriptView.post(() -> {
                    if (transcriptView.getParent() instanceof ScrollView) {
                        ((ScrollView)transcriptView.getParent()).fullScroll(View.FOCUS_DOWN);
                    }
                });
            }
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        initialUrl = getIntent().getStringExtra("url");
        episodeId = getIntent().getLongExtra("episodeId", -1L);
        if (initialUrl == null || !(initialUrl.startsWith("https://") || initialUrl.startsWith("http://"))) {
            finish();
            return;
        }
        store = new EpisodeStore(this);
        diagnostics = new DiagnosticStore(this);
        readRuntimeState();
        buildUi();
        registerUpdates();
        webView.loadUrl(initialUrl);
        diagnostics.log(episodeId, "inapp_web_open", "split_screen=true;url=" + initialUrl);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(244, 245, 247));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(6), dp(8), dp(6));
        bar.setBackgroundColor(Color.WHITE);

        TextView label = new TextView(this);
        label.setText("radiko再生（実験）");
        label.setTextSize(14);
        label.setTextColor(Color.DKGRAY);
        label.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(label, new LinearLayout.LayoutParams(0, dp(44), 1f));

        Button toggle = new Button(this);
        toggle.setText("たたむ");
        toggle.setTextSize(11);
        toggle.setOnClickListener(v -> {
            webExpanded = !webExpanded;
            setWebExpanded(webExpanded);
            toggle.setText(webExpanded ? "たたむ" : "表示");
            diagnostics.log(episodeId, "inapp_web_toggle", "expanded=" + webExpanded);
        });
        bar.addView(toggle, new LinearLayout.LayoutParams(dp(84), dp(44)));

        Button external = new Button(this);
        external.setText("外部");
        external.setTextSize(11);
        external.setOnClickListener(v -> openExternal());
        bar.addView(external, new LinearLayout.LayoutParams(dp(76), dp(44)));
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        webHolder = new LinearLayout(this);
        webHolder.setOrientation(LinearLayout.VERTICAL);
        webHolder.setBackgroundColor(Color.BLACK);
        webView = new WebView(this);
        setupWebView();
        webHolder.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(webHolder, webParams(true));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(12), dp(8), dp(12), dp(8));
        info.setBackgroundColor(Color.WHITE);

        statusView = new TextView(this);
        statusView.setText("文字起こし状態を取得中…");
        statusView.setTextSize(13);
        statusView.setTextColor(Color.rgb(31, 41, 55));
        info.addView(statusView);

        positionView = new TextView(this);
        positionView.setText("推定再生位置：—");
        positionView.setTextSize(12);
        positionView.setTextColor(Color.rgb(6, 95, 70));
        positionView.setPadding(0, dp(4), 0, dp(4));
        info.addView(positionView);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button detail = new Button(this);
        detail.setText("この回の詳細");
        detail.setTextSize(11);
        detail.setOnClickListener(v -> openDetail());
        actions.addView(detail, new LinearLayout.LayoutParams(0, dp(44), 1f));
        Button archive = new Button(this);
        archive.setText("アーカイブ");
        archive.setTextSize(11);
        archive.setOnClickListener(v -> startActivity(new Intent(this, ArchiveActivity.class)));
        actions.addView(archive, new LinearLayout.LayoutParams(0, dp(44), 1f));
        info.addView(actions);
        root.addView(info, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView heading = new TextView(this);
        heading.setText("現在の文字起こし");
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setTextSize(15);
        heading.setTextColor(Color.rgb(17, 24, 39));
        heading.setPadding(dp(12), dp(8), dp(12), dp(4));
        root.addView(heading);

        ScrollView transcriptScroll = new ScrollView(this);
        transcriptScroll.setFillViewport(true);
        transcriptScroll.setBackgroundColor(Color.WHITE);
        transcriptView = new TextView(this);
        transcriptView.setTextSize(14);
        transcriptView.setTextColor(Color.rgb(17, 24, 39));
        transcriptView.setTextIsSelectable(true);
        transcriptView.setPadding(dp(12), dp(10), dp(12), dp(18));
        transcriptView.setLineSpacing(0, 1.12f);
        transcriptView.setText("文字起こし開始後、ここに新しい結果が表示されます。");
        transcriptScroll.addView(transcriptView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(transcriptScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView note = new TextView(this);
        note.setText("radikoをたたんでもWebView自体は破棄しません。再生できない場合は「外部」を使えます。文字起こし本体とは独立しています。");
        note.setTextSize(10);
        note.setTextColor(Color.DKGRAY);
        note.setPadding(dp(10), dp(5), dp(10), dp(8));
        root.addView(note);

        setContentView(root);
        refreshFromDb();
        readRuntimeStateIntoViews();
    }

    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setSupportZoom(false);

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                String scheme = u == null ? "" : u.getScheme();
                if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
                return true;
            }
        });
        webView.setOnTouchListener((v, e) -> {
            if (v.getParent() != null) {
                boolean moving = e.getActionMasked() == MotionEvent.ACTION_DOWN
                        || e.getActionMasked() == MotionEvent.ACTION_MOVE;
                v.getParent().requestDisallowInterceptTouchEvent(moving);
            }
            return false;
        });
    }

    private LinearLayout.LayoutParams webParams(boolean expanded) {
        if (expanded) return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.48f);
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
    }

    private void setWebExpanded(boolean expanded) {
        webHolder.setLayoutParams(webParams(expanded));
        webView.setVisibility(View.VISIBLE);
    }

    private void registerUpdates() {
        IntentFilter filter = new IntentFilter(TranscribeService.ACTION_UPDATE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, filter);
    }

    /** Only an actually-alive service may override the explicit episode passed by MainActivity. */
    private void readRuntimeState() {
        SharedPreferences p = getSharedPreferences("runtime_state", MODE_PRIVATE);
        long heartbeat = p.getLong("heartbeatAt", 0L);
        boolean alive = p.getBoolean("running", false)
                && System.currentTimeMillis() - heartbeat < 30000L;
        if (alive) {
            long runtimeEpisode = p.getLong("episodeId", -1L);
            if (runtimeEpisode > 0) episodeId = runtimeEpisode;
        }
    }

    private void readRuntimeStateIntoViews() {
        SharedPreferences p = getSharedPreferences("runtime_state", MODE_PRIVATE);
        long heartbeat = p.getLong("heartbeatAt", 0L);
        boolean running = p.getBoolean("running", false)
                && System.currentTimeMillis() - heartbeat < 30000L;
        if (!running) {
            statusView.setText(episodeId > 0
                    ? "文字起こしは停止中です。"
                    : "「文字起こし開始」から開くと、ここに新しい文字起こしが表示されます。");
            positionView.setText("■ 停止   推定再生位置：—");
            return;
        }

        long pos = p.getLong("mediaPositionMs", 0L);
        long backlog = p.getLong("backlogMs", 0L);
        String phase = p.getString("phase", "capturing");
        String st = p.getString("status", "");
        float speed = p.getFloat("playbackSpeed", 1.0f);
        if (!TextUtils.isEmpty(st)) statusView.setText(st);
        String line = "● 動作中   推定 " + EpisodeStore.formatDuration(pos) + "   " + speedLabel(speed);
        if ("draining".equals(phase)) line += "   残り処理中";
        if (backlog > 1000L) line += "   認識待ち約" + EpisodeStore.formatDuration(backlog);
        positionView.setText(line);
    }

    private void refreshFromDb() {
        if (episodeId <= 0 || store == null || transcriptView == null) return;
        EpisodeStore.Episode e = store.getEpisode(episodeId);
        if (e != null && !e.transcript.trim().isEmpty()) transcriptView.setText(e.transcript);
    }

    private boolean isNearBottom() {
        if (!(transcriptView.getParent() instanceof ScrollView)) return true;
        ScrollView s = (ScrollView)transcriptView.getParent();
        return transcriptView.getBottom() - (s.getHeight() + s.getScrollY()) < dp(100);
    }

    private void openDetail() {
        if (episodeId <= 0) return;
        Intent i = new Intent(this, EpisodeDetailActivity.class);
        i.putExtra("episodeId", episodeId);
        startActivity(i);
    }

    private void openExternal() {
        String u = webView != null && webView.getUrl() != null ? webView.getUrl() : initialUrl;
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u))); }
        catch (Exception ignored) {}
    }

    private String speedLabel(float s) {
        return s >= 1.9f ? "2.0x" : s >= 1.4f ? "1.5x" : "1.0x";
    }

    @Override protected void onResume() {
        super.onResume();
        refreshFromDb();
        if (statusView != null) readRuntimeStateIntoViews();
    }

    @Override public void onBackPressed() {
        if (webExpanded && webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        try { unregisterReceiver(receiver); } catch (Exception ignored) {}
        if (webView != null) {
            try {
                webView.stopLoading();
                webView.loadUrl("about:blank");
                webView.clearHistory();
                webView.removeAllViews();
                webView.destroy();
            } catch (Exception ignored) {}
            webView = null;
        }
        super.onDestroy();
    }

    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }
}

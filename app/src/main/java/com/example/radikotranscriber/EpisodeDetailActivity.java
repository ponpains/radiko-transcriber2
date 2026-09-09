package com.example.radikotranscriber;

import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.widget.*;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;

public class EpisodeDetailActivity extends AppCompatActivity {
    private EpisodeStore store;
    private long episodeId;
    private EditText program, title, url, transcript, notes, tags, keyPoints, search;
    private TextView meta, corrections;
    private LinearLayout timeline;
    private Button fetchMetadataButton, reprocessButton, restoreReprocessButton;
    private int searchFrom = 0;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_episode_detail);
        store = new EpisodeStore(this);
        episodeId = getIntent().getLongExtra("episodeId", -1L);
        program=findViewById(R.id.detailProgram);title=findViewById(R.id.detailTitle);url=findViewById(R.id.detailUrl);
        transcript=findViewById(R.id.detailTranscript);notes=findViewById(R.id.detailNotes);tags=findViewById(R.id.detailTags);keyPoints=findViewById(R.id.detailKeyPoints);
        search=findViewById(R.id.detailSearch);meta=findViewById(R.id.detailMeta);corrections=findViewById(R.id.detailCorrections);timeline=findViewById(R.id.timelineContainer);
        fetchMetadataButton=findViewById(R.id.detailFetchMetadata);
        reprocessButton=findViewById(R.id.detailReprocess);
        restoreReprocessButton=findViewById(R.id.detailRestoreReprocess);

        setupTranscriptEditor();

        findViewById(R.id.detailSave).setOnClickListener(v->saveAll(true));
        findViewById(R.id.detailLearn).setOnClickListener(v->learn());
        findViewById(R.id.detailCopy).setOnClickListener(v->copy());
        findViewById(R.id.detailOpen).setOnClickListener(v->openUrl());
        findViewById(R.id.detailDelete).setOnClickListener(v->delete());
        findViewById(R.id.detailFindNext).setOnClickListener(v->findNext());
        findViewById(R.id.detailDictionary).setOnClickListener(v->{Intent i=new Intent(this,DictionaryActivity.class);i.putExtra("program",program.getText().toString().trim());startActivity(i);});
        fetchMetadataButton.setOnClickListener(v->fetchMetadataFromUrl());
        reprocessButton.setOnClickListener(v->reprocessLegacyTranscript());
        restoreReprocessButton.setOnClickListener(v->restoreBeforeReprocess());
        load();
    }

    private void setupTranscriptEditor() {
        // 標準のEditTextカーソル移動を残しつつ、枠内の縦スクロールだけ親へ奪われないようにする。
        transcript.setVerticalScrollBarEnabled(true);
        transcript.setNestedScrollingEnabled(true);
        transcript.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        transcript.setOnTouchListener((v, e) -> {
            ViewParent p = v.getParent();
            if (p != null) {
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) p.requestDisallowInterceptTouchEvent(true);
                else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) p.requestDisallowInterceptTouchEvent(false);
            }
            return false;
        });
    }

    private void load() {
        EpisodeStore.Episode e=store.getEpisode(episodeId);
        if(e==null){finish();return;}
        program.setText(e.program);title.setText(e.title);url.setText(e.url);transcript.setText(e.transcript);notes.setText(e.notes);tags.setText(e.tags);keyPoints.setText(e.keyPoints);
        meta.setText(EpisodeStore.displayDate(e.updatedAt)+"   "+e.transcript.length()+"文字   "+EpisodeStore.formatDuration(e.durationMs)+"   "+e.playbackSpeed+"x");
        restoreReprocessButton.setVisibility(TranscriptHistoryV032.hasBackup(store, episodeId) ? View.VISIBLE : View.GONE);
        renderTimeline();renderCorrections();
    }

    private void saveAll(boolean toast) {
        store.updateMeta(episodeId,program.getText().toString().trim(),title.getText().toString().trim(),url.getText().toString().trim());
        store.updateEditedTranscript(episodeId,transcript.getText().toString());
        store.updateEpisodeExtras(episodeId,notes.getText().toString(),tags.getText().toString(),keyPoints.getText().toString());
        store.autoBackup(this);
        if(toast)Toast.makeText(this,"保存しました",Toast.LENGTH_SHORT).show();
        loadMetaOnly();
    }

    private void loadMetaOnly(){EpisodeStore.Episode e=store.getEpisode(episodeId);if(e!=null)meta.setText(EpisodeStore.displayDate(e.updatedAt)+"   "+e.transcript.length()+"文字   "+EpisodeStore.formatDuration(e.durationMs)+"   "+e.playbackSpeed+"x");}

    private void fetchMetadataFromUrl() {
        final String requestedUrl = url.getText().toString().trim();
        if (!RadikoMetadataFetcher.looksLikeRadikoEpisode(requestedUrl)) {
            Toast.makeText(this,"radiko Podcast の回URLを入力してください",Toast.LENGTH_LONG).show();
            return;
        }
        fetchMetadataButton.setEnabled(false);
        fetchMetadataButton.setText("回名を取得中…");
        RadikoMetadataFetcher.fetchAsync(requestedUrl, r -> {
            fetchMetadataButton.setEnabled(true);
            fetchMetadataButton.setText("URLから回名を取得");
            if (isFinishing() || isDestroyed()) return;
            if (r == null) {
                Toast.makeText(this,"回名を取得できませんでした",Toast.LENGTH_LONG).show();
                return;
            }
            final String fetched = r.displayEpisode().trim();
            if (fetched.isEmpty()) {
                String reason = r.error == null || r.error.trim().isEmpty() ? "回名を判別できませんでした" : r.error;
                Toast.makeText(this,reason,Toast.LENGTH_LONG).show();
                return;
            }
            final String current = title.getText().toString().trim();
            if (current.equals(fetched)) {
                if (program.getText().toString().trim().isEmpty() && r.program != null && !r.program.trim().isEmpty()) {
                    program.setText(r.program.trim());
                    store.updateMeta(episodeId, program.getText().toString().trim(), current, requestedUrl);
                    store.autoBackup(this);
                }
                Toast.makeText(this,"回名はすでに最新です",Toast.LENGTH_SHORT).show();
                return;
            }
            if (isPlaceholderTitle(current)) {
                applyFetchedMetadata(r, fetched, requestedUrl);
                Toast.makeText(this,"URLから回名を復元しました",Toast.LENGTH_SHORT).show();
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("URLから回名を取得しました")
                    .setMessage("現在\n" + current + "\n\n取得結果\n" + fetched)
                    .setNegativeButton("そのまま", null)
                    .setPositiveButton("取得結果を使う", (d,w) -> applyFetchedMetadata(r, fetched, requestedUrl))
                    .show();
        });
    }

    private void applyFetchedMetadata(RadikoMetadataFetcher.Result r, String fetched, String requestedUrl) {
        title.setText(fetched);
        String p = program.getText().toString().trim();
        if (p.isEmpty() && r != null && r.program != null && !r.program.trim().isEmpty()) {
            p = r.program.trim();
            program.setText(p);
        }
        store.updateMeta(episodeId, p, fetched, requestedUrl);
        store.autoBackup(this);
        loadMetaOnly();
    }

    private boolean isPlaceholderTitle(String value) {
        String x = value == null ? "" : value.trim();
        return x.isEmpty() || "名称未入力の回".equals(x) || "名称未入力".equals(x)
                || "不明".equals(x) || "タイトル未入力".equals(x);
    }

    private void reprocessLegacyTranscript() {
        // Persist any manual edit currently visible so the snapshot and preview both start from the
        // exact state the user is looking at.
        saveAll(false);
        reprocessButton.setEnabled(false);
        LegacyTranscriptReprocessorV032.Result result = LegacyTranscriptReprocessorV032.preview(this, store, episodeId);
        reprocessButton.setEnabled(true);
        if (!result.safeToApply) {
            Toast.makeText(this, result.warning.isEmpty() ? "安全に再処理できませんでした" : result.warning,
                    Toast.LENGTH_LONG).show();
            return;
        }
        showReprocessPreview(result);
    }

    private void showReprocessPreview(LegacyTranscriptReprocessorV032.Result result) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int)(16 * getResources().getDisplayMetrics().density + 0.5f);
        box.setPadding(pad, pad, pad, pad);

        TextView summary = new TextView(this);
        summary.setText("元データ: " + result.sourceLabel + "\n現在 " + result.currentText.length()
                + "文字 → 再処理後 " + result.processedText.length() + "文字"
                + (result.warning.isEmpty() ? "" : "\n" + result.warning));
        box.addView(summary);

        TextView before = new TextView(this);
        before.setText("\n【現在】\n" + previewText(result.currentText));
        before.setTextIsSelectable(true);
        box.addView(before);

        TextView after = new TextView(this);
        after.setText("\n【再処理後】\n" + previewText(result.processedText));
        after.setTextIsSelectable(true);
        box.addView(after);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        new AlertDialog.Builder(this)
                .setTitle("最新の補正で再処理")
                .setView(scroll)
                .setNegativeButton("キャンセル", null)
                .setPositiveButton("再処理版に置き換える", (d,w) -> applyReprocessed(result))
                .show();
    }

    private String previewText(String s) {
        String x = s == null ? "" : s;
        int max = 3500;
        return x.length() <= max ? x : x.substring(0, max) + "\n…（続きは置き換え後の全文画面で確認できます）";
    }

    private void applyReprocessed(LegacyTranscriptReprocessorV032.Result result) {
        TranscriptHistoryV032.backup(store, episodeId, "before_reprocess_v032");
        TranscriptHistoryV032.replaceFinal(store, episodeId, result.processedText);
        TranscriptHistoryV032.replaceSegments(store, result.segmentTexts);
        transcript.setText(result.processedText);
        restoreReprocessButton.setVisibility(View.VISIBLE);
        store.autoBackup(this);
        renderTimeline();
        loadMetaOnly();
        Toast.makeText(this,"再処理版に置き換えました。再処理前の全文も保存されています",Toast.LENGTH_LONG).show();
    }

    private void restoreBeforeReprocess() {
        final TranscriptHistoryV032.Snapshot snapshot = TranscriptHistoryV032.latest(store, episodeId);
        if (snapshot == null) {
            restoreReprocessButton.setVisibility(View.GONE);
            Toast.makeText(this,"戻せる再処理前データはありません",Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("再処理前に戻しますか？")
                .setMessage("現在の文字起こしを、直前に保存した再処理前の状態へ戻します。")
                .setNegativeButton("キャンセル", null)
                .setPositiveButton("戻す", (d,w) -> {
                    TranscriptHistoryV032.backup(store, episodeId, "before_restore_v032");
                    TranscriptHistoryV032.restore(store, snapshot);
                    EpisodeStore.Episode restored = store.getEpisode(episodeId);
                    if (restored != null) transcript.setText(restored.transcript);
                    store.autoBackup(this);
                    renderTimeline();
                    loadMetaOnly();
                    Toast.makeText(this,"再処理前の文字起こしに戻しました",Toast.LENGTH_LONG).show();
                })
                .show();
    }

    private void learn(){
        saveAll(false);int n=store.learnCorrectionsFromEdit(episodeId,transcript.getText().toString());store.autoBackup(this);renderCorrections();
        Toast.makeText(this,n>0?n+"件の修正を学習しました":"学習対象の表記修正はありませんでした",Toast.LENGTH_LONG).show();
    }

    private void copy(){
        saveAll(false);EpisodeStore.Episode e=store.getEpisode(episodeId);if(e==null)return;
        String body=e.program+" / "+e.title+"\n"+e.url+"\n\n"+store.transcriptWithTimestamps(episodeId);
        android.content.ClipboardManager cm=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText("ラジオ文字起こし",body));Toast.makeText(this,"タイムスタンプ付きでコピーしました",Toast.LENGTH_SHORT).show();
    }

    private void openUrl(){try{startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url.getText().toString().trim())));}catch(Exception e){Toast.makeText(this,"URLを開けませんでした",Toast.LENGTH_SHORT).show();}}

    private void delete(){new AlertDialog.Builder(this).setTitle("この回を削除しますか？").setMessage(title.getText().toString()).setNegativeButton("キャンセル",null).setPositiveButton("削除",(d,w)->{store.deleteEpisode(episodeId);store.autoBackup(this);finish();}).show();}

    private void findNext(){
        String q=search.getText().toString();if(q.isEmpty())return;String text=transcript.getText().toString();int i=text.indexOf(q,searchFrom);if(i<0){i=text.indexOf(q);searchFrom=0;}if(i<0){Toast.makeText(this,"見つかりませんでした",Toast.LENGTH_SHORT).show();return;}transcript.requestFocus();transcript.setSelection(i,i+q.length());searchFrom=i+q.length();
    }

    private void renderTimeline(){
        timeline.removeAllViews();ArrayList<EpisodeStore.Segment> list=store.listSegments(episodeId);
        if(list.isEmpty()){TextView t=new TextView(this);t.setText("この回にはタイムスタンプデータがありません（旧バージョンの記録など）。");timeline.addView(t);return;}
        for(EpisodeStore.Segment s:list){TextView t=new TextView(this);t.setText("["+EpisodeStore.timeLabel(s.startMs)+"]  "+s.text);t.setTextSize(14);t.setPadding(6,s.topicBreak?18:7,6,7);timeline.addView(t);}
    }

    private void renderCorrections(){
        ArrayList<EpisodeStore.Correction> list=store.getCorrections(program.getText().toString().trim());StringBuilder b=new StringBuilder();int n=0;
        for(EpisodeStore.Correction c:list){if(n++>=20)break;if(b.length()>0)b.append("\n");b.append(c.wrong).append(" → ").append(c.correct).append("  (優先 ").append(c.uses).append(")");}
        corrections.setText(b.length()==0?"この番組の修正辞書はまだありません。":b.toString());
    }

    @Override protected void onPause(){saveAll(false);super.onPause();}
}

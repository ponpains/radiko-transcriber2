package com.example.radikotranscriber;

import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
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
    private int searchFrom = 0;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_episode_detail);
        store = new EpisodeStore(this);
        episodeId = getIntent().getLongExtra("episodeId", -1L);
        program=findViewById(R.id.detailProgram);title=findViewById(R.id.detailTitle);url=findViewById(R.id.detailUrl);
        transcript=findViewById(R.id.detailTranscript);notes=findViewById(R.id.detailNotes);tags=findViewById(R.id.detailTags);keyPoints=findViewById(R.id.detailKeyPoints);
        search=findViewById(R.id.detailSearch);meta=findViewById(R.id.detailMeta);corrections=findViewById(R.id.detailCorrections);timeline=findViewById(R.id.timelineContainer);
        transcript.setMovementMethod(ScrollingMovementMethod.getInstance());
        findViewById(R.id.detailSave).setOnClickListener(v->saveAll(true));
        findViewById(R.id.detailLearn).setOnClickListener(v->learn());
        findViewById(R.id.detailCopy).setOnClickListener(v->copy());
        findViewById(R.id.detailOpen).setOnClickListener(v->openUrl());
        findViewById(R.id.detailDelete).setOnClickListener(v->delete());
        findViewById(R.id.detailFindNext).setOnClickListener(v->findNext());
        findViewById(R.id.detailDictionary).setOnClickListener(v->{Intent i=new Intent(this,DictionaryActivity.class);i.putExtra("program",program.getText().toString().trim());startActivity(i);});
        load();
    }

    private void load() {
        EpisodeStore.Episode e=store.getEpisode(episodeId);
        if(e==null){finish();return;}
        program.setText(e.program);title.setText(e.title);url.setText(e.url);transcript.setText(e.transcript);notes.setText(e.notes);tags.setText(e.tags);keyPoints.setText(e.keyPoints);
        meta.setText(EpisodeStore.displayDate(e.updatedAt)+"   "+e.transcript.length()+"文字   "+EpisodeStore.formatDuration(e.durationMs)+"   "+e.playbackSpeed+"x");
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

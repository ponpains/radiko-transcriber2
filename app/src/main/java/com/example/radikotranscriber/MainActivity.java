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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;

public class MainActivity extends AppCompatActivity {
    private static final int MODE_NONE = 0, MODE_INTERNAL = 1, MODE_MIC = 2;

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
    private boolean serviceRunning = false, refreshingFilter = false;
    private final ArrayList<String> programFilterValues = new ArrayList<>();

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!TranscribeService.ACTION_UPDATE.equals(intent.getAction())) return;
            String s = intent.getStringExtra("status"), text = intent.getStringExtra("text"), mode = intent.getStringExtra("mode");
            int peak = intent.getIntExtra("peak", 0), reconnects = intent.getIntExtra("reconnects", 0);
            boolean running = intent.getBooleanExtra("running", false);
            long episodeId = intent.getLongExtra("episodeId", -1L);
            float speed = intent.getFloatExtra("playbackSpeed", 1.0f);
            serviceRunning = running;
            if (episodeId > 0) activeEpisodeId = episodeId;
            if (s != null) status.setText(s);
            String m = ("mic".equals(mode) ? "マイク" : "内部音声") + "  " + peak;
            if (running) m += "   自動復旧 " + reconnects + "回   " + speedLabel(speed);
            meter.setText(m);
            if (running && text != null && !text.contentEquals(transcript.getText())) {
                transcript.setText(text); transcript.setSelection(transcript.length());
            }
            if (!running && activeEpisodeId > 0) {
                EpisodeStore.Episode e = store.getEpisode(activeEpisodeId);
                if (e != null && !e.transcript.contentEquals(transcript.getText())) {
                    transcript.setText(e.transcript); transcript.setSelection(transcript.length());
                }
            }
            updateRunningUi(); updateCurrentLabel();
            if (!running) { refreshProgramFilter(); renderLibrary(); }
        }
    };

    private final ActivityResultLauncher<String> recordPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (!granted) { status.setText("音声認識に必要なマイク権限がありません。"); pendingMode = MODE_NONE; }
                else continuePendingStart();
            });
    private final ActivityResultLauncher<String> notificationPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {});
    private final ActivityResultLauncher<Intent> projectionPermission = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
                    status.setText("画面共有の許可がキャンセルされました。"); pendingMode = MODE_NONE; return;
                }
                long id = ensureEpisode();
                Intent service = new Intent(this, TranscribeService.class);
                service.setAction(TranscribeService.ACTION_START_INTERNAL);
                service.putExtra("episodeId", id); service.putExtra("resultCode", result.getResultCode());
                service.putExtra("projectionData", result.getData()); service.putExtra("playbackSpeed", selectedPlaybackSpeed());
                service.putExtra("autoStop", autoStopCheck.isChecked());
                ContextCompat.startForegroundService(this, service);
                status.setText("文字起こしを開始しています… ブラウザを開きます。"); pendingMode = MODE_NONE;
                new Handler(Looper.getMainLooper()).postDelayed(this::openInBrowser, 700L);
            });

    private final ActivityResultLauncher<String> backupLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/json"), uri -> writeText(uri, store.exportBackupJson(), "完全バックアップを書き出しました"));
    private final ActivityResultLauncher<String> csvLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("text/csv"), uri -> writeText(uri, store.exportCsv(), "CSVを書き出しました"));
    private final ActivityResultLauncher<String[]> restoreLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                try {
                    StringBuilder b = new StringBuilder();
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(getContentResolver().openInputStream(uri), "UTF-8"))) {
                        String line; while ((line = r.readLine()) != null) b.append(line).append('\n');
                    }
                    int n = store.importBackupJson(b.toString(), true);
                    activeEpisodeId = -1L; refreshProgramFilter(); renderLibrary();
                    ArrayList<EpisodeStore.Episode> all = store.listEpisodes(""); if (!all.isEmpty()) loadEpisode(all.get(0)); else newEpisode();
                    Toast.makeText(this, n + "回を復元しました", Toast.LENGTH_LONG).show();
                } catch (Exception e) { Toast.makeText(this, "復元に失敗しました: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
            });

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState); setContentView(R.layout.activity_main);
        store = new EpisodeStore(this); store.migrateLegacyIfNeeded(this);
        projectionManager = (MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
        programInput=findViewById(R.id.programInput); episodeInput=findViewById(R.id.episodeInput); urlInput=findViewById(R.id.urlInput);
        transcript=findViewById(R.id.transcript); librarySearch=findViewById(R.id.librarySearch); status=findViewById(R.id.status);
        meter=findViewById(R.id.meter); libraryCount=findViewById(R.id.libraryCount); currentEpisodeLabel=findViewById(R.id.currentEpisodeLabel);
        correctionHint=findViewById(R.id.correctionHint); libraryContainer=findViewById(R.id.libraryContainer);
        startButton=findViewById(R.id.startButton); micStartButton=findViewById(R.id.micStartButton); stopButton=findViewById(R.id.stopButton);
        newEpisodeButton=findViewById(R.id.newEpisodeButton); learnButton=findViewById(R.id.learnButton);
        playbackSpeedSpinner=findViewById(R.id.playbackSpeedSpinner); libraryProgramFilter=findViewById(R.id.libraryProgramFilter);
        autoStopCheck=findViewById(R.id.autoStopCheck);
        setupSpeedSpinner(); setupTranscriptScrolling();
        startButton.setOnClickListener(v->requestStart(MODE_INTERNAL)); micStartButton.setOnClickListener(v->requestStart(MODE_MIC));
        findViewById(R.id.openBrowserButton).setOnClickListener(v->openInBrowser()); stopButton.setOnClickListener(v->stopServiceTranscription());
        newEpisodeButton.setOnClickListener(v->newEpisode()); learnButton.setOnClickListener(v->learnCurrentCorrections());
        findViewById(R.id.copyButton).setOnClickListener(v->copyCurrent()); findViewById(R.id.detailButton).setOnClickListener(v->openCurrentDetail());
        findViewById(R.id.dictionaryButton).setOnClickListener(v->openDictionary()); findViewById(R.id.programSummaryButton).setOnClickListener(v->openProgramSummary());
        findViewById(R.id.backupJsonButton).setOnClickListener(v->backupLauncher.launch("radio-transcriber-backup.json"));
        findViewById(R.id.exportCsvButton).setOnClickListener(v->csvLauncher.launch("radio-transcripts.csv"));
        findViewById(R.id.restoreBackupButton).setOnClickListener(v->confirmRestoreFile());
        findViewById(R.id.restoreAutoButton).setOnClickListener(v->confirmRestoreAuto());
        librarySearch.addTextChangedListener(new TextWatcher(){ public void beforeTextChanged(CharSequence s,int st,int c,int a){} public void onTextChanged(CharSequence s,int st,int b,int c){renderLibrary();} public void afterTextChanged(Editable e){} });
        libraryProgramFilter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){ public void onItemSelected(AdapterView<?> p,View v,int pos,long id){if(!refreshingFilter)renderLibrary();} public void onNothingSelected(AdapterView<?> p){} });
        IntentFilter filter=new IntentFilter(TranscribeService.ACTION_UPDATE);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(receiver,filter,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(receiver,filter);
        refreshProgramFilter(); renderLibrary();
        ArrayList<EpisodeStore.Episode> all=store.listEpisodes(""); if(!all.isEmpty())loadEpisode(all.get(0));else newEpisode();
        if(Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            new Handler(Looper.getMainLooper()).postDelayed(()->notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS),700L);
    }

    private void setupSpeedSpinner(){
        ArrayAdapter<String>a=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,new String[]{"1.0x（最も安定）","1.5x（音声減速補助）","2.0x（音声減速補助）"});
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);playbackSpeedSpinner.setAdapter(a);
    }
    private void setupTranscriptScrolling(){
        transcript.setMovementMethod(ScrollingMovementMethod.getInstance());transcript.setVerticalScrollBarEnabled(true);transcript.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        transcript.setOnTouchListener((v,e)->{ViewParent p=v.getParent();if(p!=null){if(e.getActionMasked()==MotionEvent.ACTION_DOWN||e.getActionMasked()==MotionEvent.ACTION_MOVE)p.requestDisallowInterceptTouchEvent(true);else p.requestDisallowInterceptTouchEvent(false);}return false;});
    }
    private void requestStart(int mode){
        if(Build.VERSION.SDK_INT<33){status.setText("Android 13以上が必要です。");return;} if(serviceRunning)return;
        if(urlInput.getText().toString().trim().isEmpty()){status.setText("先にradiko PodcastのURLを入力してください。");urlInput.requestFocus();return;}
        pendingMode=mode;ensureEpisode();
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)recordPermission.launch(Manifest.permission.RECORD_AUDIO);else continuePendingStart();
    }
    private void continuePendingStart(){
        if(pendingMode==MODE_INTERNAL)projectionPermission.launch(projectionManager.createScreenCaptureIntent());
        else if(pendingMode==MODE_MIC){long id=ensureEpisode();Intent s=new Intent(this,TranscribeService.class);s.setAction(TranscribeService.ACTION_START_MIC);s.putExtra("episodeId",id);s.putExtra("playbackSpeed",selectedPlaybackSpeed());s.putExtra("autoStop",autoStopCheck.isChecked());ContextCompat.startForegroundService(this,s);pendingMode=MODE_NONE;new Handler(Looper.getMainLooper()).postDelayed(this::openInBrowser,700L);}
    }
    private float selectedPlaybackSpeed(){int p=playbackSpeedSpinner.getSelectedItemPosition();return p==2?2.0f:p==1?1.5f:1.0f;}
    private void setPlaybackSpeed(float s){playbackSpeedSpinner.setSelection(s>=1.9f?2:s>=1.4f?1:0);}
    private String speedLabel(float s){return s>=1.9f?"2.0x補助":s>=1.4f?"1.5x補助":"1.0x";}

    private long ensureEpisode(){
        String p=programInput.getText().toString().trim(),t=episodeInput.getText().toString().trim(),u=urlInput.getText().toString().trim();if(t.isEmpty())t="名称未入力の回";
        if(activeEpisodeId<=0)activeEpisodeId=store.createEpisode(p,t,u);else{store.updateMeta(activeEpisodeId,p,t,u);if(!serviceRunning)store.updateEditedTranscript(activeEpisodeId,transcript.getText().toString());}
        updateCurrentLabel();refreshProgramFilter();renderLibrary();return activeEpisodeId;
    }
    private void newEpisode(){
        if(serviceRunning){Toast.makeText(this,"文字起こしを停止してから新しい回を作成してください",Toast.LENGTH_LONG).show();return;}
        String keep=programInput==null?"":programInput.getText().toString().trim();saveCurrentEdits();activeEpisodeId=-1L;programInput.setText(keep);episodeInput.setText("");urlInput.setText("");transcript.setText("");setPlaybackSpeed(1.0f);correctionHint.setText("停止後に固有名詞などを直して「この修正を学習」を押すと次回から補正します。");status.setText("新しい回を準備中。URLを貼って開始してください。");meter.setText("待機中");updateCurrentLabel();
    }
    private void loadEpisode(EpisodeStore.Episode e){
        if(e==null||serviceRunning)return;saveCurrentEdits();activeEpisodeId=e.id;programInput.setText(e.program);episodeInput.setText(e.title);urlInput.setText(e.url);transcript.setText(e.transcript);transcript.setSelection(transcript.length());setPlaybackSpeed(e.playbackSpeed);status.setText("保存済みの回です。詳細画面でタイムライン・メモ・タグも見られます。");meter.setText(e.transcript.length()+"文字   "+speedLabel(e.playbackSpeed)+"   "+EpisodeStore.formatDuration(e.durationMs));updateCurrentLabel();
    }
    private void learnCurrentCorrections(){
        if(serviceRunning||activeEpisodeId<=0)return;int n=store.learnCorrectionsFromEdit(activeEpisodeId,transcript.getText().toString());store.autoBackup(this);
        correctionHint.setText(n>0?n+"件の修正を学習しました。":"学習できる小さな表記修正は見つかりませんでした。");Toast.makeText(this,n>0?n+"件を学習":"学習対象なし",Toast.LENGTH_LONG).show();renderLibrary();
    }

    private void refreshProgramFilter(){
        if(libraryProgramFilter==null)return;String selected=currentProgramFilter();programFilterValues.clear();programFilterValues.add("すべての番組");programFilterValues.addAll(store.listPrograms());refreshingFilter=true;ArrayAdapter<String>a=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,programFilterValues);a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);libraryProgramFilter.setAdapter(a);int idx=selected.isEmpty()?0:programFilterValues.indexOf(selected);libraryProgramFilter.setSelection(Math.max(0,idx));refreshingFilter=false;
    }
    private String currentProgramFilter(){if(libraryProgramFilter==null||libraryProgramFilter.getSelectedItemPosition()<=0)return"";Object o=libraryProgramFilter.getSelectedItem();return o==null?"":o.toString();}
    private void renderLibrary(){
        if(store==null||libraryContainer==null)return;String q=librarySearch==null?"":librarySearch.getText().toString(),pf=currentProgramFilter();ArrayList<EpisodeStore.Episode>list=store.listEpisodes(q,pf);libraryContainer.removeAllViews();libraryCount.setText(store.count()+"回保存済み / "+list.size()+"件表示");
        if(list.isEmpty()){TextView e=new TextView(this);e.setText("該当する文字起こしはありません。");e.setPadding(dp(8),dp(18),dp(8),dp(18));libraryContainer.addView(e);return;}for(EpisodeStore.Episode e:list)addEpisodeCard(e);
    }
    private void addEpisodeCard(EpisodeStore.Episode e){
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(14),dp(12),dp(14),dp(12));GradientDrawable bg=new GradientDrawable();bg.setColor(Color.parseColor(e.id==activeEpisodeId?"#ECFDF5":"#F8FAFC"));bg.setCornerRadius(dp(12));bg.setStroke(dp(1),Color.parseColor("#E5E7EB"));card.setBackground(bg);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(5),0,dp(5));card.setLayoutParams(lp);
        TextView p=new TextView(this);p.setText(e.program.isEmpty()?"番組名未入力":e.program);p.setTextSize(12);p.setTextColor(Color.parseColor("#6B7280"));card.addView(p);
        TextView t=new TextView(this);t.setText(e.title.isEmpty()?"名称未入力の回":e.title);t.setTextSize(16);t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);card.addView(t);
        TextView meta=new TextView(this);meta.setText(EpisodeStore.displayDate(e.updatedAt)+"   "+e.transcript.length()+"文字   "+EpisodeStore.formatDuration(e.durationMs)+"   "+statusLabel(e.status));meta.setTextSize(11);meta.setTextColor(Color.parseColor("#6B7280"));card.addView(meta);
        if(!e.tags.trim().isEmpty()){TextView tags=new TextView(this);tags.setText("# "+e.tags);tags.setTextSize(11);tags.setTextColor(Color.parseColor("#0F766E"));card.addView(tags);}
        String preview=e.transcript==null?"":e.transcript.replaceAll("\\s+"," ").trim();TextView pv=new TextView(this);pv.setText(preview.isEmpty()?"（文字起こしなし）":preview);pv.setTextSize(13);pv.setMaxLines(3);pv.setEllipsize(TextUtils.TruncateAt.END);pv.setPadding(0,dp(6),0,0);card.addView(pv);
        card.setOnClickListener(v->{Intent i=new Intent(this,EpisodeDetailActivity.class);i.putExtra("episodeId",e.id);startActivity(i);});
        card.setOnLongClickListener(v->{new AlertDialog.Builder(this).setTitle("この回を削除しますか？").setMessage(e.title).setNegativeButton("キャンセル",null).setPositiveButton("削除",(d,w)->{store.deleteEpisode(e.id);store.autoBackup(this);if(activeEpisodeId==e.id){activeEpisodeId=-1;transcript.setText("");}refreshProgramFilter();renderLibrary();}).show();return true;});libraryContainer.addView(card);
    }
    private String statusLabel(String s){if("complete".equals(s))return"完了";if("recording".equals(s))return"文字起こし中";if("error".equals(s))return"エラー";if("interrupted".equals(s))return"中断";if("imported".equals(s))return"移行";return"保存済み";}

    private void stopServiceTranscription(){Intent i=new Intent(this,TranscribeService.class);i.setAction(TranscribeService.ACTION_STOP);startService(i);}
    private void copyCurrent(){android.content.ClipboardManager cm=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);String h=programInput.getText()+" / "+episodeInput.getText();String body=activeEpisodeId>0?store.transcriptWithTimestamps(activeEpisodeId):transcript.getText().toString();cm.setPrimaryClip(android.content.ClipData.newPlainText("ラジオ文字起こし",h+"\n"+urlInput.getText()+"\n\n"+body));Toast.makeText(this,"タイムスタンプ付きでコピーしました",Toast.LENGTH_SHORT).show();}
    private Uri currentUri(){String s=urlInput.getText().toString().trim();return s.isEmpty()?null:Uri.parse(s);}
    private void openInBrowser(){Uri u=currentUri();if(u==null){status.setText("URLを入力してください。");return;}String[]pkgs={"com.android.chrome","com.sec.android.app.sbrowser"};for(String pkg:pkgs)try{Intent i=new Intent(Intent.ACTION_VIEW,u);i.setPackage(pkg);startActivity(i);return;}catch(Exception ignored){}try{startActivity(Intent.createChooser(new Intent(Intent.ACTION_VIEW,u),"ブラウザで開く"));}catch(Exception e){status.setText("ブラウザを開けませんでした。");}}
    private void openCurrentDetail(){if(activeEpisodeId<=0){Toast.makeText(this,"まだ保存された回がありません",Toast.LENGTH_SHORT).show();return;}saveCurrentEdits();Intent i=new Intent(this,EpisodeDetailActivity.class);i.putExtra("episodeId",activeEpisodeId);startActivity(i);}
    private void openDictionary(){Intent i=new Intent(this,DictionaryActivity.class);i.putExtra("program",programInput.getText().toString().trim());startActivity(i);}
    private void openProgramSummary(){Intent i=new Intent(this,ProgramActivity.class);i.putExtra("program",programInput.getText().toString().trim());startActivity(i);}

    private void confirmRestoreFile(){new AlertDialog.Builder(this).setTitle("完全バックアップから復元").setMessage("現在のアーカイブを置き換えます。先にバックアップを書き出すのがおすすめです。").setNegativeButton("キャンセル",null).setPositiveButton("ファイルを選ぶ",(d,w)->restoreLauncher.launch(new String[]{"application/json","text/plain"})).show();}
    private void confirmRestoreAuto(){new AlertDialog.Builder(this).setTitle("自動バックアップから復元").setMessage("直近の自動バックアップで現在のデータを置き換えます。").setNegativeButton("キャンセル",null).setPositiveButton("復元",(d,w)->{try{int n=store.restoreLatestAutoBackup(this);activeEpisodeId=-1;refreshProgramFilter();renderLibrary();ArrayList<EpisodeStore.Episode>a=store.listEpisodes("");if(!a.isEmpty())loadEpisode(a.get(0));Toast.makeText(this,n+"回を復元しました",Toast.LENGTH_LONG).show();}catch(Exception e){Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();}}).show();}
    private void writeText(Uri uri,String text,String ok){if(uri==null)return;try(OutputStreamWriter w=new OutputStreamWriter(getContentResolver().openOutputStream(uri),"UTF-8")){w.write(text);Toast.makeText(this,ok,Toast.LENGTH_LONG).show();}catch(Exception e){Toast.makeText(this,"書き出しに失敗しました",Toast.LENGTH_LONG).show();}}

    private void updateRunningUi(){startButton.setEnabled(!serviceRunning);micStartButton.setEnabled(!serviceRunning);newEpisodeButton.setEnabled(!serviceRunning);stopButton.setEnabled(serviceRunning);programInput.setEnabled(!serviceRunning);episodeInput.setEnabled(!serviceRunning);urlInput.setEnabled(!serviceRunning);playbackSpeedSpinner.setEnabled(!serviceRunning);learnButton.setEnabled(!serviceRunning);}
    private void updateCurrentLabel(){if(activeEpisodeId<=0){currentEpisodeLabel.setText("新しい回");return;}String p=programInput.getText().toString().trim(),t=episodeInput.getText().toString().trim();currentEpisodeLabel.setText((p.isEmpty()?"番組名未入力":p)+" / "+(t.isEmpty()?"名称未入力の回":t)+(serviceRunning?"  ● 文字起こし中":""));}
    private void saveCurrentEdits(){if(store==null||activeEpisodeId<=0||serviceRunning)return;store.updateMeta(activeEpisodeId,programInput.getText().toString().trim(),episodeInput.getText().toString().trim(),urlInput.getText().toString().trim());store.updateEditedTranscript(activeEpisodeId,transcript.getText().toString());}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    @Override protected void onPause(){saveCurrentEdits();super.onPause();}
    @Override protected void onResume(){super.onResume();if(store!=null){refreshProgramFilter();renderLibrary();if(activeEpisodeId>0&&!serviceRunning){EpisodeStore.Episode e=store.getEpisode(activeEpisodeId);if(e!=null){transcript.setText(e.transcript);transcript.setSelection(transcript.length());}}}}
    @Override protected void onDestroy(){try{unregisterReceiver(receiver);}catch(Exception ignored){}super.onDestroy();}
}

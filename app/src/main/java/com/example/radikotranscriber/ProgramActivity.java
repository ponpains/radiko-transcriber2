package com.example.radikotranscriber;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.*;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;

public class ProgramActivity extends AppCompatActivity {
    private EpisodeStore store;
    private Spinner programSpinner;
    private EditText search;
    private TextView stats;
    private LinearLayout episodes;
    private final ArrayList<String> programs=new ArrayList<>();

    @Override protected void onCreate(Bundle savedInstanceState){
        super.onCreate(savedInstanceState);setContentView(R.layout.activity_program);
        store=new EpisodeStore(this);programSpinner=findViewById(R.id.programSpinner);search=findViewById(R.id.programSearch);stats=findViewById(R.id.programStats);episodes=findViewById(R.id.programEpisodes);
        setupPrograms(getIntent().getStringExtra("program"));
        programSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?>p,android.view.View v,int pos,long id){render();}public void onNothingSelected(AdapterView<?>p){}});
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int b,int c){render();}public void afterTextChanged(Editable e){}});
        findViewById(R.id.programDictionary).setOnClickListener(v->{Intent i=new Intent(this,DictionaryActivity.class);i.putExtra("program",selectedProgram());startActivity(i);});
    }

    private void setupPrograms(String selected){
        programs.clear();programs.addAll(store.listPrograms());
        if(programs.isEmpty()){programs.add("番組名未入力");}
        ArrayAdapter<String>a=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,programs);a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);programSpinner.setAdapter(a);
        int idx=selected==null?-1:programs.indexOf(selected);programSpinner.setSelection(idx>=0?idx:0);
    }
    private String selectedProgram(){Object o=programSpinner.getSelectedItem();return o==null?"":o.toString();}

    private void render(){
        String p=selectedProgram();EpisodeStore.ProgramStats s=store.getProgramStats(p);
        stats.setText(s.episodeCount+"回  ・  "+s.totalChars+"文字  ・  合計 "+EpisodeStore.formatDuration(s.totalDurationMs)+"\n最終更新 "+EpisodeStore.displayDate(s.lastUpdatedAt));
        episodes.removeAllViews();ArrayList<EpisodeStore.Episode>list=store.listEpisodes(search.getText().toString(),p);
        if(list.isEmpty()){TextView t=new TextView(this);t.setText("この条件に合う回はありません。");t.setPadding(8,18,8,18);episodes.addView(t);return;}
        for(EpisodeStore.Episode e:list){
            LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(14,12,14,12);card.setBackgroundColor(Color.WHITE);
            TextView title=new TextView(this);title.setText(e.title);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);title.setTextSize(16);card.addView(title);
            TextView meta=new TextView(this);meta.setText(EpisodeStore.displayDate(e.updatedAt)+"   "+e.transcript.length()+"文字   "+EpisodeStore.formatDuration(e.durationMs));meta.setTextSize(11);meta.setTextColor(Color.parseColor("#6B7280"));card.addView(meta);
            if(!e.tags.trim().isEmpty()){TextView tag=new TextView(this);tag.setText("# "+e.tags);tag.setTextColor(Color.parseColor("#0F766E"));tag.setTextSize(11);card.addView(tag);}
            String pv=e.transcript.replaceAll("\\s+"," ").trim();TextView preview=new TextView(this);preview.setText(pv);preview.setMaxLines(3);preview.setTextSize(13);card.addView(preview);
            card.setOnClickListener(v->{Intent i=new Intent(this,EpisodeDetailActivity.class);i.putExtra("episodeId",e.id);startActivity(i);});
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,5,0,5);card.setLayoutParams(lp);episodes.addView(card);
        }
    }

    @Override protected void onResume(){super.onResume();render();}
}

package com.example.radikotranscriber;

import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.*;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;

public class DictionaryActivity extends AppCompatActivity {
    private EpisodeStore store;
    private Spinner programSpinner;
    private EditText wrongInput, correctInput;
    private LinearLayout container;
    private final ArrayList<String> programs = new ArrayList<>();

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState); setContentView(R.layout.activity_dictionary);
        store=new EpisodeStore(this); programSpinner=findViewById(R.id.dictProgram);wrongInput=findViewById(R.id.dictWrong);correctInput=findViewById(R.id.dictCorrect);container=findViewById(R.id.dictContainer);
        setupPrograms(getIntent().getStringExtra("program"));
        programSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?>p,android.view.View v,int pos,long id){render();}public void onNothingSelected(AdapterView<?>p){}});
        findViewById(R.id.dictAdd).setOnClickListener(v->add());
    }

    private void setupPrograms(String selected){
        programs.clear();programs.add("共通辞書（すべての番組）");programs.addAll(store.listPrograms());
        ArrayAdapter<String>a=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,programs);a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);programSpinner.setAdapter(a);
        int idx=selected==null?0:programs.indexOf(selected);programSpinner.setSelection(Math.max(0,idx));
    }
    private String selectedProgram(){int p=programSpinner.getSelectedItemPosition();return p<=0?"":programs.get(p);}
    private void add(){String w=wrongInput.getText().toString().trim(),c=correctInput.getText().toString().trim();if(w.length()<2||c.isEmpty()){Toast.makeText(this,"誤認識と正しい表記を入力してください",Toast.LENGTH_SHORT).show();return;}store.addCorrection(selectedProgram(),w,c);wrongInput.setText("");correctInput.setText("");store.autoBackup(this);render();}

    private void render(){
        container.removeAllViews();ArrayList<EpisodeStore.Correction> list=store.getCorrections(selectedProgram());
        if(list.isEmpty()){TextView t=new TextView(this);t.setText("まだ辞書項目はありません。");t.setPadding(8,20,8,20);container.addView(t);return;}
        for(EpisodeStore.Correction c:list){
            LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.VERTICAL);row.setPadding(10,10,10,10);row.setBackgroundColor(Color.parseColor("#F8FAFC"));
            TextView text=new TextView(this);text.setText(c.wrong+"  →  "+c.correct+"\n優先度 "+c.uses+(c.program.isEmpty()?"  ・共通":""));text.setTextSize(15);row.addView(text);
            LinearLayout actions=new LinearLayout(this);actions.setGravity(Gravity.END);
            Button down=new Button(this);down.setText("−");Button up=new Button(this);up.setText("＋");Button del=new Button(this);del.setText("削除");
            down.setOnClickListener(v->{store.adjustCorrectionPriority(c.id,-1);render();});up.setOnClickListener(v->{store.adjustCorrectionPriority(c.id,1);render();});del.setOnClickListener(v->{store.deleteCorrection(c.id);store.autoBackup(this);render();});
            actions.addView(down);actions.addView(up);actions.addView(del);row.addView(actions);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,5,0,5);row.setLayoutParams(lp);container.addView(row);
        }
    }
}

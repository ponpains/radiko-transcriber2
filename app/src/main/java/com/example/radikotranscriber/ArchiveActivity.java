package com.example.radikotranscriber;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;

public class ArchiveActivity extends AppCompatActivity {
    private EpisodeStore store;
    private EditText search;
    private Spinner programFilter, dateFilter, statusFilter, sortFilter;
    private TextView count;
    private ListView listView;
    private final ArrayList<String> programs = new ArrayList<>();
    private final ArrayList<EpisodeStore.Episode> rows = new ArrayList<>();
    private ArchiveAdapter adapter;
    private boolean refreshingPrograms = false;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_archive);
        store = new EpisodeStore(this);
        search = findViewById(R.id.archiveSearch);
        programFilter = findViewById(R.id.archiveProgramFilter);
        dateFilter = findViewById(R.id.archiveDateFilter);
        statusFilter = findViewById(R.id.archiveStatusFilter);
        sortFilter = findViewById(R.id.archiveSortFilter);
        count = findViewById(R.id.archiveCount);
        listView = findViewById(R.id.archiveList);

        setupFixedSpinners();
        refreshPrograms();
        adapter = new ArchiveAdapter();
        listView.setAdapter(adapter);

        findViewById(R.id.archiveBack).setOnClickListener(v -> finish());
        findViewById(R.id.archiveProgramSummary).setOnClickListener(v -> openProgramSummary());
        findViewById(R.id.archiveDictionary).setOnClickListener(v -> openDictionary());

        AdapterView.OnItemSelectedListener listener = new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!refreshingPrograms) render();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        };
        programFilter.setOnItemSelectedListener(listener);
        dateFilter.setOnItemSelectedListener(listener);
        statusFilter.setOnItemSelectedListener(listener);
        sortFilter.setOnItemSelectedListener(listener);

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { render(); }
            @Override public void afterTextChanged(Editable s) {}
        });

        listView.setOnItemClickListener((parent, view, position, id) -> {
            EpisodeStore.Episode e = rows.get(position);
            Intent i = new Intent(this, EpisodeDetailActivity.class);
            i.putExtra("episodeId", e.id);
            startActivity(i);
        });

        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            EpisodeStore.Episode e = rows.get(position);
            new AlertDialog.Builder(this)
                    .setTitle("この回を削除しますか？")
                    .setMessage((e.program.isEmpty() ? "番組名未入力" : e.program) + "\n" + e.title)
                    .setNegativeButton("キャンセル", null)
                    .setPositiveButton("削除", (d, w) -> {
                        store.deleteEpisode(e.id);
                        store.autoBackup(this);
                        refreshPrograms();
                        render();
                    }).show();
            return true;
        });

        render();
    }

    private void setupFixedSpinners() {
        setSpinner(dateFilter, new String[]{"すべての期間", "過去30日", "過去90日", "今年"});
        setSpinner(statusFilter, new String[]{"すべての状態", "完了", "中断・エラー", "文字起こし中"});
        setSpinner(sortFilter, new String[]{"新しい順", "古い順", "文字数が多い順", "番組名・回名順"});
    }

    private void setSpinner(Spinner spinner, String[] values) {
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, values);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(a);
    }

    private void refreshPrograms() {
        String keep = selectedProgram();
        programs.clear();
        programs.add("すべての番組");
        programs.addAll(store.listPrograms());
        refreshingPrograms = true;
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, programs);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        programFilter.setAdapter(a);
        int idx = keep.isEmpty() ? 0 : programs.indexOf(keep);
        programFilter.setSelection(Math.max(0, idx));
        refreshingPrograms = false;
    }

    private String selectedProgram() {
        if (programFilter == null || programFilter.getSelectedItemPosition() <= 0) return "";
        Object o = programFilter.getSelectedItem();
        return o == null ? "" : o.toString();
    }

    private void render() {
        if (store == null || adapter == null) return;
        String q = search.getText().toString().trim();
        String program = selectedProgram();
        ArrayList<EpisodeStore.Episode> base = store.listEpisodes(q, program);
        rows.clear();
        long minTime = minUpdatedAt();
        int statusMode = statusFilter.getSelectedItemPosition();
        for (EpisodeStore.Episode e : base) {
            if (minTime > 0 && e.updatedAt < minTime) continue;
            if (!matchesStatus(e.status, statusMode)) continue;
            rows.add(e);
        }
        sortRows();
        count.setText(store.count() + "回保存済み / " + rows.size() + "件表示");
        adapter.notifyDataSetChanged();
        if (!rows.isEmpty()) listView.setSelection(0);
    }

    private long minUpdatedAt() {
        int mode = dateFilter.getSelectedItemPosition();
        long now = System.currentTimeMillis();
        if (mode == 1) return now - 30L * 24L * 60L * 60L * 1000L;
        if (mode == 2) return now - 90L * 24L * 60L * 60L * 1000L;
        if (mode == 3) {
            Calendar c = Calendar.getInstance();
            c.set(Calendar.MONTH, Calendar.JANUARY);
            c.set(Calendar.DAY_OF_MONTH, 1);
            c.set(Calendar.HOUR_OF_DAY, 0);
            c.set(Calendar.MINUTE, 0);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            return c.getTimeInMillis();
        }
        return 0L;
    }

    private boolean matchesStatus(String status, int mode) {
        if (mode == 0) return true;
        if (mode == 1) return "complete".equals(status);
        if (mode == 2) return "interrupted".equals(status) || "error".equals(status);
        return "recording".equals(status);
    }

    private void sortRows() {
        int mode = sortFilter.getSelectedItemPosition();
        if (mode == 1) {
            Collections.sort(rows, Comparator.comparingLong(e -> e.updatedAt));
        } else if (mode == 2) {
            Collections.sort(rows, (a, b) -> Integer.compare(b.transcript.length(), a.transcript.length()));
        } else if (mode == 3) {
            Collections.sort(rows, (a, b) -> {
                int p = a.program.compareToIgnoreCase(b.program);
                return p != 0 ? p : a.title.compareToIgnoreCase(b.title);
            });
        } else {
            Collections.sort(rows, (a, b) -> Long.compare(b.updatedAt, a.updatedAt));
        }
    }

    private void openProgramSummary() {
        String p = selectedProgram();
        if (p.isEmpty()) {
            Toast.makeText(this, "先に番組を絞り込んでください", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(this, ProgramActivity.class);
        i.putExtra("program", p);
        startActivity(i);
    }

    private void openDictionary() {
        String p = selectedProgram();
        Intent i = new Intent(this, DictionaryActivity.class);
        i.putExtra("program", p);
        startActivity(i);
    }

    private CharSequence snippet(EpisodeStore.Episode e) {
        String q = search.getText().toString().trim();
        if (q.isEmpty()) {
            String tags = e.tags == null || e.tags.trim().isEmpty() ? "" : "   # " + e.tags.trim();
            return EpisodeStore.displayDate(e.updatedAt) + "   " + e.transcript.length()
                    + "文字   " + EpisodeStore.formatDuration(e.durationMs) + tags;
        }
        String hay = ((e.transcript == null ? "" : e.transcript) + "\n"
                + (e.keyPoints == null ? "" : e.keyPoints) + "\n"
                + (e.notes == null ? "" : e.notes) + "\n"
                + (e.tags == null ? "" : e.tags)).replaceAll("\\s+", " ").trim();
        int at = hay.indexOf(q);
        if (at < 0) return EpisodeStore.displayDate(e.updatedAt) + "   " + e.transcript.length() + "文字";
        int start = Math.max(0, at - 34);
        int end = Math.min(hay.length(), at + q.length() + 52);
        String text = (start > 0 ? "…" : "") + hay.substring(start, end) + (end < hay.length() ? "…" : "");
        SpannableString sp = new SpannableString(text);
        int hi = text.indexOf(q);
        if (hi >= 0) sp.setSpan(new StyleSpan(Typeface.BOLD), hi, hi + q.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sp;
    }

    private String statusLabel(String s) {
        if ("complete".equals(s)) return "完了";
        if ("recording".equals(s)) return "文字起こし中";
        if ("interrupted".equals(s)) return "中断";
        if ("error".equals(s)) return "エラー";
        return "保存済み";
    }

    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }

    private class ArchiveAdapter extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int position) { return rows.get(position); }
        @Override public long getItemId(int position) { return rows.get(position).id; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            Holder h;
            if (convertView == null) {
                LinearLayout box = new LinearLayout(ArchiveActivity.this);
                box.setOrientation(LinearLayout.VERTICAL);
                box.setGravity(Gravity.CENTER_VERTICAL);
                box.setPadding(dp(14), dp(9), dp(14), dp(9));
                TextView program = new TextView(ArchiveActivity.this);
                program.setTextSize(11);
                program.setTextColor(Color.parseColor("#6B7280"));
                TextView title = new TextView(ArchiveActivity.this);
                title.setTextSize(15);
                title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                title.setSingleLine(true);
                title.setEllipsize(TextUtils.TruncateAt.END);
                TextView sub = new TextView(ArchiveActivity.this);
                sub.setTextSize(12);
                sub.setTextColor(Color.parseColor("#4B5563"));
                sub.setMaxLines(2);
                sub.setEllipsize(TextUtils.TruncateAt.END);
                box.addView(program);
                box.addView(title);
                box.addView(sub);
                h = new Holder(program, title, sub);
                box.setTag(h);
                convertView = box;
            } else h = (Holder)convertView.getTag();

            EpisodeStore.Episode e = rows.get(position);
            h.program.setText((e.program.isEmpty() ? "番組名未入力" : e.program) + "   " + statusLabel(e.status));
            h.title.setText(e.title.isEmpty() ? "名称未入力の回" : e.title);
            h.sub.setText(snippet(e));
            convertView.setBackgroundColor(position % 2 == 0 ? Color.WHITE : Color.parseColor("#F8FAFC"));
            return convertView;
        }
    }

    private static class Holder {
        final TextView program, title, sub;
        Holder(TextView program, TextView title, TextView sub) {
            this.program = program;
            this.title = title;
            this.sub = sub;
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (store != null) {
            refreshPrograms();
            render();
        }
    }
}

package com.ponpains.shioriarchive;

import android.app.DatePickerDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.*;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.time.LocalDate;
import java.time.MonthDay;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class MainActivity extends AppCompatActivity {
    static final int INK=Color.rgb(31,33,38), PAPER=Color.rgb(246,247,249), CARD=Color.WHITE,
            ACCENT=Color.rgb(62,118,246), SOFT=Color.rgb(235,241,255), LINE=Color.rgb(230,232,236),
            MUTED=Color.rgb(112,116,126), TEAL=Color.rgb(69,157,148), RED=Color.rgb(208,83,83);
    static final int PAGE_HOME=0, PAGE_CALENDAR=1, PAGE_DIARY=2, PAGE_SEARCH=3;

    ArchiveRepository repo;
    List<ArchiveEvent> events=new ArrayList<>();
    String updated="";
    FrameLayout content;
    TextView status;
    LocalDate selectedDate=LocalDate.now();
    YearMonth shownMonth=YearMonth.from(selectedDate);
    GridLayout monthGrid;
    TextView monthTitle, dayHeader;
    LinearLayout dayOut;
    int currentPage=PAGE_HOME;
    float swipeDownX, swipeDownY;

    @Override protected void onCreate(@Nullable Bundle state){
        super.onCreate(state);
        repo=new ArchiveRepository(this);
        ArchiveRepository.ArchiveData d=repo.loadBestAvailable();
        events=d.events;
        updated=d.updatedAt;
        setContentView(shell());
        goToday();
        repo.refreshIfNeeded((e,u)->{
            events=e;
            updated=u;
            status.setText(stat());
            refreshCurrentPage();
        });
    }

    @Override public boolean dispatchTouchEvent(MotionEvent ev){
        if(ev.getActionMasked()==MotionEvent.ACTION_DOWN){
            swipeDownX=ev.getX();
            swipeDownY=ev.getY();
        }else if(ev.getActionMasked()==MotionEvent.ACTION_UP && currentPage!=PAGE_SEARCH){
            float dx=ev.getX()-swipeDownX, dy=ev.getY()-swipeDownY;
            if(Math.abs(dx)>dp(78) && Math.abs(dx)>Math.abs(dy)*1.45f){
                if(currentPage==PAGE_CALENDAR) shiftMonth(dx<0?1:-1);
                else shiftDate(dx<0?1:-1);
                return true;
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    View shell(){
        LinearLayout root=col();
        root.setPadding(dp(14),dp(12),dp(14),dp(8));
        root.addView(tx("詩央里暦",25,INK,true));
        root.addView(tx("永田詩央里さんの記録を日付でたどる",12,MUTED,false));
        status=tx(stat(),10,MUTED,false);
        status.setPadding(0,dp(2),0,dp(7));
        root.addView(status);
        content=new FrameLayout(this);
        root.addView(content,new LinearLayout.LayoutParams(-1,0,1));

        LinearLayout nav=new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER);
        nav.addView(navButton("今日",v->goToday()),navLp());
        nav.addView(navButton("カレンダー",v->calendar()),navLp());
        nav.addView(navButton("日誌",v->diary()),navLp());
        nav.addView(navButton("検索",v->search()),navLp());
        root.addView(nav);
        return root;
    }

    String stat(){
        return events.size()+"件  ·  "+activeDays()+"日  ·  更新 "+(updated.isEmpty()?"—":updated);
    }

    int activeDays(){
        Set<LocalDate> set=new HashSet<>();
        for(ArchiveEvent e:events)set.add(e.date);
        return set.size();
    }

    LinearLayout.LayoutParams navLp(){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(43),1);
        p.setMargins(dp(2),0,dp(2),0);
        return p;
    }

    Button navButton(String text,View.OnClickListener listener){
        Button b=new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setTextColor(INK);
        b.setBackground(bg(CARD,14,LINE));
        b.setOnClickListener(listener);
        return b;
    }

    void goToday(){
        selectedDate=LocalDate.now();
        shownMonth=YearMonth.from(selectedDate);
        home();
    }

    void refreshCurrentPage(){
        if(currentPage==PAGE_CALENDAR)calendar();
        else if(currentPage==PAGE_DIARY)diary();
        else if(currentPage==PAGE_SEARCH)search();
        else home();
    }

    void shiftDate(int days){
        selectedDate=selectedDate.plusDays(days);
        shownMonth=YearMonth.from(selectedDate);
        if(currentPage==PAGE_DIARY)diary(); else home();
    }

    void shiftMonth(int months){
        shownMonth=shownMonth.plusMonths(months);
        int day=Math.min(selectedDate.getDayOfMonth(),shownMonth.lengthOfMonth());
        selectedDate=shownMonth.atDay(day);
        calendar();
    }

    String dateTitle(LocalDate d){
        return d.format(DateTimeFormatter.ofPattern("M月d日（E）",Locale.JAPANESE));
    }

    void home(){
        currentPage=PAGE_HOME;
        LinearLayout body=col();
        body.addView(tx(dateTitle(selectedDate),29,INK,true));
        body.addView(tx("左右フリックで日付移動  ·  下へスクロールすると過去へ",11,MUTED,false));

        TextView now=section("この日の記録");
        body.addView(now);
        addDayEvents(body,selectedDate,false,true);
        addOnThisDay(body,selectedDate);

        TextView past=section("これまで");
        past.setPadding(0,dp(20),0,dp(3));
        body.addView(past);
        for(int i=1;i<=42;i++) addTimelineDay(body,selectedDate.minusDays(i));
        screen(scroll(body));
    }

    void addOnThisDay(LinearLayout body,LocalDate base){
        List<ArchiveEvent> hits=new ArrayList<>();
        MonthDay md=MonthDay.from(base);
        for(ArchiveEvent e:events){
            if(e.date.getYear()<base.getYear() && MonthDay.from(e.date).equals(md))hits.add(e);
        }
        if(hits.isEmpty())return;
        hits.sort(Comparator.naturalOrder());
        TextView h=section("○年前の今日");
        h.setPadding(0,dp(18),0,dp(3));
        body.addView(h);
        int shown=0;
        for(ArchiveEvent e:hits){
            if(shown>=8)break;
            int years=base.getYear()-e.date.getYear();
            LinearLayout row=new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.TOP);
            TextView age=tx(years+"年前",11,ACCENT,true);
            age.setPadding(0,dp(11),dp(8),0);
            row.addView(age,new LinearLayout.LayoutParams(dp(58),-2));
            row.addView(compactCard(e,false),new LinearLayout.LayoutParams(0,-2,1));
            body.addView(row);
            shown++;
        }
    }

    void addTimelineDay(LinearLayout body,LocalDate date){
        TextView h=tx(date.format(DateTimeFormatter.ofPattern("M月d日（E）",Locale.JAPANESE)),15,INK,true);
        h.setPadding(dp(2),dp(13),0,dp(3));
        body.addView(h);
        addDayEvents(body,date,false,false);
    }

    void addDayEvents(LinearLayout body,LocalDate date,boolean showDate,boolean roomyEmpty){
        List<ArchiveEvent> list=eventsFor(date);
        if(list.isEmpty()){
            TextView none=tx("記録なし",11,MUTED,false);
            none.setPadding(dp(10),roomyEmpty?dp(10):dp(4),0,roomyEmpty?dp(8):dp(2));
            body.addView(none);
            return;
        }
        for(ArchiveEvent e:list)body.addView(compactCard(e,showDate));
    }

    List<ArchiveEvent> eventsFor(LocalDate d){
        List<ArchiveEvent> out=new ArrayList<>();
        for(ArchiveEvent e:events)if(e.date.equals(d))out.add(e);
        out.sort((a,b)->timeKey(a.time).compareTo(timeKey(b.time)));
        return out;
    }

    void calendar(){
        currentPage=PAGE_CALENDAR;
        LinearLayout body=col();

        LinearLayout top=new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        Button prev=miniButton("‹",v->shiftMonth(-1));
        Button next=miniButton("›",v->shiftMonth(1));
        monthTitle=tx("",21,INK,true);
        monthTitle.setGravity(Gravity.CENTER);
        monthTitle.setOnClickListener(v->pickCalendarDate());
        top.addView(prev,new LinearLayout.LayoutParams(dp(42),dp(40)));
        top.addView(monthTitle,new LinearLayout.LayoutParams(0,dp(40),1));
        top.addView(next,new LinearLayout.LayoutParams(dp(42),dp(40)));
        body.addView(top);

        TextView hint=tx("左右フリックで月移動",10,MUTED,false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0,0,0,dp(7));
        body.addView(hint);

        monthGrid=new GridLayout(this);
        monthGrid.setColumnCount(7);
        monthGrid.setUseDefaultMargins(false);
        monthGrid.setBackgroundColor(PAPER);
        body.addView(monthGrid,new LinearLayout.LayoutParams(-1,-2));

        dayHeader=tx("",18,INK,true);
        dayHeader.setPadding(dp(2),dp(13),0,dp(2));
        body.addView(dayHeader);
        dayOut=col();
        body.addView(dayOut);
        renderCalendar();
        renderSelectedDay();
        screen(scroll(body));
    }

    void pickCalendarDate(){
        DatePickerDialog d=new DatePickerDialog(this,(v,y,m,day)->{
            selectedDate=LocalDate.of(y,m+1,day);
            shownMonth=YearMonth.from(selectedDate);
            calendar();
        },selectedDate.getYear(),selectedDate.getMonthValue()-1,selectedDate.getDayOfMonth());
        d.show();
    }

    void renderCalendar(){
        if(monthGrid==null)return;
        monthTitle.setText(shownMonth.format(DateTimeFormatter.ofPattern("yyyy年 M月",Locale.JAPANESE)));
        monthGrid.removeAllViews();
        String[] weekdays={"日","月","火","水","木","金","土"};
        for(int i=0;i<7;i++){
            int color=i==0?RED:(i==6?ACCENT:MUTED);
            TextView w=tx(weekdays[i],10,color,true);
            w.setGravity(Gravity.CENTER);
            monthGrid.addView(w,gridLp(dp(24),0));
        }

        Map<LocalDate,List<ArchiveEvent>> byDay=new HashMap<>();
        for(ArchiveEvent e:events)byDay.computeIfAbsent(e.date,k->new ArrayList<>()).add(e);
        int offset=shownMonth.atDay(1).getDayOfWeek().getValue()%7;
        LocalDate start=shownMonth.atDay(1).minusDays(offset);
        for(int i=0;i<42;i++){
            LocalDate date=start.plusDays(i);
            monthGrid.addView(calendarCell(date,YearMonth.from(date).equals(shownMonth),byDay.getOrDefault(date,Collections.emptyList())),gridLp(dp(61),1));
        }
    }

    View calendarCell(LocalDate date,boolean inMonth,List<ArchiveEvent> list){
        LinearLayout cell=new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.TOP|Gravity.CENTER_HORIZONTAL);
        cell.setPadding(dp(2),dp(4),dp(2),dp(2));
        boolean selected=date.equals(selectedDate);
        boolean today=date.equals(LocalDate.now());
        int fill=selected?Color.rgb(239,244,255):CARD;
        int stroke=selected?ACCENT:(today?Color.rgb(186,205,252):LINE);
        cell.setBackground(bg(fill,9,stroke));

        TextView day=tx(String.valueOf(date.getDayOfMonth()),11,inMonth?INK:Color.rgb(177,180,187),selected);
        day.setGravity(Gravity.CENTER);
        if(selected){
            day.setTextColor(Color.WHITE);
            day.setBackground(bg(ACCENT,16,ACCENT));
            cell.addView(day,new LinearLayout.LayoutParams(dp(24),dp(24)));
        }else{
            cell.addView(day,new LinearLayout.LayoutParams(dp(24),dp(24)));
        }

        if(!list.isEmpty()){
            LinearLayout dots=new LinearLayout(this);
            dots.setOrientation(LinearLayout.HORIZONTAL);
            dots.setGravity(Gravity.CENTER);
            int max=Math.min(3,list.size());
            for(int i=0;i<max;i++){
                TextView dot=new TextView(this);
                int color=list.get(i).isX()?ACCENT:TEAL;
                dot.setBackground(bg(color,5,color));
                LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(5),dp(5));
                p.setMargins(dp(1),dp(7),dp(1),0);
                dots.addView(dot,p);
            }
            cell.addView(dots,new LinearLayout.LayoutParams(-1,dp(18)));
            if(list.size()>3){
                TextView more=tx("+"+(list.size()-3),8,MUTED,false);
                more.setGravity(Gravity.CENTER);
                cell.addView(more,new LinearLayout.LayoutParams(-1,dp(12)));
            }
        }

        cell.setOnClickListener(v->{
            selectedDate=date;
            if(!YearMonth.from(date).equals(shownMonth))shownMonth=YearMonth.from(date);
            renderCalendar();
            renderSelectedDay();
        });
        return cell;
    }

    GridLayout.LayoutParams gridLp(int height,int margin){
        GridLayout.LayoutParams p=new GridLayout.LayoutParams();
        p.width=0;
        p.height=height;
        p.columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1f);
        p.setMargins(dp(margin),dp(margin),dp(margin),dp(margin));
        return p;
    }

    void renderSelectedDay(){
        if(dayHeader==null||dayOut==null)return;
        List<ArchiveEvent> list=eventsFor(selectedDate);
        dayHeader.setText(selectedDate.format(DateTimeFormatter.ofPattern("M月d日（E）",Locale.JAPANESE))+"  "+list.size()+"件");
        dayOut.removeAllViews();
        if(list.isEmpty()){
            TextView none=tx("記録なし",11,MUTED,false);
            none.setPadding(dp(10),dp(8),0,dp(8));
            dayOut.addView(none);
            return;
        }
        for(ArchiveEvent e:list)dayOut.addView(compactCard(e,false));
    }

    void diary(){
        currentPage=PAGE_DIARY;
        LinearLayout body=col();
        LinearLayout top=new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        Button prev=miniButton("‹",v->shiftDate(-1));
        Button pick=miniButton(selectedDate.format(DateTimeFormatter.ofPattern("yyyy年M月d日",Locale.JAPANESE)),v->pickDiaryDate());
        Button next=miniButton("›",v->shiftDate(1));
        top.addView(prev,new LinearLayout.LayoutParams(dp(42),dp(40)));
        top.addView(pick,new LinearLayout.LayoutParams(0,dp(40),1));
        top.addView(next,new LinearLayout.LayoutParams(dp(42),dp(40)));
        body.addView(top);
        TextView hint=tx("左右フリックで日付移動  ·  下へスクロールすると過去へ",10,MUTED,false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0,0,0,dp(5));
        body.addView(hint);
        for(int i=0;i<=55;i++)addTimelineDay(body,selectedDate.minusDays(i));
        screen(scroll(body));
    }

    void pickDiaryDate(){
        DatePickerDialog d=new DatePickerDialog(this,(v,y,m,day)->{
            selectedDate=LocalDate.of(y,m+1,day);
            shownMonth=YearMonth.from(selectedDate);
            diary();
        },selectedDate.getYear(),selectedDate.getMonthValue()-1,selectedDate.getDayOfMonth());
        d.show();
    }

    void search(){
        currentPage=PAGE_SEARCH;
        LinearLayout body=col();
        body.addView(tx("検索",25,INK,true));
        body.addView(tx("出来事・媒体・人物・X原文を横断します",11,MUTED,false));
        EditText q=new EditText(this);
        q.setHint("例：ラジオ / 鈴木瞳美 / ぼのぼの / X");
        q.setSingleLine();
        q.setBackground(bg(CARD,14,LINE));
        q.setPadding(dp(13),0,dp(13),0);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(48));
        p.setMargins(0,dp(10),0,dp(6));
        body.addView(q,p);
        TextView count=tx("",11,MUTED,false);
        body.addView(count);
        LinearLayout out=col();
        body.addView(out);
        renderResults("",out,count);
        q.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int a,int c,int d){}
            public void onTextChanged(CharSequence s,int a,int c,int d){renderResults(s.toString(),out,count);}
            public void afterTextChanged(Editable e){}
        });
        screen(scroll(body));
    }

    void renderResults(String query,LinearLayout out,TextView count){
        out.removeAllViews();
        int n=0;
        for(ArchiveEvent e:events){
            if(!e.matches(query))continue;
            if(query.trim().isEmpty()&&n>=25)break;
            out.addView(compactCard(e,true));
            n++;
        }
        count.setText(query.trim().isEmpty()?"新しい順に25件":n+"件");
        if(n==0){
            TextView none=tx("該当する記録はありません",12,MUTED,false);
            none.setPadding(dp(8),dp(13),0,0);
            out.addView(none);
        }
    }

    View compactCard(ArchiveEvent e,boolean showDate){
        LinearLayout card=col();
        card.setPadding(dp(11),dp(8),dp(11),dp(8));
        card.setBackground(bg(CARD,12,LINE));
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);
        cp.setMargins(0,dp(4),0,0);
        card.setLayoutParams(cp);

        String meta=(showDate?e.date.format(DateTimeFormatter.ofPattern("M/d",Locale.JAPANESE))+"  ·  ":"")+(e.time.isEmpty()?"":e.time+"  ·  ")+e.type;
        TextView m=tx(meta,10,e.isX()?ACCENT:MUTED,true);
        card.addView(m);

        String title=e.title==null?"":e.title.trim();
        if(!title.isEmpty() && !(e.isX()&&isGenericXTitle(title))){
            TextView t=tx(title,14,INK,true);
            t.setPadding(0,dp(2),0,0);
            t.setMaxLines(1);
            t.setEllipsize(TextUtils.TruncateAt.END);
            card.addView(t);
        }

        String body=e.bodyText()==null?"":e.bodyText().trim();
        if(!body.isEmpty()){
            TextView b=tx(body,e.isX()?13:12,INK,false);
            b.setPadding(0,dp(2),0,0);
            b.setMaxLines(e.isX()?2:3);
            b.setEllipsize(TextUtils.TruncateAt.END);
            card.addView(b);
        }

        if(e.sourceUrl.startsWith("http")){
            TextView source=tx(e.isX()?"元投稿 ↗":"出典 ↗",10,ACCENT,true);
            source.setPadding(0,dp(4),0,0);
            card.addView(source);
            card.setOnClickListener(v->open(e.sourceUrl));
        }
        return card;
    }

    boolean isGenericXTitle(String title){
        String s=title.trim();
        return s.equals("X投稿")||s.equals("本人X投稿")||s.equals("投稿");
    }

    TextView section(String text){
        TextView v=tx(text,14,ACCENT,true);
        v.setPadding(0,dp(8),0,dp(2));
        return v;
    }

    String timeKey(String s){return s==null||s.isEmpty()?"99:99:99":s;}

    Button miniButton(String text,View.OnClickListener listener){
        Button b=new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(INK);
        b.setBackground(bg(CARD,12,LINE));
        b.setOnClickListener(listener);
        return b;
    }

    void open(String url){
        try{
            Uri uri=Uri.parse(url);
            if(!"https".equalsIgnoreCase(uri.getScheme())&&!"http".equalsIgnoreCase(uri.getScheme()))return;
            startActivity(new Intent(Intent.ACTION_VIEW,uri));
        }catch(Exception ignored){
            Toast.makeText(this,"ページを開けませんでした",Toast.LENGTH_SHORT).show();
        }
    }

    LinearLayout col(){
        LinearLayout l=new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackgroundColor(PAPER);
        return l;
    }

    ScrollView scroll(View v){
        ScrollView s=new ScrollView(this);
        s.setFillViewport(true);
        s.setClipToPadding(false);
        s.addView(v,new ScrollView.LayoutParams(-1,-2));
        return s;
    }

    void screen(View v){
        content.removeAllViews();
        content.addView(v,new FrameLayout.LayoutParams(-1,-1));
    }

    TextView tx(String text,int size,int color,boolean bold){
        TextView v=new TextView(this);
        v.setText(text==null?"":text);
        v.setTextSize(size);
        v.setTextColor(color);
        if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return v;
    }

    GradientDrawable bg(int fill,int radius,int stroke){
        GradientDrawable d=new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radius));
        d.setStroke(dp(1),stroke);
        return d;
    }

    int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
}

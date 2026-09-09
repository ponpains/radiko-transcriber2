#!/usr/bin/env python3
from pathlib import Path

MAIN=Path('shiori-archive/app/src/main/java/com/ponpains/shioriarchive/MainActivity.java')
EVENT=Path('shiori-archive/app/src/main/java/com/ponpains/shioriarchive/ArchiveEvent.java')

s=MAIN.read_text(encoding='utf-8')
if 'String searchTypeFilter=' not in s:
    s=s.replace('    int currentPage=PAGE_HOME;\n','    int currentPage=PAGE_HOME;\n    String searchTypeFilter="";\n')
start=s.index('    void search(){')
end=s.index('    View compactCard(',start)
block='''    void search(){
        currentPage=PAGE_SEARCH;
        LinearLayout body=col();
        body.addView(tx("検索",25,INK,true));
        body.addView(tx("キーワードと媒体を組み合わせて絞り込めます",11,MUTED,false));
        EditText q=new EditText(this);
        q.setHint("例：ラジオ / 鈴木瞳美 / ぼのぼの");
        q.setSingleLine();
        q.setBackground(bg(CARD,14,LINE));
        q.setPadding(dp(13),0,dp(13),0);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(48));
        p.setMargins(0,dp(10),0,dp(7));
        body.addView(q,p);

        HorizontalScrollView hs=new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout filters=new LinearLayout(this);
        filters.setOrientation(LinearLayout.HORIZONTAL);
        filters.setPadding(0,0,0,dp(4));
        String[] labels={"すべて","X","Instagram","TikTok","インタビュー","SHOWROOM","YouTube","ラジオ","テレビ","イベント"};
        for(String label:labels){
            Button chip=new Button(this);
            chip.setText(label);
            chip.setTag(label);
            chip.setAllCaps(false);
            chip.setTextSize(11);
            chip.setMinHeight(0);chip.setMinimumHeight(0);chip.setMinWidth(0);chip.setMinimumWidth(0);
            chip.setPadding(dp(12),0,dp(12),0);
            boolean selected=(searchTypeFilter.isEmpty()&&label.equals("すべて"))||label.equals(searchTypeFilter);
            chip.setTextColor(selected?ACCENT:INK);
            chip.setBackground(bg(selected?SOFT:CARD,14,selected?ACCENT:LINE));
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-2,dp(38));
            cp.setMargins(0,0,dp(6),0);
            filters.addView(chip,cp);
        }
        hs.addView(filters,new HorizontalScrollView.LayoutParams(-2,-2));
        body.addView(hs,new LinearLayout.LayoutParams(-1,dp(44)));

        TextView count=tx("",11,MUTED,false);
        body.addView(count);
        LinearLayout out=col();
        body.addView(out);
        renderResults("",searchTypeFilter,out,count);

        for(int i=0;i<filters.getChildCount();i++){
            View child=filters.getChildAt(i);
            if(!(child instanceof Button))continue;
            Button chip=(Button)child;
            chip.setOnClickListener(v->{
                String label=String.valueOf(chip.getTag());
                searchTypeFilter=label.equals("すべて")?"":label;
                for(int j=0;j<filters.getChildCount();j++){
                    View c=filters.getChildAt(j);
                    if(!(c instanceof Button))continue;
                    Button b=(Button)c;
                    String l=String.valueOf(b.getTag());
                    boolean sel=(searchTypeFilter.isEmpty()&&l.equals("すべて"))||l.equals(searchTypeFilter);
                    b.setTextColor(sel?ACCENT:INK);
                    b.setBackground(bg(sel?SOFT:CARD,14,sel?ACCENT:LINE));
                }
                renderResults(q.getText().toString(),searchTypeFilter,out,count);
            });
        }
        q.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int a,int c,int d){}
            public void onTextChanged(CharSequence s,int a,int c,int d){renderResults(s.toString(),searchTypeFilter,out,count);}
            public void afterTextChanged(Editable e){}
        });
        screen(scroll(body));
    }

    boolean mediaMatches(ArchiveEvent e,String filter){
        if(filter==null||filter.isEmpty())return true;
        if(filter.equals("イベント"))return e.type.equals("イベント")||e.type.equals("イベント告知")||e.type.equals("ライブ/イベント");
        if(filter.equals("ラジオ"))return e.type.equals("ラジオ")||e.type.equals("ラジオ告知");
        return e.type.equalsIgnoreCase(filter);
    }

    void renderResults(String query,String filter,LinearLayout out,TextView count){
        out.removeAllViews();
        List<ArchiveEvent> hits=new ArrayList<>();
        for(ArchiveEvent e:events){
            if(!mediaMatches(e,filter)||!e.matches(query))continue;
            hits.add(e);
        }
        Collections.sort(hits);
        int limit=query.trim().isEmpty()?100:300;
        int shown=Math.min(limit,hits.size());
        for(int i=0;i<shown;i++)out.addView(compactCard(hits.get(i),true));
        String label=(filter==null||filter.isEmpty())?"すべて":filter;
        count.setText(label+"  "+hits.size()+"件"+(hits.size()>shown?"  ·  新しい順に"+shown+"件表示":""));
        if(hits.isEmpty()){
            TextView none=tx("該当する記録はありません",12,MUTED,false);
            none.setPadding(dp(8),dp(13),0,0);
            out.addView(none);
        }
    }

'''
s=s[:start]+block+s[end:]
MAIN.write_text(s,encoding='utf-8')

t=EVENT.read_text(encoding='utf-8')
old='''        if ("Instagram".equalsIgnoreCase(type)) {
            if (!lower.matches("https?://(www\\\\.)?instagram\\\\.com/(p|reel|tv)/[^/?#]+.*")) return "";
            return url;
        }'''
new='''        if ("Instagram".equalsIgnoreCase(type)) {
            if (lower.matches("https?://(www\\\\.)?instagram\\\\.com/(p|reel|tv)/[^/?#]+.*")) return url;
            if (lower.matches("https?://(www\\\\.)?instagrammernews\\\\.com/detail/\\\\d+.*")) return url;
            return "";
        }'''
if old not in t:
    raise SystemExit('Instagram sanitize block not found')
EVENT.write_text(t.replace(old,new),encoding='utf-8')
print('patched media filters and Instagram fallback sources')

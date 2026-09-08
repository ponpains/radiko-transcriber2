#!/usr/bin/env python3
import html,json,re,time,urllib.request
from datetime import datetime,timedelta,timezone
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
ASSET=ROOT/'app/src/main/assets/archive_seed.json'
REPORT=ROOT/'app/src/main/assets/archive_v091_report.json'
JST=timezone(timedelta(hours=9)); X_EPOCH_MS=1288834974657

def fetch():
    urls=['https://syndication.twitter.com/srv/timeline-profile/screen-name/nagata_shiori_?dnt=true','https://syndication.twitter.com/srv/timeline-profile/screen-name/nagata_shiori_']
    last=None
    for n in range(4):
        for u in urls:
            try:
                req=urllib.request.Request(u,headers={'User-Agent':'Mozilla/5.0 ShioriArchive/0.9.1'})
                return urllib.request.urlopen(req,timeout=25).read().decode('utf-8','replace')
            except Exception as e:last=e
        time.sleep(4*(n+1))
    raise last

def walk(x,out):
    if isinstance(x,dict):
        user=x.get('user') if isinstance(x.get('user'),dict) else {}
        screen=(user.get('screen_name') or user.get('screenName') or x.get('screen_name') or '')
        sid=str(x.get('id_str') or x.get('id') or '')
        text=x.get('full_text') or x.get('text') or ''
        if screen.lower()=='nagata_shiori_' and sid.isdigit() and 15<=len(sid)<=22 and isinstance(text,str):
            out[sid]=text
        for v in x.values():walk(v,out)
    elif isinstance(x,list):
        for v in x:walk(v,out)

def snowflake(sid):
    ms=(int(sid)>>22)+X_EPOCH_MS
    return datetime.fromtimestamp(ms/1000,timezone.utc).astimezone(JST)

def compact(s,n=150):
    s=html.unescape(re.sub(r'\s+',' ',s or '')).strip()
    return s[:n]

root=json.loads(ASSET.read_text(encoding='utf-8')); events=root['events']
existing={m.group(1) for e in events for m in [re.search(r'(?:x|twitter)\.com/nagata_shiori_/(?:status|statuses)/(\d+)',e.get('sourceUrl','') or '',re.I)] if m}
rep={'base_events':len(events),'syndication_fetched':False,'syndication_nagata_ids':0,'added_x':0,'updated_x':0,'removed_extra_duplicates':0,'errors':[]}
try:
    data=fetch(); rep['syndication_fetched']=True
    m=re.search(r'<script id="__NEXT_DATA__" type="application/json">(.*?)</script>',data,re.S)
    obj=json.loads(m.group(1)) if m else {}
    found={};walk(obj,found);rep['syndication_nagata_ids']=len(found)
    bysid={}
    for e in events:
        mm=re.search(r'(?:x|twitter)\.com/nagata_shiori_/(?:status|statuses)/(\d+)',e.get('sourceUrl','') or '',re.I)
        if mm:bysid[mm.group(1)]=e
    for sid,text in found.items():
        dt=snowflake(sid); url=f'https://x.com/nagata_shiori_/status/{sid}'
        if sid in bysid:
            e=bysid[sid]
            if not e.get('excerpt') and text:
                e['excerpt']=compact(text);rep['updated_x']+=1
            continue
        events.append({'id':'x-'+sid,'date':dt.strftime('%Y-%m-%d'),'time':dt.strftime('%H:%M:%S'),'type':'X','title':'X投稿','summary':'','excerpt':compact(text),'sourceName':'X @nagata_shiori_','sourceUrl':url,'confidence':'X公式公開埋め込みタイムラインで確認','tags':[],'people':[]})
        existing.add(sid);rep['added_x']+=1
except Exception as e:rep['errors'].append(repr(e))

# Remove obvious short manual duplicates: hashtag-only record within 2 min of a fuller X record whose title/text contains the same phrase.
def secs(t):
    try:
        p=[int(v) for v in t.split(':')];return p[0]*3600+p[1]*60+(p[2] if len(p)>2 else 0)
    except:return None
remove=set(); bydate={}
for e in events:
    if e.get('type')=='X':bydate.setdefault(e.get('date',''),[]).append(e)
for d,arr in bydate.items():
    for a in arr:
        ex=(a.get('excerpt') or '').strip()
        if not re.fullmatch(r'#?[\w一-龯ぁ-んァ-ヶー]{3,30}',ex):continue
        needle=ex.lstrip('#').lower();sa=secs(a.get('time',''))
        for b in arr:
            if a is b:continue
            sb=secs(b.get('time',''))
            if sa is not None and sb is not None and abs(sa-sb)>120:continue
            hay=((b.get('title') or '')+' '+(b.get('excerpt') or '')).lower()
            if needle in hay and len(b.get('excerpt') or '')>len(ex)+8:
                remove.add(id(a));break
if remove:
    rep['removed_extra_duplicates']=len(remove);events=[e for e in events if id(e) not in remove]

root['events']=sorted(events,key=lambda e:(e.get('date',''),e.get('time',''),e.get('id','')))
root['updatedAt']='2026-09-08';ASSET.write_text(json.dumps(root,ensure_ascii=False,separators=(',',':')),encoding='utf-8')
x=[e for e in events if e.get('type')=='X']
rep.update({'version':'0.9.1','total_events':len(events),'x_records':len(x),'x_direct_status_url':sum(bool(re.search(r'x\.com/nagata_shiori_/status/\d+',e.get('sourceUrl','') or '')) for e in x),'x_with_literal_excerpt':sum(bool(e.get('excerpt')) for e in x)})
REPORT.write_text(json.dumps(rep,ensure_ascii=False,indent=2),encoding='utf-8');print('V091_REPORT',json.dumps(rep,ensure_ascii=False))

#!/usr/bin/env python3
import hashlib,html,importlib.util,json,re,time,urllib.error,urllib.request
from datetime import datetime,timedelta,timezone
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
ASSET=ROOT/'app/src/main/assets/archive_seed.json'
REPORT=ROOT/'app/src/main/assets/archive_v095_report.json'
PROFILE_IDS=Path('/tmp/wayback_profile_ids.txt')
JST=timezone(timedelta(hours=9)); X_EPOCH_MS=1288834974657
XURL_RE=re.compile(r'https?://(?:www\.)?(?:x|twitter)\.com/nagata_shiori_/(?:status|statuses)/(\d{15,22})',re.I)
TALK_THREADS=[
 'https://talk.jp/boards/idol/1784226307', # 236
 'https://talk.jp/boards/idol/1784910750', # 237
 'https://talk.jp/boards/idol/1785895789', # 238
 'https://talk.jp/boards/idol/1786882112', # 239
 'https://talk.jp/boards/idol/1788430643', # 240 current 2026-09-09
]
UA='Mozilla/5.0 ShioriArchive/0.9.5'

def snowflake(sid):
    ms=(int(sid)>>22)+X_EPOCH_MS
    return datetime.fromtimestamp(ms/1000,timezone.utc).astimezone(JST)

def norm(s):
    s=html.unescape(s or '').lower();s=re.sub(r'https?://\S+','',s)
    return re.sub(r'[\s#＿_・、。,.!！?？~〜～「」『』()（）\[\]<>]+','',s)[:300]

def compact(s,n=180):
    s=html.unescape(re.sub(r'\s+',' ',s or '')).strip();return s[:n]

def secs(t):
    try:
        p=[int(x) for x in (t or '').split(':')];return p[0]*3600+p[1]*60+(p[2] if len(p)>2 else 0)
    except:return None

def sidmap(events):
    out={}
    for e in events:
        if e.get('type')!='X':continue
        m=XURL_RE.search(e.get('sourceUrl','') or '')
        if m:out[m.group(1)]=e
    return out

def merge_direct(events,bysid,sid,text,source_conf,rep):
    if not str(sid).isdigit():return
    sid=str(sid);dt=snowflake(sid);url=f'https://x.com/nagata_shiori_/status/{sid}';txt=compact(text)
    if sid in bysid:
        e=bysid[sid]
        if txt and not (e.get('excerpt') or '').strip():e['excerpt']=txt;rep['direct_excerpt_upgraded']+=1
        return
    # If exactly one content-only record exists in the same minute, upgrade rather than duplicate.
    same=[]
    for e in events:
        if e.get('type')!='X' or XURL_RE.search(e.get('sourceUrl','') or ''):continue
        if e.get('date')==dt.strftime('%Y-%m-%d') and (e.get('time') or '')[:5]==dt.strftime('%H:%M'):
            same.append(e)
    target=None
    if len(same)==1:
        target=same[0]
    elif txt:
        nt=norm(txt)
        scored=[]
        for e in same:
            ne=norm(e.get('excerpt') or '')
            if nt and ne and (nt==ne or (min(len(nt),len(ne))>=12 and (nt in ne or ne in nt))):scored.append(e)
        if len(scored)==1:target=scored[0]
    if target is not None:
        target['id']='x-'+sid;target['time']=dt.strftime('%H:%M:%S');target['sourceName']='X @nagata_shiori_';target['sourceUrl']=url;target['confidence']=source_conf
        if txt:target['excerpt']=txt
        bysid[sid]=target;rep['content_only_promoted_to_direct']+=1;return
    e={'id':'x-'+sid,'date':dt.strftime('%Y-%m-%d'),'time':dt.strftime('%H:%M:%S'),'type':'X','title':'X投稿','summary':'','excerpt':txt,'sourceName':'X @nagata_shiori_','sourceUrl':url,'confidence':source_conf,'tags':[],'people':[]}
    events.append(e);bysid[sid]=e;rep['direct_added']+=1

def fetch_syndication(rep):
    urls=['https://syndication.twitter.com/srv/timeline-profile/screen-name/nagata_shiori_?dnt=true','https://syndication.twitter.com/srv/timeline-profile/screen-name/nagata_shiori_']
    last=None
    for attempt in range(5):
        for u in urls:
            rep['syndication_http_attempts']+=1
            try:
                req=urllib.request.Request(u,headers={'User-Agent':UA})
                return urllib.request.urlopen(req,timeout=35).read().decode('utf-8','replace')
            except urllib.error.HTTPError as e:
                last=e
                if e.code!=429:continue
            except Exception as e:last=e
        time.sleep(min(45*(attempt+1),180))
    rep['errors'].append('syndication:'+repr(last));return ''

def extract_syndication(body):
    m=re.search(r'<script id="__NEXT_DATA__" type="application/json">(.*?)</script>',body,re.S)
    if not m:return {}
    obj=json.loads(m.group(1));out={}
    def walk(x):
        if isinstance(x,dict):
            user=x.get('user') if isinstance(x.get('user'),dict) else {}
            screen=(user.get('screen_name') or user.get('screenName') or x.get('screen_name') or '')
            sid=str(x.get('id_str') or x.get('id') or '')
            text=x.get('full_text') or x.get('text') or ''
            if screen.lower()=='nagata_shiori_' and sid.isdigit() and isinstance(text,str):out[sid]=text
            for v in x.values():walk(v)
        elif isinstance(x,list):
            for v in x:walk(v)
    walk(obj);return out

def cleanup(events,rep):
    # 1) only one direct record per status ID
    seen={};out=[]
    for e in events:
        if e.get('type')!='X':out.append(e);continue
        m=XURL_RE.search(e.get('sourceUrl','') or '')
        if not m:out.append(e);continue
        sid=m.group(1)
        if sid not in seen:seen[sid]=e;out.append(e);continue
        keep=seen[sid]
        if not (keep.get('excerpt') or '').strip() and (e.get('excerpt') or '').strip():keep['excerpt']=e['excerpt']
        rep['duplicate_direct_removed']+=1
    events=out
    # 2) duplicate content-only same normalized body/date
    direct_by_date={}
    for e in events:
        if e.get('type')=='X' and XURL_RE.search(e.get('sourceUrl','') or ''):
            n=norm(e.get('excerpt') or '')
            if n:direct_by_date.setdefault(e.get('date',''),[]).append(n)
    seenq=set();out=[]
    for e in events:
        if e.get('type')!='X' or XURL_RE.search(e.get('sourceUrl','') or ''):
            out.append(e);continue
        n=norm(e.get('excerpt') or '')
        key=(e.get('date',''),(e.get('time') or '')[:5],n)
        if n and key in seenq:rep['duplicate_content_removed']+=1;continue
        if n:
            duplicate=False
            for dn in direct_by_date.get(e.get('date',''),[]):
                if n==dn or (min(len(n),len(dn))>=14 and (n in dn or dn in n)):
                    duplicate=True;break
            if duplicate:rep['content_duplicate_of_direct_removed']+=1;continue
            seenq.add(key)
        out.append(e)
    return out

def main():
    root=json.loads(ASSET.read_text(encoding='utf-8'));events=root['events'];bysid=sidmap(events)
    rep={'version':'0.9.5','base_events':len(events),'base_x':sum(e.get('type')=='X' for e in events),'wayback_profile_ids':0,'talk_threads_fetched':0,'talk_status_added':0,'talk_status_upgraded':0,'talk_idless_added':0,'syndication_ids':0,'syndication_http_attempts':0,'direct_added':0,'direct_excerpt_upgraded':0,'content_only_promoted_to_direct':0,'duplicate_direct_removed':0,'duplicate_content_removed':0,'content_duplicate_of_direct_removed':0,'errors':[]}
    # Wayback profile IDs
    if PROFILE_IDS.exists():
        ids=[x.strip() for x in PROFILE_IDS.read_text().splitlines() if x.strip().isdigit()]
        rep['wayback_profile_ids']=len(ids)
        for sid in ids:merge_direct(events,bysid,sid,'','Wayback本人Xプロフィール画面でstatus IDを確認',rep)
    # Forward Talk threads 236-240, using the same parser as v0.9.3.
    spec=importlib.util.spec_from_file_location('talkmod',ROOT/'scripts/backfill_x_talk_v093.py');talk=importlib.util.module_from_spec(spec);spec.loader.exec_module(talk)
    trep={'status_added':0,'status_upgraded':0,'idless_added':0,'status_refs_seen':0}
    for url in TALK_THREADS:
        try:body=talk.request(url,timeout=25,retries=3)
        except Exception as e:rep['errors'].append(url+':'+repr(e));continue
        rep['talk_threads_fetched']+=1;talk.extract_posts(body,url,events,bysid,trep);time.sleep(.25)
    rep['talk_status_added']=trep['status_added'];rep['talk_status_upgraded']=trep['status_upgraded'];rep['talk_idless_added']=trep['idless_added']
    # Current public X syndication sample
    body=fetch_syndication(rep)
    if body:
        found=extract_syndication(body);rep['syndication_ids']=len(found)
        for sid,text in found.items():merge_direct(events,bysid,sid,text,'X公式公開埋め込みタイムラインで確認',rep)
    events=cleanup(events,rep)
    root['events']=sorted(events,key=lambda e:(e.get('date',''),e.get('time',''),e.get('id','')));root['updatedAt']='2026-09-09'
    ASSET.write_text(json.dumps(root,ensure_ascii=False,separators=(',',':')),encoding='utf-8')
    x=[e for e in events if e.get('type')=='X'];direct=sum(bool(XURL_RE.search(e.get('sourceUrl','') or '')) for e in x)
    rep.update({'total_events':len(events),'x_records':len(x),'x_direct_status':direct,'x_content_only':len(x)-direct,'x_with_excerpt':sum(bool((e.get('excerpt') or '').strip()) for e in x),'coverage_vs_5100':round(len(x)/5100,4)})
    REPORT.write_text(json.dumps(rep,ensure_ascii=False,indent=2),encoding='utf-8');print('V095_REPORT',json.dumps(rep,ensure_ascii=False))
if __name__=='__main__':main()

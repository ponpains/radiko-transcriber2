#!/usr/bin/env python3
import concurrent.futures
import difflib
import html as htmllib
import json
import re
import time
import unicodedata
import urllib.parse
import urllib.request
from collections import Counter, defaultdict
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
ASSET=ROOT/'app/src/main/assets/archive_seed.json'
REPORT=ROOT/'app/src/main/assets/archive_v090_report.json'
JST=timezone(timedelta(hours=9))
X_EPOCH_MS=1288834974657
UA={'User-Agent':'Mozilla/5.0 ShioriArchive/0.9 (+public metadata cleanup)'}


def get(url,timeout=18,retries=0):
    last=None
    for i in range(retries+1):
        try:
            req=urllib.request.Request(url,headers=UA)
            with urllib.request.urlopen(req,timeout=timeout) as r:
                return r.read().decode('utf-8','replace')
        except Exception as e:
            last=e
            if i<retries: time.sleep(.5*(i+1))
    raise last


def clean_html(s):
    s=re.sub(r'<br\s*/?>','\n',s or '',flags=re.I)
    s=re.sub(r'<[^>]+>','',s)
    return htmllib.unescape(s).strip()


def status_id_from_url(u):
    m=re.search(r'(?:x|twitter)\.com/(?:#!/)?nagata_shiori_/(?:status|statuses)/(\d{15,22})',u or '',re.I)
    return m.group(1) if m else None


def snowflake_dt(sid):
    ms=(int(sid)>>22)+X_EPOCH_MS
    return datetime.fromtimestamp(ms/1000,timezone.utc).astimezone(JST)


def parse_seconds(t):
    if not t:return None
    try:
        p=[int(x) for x in t.split(':')]
        return p[0]*3600+p[1]*60+(p[2] if len(p)>2 else 0)
    except:return None


def norm_excerpt(s):
    s=re.sub(r'https?://t\.co/\S+','',s or '')
    s=unicodedata.normalize('NFKC',s)
    s=re.sub(r'#\S+','',s)
    s=re.sub(r'[\s\u200b\u200d]+','',s)
    s=re.sub(r'[^\w一-龯ぁ-んァ-ヶー]+','',s)
    return s.lower()


def is_direct_status(u):
    return bool(status_id_from_url(u))


def score_x(e):
    score=0
    if is_direct_status(e.get('sourceUrl','')): score+=10000
    if e.get('excerpt'): score+=100+min(len(e.get('excerpt','')),300)
    if e.get('time','').count(':')==2: score+=50
    if e.get('title') and e.get('title')!='X投稿': score+=10
    return score


def merge_x(a,b):
    keep,drop=(a,b) if score_x(a)>=score_x(b) else (b,a)
    if not keep.get('sourceUrl') and drop.get('sourceUrl'):keep['sourceUrl']=drop['sourceUrl']
    if len(drop.get('excerpt',''))>len(keep.get('excerpt','')):keep['excerpt']=drop['excerpt']
    if keep.get('title')=='X投稿' and drop.get('title') not in ('','X投稿'):keep['title']=drop['title']
    if keep.get('time','').count(':')<2 and drop.get('time','').count(':')==2:keep['time']=drop['time']
    return keep


root=json.loads(ASSET.read_text(encoding='utf-8'))
events=list(root.get('events',[]))
initial=len(events)
report={'base_events':initial,'removed':Counter(),'added':Counter(),'updated':Counter(),'errors':[]}

# A) Remove low-value official sales / merch news. Keep actual SCHEDULE events.
goods_re=re.compile(r'(?:グッズ|物販|生写真|アクリル|缶バッジ|トレーディング|ペンライト|マフラータオル|タオル|Tシャツ|OFFICIAL\s*SHOP|オンラインショップ|通販|受注販売|スクラッチ|くじ|福袋|ランダム商品|特典会商品|ノイミー盤.{0,30}(?:販売|受付|申込)|(?:第\d+|最終)販売|予約受付|販売開始|販売決定)',re.I)
appearance_types={'テレビ','ラジオ','イベント','ライブ/イベント','SHOWROOM','配信','YouTube','ラジオ告知','イベント告知'}
kept=[]
for e in events:
    src=e.get('sourceName','') or ''
    title=e.get('title','') or ''
    text=' '.join([title,e.get('summary','') or '',e.get('excerpt','') or ''])
    if '≠ME公式 NEWS' in src and goods_re.search(text):
        report['removed']['official_sales_merch']+=1
        continue
    # Official NEWS is announcement-date metadata. Appearance/event facts are represented by actual-day SCHEDULE/radiko/TV records instead.
    if '≠ME公式 NEWS' in src and e.get('type') in appearance_types:
        report['removed']['appearance_announcement_news']+=1
        continue
    kept.append(e)
events=kept

# B) Correct actual-day schedule classification for handshake/photo/signing events.
for e in events:
    if 'SCHEDULE' in (e.get('sourceName','') or ''):
        t=e.get('title','') or ''
        if re.search(r'(?:お話し会|ツーショット|撮影会|サイン会|握手会|イベント)',t):
            if e.get('type')!='イベント':
                e['type']='イベント';report['updated']['schedule_reclassified_event']+=1

# C) Expand X status IDs from lawful public Wayback indexes, split by year to avoid monolithic CDX timeouts.
existing_sid={status_id_from_url(e.get('sourceUrl','')) for e in events if e.get('type')=='X'}
existing_sid.discard(None)
cdx_jobs=[]
for year in range(2019,2027):
    for host in ['twitter.com','www.twitter.com','mobile.twitter.com','x.com','www.x.com']:
        for word in ['status','statuses']:
            cdx_jobs.append((year,host,word))

def fetch_cdx(job):
    year,host,word=job
    u=(f'https://web.archive.org/cdx/search/cdx?url={host}/nagata_shiori_/{word}/*'
       f'&output=json&filter=statuscode:200&collapse=urlkey&fl=original&from={year}0101&to={year}1231&limit=10000')
    try:
        rows=json.loads(get(u,24,0))
        out=[]
        for r in rows[1:] if isinstance(rows,list) else []:
            if not r:continue
            sid=status_id_from_url(str(r[0]))
            if sid:out.append(sid)
        return out,None
    except Exception as ex:return [],f'{year} {host}/{word}: {ex!r}'

new_sids=set()
with concurrent.futures.ThreadPoolExecutor(max_workers=8) as ex:
    for ids,err in ex.map(fetch_cdx,cdx_jobs):
        new_sids.update(ids)
        if err: report['errors'].append('CDX '+err)

by_id={str(e.get('id','')):e for e in events}
for sid in sorted(new_sids-existing_sid,key=int):
    dt=snowflake_dt(sid)
    ev={'id':'x-'+sid,'date':dt.strftime('%Y-%m-%d'),'time':dt.strftime('%H:%M:%S'),'type':'X','title':'X投稿','summary':'','excerpt':'','sourceName':'X @nagata_shiori_','sourceUrl':f'https://x.com/nagata_shiori_/status/{sid}','confidence':'投稿IDを公開Webアーカイブで確認','tags':[],'people':[]}
    if ev['id'] not in by_id:
        events.append(ev);by_id[ev['id']]=ev;existing_sid.add(sid);report['added']['x_wayback_status']+=1

# D) Recover direct URLs for recent X records using Yahoo! realtime public result pages.
# Query each record by its own literal excerpt and only accept status IDs whose snowflake time matches the record.
recent_missing=[e for e in events if e.get('type')=='X' and e.get('date','')>='2026-08-01' and not is_direct_status(e.get('sourceUrl','')) and e.get('excerpt')]

def yahoo_candidates(e):
    q=re.sub(r'https?://t\.co/\S+','',e.get('excerpt','')).strip()
    q=re.sub(r'\s+',' ',q)[:48]
    if len(q)<5:return e,[]
    u='https://search.yahoo.co.jp/realtime/search?ei=UTF-8&ifr=tl_unit&rkf=1&p='+urllib.parse.quote(q)
    try:
        p=get(u,16,0)
        p=urllib.parse.unquote(htmllib.unescape(p))
        ids=set(re.findall(r'https?://(?:www\.)?(?:x|twitter)\.com/nagata_shiori_/(?:status|statuses)/(\d{15,22})',p,re.I))
        return e,list(ids)
    except Exception:return e,[]

with concurrent.futures.ThreadPoolExecutor(max_workers=4) as ex:
    for e,ids in ex.map(yahoo_candidates,recent_missing):
        target=parse_seconds(e.get('time'))
        best=None;bestdiff=999999
        for sid in ids:
            dt=snowflake_dt(sid)
            if dt.strftime('%Y-%m-%d')!=e.get('date'):continue
            sec=dt.hour*3600+dt.minute*60+dt.second
            diff=abs(sec-target) if target is not None else 0
            if diff<bestdiff:
                best,bestdiff=sid,diff
        if best and (target is None or bestdiff<=180):
            e['sourceUrl']=f'https://x.com/nagata_shiori_/status/{best}'
            e['time']=snowflake_dt(best).strftime('%H:%M:%S')
            existing_sid.add(best);report['updated']['recent_x_direct_url']+=1

# E) Fill a limited number of missing excerpts through X/Twitter's public oEmbed endpoint.
# Only a short literal excerpt is stored; no AI paraphrase.
missing_excerpt=[e for e in events if e.get('type')=='X' and is_direct_status(e.get('sourceUrl','')) and not e.get('excerpt')]
missing_excerpt.sort(key=lambda e:(e.get('date',''),e.get('time','')),reverse=True)

def fetch_oembed(e):
    try:
        u='https://publish.twitter.com/oembed?omit_script=1&dnt=1&url='+urllib.parse.quote(e['sourceUrl'],safe='')
        obj=json.loads(get(u,10,0))
        h=obj.get('html','')
        m=re.search(r'<p[^>]*>(.*?)</p>',h,re.I|re.S)
        txt=clean_html(m.group(1)) if m else ''
        txt=re.sub(r'\s+',' ',txt).strip()
        return e,txt[:150]
    except Exception:return e,''
with concurrent.futures.ThreadPoolExecutor(max_workers=5) as ex:
    for e,txt in ex.map(fetch_oembed,missing_excerpt[:250]):
        if txt:
            e['excerpt']=txt;report['updated']['x_literal_excerpt_oembed']+=1

# F) X dedupe: exact status IDs first, then same-minute near-duplicates (manual rounded record vs exact record).
by_sid=defaultdict(list);x_other=[]
for e in events:
    if e.get('type')!='X':continue
    sid=status_id_from_url(e.get('sourceUrl',''))
    if sid:by_sid[sid].append(e)
    else:x_other.append(e)
remove_ids=set()
for sid,arr in by_sid.items():
    if len(arr)<2:continue
    best=arr[0]
    for e in arr[1:]:best=merge_x(best,e)
    for e in arr:
        if e is not best:remove_ids.add(id(e));report['removed']['x_same_status']+=1

# Compare only literal excerpts; never dedupe generic "X投稿" records with no text.
by_date=defaultdict(list)
for e in events:
    if e.get('type')=='X' and id(e) not in remove_ids:by_date[e.get('date','')].append(e)
for d,arr in by_date.items():
    arr=sorted(arr,key=lambda e:(parse_seconds(e.get('time')) if parse_seconds(e.get('time')) is not None else -1))
    for i,a in enumerate(arr):
        if id(a) in remove_ids or not a.get('excerpt'):continue
        ta=parse_seconds(a.get('time'))
        for b in arr[i+1:]:
            if id(b) in remove_ids or not b.get('excerpt'):continue
            tb=parse_seconds(b.get('time'))
            if ta is not None and tb is not None and tb-ta>90:break
            na,nb=norm_excerpt(a.get('excerpt')),norm_excerpt(b.get('excerpt'))
            if min(len(na),len(nb))<6:continue
            ratio=difflib.SequenceMatcher(None,na,nb).ratio()
            contained=(na in nb or nb in na)
            if ratio>=0.72 or contained:
                best=merge_x(a,b)
                loser=b if best is a else a
                remove_ids.add(id(loser));report['removed']['x_near_duplicate']+=1
                if loser is a:break

events=[e for e in events if id(e) not in remove_ids]

# G) Collapse obvious non-X duplicates for the same actual broadcast day.
def key_title(s):
    s=unicodedata.normalize('NFKC',s or '')
    s=re.sub(r'[\s「」『』()（）#・:：\-]','',s)
    return s.lower()

def quality_nonx(e):
    u=e.get('sourceUrl','') or ''
    q=0
    if '/podcast/episodes/' in u:q+=1000
    if '/schedule/detail/' in u:q+=700
    if u.startswith('http'):q+=400
    if e.get('time'):q+=30
    return q
for marker,typ in [('けれけれ','ラジオ'),('めざましテレビ','テレビ')]:
    groups=defaultdict(list)
    for e in events:
        if e.get('type')==typ and marker in key_title(e.get('title','')):groups[e.get('date','')].append(e)
    for d,arr in groups.items():
        if len(arr)<=1:continue
        best=max(arr,key=quality_nonx)
        for e in arr:
            if e is not best:
                remove_ids.add(id(e));report['removed']['same_day_media_duplicate']+=1
events=[e for e in events if id(e) not in remove_ids]

# H) Final counts / save.
events.sort(key=lambda e:(e.get('date',''),e.get('time',''),e.get('id','')))
root['events']=events
root['updatedAt']='2026-09-08'
ASSET.write_text(json.dumps(root,ensure_ascii=False,separators=(',',':')),encoding='utf-8')

x=[e for e in events if e.get('type')=='X']
recent=[e for e in x if e.get('date','')>='2026-08-01']
report.update({
 'version':'0.9.0',
 'total_events':len(events),
 'net_change':len(events)-initial,
 'x_records':len(x),
 'x_direct_status_url':sum(is_direct_status(e.get('sourceUrl','')) for e in x),
 'x_with_literal_excerpt':sum(bool(e.get('excerpt')) for e in x),
 'recent_x_records_since_2026_08_01':len(recent),
 'recent_x_direct_status_url':sum(is_direct_status(e.get('sourceUrl','')) for e in recent),
 'instagram_records':sum(e.get('type')=='Instagram' for e in events),
 'showroom_records':sum(e.get('type')=='SHOWROOM' for e in events),
 'radio_records':sum(e.get('type')=='ラジオ' for e in events),
 'tv_records':sum(e.get('type')=='テレビ' for e in events),
 'interview_records':sum(e.get('type')=='インタビュー' for e in events),
 'records_with_url':sum(str(e.get('sourceUrl','')).startswith('http') for e in events),
 'removed':dict(report['removed']), 'added':dict(report['added']), 'updated':dict(report['updated'])
})
REPORT.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print('V090_REPORT',json.dumps(report,ensure_ascii=False))

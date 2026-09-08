#!/usr/bin/env python3
import concurrent.futures,json,re,time,urllib.parse,urllib.request
from datetime import datetime,timedelta,timezone
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
ASSET=ROOT/'app/src/main/assets/archive_seed.json'
REPORT=ROOT/'app/src/main/assets/archive_v010_report.json'
JST=timezone(timedelta(hours=9)); X_EPOCH_MS=1288834974657
UA='Mozilla/5.0 ShioriArchive/0.10 (+public-index-metadata-only)'
X_RE=re.compile(r'https?://(?:www\.)?(?:x|twitter)\.com/nagata_shiori_/(?:status|statuses)/(\d{15,22})',re.I)
SID_RE=re.compile(r'/(?:status|statuses)/(\d{15,22})',re.I)
WB_PREFIXES=[
 'twitter.com/nagata_shiori_/status/','twitter.com/nagata_shiori_/statuses/',
 'www.twitter.com/nagata_shiori_/status/','mobile.twitter.com/nagata_shiori_/status/',
 'x.com/nagata_shiori_/status/','www.x.com/nagata_shiori_/status/'
]
CC_PATTERNS=['twitter.com/nagata_shiori_/status/*','x.com/nagata_shiori_/status/*']

def request(url,timeout=25,tries=2):
    last=None
    for i in range(tries):
        try:
            req=urllib.request.Request(url,headers={'User-Agent':UA,'Accept':'text/plain,application/json;q=0.9,*/*;q=0.5'})
            with urllib.request.urlopen(req,timeout=timeout) as r:return r.read().decode('utf-8','replace')
        except Exception as e:
            last=e; time.sleep(2*(i+1))
    raise last

def x_time(sid):
    ms=(int(sid)>>22)+X_EPOCH_MS
    return datetime.fromtimestamp(ms/1000,timezone.utc).astimezone(JST)

def valid_sid(sid):
    if not sid.isdigit():return False
    try:dt=x_time(sid)
    except:return False
    return datetime(2019,2,1,tzinfo=JST)<=dt<=datetime.now(JST)+timedelta(days=2)

def parse_ids(text):
    out=set()
    for line in text.splitlines():
        m=SID_RE.search(line)
        if m and valid_sid(m.group(1)):out.add(m.group(1))
    return out

def wayback_one(prefix):
    q={'url':prefix,'matchType':'prefix','output':'txt','fl':'original','collapse':'urlkey','from':'2019','to':'2026','limit':'20000'}
    u='https://web.archive.org/cdx/search/cdx?'+urllib.parse.urlencode(q)
    try:return prefix,parse_ids(request(u,35,2)),None
    except Exception as e:return prefix,set(),repr(e)

def wayback_all(report):
    ids=set(); failed=[]
    # Low concurrency: enough to avoid serial timeouts without hammering the public index.
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as ex:
        for p,got,err in ex.map(wayback_one,WB_PREFIXES):
            ids|=got; report.setdefault('wayback_patterns',[]).append({'prefix':p,'ids':len(got)})
            if err:failed.append((p,err))
    # Retry failed prefixes year-by-year with one request at a time.
    for p,err in failed:
        report.setdefault('wayback_errors',[]).append({'prefix':p,'error':err})
        for y in range(2019,2027):
            q={'url':p,'matchType':'prefix','output':'txt','fl':'original','collapse':'urlkey','from':str(y),'to':str(y),'limit':'5000'}
            u='https://web.archive.org/cdx/search/cdx?'+urllib.parse.urlencode(q)
            try:ids|=parse_ids(request(u,18,1))
            except Exception:pass
            time.sleep(.25)
    return ids

def cc_collections(report):
    try:items=json.loads(request('https://index.commoncrawl.org/collinfo.json',20,2))
    except Exception as e:
        report['commoncrawl_collection_error']=repr(e);return []
    out=[x.get('id','') for x in items if re.match(r'CC-MAIN-(2019|2020|2021|2022|2023|2024|2025|2026)-',x.get('id',''))]
    report['commoncrawl_collections']=len(out);return out

def cc_one(args):
    coll,pat=args
    q=urllib.parse.urlencode({'url':pat,'output':'json','collapse':'urlkey'})
    u=f'https://index.commoncrawl.org/{coll}-index?{q}'
    try:return coll,pat,parse_ids(request(u,18,1)),None
    except Exception as e:return coll,pat,set(),repr(e)

def commoncrawl_all(report):
    cs=cc_collections(report);jobs=[(c,p) for c in cs for p in CC_PATTERNS]
    ids=set();errors=[];nonempty=0
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as ex:
        for c,p,got,err in ex.map(cc_one,jobs):
            ids|=got
            if got:nonempty+=1
            if err:errors.append({'collection':c,'pattern':p,'error':err})
    report['commoncrawl_queries']=len(jobs);report['commoncrawl_nonempty_queries']=nonempty
    report['commoncrawl_errors']=errors[:30]
    return ids

def add_ids(events,origins,report):
    existing={}
    for e in events:
        if e.get('type')!='X':continue
        m=X_RE.search(e.get('sourceUrl','') or '')
        if m:existing[m.group(1)]=e
    added={'Wayback':0,'Common Crawl':0,'both':0}
    for sid in sorted(origins,key=int):
        if sid in existing:continue
        dt=x_time(sid);src=origins[sid]
        label='Internet Archive / Common Crawl' if len(src)>1 else next(iter(src))
        key='both' if len(src)>1 else next(iter(src))
        added[key]=added.get(key,0)+1
        events.append({'id':'x-'+sid,'date':dt.strftime('%Y-%m-%d'),'time':dt.strftime('%H:%M:%S'),'type':'X','title':'X投稿','summary':'','excerpt':'','sourceName':'X @nagata_shiori_','sourceUrl':f'https://x.com/nagata_shiori_/status/{sid}','confidence':f'{label}の公開URL索引で本人アカウントのstatus IDを確認','tags':[],'people':[]})
        existing[sid]=events[-1]
    report['x_archive_added_by_origin']=added;report['x_archive_added']=sum(added.values())

def strict_dedupe(events,report):
    out=[];x={};removed=0
    def score(v):return (1000 if v.get('excerpt') else 0)+len(v.get('excerpt') or '')+(20 if v.get('title') not in ('','X投稿') else 0)
    for e in events:
        if e.get('type')!='X':out.append(e);continue
        m=X_RE.search(e.get('sourceUrl','') or '')
        if not m:removed+=1;continue
        sid=m.group(1);e=dict(e);e['sourceUrl']=f'https://x.com/nagata_shiori_/status/{sid}'
        old=x.get(sid)
        if old is None or score(e)>score(old):x[sid]=e
        if old is not None:removed+=1
    out.extend(x.values());report['x_removed_or_deduped']=removed;return out

def main():
    root=json.loads(ASSET.read_text(encoding='utf-8'));events=root.get('events',[])
    report={'version':'0.10.0','base_events':len(events),'base_x':sum(e.get('type')=='X' for e in events)}
    wb=wayback_all(report);report['wayback_unique_ids']=len(wb)
    cc=commoncrawl_all(report);report['commoncrawl_unique_ids']=len(cc)
    origins={}
    for sid in wb:origins.setdefault(sid,set()).add('Wayback')
    for sid in cc:origins.setdefault(sid,set()).add('Common Crawl')
    report['archive_union_unique_ids']=len(origins)
    add_ids(events,origins,report);events=strict_dedupe(events,report)
    events=sorted(events,key=lambda e:(e.get('date',''),e.get('time',''),e.get('id','')))
    root['events']=events;root['updatedAt']='2026-09-08';ASSET.write_text(json.dumps(root,ensure_ascii=False,separators=(',',':')),encoding='utf-8')
    x=[e for e in events if e.get('type')=='X']
    report.update({'total_events':len(events),'x_records':len(x),'x_direct_status_url':sum(bool(X_RE.search(e.get('sourceUrl','') or '')) for e in x),'x_with_excerpt':sum(bool(e.get('excerpt')) for e in x),'capture_vs_5100_pct':round(len(x)/5100*100,1)})
    REPORT.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8');print('V010_REPORT',json.dumps(report,ensure_ascii=False),flush=True)
if __name__=='__main__':main()

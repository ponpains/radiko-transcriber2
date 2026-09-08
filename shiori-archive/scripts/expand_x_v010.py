#!/usr/bin/env python3
import json,re,time,urllib.parse,urllib.request,urllib.error
from datetime import datetime,timedelta,timezone
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
ASSET=ROOT/'app/src/main/assets/archive_seed.json'
REPORT=ROOT/'app/src/main/assets/archive_v010_report.json'
JST=timezone(timedelta(hours=9)); X_EPOCH_MS=1288834974657
UA='Mozilla/5.0 ShioriArchive/0.10 (+public-index-metadata-only)'
X_RE=re.compile(r'https?://(?:www\.)?(?:x|twitter)\.com/nagata_shiori_/(?:status|statuses)/(\d{15,22})',re.I)
SID_RE=re.compile(r'/(?:status|statuses)/(\d{15,22})',re.I)
PREFIXES=[
 'twitter.com/nagata_shiori_/status/*',
 'twitter.com/nagata_shiori_/statuses/*',
 'www.twitter.com/nagata_shiori_/status/*',
 'mobile.twitter.com/nagata_shiori_/status/*',
 'x.com/nagata_shiori_/status/*',
 'www.x.com/nagata_shiori_/status/*',
]

def request(url,timeout=35,tries=3):
    last=None
    for i in range(tries):
        try:
            req=urllib.request.Request(url,headers={'User-Agent':UA,'Accept':'text/plain,application/json;q=0.9,*/*;q=0.5'})
            with urllib.request.urlopen(req,timeout=timeout) as r:
                return r.read().decode('utf-8','replace')
        except Exception as e:
            last=e
            time.sleep(min(4*(i+1),12))
    raise last

def x_time(sid):
    ms=(int(sid)>>22)+X_EPOCH_MS
    return datetime.fromtimestamp(ms/1000,timezone.utc).astimezone(JST)

def valid_sid(sid):
    if not sid.isdigit(): return False
    try: dt=x_time(sid)
    except Exception: return False
    return datetime(2019,2,1,tzinfo=JST) <= dt <= datetime.now(JST)+timedelta(days=2)

def cdx_ids(pattern,report):
    params={'url':pattern,'output':'txt','fl':'original','collapse':'urlkey','from':'2019','to':'2026','limit':'20000'}
    url='https://web.archive.org/cdx/search/cdx?'+urllib.parse.urlencode(params)
    try: body=request(url,40,2)
    except Exception as e:
        report.setdefault('wayback_errors',[]).append({'pattern':pattern,'error':repr(e)})
        return set()
    ids=set()
    for line in body.splitlines():
        m=SID_RE.search(line)
        if m and valid_sid(m.group(1)): ids.add(m.group(1))
    report.setdefault('wayback_patterns',[]).append({'pattern':pattern,'ids':len(ids),'lines':len(body.splitlines())})
    return ids

def cdx_segmented(report):
    # Fallback: a broad query can time out, so split only failed/weak patterns by year.
    allids=set()
    for pattern in PREFIXES:
        got=cdx_ids(pattern,report); allids|=got
        if len(got)>=100: continue
        base=pattern
        for year in range(2019,2027):
            params={'url':base,'output':'txt','fl':'original','collapse':'urlkey','from':str(year),'to':str(year),'limit':'5000'}
            url='https://web.archive.org/cdx/search/cdx?'+urllib.parse.urlencode(params)
            try: body=request(url,22,1)
            except Exception: continue
            for line in body.splitlines():
                m=SID_RE.search(line)
                if m and valid_sid(m.group(1)): allids.add(m.group(1))
            time.sleep(.35)
    return allids

def add_ids(events,ids,report):
    existing={}
    for e in events:
        if e.get('type')!='X': continue
        m=X_RE.search(e.get('sourceUrl','') or '')
        if m: existing[m.group(1)]=e
    added=0
    for sid in sorted(ids,key=int):
        if sid in existing: continue
        dt=x_time(sid)
        events.append({
          'id':'x-'+sid,'date':dt.strftime('%Y-%m-%d'),'time':dt.strftime('%H:%M:%S'),
          'type':'X','title':'X投稿','summary':'','excerpt':'',
          'sourceName':'X @nagata_shiori_','sourceUrl':f'https://x.com/nagata_shiori_/status/{sid}',
          'confidence':'Internet Archive公開URL索引で本人アカウントのstatus IDを確認',
          'tags':[],'people':[]})
        existing[sid]=events[-1]; added+=1
    report['x_wayback_added']=added

def strict_dedupe(events,report):
    out=[]; x={}; removed=0
    for e in events:
        if e.get('type')!='X': out.append(e); continue
        m=X_RE.search(e.get('sourceUrl','') or '')
        if not m: removed+=1; continue
        sid=m.group(1); e=dict(e); e['sourceUrl']=f'https://x.com/nagata_shiori_/status/{sid}'
        old=x.get(sid)
        if old is None: x[sid]=e
        else:
            # Prefer a record with literal excerpt/title over metadata-only record.
            def score(v): return (1 if v.get('excerpt') else 0)*1000+len(v.get('excerpt') or '')+(20 if v.get('title') not in ('','X投稿') else 0)
            if score(e)>score(old): x[sid]=e
            removed+=1
    out.extend(x.values()); report['x_removed_or_deduped']=removed
    return out

def main():
    root=json.loads(ASSET.read_text(encoding='utf-8')); events=root.get('events',[])
    report={'version':'0.10.0','base_events':len(events),'base_x':sum(e.get('type')=='X' for e in events)}
    ids=cdx_segmented(report); report['wayback_unique_ids']=len(ids)
    add_ids(events,ids,report)
    events=strict_dedupe(events,report)
    events=sorted(events,key=lambda e:(e.get('date',''),e.get('time',''),e.get('id','')))
    root['events']=events; root['updatedAt']='2026-09-08'
    ASSET.write_text(json.dumps(root,ensure_ascii=False,separators=(',',':')),encoding='utf-8')
    x=[e for e in events if e.get('type')=='X']
    report.update({'total_events':len(events),'x_records':len(x),'x_direct_status_url':sum(bool(X_RE.search(e.get('sourceUrl','') or '')) for e in x),'x_with_excerpt':sum(bool(e.get('excerpt')) for e in x)})
    REPORT.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print('V010_REPORT',json.dumps(report,ensure_ascii=False),flush=True)
if __name__=='__main__': main()

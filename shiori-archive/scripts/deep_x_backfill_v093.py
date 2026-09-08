#!/usr/bin/env python3
import html,json,re,time,urllib.parse,urllib.request,urllib.error
from datetime import datetime,timedelta,timezone
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
ASSET=ROOT/'app/src/main/assets/archive_seed.json'
REPORT=ROOT/'app/src/main/assets/archive_v093_report.json'
JST=timezone(timedelta(hours=9)); X_EPOCH_MS=1288834974657
UA='Mozilla/5.0 ShioriArchive/0.9.3 (+public-archive-metadata)'
SID_RE=re.compile(r'(?:x|twitter)\.com/(?:#!/)?nagata_shiori_/(?:status|statuses)/(\d{15,22})',re.I)


def req(url,timeout=45,retries=4):
    last=None
    for n in range(retries):
        try:
            r=urllib.request.Request(url,headers={'User-Agent':UA,'Accept-Language':'ja,en;q=0.8'})
            with urllib.request.urlopen(r,timeout=timeout) as z:return z.read().decode('utf-8','replace')
        except urllib.error.HTTPError as e:
            last=e
            if e.code in (429,503): time.sleep(min(10*(n+1),40));continue
            raise
        except Exception as e:
            last=e;time.sleep(min(5*(n+1),20))
    raise last


def cdx(url,fl='timestamp,original,digest',collapse='digest',limit=None):
    q={'url':url,'output':'json','fl':fl,'filter':'statuscode:200','collapse':collapse}
    if limit:q['limit']=str(limit)
    body=req('https://web.archive.org/cdx/search/cdx?'+urllib.parse.urlencode(q),60)
    data=json.loads(body)
    if not data or len(data)<2:return []
    head=data[0];return [dict(zip(head,row)) for row in data[1:]]


def x_dt(sid):
    ms=(int(sid)>>22)+X_EPOCH_MS
    return datetime.fromtimestamp(ms/1000,timezone.utc).astimezone(JST)


def add_sid(found,sid,source):
    if not sid.isdigit():return
    try:dt=x_dt(sid)
    except:return
    if not (datetime(2019,1,1,tzinfo=JST)<=dt<=datetime.now(JST)+timedelta(days=2)):return
    found.setdefault(sid,set()).add(source)


def ids_from_text(text,found,source):
    # Decode escaped URLs commonly present in archived JS/JSON.
    text=html.unescape(text).replace('\\/','/').replace('\\u002F','/').replace('%2F','/')
    for m in SID_RE.finditer(text):add_sid(found,m.group(1),source)


def direct_cdx(found,rep):
    pats=[
      'twitter.com/nagata_shiori_/status/*','www.twitter.com/nagata_shiori_/status/*',
      'mobile.twitter.com/nagata_shiori_/status/*','twitter.com/nagata_shiori_/statuses/*',
      'x.com/nagata_shiori_/status/*','www.x.com/nagata_shiori_/status/*'
    ]
    for p in pats:
        try:
            rows=cdx(p,fl='original',collapse='urlkey')
            rep.setdefault('direct_cdx_rows',{})[p]=len(rows)
            for r in rows:ids_from_text(r.get('original',''),found,'wayback-direct:'+p)
        except Exception as e:rep.setdefault('errors',[]).append('direct '+p+' '+repr(e))
        time.sleep(2)


def profile_captures(found,rep):
    profiles=[
      'twitter.com/nagata_shiori_','www.twitter.com/nagata_shiori_',
      'mobile.twitter.com/nagata_shiori_','twitter.com/nagata_shiori_/with_replies',
      'twitter.com/nagata_shiori_/media','x.com/nagata_shiori_'
    ]
    captures=[];seen=set()
    for p in profiles:
        try:
            rows=cdx(p,fl='timestamp,original,digest',collapse='digest')
            rep.setdefault('profile_cdx_rows',{})[p]=len(rows)
            for r in rows:
                key=(r.get('timestamp'),r.get('original'))
                if key not in seen:seen.add(key);captures.append(r)
        except Exception as e:rep.setdefault('errors',[]).append('profile-cdx '+p+' '+repr(e))
        time.sleep(2)
    # Prefer broad temporal coverage. Cap keeps the build bounded and polite to Wayback.
    captures.sort(key=lambda r:r.get('timestamp',''))
    if len(captures)>650:
        step=max(1,len(captures)//650);captures=captures[::step][:650]
    rep['profile_snapshots_selected']=len(captures)
    ok=0
    for i,r in enumerate(captures):
        ts=r.get('timestamp','');orig=r.get('original','')
        if not ts or not orig:continue
        u=f'https://web.archive.org/web/{ts}id_/{orig}'
        try:
            body=req(u,40,retries=3);ids_from_text(body,found,'wayback-profile:'+ts);ok+=1
        except Exception as e:
            if len(rep.setdefault('snapshot_errors',[]))<30:rep['snapshot_errors'].append(ts+':'+repr(e))
        time.sleep(0.45)
    rep['profile_snapshots_fetched']=ok


def crawl_public_forum_search(found,rep):
    # Search-engine-independent public archive pages that often preserve literal status URLs.
    # Only fetch a small known set of public result/archive pages; no login or bypass.
    urls=[
      'https://kako.5ch.io/test/read.cgi/akb/1608406353/',
      'https://talk.jp/boards/idol/1750507164'
    ]
    before=len(found)
    for u in urls:
        try:ids_from_text(req(u,35,2),found,'public-forum')
        except Exception as e:rep.setdefault('errors',[]).append('forum '+repr(e))
        time.sleep(2)
    rep['forum_new_candidate_ids']=len(found)-before


def main():
    root=json.loads(ASSET.read_text(encoding='utf-8'));events=root.get('events',[])
    rep={'version':'0.9.3','base_events':len(events),'base_x':sum(e.get('type')=='X' for e in events),'errors':[]}
    existing={}
    for e in events:
        if e.get('type')!='X':continue
        m=SID_RE.search(e.get('sourceUrl','') or '')
        if m:existing[m.group(1)]=e
    found={}
    direct_cdx(found,rep)
    profile_captures(found,rep)
    crawl_public_forum_search(found,rep)
    rep['candidate_unique_ids']=len(found)
    new=0
    for sid,sources in found.items():
        if sid in existing:continue
        dt=x_dt(sid)
        events.append({'id':'x-'+sid,'date':dt.strftime('%Y-%m-%d'),'time':dt.strftime('%H:%M:%S'),'type':'X','title':'X投稿','summary':'','excerpt':'','sourceName':'X @nagata_shiori_','sourceUrl':f'https://x.com/nagata_shiori_/status/{sid}','confidence':'公開Webアーカイブで本人status URLを確認','tags':[],'people':[]})
        existing[sid]=events[-1];new+=1
    # Hard invariant: X is unique by status ID and always direct URL.
    out=[];seen=set();dropped=0
    for e in events:
        if e.get('type')!='X':out.append(e);continue
        m=SID_RE.search(e.get('sourceUrl','') or '')
        if not m: dropped+=1;continue
        sid=m.group(1)
        if sid in seen:dropped+=1;continue
        seen.add(sid);e=dict(e);e['sourceUrl']=f'https://x.com/nagata_shiori_/status/{sid}';out.append(e)
    out.sort(key=lambda e:(e.get('date',''),e.get('time',''),e.get('id','')))
    root['events']=out;root['updatedAt']='2026-09-08';ASSET.write_text(json.dumps(root,ensure_ascii=False,separators=(',',':')),encoding='utf-8')
    rep.update({'x_added':new,'x_duplicate_or_invalid_dropped':dropped,'total_events':len(out),'x_records':len(seen),'x_direct_status_url':len(seen),'coverage_vs_5100':round(len(seen)/5100,4)})
    REPORT.write_text(json.dumps(rep,ensure_ascii=False,indent=2),encoding='utf-8')
    print('V093_REPORT',json.dumps(rep,ensure_ascii=False))

if __name__=='__main__':main()

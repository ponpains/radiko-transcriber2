#!/usr/bin/env python3
import concurrent.futures, difflib, hashlib, html, json, re, time, urllib.error, urllib.parse, urllib.request
from collections import Counter, defaultdict
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
ASSET=ROOT/'app/src/main/assets/archive_seed.json'
REPORT=ROOT/'app/src/main/assets/archive_v096_report.json'
JST=timezone(timedelta(hours=9)); X_EPOCH_MS=1288834974657
UA='Mozilla/5.0 ShioriArchive/0.9.6 (+public-archive-repair)'
X_RE=re.compile(r'https?://(?:www\.)?(?:x|twitter)\.com/nagata_shiori_/(?:status|statuses)/(\d{15,22})',re.I)
SID_RE=re.compile(r'/(?:status|statuses)/(\d{15,22})',re.I)
IG_DIRECT_RE=re.compile(r'https?://(?:www\.)?instagram\.com/(?:p|reel|tv)/([A-Za-z0-9_-]+)',re.I)
IG_DETAIL_RE=re.compile(r'https?://(?:www\.)?instagrammernews\.com/detail/(\d{10,24})',re.I)
TT_RE=re.compile(r'https?://(?:www\.)?tiktok\.com/@notequal_me_shiori/video/(\d{18,20})',re.I)


def request(url,timeout=25,tries=2):
    last=None
    for i in range(tries):
        try:
            req=urllib.request.Request(url,headers={'User-Agent':UA,'Accept-Language':'ja,en;q=0.8'})
            with urllib.request.urlopen(req,timeout=timeout) as r:return r.read().decode('utf-8','replace')
        except Exception as e:
            last=e; time.sleep(min(1.5*(i+1),4))
    raise last

def compact(s,n=120):
    s=html.unescape(s or '').replace('\\n',' ').replace('\\"','"')
    s=re.sub(r'<br\s*/?>',' ',s,flags=re.I);s=re.sub(r'<[^>]+>','',s)
    s=re.sub(r'\s+',' ',s).strip().strip('“”"')
    return s[:n]

def norm(s):
    s=html.unescape(s or '').lower();s=re.sub(r'https?://\S+','',s)
    return re.sub(r'[\s#＿_・、。,.!！?？~〜～「」『』()（）\[\]<>:：…“”"\'\-—]+','',s)[:420]

def secs(t):
    try:
        p=[int(x) for x in (t or '').split(':')]
        return p[0]*3600+p[1]*60+(p[2] if len(p)>2 else 0) if len(p)>=2 else None
    except:return None

def similarity(a,b):
    a=norm(a);b=norm(b)
    if not a or not b:return 0.0
    if a==b:return 1.0
    if min(len(a),len(b))>=8 and (a in b or b in a):return 0.98
    return difflib.SequenceMatcher(None,a,b).ratio()

def x_time(sid):
    ms=(int(sid)>>22)+X_EPOCH_MS
    return datetime.fromtimestamp(ms/1000,timezone.utc).astimezone(JST)

def tiktok_time(vid):
    return datetime.fromtimestamp(int(vid)>>32,timezone.utc).astimezone(JST)

def valid_dt(dt):
    return datetime(2019,2,1,tzinfo=JST)<=dt<=datetime.now(JST)+timedelta(days=2)

def x_sid(e):
    m=X_RE.search(e.get('sourceUrl','') or '') if e.get('type')=='X' else None
    return m.group(1) if m else None

def rich_score(e):
    u=e.get('sourceUrl','') or '';text=e.get('excerpt','') or e.get('summary','') or ''
    return (200 if X_RE.search(u) or IG_DIRECT_RE.search(u) or TT_RE.search(u) else 0)+(80 if u else 0)+min(len(text),160)+(10 if e.get('time') else 0)

# ---------- X repair / dedupe ----------
def dedupe_x(events,rep):
    non=[e for e in events if e.get('type')!='X']; direct={}; content=[]
    for e in events:
        if e.get('type')!='X':continue
        sid=x_sid(e)
        if not sid: content.append(dict(e));continue
        z=dict(e);z['sourceUrl']=f'https://x.com/nagata_shiori_/status/{sid}'
        old=direct.get(sid)
        if old is None:direct[sid]=z
        else:
            rep['x_duplicate_status_removed']+=1
            if rich_score(z)>rich_score(old):
                if not z.get('excerpt') and old.get('excerpt'):z['excerpt']=old['excerpt']
                direct[sid]=z
            elif not old.get('excerpt') and z.get('excerpt'):old['excerpt']=z['excerpt']
    bydate=defaultdict(list)
    for e in direct.values():bydate[e.get('date','')].append(e)
    kept=[]
    for q in sorted(content,key=lambda e:(e.get('date',''),e.get('time',''),e.get('id',''))):
        txt=q.get('excerpt','') or q.get('summary','') or ''
        nq=norm(txt);sq=secs(q.get('time',''));best=None
        for d in bydate.get(q.get('date',''),[]):
            nd=norm(d.get('excerpt','') or '');sd=secs(d.get('time',''))
            diff=999999 if sq is None or sd is None else abs(sq-sd)
            sim=similarity(txt,d.get('excerpt','') or '') if nd else 0.0
            ok=(diff<=90 and ((not nd and len(nq)>=3) or sim>=0.55)) or sim>=0.84
            if ok:
                score=(2.0 if diff<=90 else 0.0)+sim
                if best is None or score>best[0]:best=(score,d,diff,sim)
        if best:
            d=best[1]
            if not (d.get('excerpt') or '').strip() and txt.strip():
                d['excerpt']=compact(txt);rep['x_content_promoted_to_direct']+=1
            rep['x_fuzzy_duplicate_removed']+=1
            continue
        # content-only vs content-only; require same date and close time or very high similarity.
        duplicate=None
        for k in kept:
            if k.get('date')!=q.get('date'):continue
            sk=secs(k.get('time',''));diff=999999 if sq is None or sk is None else abs(sq-sk)
            sim=similarity(txt,k.get('excerpt','') or k.get('summary','') or '')
            if (diff<=120 and sim>=0.78) or sim>=0.92:
                duplicate=k;break
        if duplicate:
            rep['x_content_duplicate_removed']+=1
            if rich_score(q)>rich_score(duplicate):
                kept.remove(duplicate);kept.append(q)
            continue
        kept.append(q)
    return non+list(direct.values())+kept

def cdx_snapshots(rep):
    prefixes=['twitter.com/nagata_shiori_/status/*','twitter.com/nagata_shiori_/statuses/*','www.twitter.com/nagata_shiori_/status/*','mobile.twitter.com/nagata_shiori_/status/*','x.com/nagata_shiori_/status/*','www.x.com/nagata_shiori_/status/*']
    def one(prefix):
        q={'url':prefix,'output':'json','filter':'statuscode:200','collapse':'urlkey','fl':'timestamp,original','limit':'10000'}
        url='https://web.archive.org/cdx/search/cdx?'+urllib.parse.urlencode(q)
        try:
            rows=json.loads(request(url,40,2));out={}
            for row in rows[1:] if rows and isinstance(rows[0],list) else rows:
                if not isinstance(row,list) or len(row)<2:continue
                m=SID_RE.search(row[1])
                if m and m.group(1) not in out:out[m.group(1)]=(row[0],row[1])
            return prefix,out,None
        except Exception as e:return prefix,{},repr(e)
    allmap={}
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as ex:
        for p,out,err in ex.map(one,prefixes):
            rep.setdefault('wayback_prefixes',[]).append({'prefix':p,'ids':len(out)})
            if err:rep.setdefault('wayback_errors',[]).append({'prefix':p,'error':err})
            for sid,v in out.items():allmap.setdefault(sid,v)
    rep['wayback_unique_status_ids']=len(allmap);return allmap

def commoncrawl_ids(rep):
    try:cols=json.loads(request('https://index.commoncrawl.org/collinfo.json',25,2))
    except Exception as e:rep['commoncrawl_error']=repr(e);return set()
    byyear={}
    for x in cols:
        m=re.search(r'CC-MAIN-(20\d{2})-',x.get('id',''))
        if m and 2019<=int(m.group(1))<=2026:byyear.setdefault(m.group(1),[]).append(x.get('id',''))
    chosen=[]
    for y,ls in byyear.items():chosen.extend(ls[:2])
    jobs=[(c,p) for c in chosen for p in ('twitter.com/nagata_shiori_/status/*','x.com/nagata_shiori_/status/*')]
    def one(job):
        c,p=job;q=urllib.parse.urlencode({'url':p,'output':'json','collapse':'urlkey'})
        try:
            body=request(f'https://index.commoncrawl.org/{c}-index?{q}',20,1);ids=set()
            for line in body.splitlines():
                m=SID_RE.search(line)
                if m:ids.add(m.group(1))
            return ids
        except:return set()
    ids=set()
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as ex:
        for got in ex.map(one,jobs):ids|=got
    rep['commoncrawl_queries']=len(jobs);rep['commoncrawl_unique_status_ids']=len(ids);return ids

def add_x_ids(events,ids,rep):
    known={x_sid(e) for e in events if x_sid(e)};added=0
    for sid in sorted(ids,key=int):
        if sid in known:continue
        try:dt=x_time(sid)
        except:continue
        if not valid_dt(dt):continue
        events.append({'id':'x-'+sid,'date':dt.strftime('%Y-%m-%d'),'time':dt.strftime('%H:%M:%S'),'type':'X','title':'X投稿','summary':'','excerpt':'','sourceName':'X @nagata_shiori_','sourceUrl':f'https://x.com/nagata_shiori_/status/{sid}','confidence':'公開Webアーカイブ索引で本人Xのstatus IDを確認','tags':[],'people':[]});known.add(sid);added+=1
    rep['x_missing_status_added']=added

def extract_tweet_text(page):
    cand=[]
    pats=[r'<meta[^>]+(?:property|name)=["\'](?:og:description|description)["\'][^>]+content=["\']([^"\']+)',r'<meta[^>]+content=["\']([^"\']+)["\'][^>]+(?:property|name)=["\'](?:og:description|description)["\']',r'"full_text"\s*:\s*"((?:\\.|[^"\\]){2,600})"',r'"text"\s*:\s*"((?:\\.|[^"\\]){2,600})"',r'data-testid=["\']tweetText["\'][^>]*>(.*?)</div>']
    for pat in pats:cand.extend(re.findall(pat,page,re.I|re.S)[:6])
    cleaned=[]
    for x in cand:
        x=compact(x,160)
        low=x.lower()
        if len(norm(x))<2:continue
        if any(z in low for z in ('see new posts','log in','sign up for twitter','don’t miss what’s happening','twitter. it’s what’s happening')):continue
        if x not in cleaned:cleaned.append(x)
    # Prefer tweet-like candidates; descriptions with Japanese text are usually best.
    cleaned.sort(key=lambda s:((1 if re.search('[ぁ-んァ-ン一-龯]',s) else 0),min(len(s),140)),reverse=True)
    return compact(cleaned[0],120) if cleaned else ''

def recover_x_text(events,snaps,rep):
    missing=[]
    for e in events:
        sid=x_sid(e)
        if sid and not (e.get('excerpt') or '').strip() and sid in snaps:missing.append((sid,e,snaps[sid]))
    rep['x_direct_missing_text_before_recovery']=len(missing)
    def one(item):
        sid,e,(ts,orig)=item
        url=f'https://web.archive.org/web/{ts}id_/{orig}'
        try:return sid,extract_tweet_text(request(url,22,1))
        except:return sid,''
    found={}
    with concurrent.futures.ThreadPoolExecutor(max_workers=7) as ex:
        for sid,text in ex.map(one,missing):
            if text:found[sid]=text
    for e in events:
        sid=x_sid(e)
        if sid in found and not (e.get('excerpt') or '').strip():e['excerpt']=found[sid]
    rep['x_wayback_text_recovered']=len(found)

def twfan_upgrade(events,rep):
    known={x_sid(e):e for e in events if x_sid(e)};seen=0;up=0
    for mode in ('new','old'):
        stale=0
        for page in range(1,31):
            url=f'https://twfan.net/user/1098230959269281792/{mode}/{page}'
            try:body=request(url,18,1)
            except:break
            before=seen
            # Extract individual tweet anchors and nearby visible text.
            for m in re.finditer(r'https?://(?:www\.)?(?:x|twitter)\.com/nagata_shiori_/status/(\d{15,22})',html.unescape(body),re.I):
                sid=m.group(1);seen+=1
                e=known.get(sid)
                if not e or (e.get('excerpt') or '').strip():continue
                chunk=body[max(0,m.start()-3500):m.start()+400]
                am=list(re.finditer(r'@nagata_shiori_',chunk,re.I))
                if not am:continue
                text=compact(chunk[am[-1].end():],120)
                text=re.sub(r'(リツイート|いいね).*','',text).strip()
                if len(norm(text))>=3:e['excerpt']=text;up+=1
            if seen==before:stale+=1
            else:stale=0
            if stale>=3:break
            time.sleep(.12)
    rep['twfan_status_refs_seen']=seen;rep['x_twfan_text_recovered']=up

# ---------- Instagram ----------
def parse_ig_detail(mid,body):
    if 'nagata__shiori' not in body.lower():return None,[]
    txt=html.unescape(body)
    direct=IG_DIRECT_RE.search(txt)
    # Prefer explicit dates in the article body.
    dm=re.search(r'(20\d{2})/(\d{1,2})/(\d{1,2})',txt) or re.search(r'(20\d{2})年\s*(\d{1,2})月\s*(\d{1,2})日',txt)
    if not dm:return None,[]
    y,mo,d=map(int,dm.groups());tm=re.search(r'(\d{1,2})月(\d{1,2})日\s*(\d{1,2})時(\d{2})分',txt)
    t=''
    if tm and int(tm.group(1))==mo and int(tm.group(2))==d:t=f'{int(tm.group(3)):02d}:{int(tm.group(4)):02d}'
    desc=''
    for pat in (r'<meta[^>]+(?:property|name)=["\'](?:og:description|description)["\'][^>]+content=["\']([^"\']+)',r'<meta[^>]+content=["\']([^"\']+)["\'][^>]+(?:property|name)=["\'](?:og:description|description)["\']'):
        mm=re.search(pat,body,re.I|re.S)
        if mm:desc=compact(mm.group(1),120);break
    # Strip common index wrapper if possible.
    desc=re.sub(r'^.*?Instagram[^:：]*[:：]\s*','',desc,flags=re.I)
    rec={'id':'instagram-'+mid,'date':f'{y:04d}-{mo:02d}-{d:02d}','time':t,'type':'Instagram','title':'Instagram投稿','summary':desc,'excerpt':'','sourceName':'Instagram @nagata__shiori' if direct else 'Instagram公開索引（Instagrammer News）','sourceUrl':(direct.group(0).rstrip('/')+'/' if direct else f'https://instagrammernews.com/detail/{mid}'),'confidence':'公開索引で投稿日時を確認'+('・元投稿URL確認' if direct else ''),'tags':[],'people':[]}
    links=list(dict.fromkeys(re.findall(r'/detail/(\d{10,24})',body)))
    return rec,links

def expand_instagram(events,rep):
    seeds=[]
    try:
        body=request('https://instagrammernews.com/user/58749300526',25,2);seeds=list(dict.fromkeys(re.findall(r'/detail/(\d{10,24})',body)))
    except Exception as e:rep['instagram_profile_error']=repr(e)
    queue=list(seeds);seen=set();found=[]
    while queue and len(seen)<180:
        mid=queue.pop(0)
        if mid in seen:continue
        seen.add(mid)
        try:body=request(f'https://instagrammernews.com/detail/{mid}',20,1)
        except:continue
        rec,links=parse_ig_detail(mid,body)
        if rec:
            found.append(rec)
            for z in links:
                if z not in seen and z not in queue:queue.append(z)
        time.sleep(.08)
    rep['instagram_detail_pages_checked']=len(seen);rep['instagram_target_posts_found']=len(found)
    existing=[dict(e) for e in events if e.get('type')=='Instagram'];other=[e for e in events if e.get('type')!='Instagram']
    allig=existing+found;out=[];bydirect={};bydetail={}
    for e in allig:
        u=e.get('sourceUrl','') or '';dm=IG_DIRECT_RE.search(u);im=IG_DETAIL_RE.search(u)
        key=('direct',dm.group(1)) if dm else (('detail',im.group(1)) if im else None)
        if key:
            target=bydirect if key[0]=='direct' else bydetail
            if key[1] in target:
                rep['instagram_duplicates_removed']+=1
                old=target[key[1]]
                if rich_score(e)>rich_score(old):out.remove(old);out.append(e);target[key[1]]=e
                continue
            target[key[1]]=e
        # Fuzzy duplicate when same date/time and similar caption, or same date+same nonempty caption.
        dup=None
        for old in out:
            if old.get('date')!=e.get('date'):continue
            so,se=secs(old.get('time','')),secs(e.get('time',''))
            close=so is not None and se is not None and abs(so-se)<=120
            sim=similarity(old.get('summary',''),e.get('summary',''))
            if (close and (sim>=0.55 or not norm(old.get('summary','')) or not norm(e.get('summary','')))) or sim>=0.9:
                dup=old;break
        if dup:
            rep['instagram_duplicates_removed']+=1
            if rich_score(e)>rich_score(dup):out.remove(dup);out.append(e)
            continue
        out.append(e)
    before=len(existing);rep['instagram_net_added']=max(0,len(out)-before);return other+out

# ---------- TikTok ----------
def expand_tiktok(events,rep):
    ids=set();existing={}
    for e in events:
        if e.get('type')=='TikTok':
            m=TT_RE.search(e.get('sourceUrl','') or '')
            if m:ids.add(m.group(1));existing[m.group(1)]=e
    for e in events:
        blob=' '.join(str(e.get(k,'') or '') for k in ('summary','excerpt','sourceUrl'))
        for m in TT_RE.finditer(blob):ids.add(m.group(1))
    pages=['https://urlebird.com/hash/%E6%B0%B8%E7%94%B0%E8%A9%A9%E5%A4%AE%E9%87%8C/','https://urlebird.com/user/notequal_me_shiori/']
    for base in pages:
        stale=0
        for page in range(1,31):
            url=base if page==1 else base+f'?page={page}'
            try:body=request(url,18,1)
            except:break
            before=len(ids)
            for m in re.finditer(r'(?:href=["\'][^"\']*)?/video/[^"\'<]*?(\d{18,20})',body,re.I):
                chunk=body[max(0,m.start()-1600):min(len(body),m.end()+500)]
                if 'notequal_me_shiori' in chunk.lower():ids.add(m.group(1))
            stale=stale+1 if len(ids)==before else 0
            if stale>=3:break
            time.sleep(.1)
    rep['tiktok_unique_ids_seen']=len(ids);added=0
    for vid in sorted(ids):
        if vid in existing:continue
        try:dt=tiktok_time(vid)
        except:continue
        if not valid_dt(dt):continue
        events.append({'id':'tiktok-'+vid,'date':dt.strftime('%Y-%m-%d'),'time':dt.strftime('%H:%M:%S'),'type':'TikTok','title':'TikTok投稿','summary':'','excerpt':'','sourceName':'TikTok @notequal_me_shiori','sourceUrl':f'https://www.tiktok.com/@notequal_me_shiori/video/{vid}','confidence':'公開索引で動画ID確認・動画IDから投稿時刻を算出','tags':[],'people':[]});existing[vid]=events[-1];added+=1
    rep['tiktok_added']=added;return events

# ---------- Interviews / generic cleanup ----------
def cleanup_interviews(events,rep):
    bad_hosts=('fonts.googleapis.com','link.naver.com','tiktok.com','twitter.com','x.com','instagram.com','showroom-live.com')
    seen={};out=[]
    for e0 in events:
        if e0.get('type')!='インタビュー':out.append(e0);continue
        e=dict(e0);u=e.get('sourceUrl','') or ''
        if any(h in u.lower() for h in bad_hosts):e['sourceUrl']='';rep['interview_bad_source_cleared']+=1
        key=(e.get('date',''),norm(e.get('title','')+' '+e.get('summary','')))
        if key[1] and key in seen:
            rep['interview_duplicates_removed']+=1
            old=seen[key]
            if rich_score(e)>rich_score(old):out.remove(old);out.append(e);seen[key]=e
            continue
        seen[key]=e;out.append(e)
    return out

def main():
    root=json.loads(ASSET.read_text(encoding='utf-8'));events=root.get('events',[])
    rep={'version':'0.9.6','base_events':len(events),'base_type_counts':dict(Counter(e.get('type','') for e in events)),'x_duplicate_status_removed':0,'x_fuzzy_duplicate_removed':0,'x_content_duplicate_removed':0,'x_content_promoted_to_direct':0,'instagram_duplicates_removed':0,'interview_bad_source_cleared':0,'interview_duplicates_removed':0,'errors':[]}
    x0=[e for e in events if e.get('type')=='X'];rep['base_x']=len(x0);rep['base_x_direct']=sum(bool(x_sid(e)) for e in x0);rep['base_x_direct_missing_text']=sum(bool(x_sid(e)) and not (e.get('excerpt') or '').strip() for e in x0)
    events=dedupe_x(events,rep)
    snaps=cdx_snapshots(rep);cc=commoncrawl_ids(rep);add_x_ids(events,set(snaps)|cc,rep)
    recover_x_text(events,snaps,rep);twfan_upgrade(events,rep)
    events=dedupe_x(events,rep)
    events=expand_instagram(events,rep);events=expand_tiktok(events,rep);events=cleanup_interviews(events,rep)
    # Final strict exact-key dedupe for non-X records only.
    final=[];seen=set()
    for e in sorted(events,key=lambda e:(e.get('date',''),e.get('time',''),e.get('type',''),e.get('id',''))):
        if e.get('type')=='X':final.append(e);continue
        key=(e.get('type',''),e.get('sourceUrl','').rstrip('/'),e.get('date',''),e.get('time',''),norm(e.get('title','')+' '+e.get('summary','')))
        if key in seen:rep['other_exact_duplicates_removed']=rep.get('other_exact_duplicates_removed',0)+1;continue
        seen.add(key);final.append(e)
    events=final;root['events']=events;root['updatedAt']='2026-09-09';ASSET.write_text(json.dumps(root,ensure_ascii=False,separators=(',',':')),encoding='utf-8')
    x=[e for e in events if e.get('type')=='X'];direct=[e for e in x if x_sid(e)]
    rep.update({'total_events':len(events),'type_counts':dict(Counter(e.get('type','') for e in events)),'x_records':len(x),'x_direct_status':len(direct),'x_content_only':len(x)-len(direct),'x_direct_missing_text':sum(not (e.get('excerpt') or '').strip() for e in direct),'x_with_text':sum(bool((e.get('excerpt') or '').strip()) for e in x),'x_capture_vs_5100':round(len(x)/5100,4),'instagram_records':sum(e.get('type')=='Instagram' for e in events),'tiktok_records':sum(e.get('type')=='TikTok' for e in events),'interview_records':sum(e.get('type')=='インタビュー' for e in events)})
    REPORT.write_text(json.dumps(rep,ensure_ascii=False,indent=2),encoding='utf-8');print('V096_REPORT',json.dumps(rep,ensure_ascii=False),flush=True)
if __name__=='__main__':main()

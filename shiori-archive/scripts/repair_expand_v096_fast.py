#!/usr/bin/env python3
import concurrent.futures, importlib.util, json, re, time, urllib.request
from collections import Counter
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('basev096',ROOT/'scripts/repair_expand_v096.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)

# Public archives can be slow. Keep each request short and use parallelism instead of long serial waits.
def fast_request(url,timeout=5,tries=1):
    last=None
    for _ in range(max(1,min(tries,1))):
        try:
            req=urllib.request.Request(url,headers={'User-Agent':m.UA,'Accept-Language':'ja,en;q=0.8'})
            with urllib.request.urlopen(req,timeout=min(float(timeout),5.0)) as r:
                return r.read().decode('utf-8','replace')
        except Exception as e:last=e
    raise last
m.request=fast_request


def fast_recover_x(events,snaps,rep):
    missing=[]
    for e in events:
        sid=m.x_sid(e)
        if sid and not (e.get('excerpt') or '').strip() and sid in snaps:
            missing.append((sid,e,snaps[sid]))
    rep['x_direct_missing_text_before_recovery']=len(missing)
    def one(item):
        sid,e,(ts,orig)=item
        try:
            page=fast_request(f'https://web.archive.org/web/{ts}id_/{orig}',3.5,1)
            return sid,m.extract_tweet_text(page)
        except Exception:return sid,''
    found={}
    with concurrent.futures.ThreadPoolExecutor(max_workers=20) as ex:
        for sid,text in ex.map(one,missing):
            if text:found[sid]=text
    for e in events:
        sid=m.x_sid(e)
        if sid in found and not (e.get('excerpt') or '').strip():e['excerpt']=found[sid]
    rep['x_wayback_text_recovered']=len(found)


def fast_twfan(events,rep):
    known={m.x_sid(e):e for e in events if m.x_sid(e)}
    urls=[f'https://twfan.net/user/1098230959269281792/{mode}/{page}' for mode in ('new','old') for page in range(1,13)]
    def one(url):
        try:return url,fast_request(url,4,1)
        except:return url,''
    seen=up=0
    with concurrent.futures.ThreadPoolExecutor(max_workers=10) as ex:
        for url,body in ex.map(one,urls):
            if not body:continue
            textbody=m.html.unescape(body)
            for mm in re.finditer(r'https?://(?:www\.)?(?:x|twitter)\.com/nagata_shiori_/status/(\d{15,22})',textbody,re.I):
                sid=mm.group(1);seen+=1;e=known.get(sid)
                if not e or (e.get('excerpt') or '').strip():continue
                chunk=body[max(0,mm.start()-3500):mm.start()+400]
                am=list(re.finditer(r'@nagata_shiori_',chunk,re.I))
                if not am:continue
                txt=m.compact(chunk[am[-1].end():],120)
                txt=re.sub(r'(リツイート|いいね).*','',txt).strip()
                if len(m.norm(txt))>=3:e['excerpt']=txt;up+=1
    rep['twfan_status_refs_seen']=seen;rep['x_twfan_text_recovered']=up


def merge_instagram(events,found,rep):
    existing=[dict(e) for e in events if e.get('type')=='Instagram'];other=[e for e in events if e.get('type')!='Instagram']
    out=[];keys={}
    for e in existing+found:
        u=e.get('sourceUrl','') or '';dm=m.IG_DIRECT_RE.search(u);im=m.IG_DETAIL_RE.search(u)
        key=('d',dm.group(1)) if dm else (('i',im.group(1)) if im else None)
        if key and key in keys:
            rep['instagram_duplicates_removed']+=1;old=keys[key]
            if m.rich_score(e)>m.rich_score(old):out.remove(old);out.append(e);keys[key]=e
            continue
        dup=None
        for old in out:
            if old.get('date')!=e.get('date'):continue
            so,se=m.secs(old.get('time','')),m.secs(e.get('time',''))
            close=so is not None and se is not None and abs(so-se)<=120
            sim=m.similarity(old.get('summary',''),e.get('summary',''))
            if (close and (sim>=0.55 or not m.norm(old.get('summary','')) or not m.norm(e.get('summary','')))) or sim>=0.92:
                dup=old;break
        if dup:
            rep['instagram_duplicates_removed']+=1
            if m.rich_score(e)>m.rich_score(dup):out.remove(dup);out.append(e)
            continue
        out.append(e)
        if key:keys[key]=e
    rep['instagram_net_added']=max(0,len(out)-len(existing))
    return other+out


def fast_instagram(events,rep):
    try:profile=fast_request('https://instagrammernews.com/user/58749300526',5,1)
    except Exception as e:rep['instagram_profile_error']=repr(e);return events
    queue=list(dict.fromkeys(re.findall(r'/detail/(\d{10,24})',profile)));seen=set();found=[]
    # Walk previous/next links in small parallel batches. Stop around the public profile count plus margin.
    while queue and len(seen)<125:
        batch=[]
        while queue and len(batch)<12 and len(seen)+len(batch)<125:
            z=queue.pop(0)
            if z not in seen:seen.add(z);batch.append(z)
        def one(mid):
            try:return mid,fast_request(f'https://instagrammernews.com/detail/{mid}',4,1)
            except:return mid,''
        with concurrent.futures.ThreadPoolExecutor(max_workers=12) as ex:
            for mid,body in ex.map(one,batch):
                if not body:continue
                rec,links=m.parse_ig_detail(mid,body)
                if rec:
                    found.append(rec)
                    for z in links:
                        if z not in seen and z not in queue:queue.append(z)
    rep['instagram_detail_pages_checked']=len(seen);rep['instagram_target_posts_found']=len(found)
    return merge_instagram(events,found,rep)


def fast_tiktok(events,rep):
    ids=set();existing={}
    for e in events:
        if e.get('type')=='TikTok':
            mm=m.TT_RE.search(e.get('sourceUrl','') or '')
            if mm:ids.add(mm.group(1));existing[mm.group(1)]=e
        blob=' '.join(str(e.get(k,'') or '') for k in ('summary','excerpt','sourceUrl'))
        for mm in m.TT_RE.finditer(blob):ids.add(mm.group(1))
    bases=['https://urlebird.com/hash/%E6%B0%B8%E7%94%B0%E8%A9%A9%E5%A4%AE%E9%87%8C/','https://urlebird.com/user/notequal_me_shiori/']
    urls=[]
    for base in bases:
        urls.append(base)
        urls.extend(base+f'?page={i}' for i in range(2,16))
    def one(url):
        try:return fast_request(url,4,1)
        except:return ''
    with concurrent.futures.ThreadPoolExecutor(max_workers=12) as ex:
        for body in ex.map(one,urls):
            for mm in re.finditer(r'(?:href=["\'][^"\']*)?/video/[^"\'<]*?(\d{18,20})',body,re.I):
                chunk=body[max(0,mm.start()-1600):min(len(body),mm.end()+500)]
                if 'notequal_me_shiori' in chunk.lower():ids.add(mm.group(1))
    added=0
    for vid in sorted(ids):
        if vid in existing:continue
        try:dt=m.tiktok_time(vid)
        except:continue
        if not m.valid_dt(dt):continue
        e={'id':'tiktok-'+vid,'date':dt.strftime('%Y-%m-%d'),'time':dt.strftime('%H:%M:%S'),'type':'TikTok','title':'TikTok投稿','summary':'','excerpt':'','sourceName':'TikTok @notequal_me_shiori','sourceUrl':f'https://www.tiktok.com/@notequal_me_shiori/video/{vid}','confidence':'公開索引で動画ID確認・動画IDから投稿時刻を算出','tags':[],'people':[]}
        events.append(e);existing[vid]=e;added+=1
    rep['tiktok_unique_ids_seen']=len(ids);rep['tiktok_added']=added
    return events


def final_exact(events,rep):
    out=[];seen=set()
    for e in sorted(events,key=lambda e:(e.get('date',''),e.get('time',''),e.get('type',''),e.get('id',''))):
        if e.get('type')=='X':out.append(e);continue
        key=(e.get('type',''),(e.get('sourceUrl','') or '').rstrip('/'),e.get('date',''),e.get('time',''),m.norm((e.get('title','') or '')+' '+(e.get('summary','') or '')))
        if key in seen:rep['other_exact_duplicates_removed']=rep.get('other_exact_duplicates_removed',0)+1;continue
        seen.add(key);out.append(e)
    return out


def main():
    root=json.loads(m.ASSET.read_text(encoding='utf-8'));events=root.get('events',[])
    rep={'version':'0.9.6','mode':'bounded-parallel-public-recovery','base_events':len(events),'base_type_counts':dict(Counter(e.get('type','') for e in events)),'x_duplicate_status_removed':0,'x_fuzzy_duplicate_removed':0,'x_content_duplicate_removed':0,'x_content_promoted_to_direct':0,'instagram_duplicates_removed':0,'interview_bad_source_cleared':0,'interview_duplicates_removed':0,'errors':[]}
    x0=[e for e in events if e.get('type')=='X'];rep['base_x']=len(x0);rep['base_x_direct']=sum(bool(m.x_sid(e)) for e in x0);rep['base_x_direct_missing_text']=sum(bool(m.x_sid(e)) and not (e.get('excerpt') or '').strip() for e in x0)
    events=m.dedupe_x(events,rep)
    snaps=m.cdx_snapshots(rep);cc=m.commoncrawl_ids(rep);m.add_x_ids(events,set(snaps)|cc,rep)
    fast_recover_x(events,snaps,rep);fast_twfan(events,rep)
    events=m.dedupe_x(events,rep)
    events=fast_instagram(events,rep);events=fast_tiktok(events,rep);events=m.cleanup_interviews(events,rep);events=final_exact(events,rep)
    root['events']=events;root['updatedAt']='2026-09-09';m.ASSET.write_text(json.dumps(root,ensure_ascii=False,separators=(',',':')),encoding='utf-8')
    x=[e for e in events if e.get('type')=='X'];direct=[e for e in x if m.x_sid(e)]
    rep.update({'total_events':len(events),'type_counts':dict(Counter(e.get('type','') for e in events)),'x_records':len(x),'x_direct_status':len(direct),'x_content_only':len(x)-len(direct),'x_direct_missing_text':sum(not (e.get('excerpt') or '').strip() for e in direct),'x_with_text':sum(bool((e.get('excerpt') or '').strip()) for e in x),'x_capture_vs_5100':round(len(x)/5100,4),'instagram_records':sum(e.get('type')=='Instagram' for e in events),'tiktok_records':sum(e.get('type')=='TikTok' for e in events),'interview_records':sum(e.get('type')=='インタビュー' for e in events)})
    m.REPORT.write_text(json.dumps(rep,ensure_ascii=False,indent=2),encoding='utf-8');print('V096_FAST_REPORT',json.dumps(rep,ensure_ascii=False),flush=True)
if __name__=='__main__':main()

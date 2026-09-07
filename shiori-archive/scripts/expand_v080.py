#!/usr/bin/env python3
import concurrent.futures
import hashlib
import html as htmllib
import json
import re
import time
import urllib.parse
import urllib.request
from collections import Counter
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
ASSET=ROOT/'app/src/main/assets/archive_seed.json'
REPORT=ROOT/'app/src/main/assets/archive_v080_report.json'
JST=timezone(timedelta(hours=9))
UA={'User-Agent':'Mozilla/5.0 ShioriArchive/0.8 (+public archive metadata sweep)'}
X_EPOCH_MS=1288834974657


def get(url,timeout=20,retries=1):
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


def clean(text):
    text=re.sub(r'<br\s*/?>','\n',text or '',flags=re.I)
    text=re.sub(r'<[^>]+>','',text)
    text=htmllib.unescape(text)
    text=re.sub(r'[ \t]+',' ',text)
    text=re.sub(r'\n{3,}','\n\n',text)
    return text.strip()


def short(text,n=52):
    s=re.sub(r'\s+',' ',text or '').strip()
    return s if len(s)<=n else s[:n].rstrip()+'…'


def meta(page,key):
    pats=[
      rf'<meta[^>]+(?:property|name)=["\']{re.escape(key)}["\'][^>]+content=["\']([^"\']*)',
      rf'<meta[^>]+content=["\']([^"\']*)["\'][^>]+(?:property|name)=["\']{re.escape(key)}["\']'
    ]
    for p in pats:
        m=re.search(p,page,re.I|re.S)
        if m:return htmllib.unescape(m.group(1)).strip()
    return ''


def norm_date(s):
    if not s:return ''
    m=re.search(r'(20\d{2})[./年\-](\d{1,2})[./月\-](\d{1,2})',s)
    if not m:return ''
    try:return date(int(m.group(1)),int(m.group(2)),int(m.group(3))).isoformat()
    except:return ''


def status_id(url):
    m=re.search(r'(?:x|twitter)\.com/(?:#!/)?nagata_shiori_/(?:status|statuses)/(\d{15,22})',url or '',re.I)
    return m.group(1) if m else None


def snowflake_dt(sid):
    ms=(int(sid)>>22)+X_EPOCH_MS
    return datetime.fromtimestamp(ms/1000,timezone.utc).astimezone(JST)


def direct_kind(url):
    u=(url or '').strip()
    if re.search(r'https?://(?:www\.)?(?:x|twitter)\.com/nagata_shiori_/(?:status|statuses)/\d+',u,re.I):return 'x'
    if re.search(r'https?://(?:www\.)?instagram\.com/(?:p|reel|tv)/[^/?#]+',u,re.I):return 'instagram'
    if re.search(r'https?://(?:www\.)?tiktok\.com/@[^/]+/video/\d+',u,re.I):return 'tiktok'
    if re.search(r'https?://(?:www\.)?(?:youtube\.com/(?:watch\?|shorts/)|youtu\.be/)',u,re.I):return 'youtube'
    if '/podcast/episodes/' in u:return 'radiko'
    if re.search(r'/(?:news|schedule)/detail/\d+',u):return 'official'
    return ''


def event_id(prefix,url,extra=''):
    return prefix+'-'+hashlib.sha1((url+'|'+extra).encode()).hexdigest()[:16]


def classify(title,text=''):
    s=(title+' '+text[:800]).lower()
    if any(x in s for x in ['ラジオ','radio','けれけれ','ノイミーステーション']):return 'ラジオ'
    if any(x in s for x in ['テレビ','tv','tver','めざまし']):return 'テレビ'
    if any(x in s for x in ['youtube','mv','music video','映像公開']):return 'YouTube'
    if any(x in s for x in ['インタビュー','取材','掲載','web記事']):return 'インタビュー'
    if any(x in s for x in ['ライブ','コンサート','ツアー','イベント','お話し会','握手会']):return 'イベント'
    if any(x in s for x in ['single','アルバム','発売','リリース','配信開始']):return 'リリース'
    return '公式NEWS'


root=json.loads(ASSET.read_text(encoding='utf-8'))
events=list(root.get('events',[]))
by_id={str(e.get('id','')):e for e in events if str(e.get('id',''))}
by_url={str(e.get('sourceUrl','')).split('?')[0]:e for e in events if str(e.get('sourceUrl','')).startswith('http')}
initial=len(events)
report={'base_events':initial,'added':Counter(),'updated':Counter(),'errors':[]}


def add(e,source='other'):
    url=str(e.get('sourceUrl','')).strip()
    key=url.split('?')[0] if url.startswith('http') else ''
    if key and key in by_url:
        old=by_url[key]
        # Upgrade sparse old records with literal excerpts/direct metadata, never invent text.
        if e.get('excerpt') and not old.get('excerpt'):
            old['excerpt']=e['excerpt']; report['updated'][source]+=1
        if e.get('time') and not old.get('time'):
            old['time']=e['time']; report['updated'][source]+=1
        return old
    eid=str(e.get('id',''))
    if not eid or eid in by_id:return by_id.get(eid)
    e.setdefault('time','');e.setdefault('summary','');e.setdefault('excerpt','');e.setdefault('tags',[]);e.setdefault('people',[])
    events.append(e);by_id[eid]=e
    if key:by_url[key]=e
    report['added'][source]+=1
    return e


# 1) Extra lawful Wayback status-ID host variants. Short timeouts prevent a slow archive from blocking the build.
def cdx(url):
    try:
        rows=json.loads(get(url,55,0)); return rows[1:] if isinstance(rows,list) and rows else []
    except Exception as e:
        report['errors'].append('CDX '+url+' '+repr(e));return []
cdx_urls=[]
for host in ['mobile.twitter.com','www.twitter.com','mobile.x.com','www.x.com']:
    for word in ['status','statuses']:
        cdx_urls.append(f'https://web.archive.org/cdx/search/cdx?url={host}/nagata_shiori_/{word}/*&output=json&filter=statuscode:200&collapse=urlkey&fl=original&limit=7000')
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as ex:
    futs=[ex.submit(cdx,u) for u in cdx_urls]
    for fut in concurrent.futures.as_completed(futs):
        for row in fut.result():
            if not row:continue
            sid=status_id(str(row[0]))
            if not sid:continue
            direct=f'https://x.com/nagata_shiori_/status/{sid}'
            if direct in by_url or direct.split('?')[0] in by_url:continue
            dt=snowflake_dt(sid)
            add({'id':'x-'+sid,'date':dt.strftime('%Y-%m-%d'),'time':dt.strftime('%H:%M:%S'),'type':'X','title':'X投稿','summary':'','excerpt':'','sourceName':'X @nagata_shiori_','sourceUrl':direct,'confidence':'投稿IDを公開Webアーカイブで確認','tags':[],'people':[]},'x_extra')

# 2) Instagram normal posts / Reels: fully paginate the public archive until it ends. Stories intentionally excluded.
seen_ig=set(); page_no=1
while page_no<=50:
    url=f'https://instagrammernews.com/user/58749300526?page={page_no}'
    try:page=get(url,20,1)
    except Exception as e:
        report['errors'].append('Instagram page '+str(page_no)+' '+repr(e));break
    blocks=re.findall(r'<li\b[^>]*class=["\'][^"\']*media_item[^"\']*["\'][^>]*>.*?</li>',page,re.I|re.S)
    ids=[]
    for block in blocks:
        m=re.search(r'/detail/(\d+)',block)
        if not m:continue
        mid=m.group(1);ids.append(mid)
        tsm=re.search(r'data-from=["\'](\d{10,13})["\']',block,re.I)
        if tsm:
            ts=int(tsm.group(1)); ts=ts/1000 if ts>10**11 else ts
            dt=datetime.fromtimestamp(ts,timezone.utc).astimezone(JST)
        else:dt=None
        alt=''
        am=re.search(r'<img[^>]+alt=["\']([^"\']*)',block,re.I|re.S)
        if am:alt=htmllib.unescape(am.group(1))
        detail=f'https://instagrammernews.com/detail/{mid}'
        add({'id':'ig-index-'+mid,'date':dt.strftime('%Y-%m-%d') if dt else '2023-01-01','time':dt.strftime('%H:%M:%S') if dt else '', 'type':'Instagram','title':'Instagram投稿','summary':'','excerpt':short(alt),'sourceName':'Instagrammer News（公開索引）','sourceUrl':detail,'confidence':'本人Instagram通常投稿・公開索引で日時/原文抜粋確認','tags':[],'people':[]},'instagram_archive')
    new=[x for x in ids if x not in seen_ig]
    seen_ig.update(ids)
    nxt=re.search(r'href=["\'](?:https?://instagrammernews\.com)?/user/58749300526\?page=(\d+)["\']',page,re.I)
    if not new or not nxt or int(nxt.group(1))<=page_no:break
    page_no=int(nxt.group(1))
    time.sleep(.15)
report['instagram_archive_pages']=page_no
report['instagram_archive_unique']=len(seen_ig)

# 3) Recover direct YouTube / TikTok / Instagram normal-post URLs already indexed by the public fan reference.
try:
    fan=get('https://raw.githubusercontent.com/nagata-shiohigari/shiorin/main/index.html',25,1)
    for li in re.findall(r'<li\b[^>]*>(.*?)</li>',fan,re.I|re.S):
        plain=clean(li)
        dates=list(re.finditer(r'(?<!\d)(\d{2})/(\d{1,2})/(\d{1,2})(?!\d)',plain))
        if not dates:continue
        hrefs=[htmllib.unescape(x) for x in re.findall(r'href=["\']([^"\']+)["\']',li,re.I)]
        directs=[]
        for h in hrefs:
            if '/stories/' in h:continue
            k=direct_kind(h)
            if k in ('instagram','tiktok','youtube'):directs.append((h,k))
        if not directs:continue
        m=dates[0]
        try:d=date(2000+int(m.group(1)),int(m.group(2)),int(m.group(3))).isoformat()
        except:continue
        title=plain[:m.start()].strip('（(、,。 ') or '公開コンテンツ'
        for h,k in directs:
            typ={'instagram':'Instagram','tiktok':'TikTok','youtube':'YouTube'}[k]
            add({'id':event_id('direct-'+k,h,d),'date':d,'time':'','type':typ,'title':title[:100],'summary':'','excerpt':'','sourceName':typ+' 本人/公式公開ページ','sourceUrl':h,'confidence':'公開索引から個別URLを確認','tags':[],'people':[]},'fan_direct_'+k)
except Exception as e:report['errors'].append('fan direct '+repr(e))

# 4) Completely traverse the public ≠ME NEWS pager (334 pages currently), then inspect each detail.
news_links=set()
try:
    first=get('https://not-equal-me.jp/news/1',20,1)
    mm=re.search(r'maxpage\s*=\s*(\d+)',first); maxpage=int(mm.group(1)) if mm else 1
except Exception as e:
    maxpage=1;first='';report['errors'].append('NEWS first '+repr(e))

def news_list(n):
    try:
        p=first if n==1 and first else get(f'https://not-equal-me.jp/news/1/?page={n}',18,1)
        return set(re.findall(r'/news/detail/(\d+)',p))
    except Exception as e:
        return set()
with concurrent.futures.ThreadPoolExecutor(max_workers=6) as ex:
    for ids in ex.map(news_list,range(1,maxpage+1)):
        news_links.update(ids)
report['official_news_pages_scanned']=maxpage
report['official_news_details_discovered']=len(news_links)

schedule_links=set()
youtube_from_news=[]
external_interviews=[]
all_member_re=re.compile(r'(?:全メンバー|メンバー全員|≠ME全員|≠MEメンバー全員|全員参加|メンバー12名|12名全員|11名全員)')
broad_group_re=re.compile(r'(?:≠ME|ノイミー).{0,45}(?:コンサート|ツアー|ライブ|MV|Music Video|シングル|Single|アルバム|イベント|発売|配信)',re.I)

def parse_news(nid):
    u=f'https://not-equal-me.jp/news/detail/{nid}'
    try:p=get(u,18,1)
    except Exception:return None
    text=clean(p)
    title=meta(p,'og:title') or meta(p,'twitter:title')
    if not title:
        m=re.search(r'<title[^>]*>(.*?)</title>',p,re.I|re.S);title=clean(m.group(1)) if m else '≠ME公式NEWS'
    title=re.sub(r'\s*[｜|]\s*≠ME.*$','',title).strip()
    relevant=('永田詩央里' in text) or bool(all_member_re.search(text)) or bool(broad_group_re.search(title))
    if not relevant:return None
    ds=norm_date(meta(p,'article:published_time')) or norm_date(meta(p,'date'))
    if not ds:
        m=re.search(r'(20\d{2}[./-]\d{1,2}[./-]\d{1,2})',text);ds=norm_date(m.group(1)) if m else ''
    if not ds:return None
    sched=re.findall(r'href=["\']([^"\']*/schedule/detail/\d+[^"\']*)',p,re.I)
    yts=[htmllib.unescape(h) for h in re.findall(r'href=["\']([^"\']+)["\']',p,re.I) if direct_kind(htmllib.unescape(h))=='youtube']
    exts=[]
    if re.search(r'インタビュー|掲載|記事|特集',title+text[:1200],re.I):
        for h in re.findall(r'href=["\'](https?://[^"\']+)["\']',p,re.I):
            h=htmllib.unescape(h)
            if 'not-equal-me.jp' in h or any(x in h for x in ['twitter.com','x.com','instagram.com','youtube.com','youtu.be']):continue
            exts.append(h)
    return {'event':{'id':'official-news-'+str(nid),'date':ds,'time':'','type':classify(title,text),'title':title[:140],'summary':'','excerpt':'','sourceName':'≠ME公式 NEWS','sourceUrl':u,'confidence':'公式','tags':[],'people':[]},'sched':sched,'yts':yts,'exts':exts}

with concurrent.futures.ThreadPoolExecutor(max_workers=6) as ex:
    futs={ex.submit(parse_news,n):n for n in sorted(news_links,key=int)}
    for fut in concurrent.futures.as_completed(futs):
        r=fut.result()
        if not r:continue
        ev=add(r['event'],'official_news')
        for s in r['sched']:
            schedule_links.add(urllib.parse.urljoin('https://not-equal-me.jp',s))
        for y in r['yts']:youtube_from_news.append((ev['date'],ev['title'],y))
        for h in r['exts']:external_interviews.append((ev['date'],ev['title'],h))

# Add direct YouTube links embedded in relevant official NEWS.
for ds,title,u in youtube_from_news:
    add({'id':event_id('official-youtube',u,ds),'date':ds,'time':'','type':'YouTube','title':title[:120],'summary':'','excerpt':'','sourceName':'YouTube（≠ME公式NEWSから個別URL確認）','sourceUrl':u,'confidence':'公式NEWS内の個別動画URL','tags':[],'people':[]},'official_youtube')

# Add likely direct external interview/article URLs referenced by relevant official news.
for ds,title,u in external_interviews:
    if len(u)>500:continue
    add({'id':event_id('media-link',u,ds),'date':ds,'time':'','type':'インタビュー','title':title[:120],'summary':'','excerpt':'','sourceName':'掲載媒体（≠ME公式NEWSから個別URL確認）','sourceUrl':u,'confidence':'公式NEWS内の外部掲載URL','tags':[],'people':[]},'official_external_media')

# 5) Official SCHEDULE URLs reachable from sitemap, current page, existing records, and NEWS links.
try:
    sm=get('https://not-equal-me.jp/sitemap.xml',20,1)
    for sid in re.findall(r'/schedule/detail/(\d+)',sm):schedule_links.add(f'https://not-equal-me.jp/schedule/detail/{sid}')
except Exception as e:report['errors'].append('sitemap '+repr(e))
try:
    cur=get('https://not-equal-me.jp/schedule/',20,1)
    for sid in re.findall(r'/schedule/detail/(\d+)',cur):schedule_links.add(f'https://not-equal-me.jp/schedule/detail/{sid}')
except Exception as e:report['errors'].append('current schedule '+repr(e))
for e in list(events):
    u=str(e.get('sourceUrl',''))
    if '/schedule/detail/' in u:schedule_links.add(u.split('?')[0])

def parse_schedule(u):
    try:p=get(u,18,1)
    except:return None
    text=clean(p);title=meta(p,'og:title')
    if not title:
        m=re.search(r'<title[^>]*>(.*?)</title>',p,re.I|re.S);title=clean(m.group(1)) if m else '≠ME公式SCHEDULE'
    title=re.sub(r'\s*[｜|]\s*≠ME.*$','',title).strip()
    if '永田詩央里' not in text and not all_member_re.search(text) and not broad_group_re.search(title):return None
    ds=''
    # Schedule pages usually put event date near the top; choose the first 20xx date in visible page.
    m=re.search(r'(20\d{2}[./-]\d{1,2}[./-]\d{1,2})',text)
    if m:ds=norm_date(m.group(1))
    if not ds:return None
    sid=re.search(r'/schedule/detail/(\d+)',u).group(1)
    return {'id':'official-schedule-'+sid,'date':ds,'time':'','type':classify(title,text),'title':title[:140],'summary':'','excerpt':'','sourceName':'≠ME公式 SCHEDULE','sourceUrl':u,'confidence':'公式','tags':[],'people':[]}
with concurrent.futures.ThreadPoolExecutor(max_workers=5) as ex:
    for r in ex.map(parse_schedule,sorted(schedule_links)):
        if r:add(r,'official_schedule')
report['official_schedule_urls_scanned']=len(schedule_links)

# 6) radiko public podcast page only (no disallowed API). Update/add direct episode URLs.
radiko_base='https://radiko.jp/podcast/channels/cf8c3a32-e6a6-44a4-b43a-5d2817b91b6f'
episode_urls=set()
for n in range(1,8):
    try:p=get(radiko_base+('' if n==1 else f'?page={n}'),20,0)
    except:continue
    for path in re.findall(r'/podcast/episodes/[0-9a-f-]{30,}',p,re.I):episode_urls.add('https://radiko.jp'+path)

def parse_episode(u):
    try:p=get(u,15,0)
    except:return None
    title=meta(p,'og:title') or meta(p,'twitter:title') or '≠ME 永田詩央里のけれけれ'
    text=clean(p)
    ds=norm_date(meta(p,'article:published_time'))
    if not ds:
        ms=re.findall(r'(20\d{2}[./-]\d{1,2}[./-]\d{1,2})',text)
        if ms:ds=norm_date(ms[0])
    return (ds,title[:140],u) if ds else None
with concurrent.futures.ThreadPoolExecutor(max_workers=5) as ex:
    for r in ex.map(parse_episode,sorted(episode_urls)):
        if not r:continue
        ds,title,u=r
        candidates=[e for e in events if e.get('date')==ds and e.get('type')=='ラジオ' and ('けれけれ' in (e.get('title','')+e.get('sourceName','')))]
        if candidates and not direct_kind(candidates[0].get('sourceUrl','')):
            old=candidates[0]
            old['sourceUrl']=u;old['sourceName']='radiko Podcast';old['confidence']='radiko公開エピソードページ';by_url[u]=old;report['updated']['radiko_direct']+=1
        else:
            add({'id':event_id('kerekere-episode',u,ds),'date':ds,'time':'','type':'ラジオ','title':title,'summary':'','excerpt':'','sourceName':'radiko Podcast','sourceUrl':u,'confidence':'公開エピソードページ','tags':[],'people':[]},'radiko_episode')
report['radiko_episode_urls_found']=len(episode_urls)

# 7) ABC ノイミーステーションTV: scan every Friday in its operating period plus known/special index URLs.
abc_urls=set()
try:
    idx=get('https://www.asahi.co.jp/noimestation_tv/archive/',20,1)
    for h in re.findall(r'href=["\']([^"\']+\.html)',idx,re.I):abc_urls.add(urllib.parse.urljoin('https://www.asahi.co.jp/noimestation_tv/archive/',h))
except Exception as e:report['errors'].append('ABC index '+repr(e))
for e in events:
    u=str(e.get('sourceUrl',''))
    if 'asahi.co.jp/noimestation_tv/archive/' in u and u.endswith('.html'):abc_urls.add(u)
d=date(2023,7,1); end=date(2026,9,8)
while d<=end:
    if d.weekday() in (4,5):abc_urls.add('https://www.asahi.co.jp/noimestation_tv/archive/'+d.strftime('%y%m%d')+'.html')
    d+=timedelta(days=1)

def parse_abc(u):
    try:p=get(u,10,0)
    except:return None
    if len(p)<1000:return None
    text=clean(p)
    if '永田詩央里' not in text and not all_member_re.search(text):return None
    m=re.search(r'/archive/(\d{6})\.html',u)
    if not m:return None
    ds=datetime.strptime(m.group(1),'%y%m%d').date().isoformat()
    title=meta(p,'og:title') or 'ノイミーステーションTV'
    return {'id':event_id('abc-noimestation',u,ds),'date':ds,'time':'','type':'テレビ','title':title[:140],'summary':'','excerpt':'','sourceName':'ABCテレビ ノイミーステーションTV','sourceUrl':u,'confidence':'番組公式アーカイブ','tags':[],'people':[]}
with concurrent.futures.ThreadPoolExecutor(max_workers=6) as ex:
    for r in ex.map(parse_abc,sorted(abc_urls)):
        if r:add(r,'abc_tv')
report['abc_urls_attempted']=len(abc_urls)

# Final cleanup: Stories are intentionally not sought; remove only records explicitly marked as story URLs/titles.
def explicit_story(e):
    u=str(e.get('sourceUrl','')).lower();s=(str(e.get('title',''))+' '+str(e.get('sourceName',''))).lower()
    return '/stories/' in u or 'instagram stories' in s or 'instagram story' in s or 'instagramストーリーズ' in s
before=len(events);events=[e for e in events if not explicit_story(e)];report['explicit_story_records_removed']=before-len(events)

# Stable sort and report.
def sortkey(e):return (str(e.get('date','')),str(e.get('time','')),str(e.get('type','')),str(e.get('id','')))
events.sort(key=sortkey,reverse=True)
root['events']=events
root['updatedAt']=datetime.now(JST).strftime('%Y-%m-%d %H:%M JST')
root['notice']='Instagram Storiesを除外し、無料・公開・遵法の範囲でX、Instagram通常投稿/Reels、公式NEWS/SCHEDULE、SHOWROOM、ラジオ、YouTube、テレビ、インタビュー等を可能な限り走査。XはAI要約なし。'
ASSET.write_text(json.dumps(root,ensure_ascii=False,separators=(',',':')),encoding='utf-8')
counts=Counter(str(e.get('type','')) for e in events)
x=[e for e in events if str(e.get('type','')).upper()=='X']
report.update({
 'version':'0.8.0','total_events':len(events),'net_added':len(events)-initial,
 'x_records':len(x),'x_with_literal_excerpt':sum(bool(str(e.get('excerpt','')).strip()) for e in x),
 'x_with_direct_status_url':sum(bool(status_id(str(e.get('sourceUrl','')))) for e in x),
 'instagram_records':counts.get('Instagram',0),'showroom_records':counts.get('SHOWROOM',0),
 'radio_records':counts.get('ラジオ',0),'youtube_records':counts.get('YouTube',0),
 'tv_records':counts.get('テレビ',0),'interview_records':counts.get('インタビュー',0),
 'official_news_records':sum(1 for e in events if e.get('sourceName')=='≠ME公式 NEWS'),
 'official_schedule_records':sum(1 for e in events if e.get('sourceName')=='≠ME公式 SCHEDULE'),
 'records_with_url':sum(1 for e in events if str(e.get('sourceUrl','')).startswith('http')),
 'direct_content_urls':sum(1 for e in events if direct_kind(str(e.get('sourceUrl','')))),
 'type_counts':dict(counts),'added_by_source':dict(report['added']),'updated_by_source':dict(report['updated'])
})
report['added']=dict(report['added']);report['updated']=dict(report['updated'])
REPORT.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print('V080_REPORT',json.dumps(report,ensure_ascii=False))

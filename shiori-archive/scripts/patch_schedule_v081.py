#!/usr/bin/env python3
import concurrent.futures
import html as htmllib
import json
import re
import time
import urllib.parse
import urllib.request
from collections import Counter
from datetime import date
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
ASSET=ROOT/'app/src/main/assets/archive_seed.json'
REPORT=ROOT/'app/src/main/assets/archive_v081_report.json'
UA={'User-Agent':'Mozilla/5.0 ShioriArchive/0.8.1 (+public official schedule backfill)'}


def get(url,timeout=12,retries=1):
    last=None
    for i in range(retries+1):
        try:
            req=urllib.request.Request(url,headers=UA)
            with urllib.request.urlopen(req,timeout=timeout) as r:
                return r.read().decode('utf-8','replace')
        except Exception as e:
            last=e
            if i<retries: time.sleep(.35*(i+1))
    raise last


def clean(text):
    text=re.sub(r'<br\s*/?>','\n',text or '',flags=re.I)
    text=re.sub(r'<[^>]+>','',text)
    text=htmllib.unescape(text)
    text=re.sub(r'[ \t]+',' ',text)
    text=re.sub(r'\n{3,}','\n\n',text)
    return text.strip()


def meta(page,key):
    for pat in [
        rf'<meta[^>]+(?:property|name)=["\']{re.escape(key)}["\'][^>]+content=["\']([^"\']*)',
        rf'<meta[^>]+content=["\']([^"\']*)["\'][^>]+(?:property|name)=["\']{re.escape(key)}["\']'
    ]:
        m=re.search(pat,page,re.I|re.S)
        if m:return htmllib.unescape(m.group(1)).strip()
    return ''


def norm_date(s):
    if not s:return ''
    m=re.search(r'(20\d{2})[./年\-](\d{1,2})[./月\-](\d{1,2})',s)
    if not m:return ''
    try:return date(int(m.group(1)),int(m.group(2)),int(m.group(3))).isoformat()
    except:return ''


def event_date(text):
    # Official detail header examples: "08.23 SUN.2026". Prefer this over dates in the body.
    m=re.search(r'(?<!\d)(\d{1,2})[./](\d{1,2})\s*[A-Z]{3}\.?\s*(20\d{2})',text,re.I)
    if m:
        try:return date(int(m.group(3)),int(m.group(1)),int(m.group(2))).isoformat()
        except:pass
    # Japanese and ISO-ish dates used by other generations of the site.
    m=re.search(r'(20\d{2}(?:[./-]\d{1,2}[./-]\d{1,2}|年\d{1,2}月\d{1,2}日?))',text)
    return norm_date(m.group(1)) if m else ''


def title_from(page):
    title=meta(page,'og:title') or meta(page,'twitter:title')
    generic=(not title) or re.match(r'^\s*SCHEDULE\b',title,re.I)
    if generic:
        candidates=[]
        for tag in ('h1','h2','h3','h4'):
            for raw in re.findall(rf'<{tag}\b[^>]*>(.*?)</{tag}>',page,re.I|re.S):
                t=clean(raw)
                if t and t.upper()!='SCHEDULE' and 'ノットイコールミー' not in t:
                    candidates.append(t)
        if candidates:title=max(candidates,key=len)
    if not title:
        m=re.search(r'<title[^>]*>(.*?)</title>',page,re.I|re.S)
        title=clean(m.group(1)) if m else '≠ME公式SCHEDULE'
    title=re.sub(r'\s*[｜|]\s*≠ME.*$','',title).strip()
    return title[:140] or '≠ME公式SCHEDULE'


def relevant(title,text):
    if '永田詩央里' in text:return True
    if re.search(r'(?:全メンバー|メンバー全員|≠ME全員|≠MEメンバー全員|12名全員|11名全員)',text):return True
    if re.search(r'(?:※\s*≠ME|出演.{0,30}≠ME|出演メンバー.{0,30}≠ME)',text,re.I):return True
    if re.search(r'≠ME.{0,50}(?:コンサート|ツアー|ライブ|イベント|お話し会|握手会|撮影会)',title,re.I):return True
    return False


def classify(title,text):
    s=(title+' '+text[:1000]).lower()
    if any(x in s for x in ['ラジオ','radio','けれけれ','ノイミーステーション']):return 'ラジオ'
    if any(x in s for x in ['テレビ','tv','tver','めざまし']):return 'テレビ'
    if any(x in s for x in ['youtube','mv','music video']):return 'YouTube'
    if any(x in s for x in ['インタビュー','掲載','web記事']):return 'インタビュー'
    if any(x in s for x in ['single','アルバム','発売','リリース','配信開始']):return 'リリース'
    return 'イベント'


root=json.loads(ASSET.read_text(encoding='utf-8'))
events=list(root.get('events',[]))
initial=len(events)
by_id={str(e.get('id','')):e for e in events if e.get('id')}
by_url={str(e.get('sourceUrl','')).split('?')[0]:e for e in events if str(e.get('sourceUrl','')).startswith('http')}
report={'version':'0.8.1','base_events':initial,'schedule_links':0,'official_news_pages_checked':0,'added_schedule':0,'errors':[]}
links=set()

# Discover official schedule details from the sitemap and currently published schedule page.
for u in ['https://not-equal-me.jp/sitemap.xml','https://not-equal-me.jp/schedule/']:
    try:
        p=get(u,15,1)
        for sid in re.findall(r'/schedule/detail/(\d+)',p):
            links.add(f'https://not-equal-me.jp/schedule/detail/{sid}')
    except Exception as e:
        report['errors'].append(f'discovery {u} {e!r}')

# Keep anything already known in the archive.
for e in events:
    u=str(e.get('sourceUrl','')).split('?')[0]
    if '/schedule/detail/' in u:links.add(u)

# Relevant NEWS was already selected in v0.8.0. Revisit only those official NEWS pages to recover their linked schedules.
news_urls=[]
for e in events:
    u=str(e.get('sourceUrl',''))
    if re.search(r'https://not-equal-me\.jp/news/detail/\d+',u):news_urls.append(u.split('?')[0])
news_urls=sorted(set(news_urls))

def linked_schedules(u):
    try:
        p=get(u,9,0)
        out=[]
        for s in re.findall(r'href=["\']([^"\']*/schedule/detail/\d+[^"\']*)',p,re.I):
            out.append(urllib.parse.urljoin('https://not-equal-me.jp',htmllib.unescape(s)).split('?')[0])
        return out,None
    except Exception as e:
        return [],repr(e)

with concurrent.futures.ThreadPoolExecutor(max_workers=6) as ex:
    futs={ex.submit(linked_schedules,u):u for u in news_urls}
    for fut in concurrent.futures.as_completed(futs):
        out,err=fut.result()
        report['official_news_pages_checked']+=1
        links.update(out)
        if err and len(report['errors'])<20:report['errors'].append('news '+futs[fut]+' '+err)

report['schedule_links']=len(links)

def parse_schedule(u):
    try:p=get(u,12,1)
    except Exception as e:return None,repr(e)
    text=clean(p)
    title=title_from(p)
    if not relevant(title,text):return None,None
    ds=event_date(text)
    if not ds:return None,'date-not-found'
    m=re.search(r'/schedule/detail/(\d+)',u)
    if not m:return None,'id-not-found'
    sid=m.group(1)
    return {
        'id':'official-schedule-'+sid,
        'date':ds,
        'time':'',
        'type':classify(title,text),
        'title':title,
        'summary':'',
        'excerpt':'',
        'sourceName':'≠ME公式 SCHEDULE',
        'sourceUrl':u,
        'confidence':'公式',
        'tags':[],
        'people':[]
    },None

with concurrent.futures.ThreadPoolExecutor(max_workers=5) as ex:
    futs={ex.submit(parse_schedule,u):u for u in sorted(links)}
    for fut in concurrent.futures.as_completed(futs):
        ev,err=fut.result()
        if err and err!='date-not-found' and len(report['errors'])<40:
            report['errors'].append('schedule '+futs[fut]+' '+err)
        if not ev:continue
        key=ev['sourceUrl'].split('?')[0]
        if ev['id'] in by_id or key in by_url:continue
        events.append(ev);by_id[ev['id']]=ev;by_url[key]=ev
        report['added_schedule']+=1

# Final deterministic ordering.
events.sort(key=lambda e:(str(e.get('date','')),str(e.get('time','')),str(e.get('id',''))))
root['events']=events
root['updatedAt']='2026-09-08 12:30'
root['notice']='Instagram Storiesを除外し、公開情報を可能な範囲で全走査。個別URLを確認できる記録は個別URLを保持。'
ASSET.write_text(json.dumps(root,ensure_ascii=False,separators=(',',':')),encoding='utf-8')
report['total_events']=len(events)
report['net_added']=len(events)-initial
report['records_with_url']=sum(1 for e in events if str(e.get('sourceUrl','')).startswith('http'))
report['type_counts']=dict(Counter(str(e.get('type','')) for e in events))
REPORT.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print('V081_REPORT',json.dumps(report,ensure_ascii=False))

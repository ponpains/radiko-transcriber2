#!/usr/bin/env python3
import hashlib
import html
import json
import re
import time
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
ASSET=ROOT/'app/src/main/assets/archive_seed.json'
REPORT=ROOT/'app/src/main/assets/archive_v093_report.json'
JST=timezone(timedelta(hours=9))
X_EPOCH_MS=1288834974657
UA='Mozilla/5.0 ShioriArchive/0.9.3 (+public-archive-backfill)'
XURL_RE=re.compile(r'https?://(?:www\.)?(?:x|twitter)\.com/nagata_shiori_/(?:status|statuses)/(\d+)(?:\?[^\s<]*)?',re.I)

START_THREAD='https://talk.jp/boards/idol/1783248071'  # ≠ME★235, archived/public
MAX_THREADS=120


def request(url,timeout=15,retries=2):
    last=None
    for i in range(retries):
        try:
            req=urllib.request.Request(url,headers={'User-Agent':UA,'Accept-Language':'ja,en;q=0.8'})
            with urllib.request.urlopen(req,timeout=timeout) as r:
                return r.read().decode('utf-8','replace')
        except urllib.error.HTTPError as e:
            last=e
            if e.code in (429,503):
                wait=min(10*(2**i),45)
                time.sleep(wait)
                continue
            break
        except Exception as e:
            last=e
            time.sleep(min(3*(i+1),8))
    raise last


def x_time(sid):
    ms=(int(sid)>>22)+X_EPOCH_MS
    return datetime.fromtimestamp(ms/1000,timezone.utc).astimezone(JST)


def plain(body):
    s=re.sub(r'(?i)<br\s*/?>','\n',body)
    s=re.sub(r'(?i)</(?:p|div|li|article|section|blockquote|h[1-6])>','\n',s)
    s=re.sub(r'<[^>]+>','',s)
    s=html.unescape(s).replace('\r','')
    s=re.sub(r'\n[ \t]+','\n',s)
    s=re.sub(r'\n{3,}','\n\n',s)
    return s


def compact_text(s,n=180):
    s=html.unescape(s or '')
    lines=[]
    for line in s.splitlines():
        line=re.sub(r'\s+',' ',line).strip()
        if not line: continue
        if line.startswith(('Image:','討議資料・引用元','202','午後','午前')): continue
        if re.fullmatch(r'[0-9.,K万]+',line): continue
        lines.append(line)
    out=' '.join(lines)
    out=re.sub(r'https?://(?:x|twitter)\.com/nagata_shiori_/status/\d+\S*','',out,flags=re.I)
    return re.sub(r'\s+',' ',out).strip()[:n]


def current_sid_map(events):
    out={}
    for e in events:
        if e.get('type')!='X': continue
        m=XURL_RE.search(e.get('sourceUrl','') or '')
        if m: out[m.group(1)]=e
    return out


def add_status(events,bysid,sid,text,thread_url,rep):
    if not sid.isdigit(): return
    dt=x_time(sid)
    if dt.year<2019 or dt>datetime.now(JST)+timedelta(days=2): return
    text=compact_text(text)
    url=f'https://x.com/nagata_shiori_/status/{sid}'
    if sid in bysid:
        e=bysid[sid]
        changed=False
        if text and not (e.get('excerpt') or '').strip():
            e['excerpt']=text;changed=True
        if e.get('sourceUrl')!=url:
            e['sourceUrl']=url;changed=True
        if changed: rep['status_upgraded']+=1
        return
    e={'id':'x-'+sid,'date':dt.strftime('%Y-%m-%d'),'time':dt.strftime('%H:%M:%S'),'type':'X','title':'X投稿','summary':'','excerpt':text,'sourceName':'X @nagata_shiori_','sourceUrl':url,'confidence':'公開引用ログでstatus IDを確認','tags':[],'people':[]}
    events.append(e);bysid[sid]=e;rep['status_added']+=1


def norm(s):
    s=html.unescape(s or '').lower()
    s=re.sub(r'https?://\S+','',s)
    s=re.sub(r'[\s#＿_・、。,.!！?？~〜～「」『』()（）\[\]]+','',s)
    return s[:220]


def parse_jp_datetime(s):
    m=re.search(r'(午前|午後)\s*(\d{1,2}):(\d{2})\s*[·・]\s*(20\d{2})年(\d{1,2})月(\d{1,2})日',s)
    if not m:return None
    ap,h,mi,y,mo,d=m.groups(); h=int(h);mi=int(mi)
    if ap=='午後' and h<12:h+=12
    if ap=='午前' and h==12:h=0
    try:return datetime(int(y),int(mo),int(d),h,mi,tzinfo=JST)
    except:return None


def add_idless(events,text,dt,thread_url,rep):
    text=compact_text(text)
    if len(norm(text))<3:return
    date=dt.strftime('%Y-%m-%d'); tm=dt.strftime('%H:%M')
    n=norm(text)
    for e in events:
        if e.get('type')!='X' or e.get('date')!=date: continue
        en=norm(e.get('excerpt') or e.get('title') or '')
        if n and en and (n==en or (len(n)>=12 and (n in en or en in n))): return
    key=f'{date}|{tm}|{n}'
    hid=hashlib.sha1(key.encode('utf-8')).hexdigest()[:16]
    if any(e.get('id')=='x-quoted-'+hid for e in events):return
    events.append({'id':'x-quoted-'+hid,'date':date,'time':tm,'type':'X','title':'X投稿（引用記録）','summary':'','excerpt':text,'sourceName':'X公開引用ログ（Talk）','sourceUrl':thread_url,'confidence':'公開引用ログで本人X本文・日時を確認／元status ID未確認','tags':[],'people':[]})
    rep['idless_added']+=1


def extract_posts(body,thread_url,events,bysid,rep):
    p=plain(body)
    for m in XURL_RE.finditer(p):
        sid=m.group(1)
        before=p[max(0,m.start()-2500):m.start()]
        pos=max(before.rfind('@nagata_shiori_'),before.rfind('永田 詩央里'))
        if pos<0: continue
        text=before[pos:]
        text=re.sub(r'^.*?@nagata_shiori_\s*','',text,flags=re.S)
        parts=[x.strip() for x in re.split(r'\n\s*\n',text) if x.strip()]
        if parts:text=parts[-1]
        add_status(events,bysid,sid,text,thread_url,rep)
        rep['status_refs_seen']+=1

    marker='@nagata_shiori_'
    starts=[m.start() for m in re.finditer(re.escape(marker),p)]
    for st in starts:
        block=p[st:st+1800]
        nxt=block.find(marker,len(marker))
        if nxt>0:block=block[:nxt]
        if XURL_RE.search(block):continue
        dt=parse_jp_datetime(block)
        if not dt:continue
        tpart=re.split(r'(?:午前|午後)\s*\d{1,2}:\d{2}\s*[·・]\s*20\d{2}年',block,1)[0]
        tpart=tpart[len(marker):]
        add_idless(events,tpart,dt,thread_url,rep)


def find_prev(body,current_url):
    m=re.search(r'前スレ.{0,500}?href=["\']([^"\']*/boards/idol/\d+)["\']',body,re.S|re.I)
    if m:
        u=m.group(1)
        if u.startswith('/'):u='https://talk.jp'+u
        if u.startswith('http'):return u.split('?')[0]
    links=re.findall(r'href=["\'](https?://talk\.jp/boards/idol/\d+|/boards/idol/\d+)["\']',body,re.I)
    for u in links:
        if u.startswith('/'):u='https://talk.jp'+u
        if u.split('?')[0]!=current_url.split('?')[0]:return u.split('?')[0]
    return None


def main():
    root=json.loads(ASSET.read_text(encoding='utf-8'));events=root['events'];bysid=current_sid_map(events)
    rep={'version':'0.9.3','base_events':len(events),'base_x':sum(e.get('type')=='X' for e in events),'threads_fetched':0,'status_refs_seen':0,'status_added':0,'status_upgraded':0,'idless_added':0,'errors':[]}
    url=START_THREAD;seen=set()
    for i in range(MAX_THREADS):
        if not url or url in seen:break
        seen.add(url)
        try:body=request(url)
        except Exception as e:
            rep['errors'].append(f'{url}: {e!r}');break
        rep['threads_fetched']+=1
        extract_posts(body,url,events,bysid,rep)
        prev=find_prev(body,url)
        if not prev:break
        url=prev
        time.sleep(0.15)

    status_keys=set()
    for e in events:
        if e.get('type')=='X' and XURL_RE.search(e.get('sourceUrl','') or ''):
            status_keys.add((e.get('date'),norm(e.get('excerpt') or e.get('title') or '')))
    out=[];seen_q=set();removed=0
    for e in events:
        if e.get('type')=='X' and str(e.get('id','')).startswith('x-quoted-'):
            k=(e.get('date'),norm(e.get('excerpt') or ''))
            if k in status_keys or k in seen_q:
                removed+=1;continue
            seen_q.add(k)
        out.append(e)
    events=out
    rep['idless_removed_as_duplicate']=removed
    root['events']=sorted(events,key=lambda e:(e.get('date',''),e.get('time',''),e.get('id','')))
    root['updatedAt']='2026-09-08'
    ASSET.write_text(json.dumps(root,ensure_ascii=False,separators=(',',':')),encoding='utf-8')
    x=[e for e in events if e.get('type')=='X']
    rep['total_events']=len(events);rep['x_records']=len(x);rep['x_direct_status']=sum(bool(XURL_RE.search(e.get('sourceUrl','') or '')) for e in x);rep['x_content_only']=sum(str(e.get('id','')).startswith('x-quoted-') for e in x);rep['x_with_excerpt']=sum(bool((e.get('excerpt') or '').strip()) for e in x)
    REPORT.write_text(json.dumps(rep,ensure_ascii=False,indent=2),encoding='utf-8')
    print('V093_REPORT',json.dumps(rep,ensure_ascii=False))

if __name__=='__main__':main()

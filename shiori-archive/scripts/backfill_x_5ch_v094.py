#!/usr/bin/env python3
import hashlib,html,json,re,time,urllib.error,urllib.parse,urllib.request
from datetime import datetime,timedelta,timezone
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
ASSET=ROOT/'app/src/main/assets/archive_seed.json'
REPORT=ROOT/'app/src/main/assets/archive_v094_5ch_report.json'
JST=timezone(timedelta(hours=9)); X_EPOCH_MS=1288834974657
UA='Mozilla/5.0 ShioriArchive/0.9.4 (+public-5ch-archive-backfill)'
XURL_RE=re.compile(r'https?://(?:www\.)?(?:x|twitter)\.com/nagata_shiori_/(?:status|statuses)/(\d{15,22})',re.I)

STARTS=[
 'https://fate.5ch.io/test/read.cgi/idol/1683810367/', # ≠ME★121, May 2023
 'https://fate.5ch.io/test/read.cgi/idol/1637151879/', # ≠ME★68, Nov 2021 fallback
 'https://kako.5ch.io/test/read.cgi/akb/1610824933/', # ≠ME★46.3, Jan 2021 fallback
 'https://kako.5ch.io/test/read.cgi/akb/1608406353/', # ≠ME★46.1, Dec 2020 fallback
]
MAX_TOTAL_THREADS=150


def request(url,timeout=25,retries=3):
    last=None
    for i in range(retries):
        try:
            req=urllib.request.Request(url,headers={'User-Agent':UA,'Accept-Language':'ja,en;q=0.8'})
            with urllib.request.urlopen(req,timeout=timeout) as r:
                raw=r.read()
                enc=r.headers.get_content_charset() or 'utf-8'
                try:return raw.decode(enc,'replace')
                except:return raw.decode('utf-8','replace')
        except urllib.error.HTTPError as e:
            last=e
            if e.code in (429,503): time.sleep(min(12*(i+1),40)); continue
            break
        except Exception as e:
            last=e; time.sleep(min(4*(i+1),12))
    raise last


def x_time(sid):
    ms=(int(sid)>>22)+X_EPOCH_MS
    return datetime.fromtimestamp(ms/1000,timezone.utc).astimezone(JST)


def plain(body):
    s=re.sub(r'(?i)<br\s*/?>','\n',body)
    s=re.sub(r'(?i)</(?:p|div|li|article|section|blockquote|h[1-6]|dt|dd)>','\n',s)
    s=re.sub(r'<[^>]+>','',s)
    s=html.unescape(s).replace('\r','')
    s=re.sub(r'\n[ \t]+','\n',s);s=re.sub(r'\n{3,}','\n\n',s)
    return s


def norm(s):
    s=html.unescape(s or '').lower()
    s=re.sub(r'https?://\S+','',s)
    s=re.sub(r'[\s#＿_・、。,.!！?？~〜～「」『』()（）\[\]<>]+','',s)
    return s[:260]


def compact(s,n=180):
    s=html.unescape(s or '')
    s=re.sub(r'https?://(?:pbs\.twimg\.com|video\.twimg\.com|t\.co)/\S+','',s)
    s=re.sub(r'https?://(?:x|twitter)\.com/nagata_shiori_/status/\d+\S*','',s,flags=re.I)
    lines=[]
    for z in s.splitlines():
        z=re.sub(r'\s+',' ',z).strip()
        if not z: continue
        if z.startswith(('Image:','討議資料・引用元','垢版','引用ツイート')): continue
        if re.match(r'^(午前|午後)\s*\d',z): continue
        lines.append(z)
    return re.sub(r'\s+',' ',' '.join(lines)).strip()[:n]


def parse_dt(block):
    pats=[
      r'(午前|午後)\s*(\d{1,2}):(\d{2})\s*(?:[·・]|&#183;)?\s*(20\d{2})年(\d{1,2})月(\d{1,2})日',
      r'(20\d{2})年(\d{1,2})月(\d{1,2})日\s*(午前|午後)?\s*(\d{1,2}):(\d{2})'
    ]
    m=re.search(pats[0],block)
    if m:
        ap,h,mi,y,mo,d=m.groups();h=int(h);mi=int(mi)
        if ap=='午後' and h<12:h+=12
        if ap=='午前' and h==12:h=0
        try:return datetime(int(y),int(mo),int(d),h,mi,tzinfo=JST)
        except:return None
    m=re.search(pats[1],block)
    if m:
        y,mo,d,ap,h,mi=m.groups();h=int(h);mi=int(mi)
        if ap=='午後' and h<12:h+=12
        if ap=='午前' and h==12:h=0
        try:return datetime(int(y),int(mo),int(d),h,mi,tzinfo=JST)
        except:return None
    return None


def secs(t):
    try:
        p=[int(x) for x in (t or '').split(':')];return p[0]*3600+p[1]*60+(p[2] if len(p)>2 else 0)
    except:return None


def direct_map(events):
    out={}
    for e in events:
        if e.get('type')!='X':continue
        m=XURL_RE.search(e.get('sourceUrl','') or '')
        if m:out[m.group(1)]=e
    return out


def text_duplicate(events,date,text):
    n=norm(text)
    if len(n)<4:return True
    for e in events:
        if e.get('type')!='X' or e.get('date')!=date:continue
        en=norm(e.get('excerpt') or e.get('title') or '')
        if not en:continue
        if n==en:return True
        if min(len(n),len(en))>=14 and (n in en or en in n):return True
    return False


def maybe_upgrade_near_direct(events,dt,text,rep):
    n=norm(text)
    cand=[]
    for e in events:
        if e.get('type')!='X' or e.get('date')!=dt.strftime('%Y-%m-%d'):continue
        if not XURL_RE.search(e.get('sourceUrl','') or ''):continue
        s=secs(e.get('time',''))
        if s is None:continue
        diff=abs(s-(dt.hour*3600+dt.minute*60+dt.second))
        if diff<=75:cand.append((diff,e))
    if len(cand)==1:
        e=cand[0][1]
        if not (e.get('excerpt') or '').strip():
            e['excerpt']=compact(text);rep['direct_excerpt_upgraded']+=1;return True
        en=norm(e.get('excerpt') or '')
        if n and en and (n==en or (min(len(n),len(en))>=14 and (n in en or en in n))):return True
    return False


def add_direct(events,bysid,sid,text,thread,rep):
    if not sid.isdigit():return
    dt=x_time(sid)
    if dt.year<2019 or dt>datetime.now(JST)+timedelta(days=2):return
    t=compact(text);url=f'https://x.com/nagata_shiori_/status/{sid}'
    if sid in bysid:
        e=bysid[sid]
        if t and not (e.get('excerpt') or '').strip():e['excerpt']=t;rep['direct_excerpt_upgraded']+=1
        return
    e={'id':'x-'+sid,'date':dt.strftime('%Y-%m-%d'),'time':dt.strftime('%H:%M:%S'),'type':'X','title':'X投稿','summary':'','excerpt':t,'sourceName':'X @nagata_shiori_','sourceUrl':url,'confidence':'5ch公開過去ログで本人Xのstatus IDを確認','tags':[],'people':[]}
    events.append(e);bysid[sid]=e;rep['direct_added']+=1


def add_idless(events,text,dt,thread,rep):
    t=compact(text); n=norm(t)
    if len(n)<4:return
    date=dt.strftime('%Y-%m-%d')
    if maybe_upgrade_near_direct(events,dt,t,rep):return
    if text_duplicate(events,date,t):return
    key=f'{date}|{dt.strftime("%H:%M")}|{n}'
    hid=hashlib.sha1(key.encode()).hexdigest()[:18]
    iid='x-quoted-5ch-'+hid
    if any(e.get('id')==iid for e in events):return
    events.append({'id':iid,'date':date,'time':dt.strftime('%H:%M'),'type':'X','title':'X投稿（引用記録）','summary':'','excerpt':t,'sourceName':'X公開引用ログ（5ch過去ログ）','sourceUrl':thread,'confidence':'5ch公開過去ログで本人X本文・投稿日時を確認／status ID未確認','tags':[],'people':[]})
    rep['content_only_added']+=1


def extract(body,thread,events,bysid,rep):
    p=plain(body)
    # Direct status URLs first: use nearby account block and text when available.
    for m in XURL_RE.finditer(p):
        sid=m.group(1); before=p[max(0,m.start()-2200):m.start()]
        pos=max(before.rfind('@nagata_shiori_'),before.rfind('永田 詩央里'))
        if pos<0:continue
        text=before[pos:]
        if '@nagata_shiori_' in text:text=text.split('@nagata_shiori_',1)[1]
        text=text.split('引用ツイート',1)[0]
        add_direct(events,bysid,sid,text,thread,rep);rep['status_refs_seen']+=1

    # Content-only embeds/quotes. We accept only blocks with account name + absolute tweet datetime.
    for m in re.finditer(r'@nagata_shiori_',p,re.I):
        lead=p[max(0,m.start()-80):m.start()]
        if '永田' not in lead:continue
        block=p[m.end():m.end()+2300]
        # avoid swallowing the following account occurrence
        nxt=re.search(r'\n(?:[一-龯ぁ-んァ-ヶA-Za-z ]{1,30})\n@[A-Za-z0-9_]{2,20}',block)
        if nxt and nxt.start()>80:block=block[:nxt.start()]
        dt=parse_dt(block)
        if not dt or dt.year<2019 or dt>datetime.now(JST)+timedelta(days=2):continue
        # If block contains a direct status URL, it was handled above.
        dm=XURL_RE.search(block)
        raw=block
        # parent tweet text is before quoted-tweet marker; timestamp can remain later.
        raw=raw.split('引用ツイート',1)[0]
        # cut at explicit absolute timestamp if it appears before quote marker
        raw=re.split(r'(?:午前|午後)\s*\d{1,2}:\d{2}\s*(?:[·・]|&#183;)?\s*20\d{2}年',raw,1)[0]
        t=compact(raw)
        if not t:continue
        if dm:
            add_direct(events,bysid,dm.group(1),t,thread,rep)
        else:
            add_idless(events,t,dt,thread,rep)


def normalize_thread_url(u):
    if not u:return None
    u=html.unescape(u).strip().rstrip(').,]')
    if u.startswith('//'):u='https:'+u
    if u.startswith('/'):return None
    m=re.search(r'https?://[^/]+/test/read\.cgi/(idol|akb)/(\d+)',u,re.I)
    if not m:return None
    board,tid=m.groups()
    if board.lower()=='idol':return f'https://fate.5ch.io/test/read.cgi/idol/{tid}/'
    return f'https://kako.5ch.io/test/read.cgi/akb/{tid}/'


def prev_links(body):
    out=[]
    # Prefer links close to 前スレ, then any ≠ME previous-thread URLs in the page.
    for mm in re.finditer(r'前スレ',body,re.I):
        seg=body[mm.start():mm.start()+1800]
        for u in re.findall(r'https?://[^\s"\'<>]+/test/read\.cgi/(?:idol|akb)/\d+/?',seg,re.I):
            n=normalize_thread_url(u)
            if n and n not in out:out.append(n)
    if not out:
        for u in re.findall(r'https?://[^\s"\'<>]+/test/read\.cgi/(?:idol|akb)/\d+/?',body,re.I):
            n=normalize_thread_url(u)
            if n and n not in out:out.append(n)
    return out


def main():
    root=json.loads(ASSET.read_text(encoding='utf-8'));events=root['events'];bysid=direct_map(events)
    rep={'version':'0.9.4-5ch','base_events':len(events),'base_x':sum(e.get('type')=='X' for e in events),'threads_fetched':0,'status_refs_seen':0,'direct_added':0,'content_only_added':0,'direct_excerpt_upgraded':0,'errors':[],'thread_ids':[]}
    seen=set();queue=list(STARTS)
    while queue and len(seen)<MAX_TOTAL_THREADS:
        url=queue.pop(0)
        if url in seen:continue
        seen.add(url)
        try:body=request(url)
        except Exception as e:
            rep['errors'].append(f'{url}:{e!r}');continue
        rep['threads_fetched']+=1;rep['thread_ids'].append(url.rsplit('/',2)[-2])
        extract(body,url,events,bysid,rep)
        for p in prev_links(body):
            if p not in seen and p not in queue:queue.append(p)
        time.sleep(.18)
    root['events']=sorted(events,key=lambda e:(e.get('date',''),e.get('time',''),e.get('id','')));root['updatedAt']='2026-09-09'
    ASSET.write_text(json.dumps(root,ensure_ascii=False,separators=(',',':')),encoding='utf-8')
    x=[e for e in events if e.get('type')=='X']; direct=sum(bool(XURL_RE.search(e.get('sourceUrl','') or '')) for e in x)
    rep.update({'total_events':len(events),'x_records':len(x),'x_direct_status':direct,'x_content_only':len(x)-direct,'x_with_excerpt':sum(bool((e.get('excerpt') or '').strip()) for e in x)})
    REPORT.write_text(json.dumps(rep,ensure_ascii=False,indent=2),encoding='utf-8')
    print('V094_5CH_REPORT',json.dumps({k:v for k,v in rep.items() if k!='thread_ids'},ensure_ascii=False))
if __name__=='__main__':main()

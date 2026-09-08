#!/usr/bin/env python3
import json,re,time,urllib.parse,urllib.request,urllib.error,html
from datetime import datetime,timedelta,timezone
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
ASSET=ROOT/'app/src/main/assets/archive_seed.json'
JST=timezone(timedelta(hours=9)); EPOCH=1288834974657
UA='Mozilla/5.0 ShioriArchive/0.9.3 (+public-wayback-direct)'
SID_RE=re.compile(r'(?:x|twitter)\.com/(?:#!/)?nagata_shiori_/(?:status|statuses)/(\d{15,22})',re.I)

def req(url,timeout=35,retries=2):
    last=None
    for i in range(retries):
        try:
            q=urllib.request.Request(url,headers={'User-Agent':UA})
            with urllib.request.urlopen(q,timeout=timeout) as r:return r.read().decode('utf-8','replace')
        except Exception as e:
            last=e; time.sleep(3*(i+1))
    raise last

def dt(sid):
    ms=(int(sid)>>22)+EPOCH
    return datetime.fromtimestamp(ms/1000,timezone.utc).astimezone(JST)

def main():
    root=json.loads(ASSET.read_text(encoding='utf-8')); ev=root['events']
    existing={}
    for e in ev:
        if e.get('type')!='X':continue
        m=SID_RE.search(e.get('sourceUrl','') or '')
        if m:existing[m.group(1)]=e
    pats=['twitter.com/nagata_shiori_/status/*','www.twitter.com/nagata_shiori_/status/*','mobile.twitter.com/nagata_shiori_/status/*','twitter.com/nagata_shiori_/statuses/*','x.com/nagata_shiori_/status/*','www.x.com/nagata_shiori_/status/*']
    found=set(); errors=[]; rows_by={}
    for p in pats:
        u='https://web.archive.org/cdx/search/cdx?'+urllib.parse.urlencode({'url':p,'output':'json','fl':'original','filter':'statuscode:200','collapse':'urlkey'})
        try:
            data=json.loads(req(u)); rows=data[1:] if isinstance(data,list) and data else []
            rows_by[p]=len(rows)
            for row in rows:
                text=html.unescape(str(row[0] if isinstance(row,list) and row else row)).replace('\\/','/')
                m=SID_RE.search(text)
                if m:found.add(m.group(1))
        except Exception as e: errors.append(p+':'+repr(e))
        time.sleep(1)
    added=0
    now=datetime.now(JST)+timedelta(days=2)
    for sid in sorted(found,key=int):
        if sid in existing:continue
        d=dt(sid)
        if d.year<2019 or d>now:continue
        ev.append({'id':'x-'+sid,'date':d.strftime('%Y-%m-%d'),'time':d.strftime('%H:%M:%S'),'type':'X','title':'X投稿','summary':'','excerpt':'','sourceName':'X @nagata_shiori_','sourceUrl':f'https://x.com/nagata_shiori_/status/{sid}','confidence':'Wayback公開索引で本人status URLを確認','tags':[],'people':[]}); added+=1
    root['events']=sorted(ev,key=lambda e:(e.get('date',''),e.get('time',''),e.get('id','')))
    ASSET.write_text(json.dumps(root,ensure_ascii=False,separators=(',',':')),encoding='utf-8')
    print(json.dumps({'direct_cdx_rows':rows_by,'candidate_ids':len(found),'added':added,'errors':errors},ensure_ascii=False))
if __name__=='__main__':main()

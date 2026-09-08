#!/usr/bin/env python3
import json,re,time,urllib.parse,urllib.request,urllib.error
from pathlib import Path

UA='Mozilla/5.0 ShioriArchive/0.10 (+public-index-metadata-only)'
SID_RE=re.compile(r'/(?:status|statuses)/(\d{15,22})',re.I)
YEARS=range(2019,2027)
PREFIXES=[
    'twitter.com/nagata_shiori_/status',
    'twitter.com/nagata_shiori_/statuses',
    'www.twitter.com/nagata_shiori_/status',
    'mobile.twitter.com/nagata_shiori_/status',
    'x.com/nagata_shiori_/status',
    'www.x.com/nagata_shiori_/status',
]

def fetch(url,tries=5):
    last=None
    for i in range(tries):
        try:
            req=urllib.request.Request(url,headers={'User-Agent':UA})
            with urllib.request.urlopen(req,timeout=45) as r:
                return r.read().decode('utf-8','replace')
        except Exception as e:
            last=e; time.sleep(min(3*(2**i),30))
    raise last

def cdx(prefix,year):
    q={
        'url':prefix,
        'matchType':'prefix',
        'from':str(year),
        'to':str(year),
        'output':'json',
        'fl':'original',
        'collapse':'urlkey',
        'limit':'20000',
    }
    url='https://web.archive.org/cdx/search/cdx?'+urllib.parse.urlencode(q)
    body=fetch(url)
    try: rows=json.loads(body)
    except Exception: return set(),{'url':url,'error':'non-json','sample':body[:200]}
    out=set()
    for row in rows[1:] if rows and isinstance(rows[0],list) else rows:
        orig=row[0] if isinstance(row,list) else str(row)
        m=SID_RE.search(orig)
        if m: out.add(m.group(1))
    return out,{'url':url,'rows':max(0,len(rows)-1) if isinstance(rows,list) else 0,'ids':len(out)}

def main():
    ids=set(); by_year={}; details=[]; errors=[]
    for year in YEARS:
        y=set()
        for prefix in PREFIXES:
            try:
                got,meta=cdx(prefix,year); y|=got; ids|=got; details.append({'year':year,'prefix':prefix,**meta})
            except Exception as e:
                errors.append({'year':year,'prefix':prefix,'error':repr(e)})
            time.sleep(0.6)
        by_year[str(year)]=len(y)
        print('YEAR',year,'IDS',len(y),'TOTAL',len(ids),flush=True)
    report={'unique_ids':len(ids),'by_year':by_year,'errors':errors,'details':details}
    Path('/tmp/wayback_x_ids.json').write_text(json.dumps(sorted(ids),ensure_ascii=False),encoding='utf-8')
    Path('/tmp/wayback_x_report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print('REPORT',json.dumps(report,ensure_ascii=False))
if __name__=='__main__': main()

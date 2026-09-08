#!/usr/bin/env python3
import json,re,urllib.parse,urllib.request,concurrent.futures,time
SID=re.compile(r'/(?:status|statuses)/(\d{15,22})',re.I)
UA='Mozilla/5.0 ShioriArchive/0.10 (+public-index-metadata-only)'

def get(url,timeout=25):
    req=urllib.request.Request(url,headers={'User-Agent':UA})
    with urllib.request.urlopen(req,timeout=timeout) as r:return r.read().decode('utf-8','replace')

def collections():
    x=json.loads(get('https://index.commoncrawl.org/collinfo.json',20))
    return [v['id'] for v in x if re.search(r'CC-MAIN-(2019|2020|2021|2022|2023|2024|2025|2026)-',v.get('id',''))]

def query(coll,pattern):
    q=urllib.parse.urlencode({'url':pattern,'output':'json','filter':'status:200','collapse':'urlkey'})
    u=f'https://index.commoncrawl.org/{coll}-index?{q}'
    try:b=get(u,25)
    except Exception as e:return coll,pattern,set(),repr(e)
    ids=set()
    for line in b.splitlines():
        try:o=json.loads(line); url=o.get('url','')
        except: url=line
        m=SID.search(url)
        if m:ids.add(m.group(1))
    return coll,pattern,ids,None

def main():
    cs=collections(); pats=['twitter.com/nagata_shiori_/status/*','x.com/nagata_shiori_/status/*']
    allids=set(); errors=[]; counts={}
    jobs=[(c,p) for c in cs for p in pats]
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as ex:
        futs=[ex.submit(query,c,p) for c,p in jobs]
        for f in concurrent.futures.as_completed(futs):
            c,p,ids,err=f.result(); allids|=ids; counts[c]=counts.get(c,0)+len(ids)
            if err:errors.append({'collection':c,'pattern':p,'error':err})
    byyear={}
    for c,n in counts.items():
        y=re.search(r'CC-MAIN-(\d{4})-',c).group(1);byyear[y]=byyear.get(y,0)+n
    rep={'collections':len(cs),'queries':len(jobs),'unique_ids':len(allids),'counts_by_year_sum':byyear,'errors':errors[:30]}
    open('/tmp/commoncrawl_x_ids.json','w').write(json.dumps(sorted(allids)))
    open('/tmp/commoncrawl_x_report.json','w').write(json.dumps(rep,indent=2))
    print('CC_REPORT',json.dumps(rep),flush=True)
if __name__=='__main__':main()

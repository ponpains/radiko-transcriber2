#!/usr/bin/env python3
"""Import public posts from @nagata_shiori_ using x-cli's free public syndication surface."""
import json, re, subprocess, sys
from datetime import datetime, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

ROOT=Path("shiori-archive/data")
OUT=ROOT/"x_2026_oct_nov_recent.json"
MANIFEST=ROOT/"manifest.json"
cmd=["x","timeline","nagata_shiori_","-o","jsonl","-n","100"]
p=subprocess.run(cmd,text=True,capture_output=True,timeout=90)
if p.returncode:
    print(p.stderr[-4000:],file=sys.stderr)
    raise SystemExit(p.returncode)
rows=[]
for line in p.stdout.splitlines():
    line=line.strip()
    if not line: continue
    try: rows.append(json.loads(line))
    except json.JSONDecodeError: continue
if not rows:
    raise SystemExit("x-cli returned no parseable timeline rows; refusing to overwrite archive.")

def find(d,*keys):
    if not isinstance(d,dict): return None
    for k in keys:
        if d.get(k) not in (None,""): return d[k]
    return None

def normalise(r):
    tid=str(find(r,"id","tweet_id","rest_id") or "")
    text=find(r,"text","full_text","content")
    created=find(r,"created_at","createdAt","date","timestamp")
    if not tid or not re.fullmatch(r"\d{15,22}",tid) or not isinstance(text,str) or not text.strip() or not created:
        return None
    if isinstance(created,(int,float)):
        dt=datetime.fromtimestamp(created,tz=timezone.utc)
    else:
        raw=str(created).replace("Z","+00:00")
        try: dt=datetime.fromisoformat(raw)
        except ValueError:
            for fmt in ("%a %b %d %H:%M:%S %z %Y","%Y-%m-%d %H:%M:%S %z"):
                try: dt=datetime.strptime(str(created),fmt); break
                except ValueError: pass
            else: return None
        if dt.tzinfo is None: dt=dt.replace(tzinfo=timezone.utc)
    local=dt.astimezone(ZoneInfo("Asia/Tokyo"))
    if "retweeted_status" in r or r.get("is_retweet") is True: return None
    return {"id":"x-"+tid,"date":local.date().isoformat(),"time":local.strftime("%H:%M"),"type":"X",
      "title":text.strip().splitlines()[0][:70],"summary":"","excerpt":text.strip()[:1000],
      "sourceName":"X @nagata_shiori_","sourceUrl":f"https://x.com/nagata_shiori_/status/{tid}",
      "confidence":"公開タイムライン取得・投稿ID確認（非公式公開エンドポイント）",
      "tags":["本人投稿"],"people":[]}

incoming=[e for r in rows if (e:=normalise(r))]
if not incoming:
    raise SystemExit("Timeline was fetched but its JSON fields were not recognised; refusing to write.")
data=json.loads(OUT.read_text("utf-8")) if OUT.exists() else {"schemaVersion":1,"updatedAt":"","notice":"本人Xの公開投稿。原文と個別投稿への導線を掲載。","events":[]}
known={e.get("id") for e in data.get("events",[])}
added=[e for e in incoming if e["id"] not in known]
if added:
    data["events"].extend(added)
    data["events"].sort(key=lambda e:(e.get("date",""),e.get("time",""),e.get("id","")),reverse=True)
    data["updatedAt"]=datetime.now(ZoneInfo("Asia/Tokyo")).date().isoformat()
    OUT.write_text(json.dumps(data,ensure_ascii=False,indent=2)+"\n","utf-8")
    manifest=json.loads(MANIFEST.read_text("utf-8"))
    if OUT.name not in manifest["files"]: manifest["files"].append(OUT.name)
    manifest["updatedAt"]=data["updatedAt"]
    MANIFEST.write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+"\n","utf-8")
print(f"Fetched {len(rows)} rows; parsed {len(incoming)}; added {len(added)}.")

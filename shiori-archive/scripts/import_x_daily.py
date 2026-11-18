#!/usr/bin/env python3
"""Daily importer for public posts by @nagata_shiori_ using the official X API v2."""
import json, os, re, sys, urllib.parse, urllib.request
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path("shiori-archive/data")
MANIFEST = ROOT / "manifest.json"
CURSOR = ROOT / "x_cursor.json"
OUT = ROOT / "x_2026_oct_nov_recent.json"
TOKEN = os.environ.get("X_BEARER_TOKEN", "").strip()
if not TOKEN:
    raise SystemExit("Missing X_BEARER_TOKEN repository secret. Add an X API Bearer Token to enable daily imports.")

def get_json(url):
    req = urllib.request.Request(url, headers={"Authorization": "Bearer " + TOKEN, "User-Agent": "ShioriArchiveDailyImporter/1.0"})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return json.load(r)
    except Exception as e:
        raise SystemExit(f"X API request failed: {e}")

user = get_json("https://api.x.com/2/users/by/username/nagata_shiori_?user.fields=id,username")
uid = user.get("data", {}).get("id")
if not uid:
    raise SystemExit("X API did not return the requested public user.")

cursor = json.loads(CURSOR.read_text("utf-8")) if CURSOR.exists() else {}
params = {"max_results": "100", "tweet.fields": "created_at,lang,referenced_tweets,entities", "exclude": "retweets"}
if cursor.get("since_id"):
    params["since_id"] = str(cursor["since_id"])
url = f"https://api.x.com/2/users/{uid}/tweets?{urllib.parse.urlencode(params)}"
payload = get_json(url)
posts = payload.get("data", [])
posts.sort(key=lambda x: int(x["id"]))
existing = json.loads(OUT.read_text("utf-8")) if OUT.exists() else {"schemaVersion":1,"updatedAt":datetime.now(timezone.utc).date().isoformat(),"notice":"本人Xの公開投稿。原文と個別投稿への導線を掲載。","events":[]}
known = {e.get("id") for e in existing.get("events", [])}
added = []
for p in posts:
    pid = str(p.get("id", ""))
    text = (p.get("text") or "").strip()
    created = p.get("created_at")
    if not pid or not text or not created or "x-" + pid in known:
        continue
    dt = datetime.fromisoformat(created.replace("Z", "+00:00")).astimezone(timezone.utc)
    local = dt.astimezone(__import__("zoneinfo").ZoneInfo("Asia/Tokyo"))
    excerpt = text[:1000]
    added.append({
        "id": "x-" + pid, "date": local.date().isoformat(), "time": local.strftime("%H:%M"),
        "type": "X", "title": text.splitlines()[0][:70],
        "summary": "", "excerpt": excerpt,
        "sourceName": "X @nagata_shiori_",
        "sourceUrl": f"https://x.com/nagata_shiori_/status/{pid}",
        "confidence": "X API v2本人アカウント投稿・投稿ID確認",
        "tags": ["本人投稿"], "people": []
    })
if added:
    existing["events"].extend(added)
    existing["events"].sort(key=lambda e:(e.get("date",""),e.get("time",""),e.get("id","")), reverse=True)
    existing["updatedAt"] = datetime.now(timezone.utc).date().isoformat()
    OUT.write_text(json.dumps(existing, ensure_ascii=False, indent=2) + "\n", "utf-8")
    manifest = json.loads(MANIFEST.read_text("utf-8"))
    if OUT.name not in manifest["files"]:
        manifest["files"].append(OUT.name)
    manifest["updatedAt"] = existing["updatedAt"]
    MANIFEST.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", "utf-8")
    print(f"Added {len(added)} X posts.")
else:
    print("No new X posts.")
meta = payload.get("meta", {})
new_since = meta.get("newest_id") or cursor.get("since_id")
if new_since:
    CURSOR.write_text(json.dumps({"since_id": str(new_since), "updatedAt": datetime.now(timezone.utc).isoformat()}, ensure_ascii=False, indent=2) + "\n", "utf-8")
print(f"Checked @{user['data'].get('username')}: fetched={len(posts)} added={len(added)}")

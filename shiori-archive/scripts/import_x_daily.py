#!/usr/bin/env python3
"""Import @nagata_shiori_ public posts using x-cli's free public timeline."""
import json, re, subprocess, sys
from datetime import datetime, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

ROOT = Path("shiori-archive/data")
OUT = ROOT / "x_2026_oct_nov_recent.json"
MANIFEST = ROOT / "manifest.json"
INPUT = Path("/tmp/shiori-x.jsonl")

# The workflow fetches once and hands the JSONL to this importer. Local runs
# can still invoke x-cli directly when no prepared input file exists.
if INPUT.exists():
    raw = INPUT.read_text("utf-8")
else:
    p = subprocess.run(
        ["x", "timeline", "nagata_shiori_", "-o", "jsonl", "-n", "100"],
        text=True, capture_output=True, timeout=90
    )
    if p.returncode:
        print(p.stderr[-4000:], file=sys.stderr)
        raise SystemExit(p.returncode)
    raw = p.stdout

rows = []
for line in raw.splitlines():
    line = line.strip()
    if not line:
        continue
    try:
        rows.append(json.loads(line))
    except json.JSONDecodeError:
        continue
if not rows:
    raise SystemExit("No parseable x-cli timeline rows; refusing to modify archive.")

def first(d, *keys):
    if not isinstance(d, dict):
        return None
    for key in keys:
        value = d.get(key)
        if value not in (None, ""):
            return value
    return None

def normalise(row):
    tid = str(first(row, "id", "tweet_id", "rest_id") or "")
    body = first(row, "text", "full_text", "content")
    created = first(row, "created_at", "createdAt", "date", "timestamp")
    # x-cli's normalized Tweet uses Meta.id, text and RFC3339 created_at.
    if not re.fullmatch(r"\d{15,22}", tid) or not isinstance(body, str) or not body.strip() or not created:
        return None
    if row.get("is_retweet") is True or row.get("retweeted") or row.get("retweeted_status"):
        return None
    if isinstance(created, (int, float)):
        dt = datetime.fromtimestamp(created, tz=timezone.utc)
    else:
        raw_date = str(created).replace("Z", "+00:00")
        try:
            dt = datetime.fromisoformat(raw_date)
        except ValueError:
            for fmt in ("%a %b %d %H:%M:%S %z %Y", "%Y-%m-%d %H:%M:%S %z"):
                try:
                    dt = datetime.strptime(str(created), fmt)
                    break
                except ValueError:
                    pass
            else:
                return None
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
    local = dt.astimezone(ZoneInfo("Asia/Tokyo"))
    text = body.strip()
    return {
        "id": "x-" + tid,
        "date": local.date().isoformat(),
        "time": local.strftime("%H:%M"),
        "type": "X",
        "title": text.splitlines()[0][:70],
        "summary": "",
        "excerpt": text[:1000],
        "sourceName": "X @nagata_shiori_",
        "sourceUrl": f"https://x.com/nagata_shiori_/status/{tid}",
        "confidence": "公開タイムライン取得・投稿ID確認（非公式公開エンドポイント）",
        "tags": ["本人投稿"],
        "people": []
    }

incoming = [event for row in rows if (event := normalise(row))]
if not incoming:
    raise SystemExit("Fetched timeline, but no rows matched the expected tweet fields; archive unchanged.")

data = json.loads(OUT.read_text("utf-8")) if OUT.exists() else {
    "schemaVersion": 1,
    "updatedAt": "",
    "notice": "本人Xの公開投稿。原文と個別投稿への導線を掲載。",
    "events": []
}
known = {event.get("id") for event in data.get("events", [])}
added = [event for event in incoming if event["id"] not in known]
if added:
    data["events"].extend(added)
    data["events"].sort(
        key=lambda event: (event.get("date", ""), event.get("time", ""), event.get("id", "")),
        reverse=True
    )
    today = datetime.now(ZoneInfo("Asia/Tokyo")).date().isoformat()
    data["updatedAt"] = today
    OUT.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", "utf-8")
    manifest = json.loads(MANIFEST.read_text("utf-8"))
    if OUT.name not in manifest["files"]:
        manifest["files"].append(OUT.name)
    manifest["updatedAt"] = today
    MANIFEST.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", "utf-8")

print(f"Fetched {len(rows)} rows; parsed {len(incoming)}; added {len(added)}.")

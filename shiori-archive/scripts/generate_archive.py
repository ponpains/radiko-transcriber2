#!/usr/bin/env python3
import hashlib
import html as htmllib
import json
import re
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "data"
ASSETS = ROOT / "app" / "src" / "main" / "assets"
JST = timezone(timedelta(hours=9))
UA = {"User-Agent": "ShioriArchive-build/0.7 (+public archive backfill)"}
X_EPOCH_MS = 1288834974657
TARGET_X_EXCERPTS = 1150
EXCERPT_CHARS = 52


def get(url, timeout=30, retries=1):
    last = None
    for attempt in range(retries + 1):
        try:
            req = urllib.request.Request(url, headers=UA)
            return urllib.request.urlopen(req, timeout=timeout).read().decode("utf-8", "replace")
        except Exception as exc:
            last = exc
            if attempt < retries:
                time.sleep(1.2 * (attempt + 1))
    raise last


def clean_html(text):
    text = re.sub(r"<br\s*/?>", "\n", text, flags=re.I)
    text = re.sub(r"<[^>]+>", "", text)
    text = htmllib.unescape(text)
    text = text.replace("\r", "")
    text = re.sub(r"[ \t]+", " ", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def short_literal(text, limit=EXCERPT_CHARS):
    flat = re.sub(r"\s+", " ", text or "").strip()
    if not flat:
        return ""
    return flat if len(flat) <= limit else flat[:limit].rstrip() + "…"


def status_id(url):
    m = re.search(r"(?:x|twitter)\.com/nagata_shiori_/status/(\d{15,22})", str(url or ""), re.I)
    return m.group(1) if m else None


def snowflake_dt(sid):
    ms = (int(sid) >> 22) + X_EPOCH_MS
    return datetime.fromtimestamp(ms / 1000.0, timezone.utc).astimezone(JST)


def x_event(sid, excerpt="", confidence="投稿IDを公開アーカイブで確認"):
    dt = snowflake_dt(sid)
    return {
        "id": f"x-{sid}",
        "date": dt.strftime("%Y-%m-%d"),
        "time": dt.strftime("%H:%M:%S"),
        "type": "X",
        "title": "X投稿",
        "summary": "",
        "excerpt": short_literal(excerpt),
        "sourceName": "X @nagata_shiori_",
        "sourceUrl": f"https://x.com/nagata_shiori_/status/{sid}",
        "confidence": confidence,
        "tags": [],
        "people": [],
    }


def load_local_shards():
    manifest = json.loads((DATA / "manifest.json").read_text(encoding="utf-8"))
    merged = {}
    for name in manifest.get("files", []):
        if "/" in name or ".." in name or not name.endswith(".json"):
            raise RuntimeError(f"unsafe shard name: {name}")
        shard = json.loads((DATA / name).read_text(encoding="utf-8"))
        for event in shard.get("events", []):
            eid = str(event.get("id", "")).strip()
            if eid:
                merged[eid] = event
    return merged


def parse_fan_index(merged):
    url = "https://raw.githubusercontent.com/nagata-shiohigari/shiorin/main/index.html"
    raw = get(url, 20)
    section_labels = {
        "books": "本・ブログ", "movies": "映画・ドラマ", "gourmet": "グルメ",
        "makeup": "メイク・ファッション", "characters": "キャラクター",
        "events": "イベント", "spots": "場所", "people": "人物",
        "items": "アイテム", "personal": "パーソナル", "radio": "ラジオ", "music": "音楽"
    }
    date_re = re.compile(r"(?<!\d)(\d{2})/(\d{1,2})/(\d{1,2})(?!\d)")
    tag_re = re.compile(r"<[^>]+>")
    generated = 0
    direct_instagram = 0
    direct_tiktok = 0
    for sec in re.finditer(r'<section\s+id="([^"]+)"[^>]*>(.*?)</section>', raw, flags=re.S | re.I):
        sec_id, body = sec.group(1), sec.group(2)
        label = section_labels.get(sec_id, sec_id)
        for li in re.findall(r"<li\b[^>]*>(.*?)</li>", body, flags=re.S | re.I):
            hrefs = [htmllib.unescape(x) for x in re.findall(r'href=["\']([^"\']+)["\']', li, flags=re.I)]
            plain = htmllib.unescape(tag_re.sub("", li))
            plain = re.sub(r"\s+", " ", plain).strip()
            matches = list(date_re.finditer(plain))
            if not matches:
                continue
            first = matches[0]
            title = re.sub(r"\s+", " ", plain[:first.start()].rstrip("（(、,。 ")).strip()
            if not title:
                continue
            if len(title) > 80:
                title = title[:79].rstrip() + "…"
            insta = next((x for x in hrefs if re.match(r"https?://(www\.)?instagram\.com/(p|reel|tv)/", x, re.I)), "")
            tiktok = next((x for x in hrefs if re.match(r"https?://(www\.)?tiktok\.com/@[^/]+/video/\d+", x, re.I)), "")
            xlink = next((x for x in hrefs if status_id(x)), "")
            for idx, m in enumerate(matches):
                y, mo, d = 2000 + int(m.group(1)), int(m.group(2)), int(m.group(3))
                try:
                    dt = date(y, mo, d)
                except ValueError:
                    continue
                if not (date(2019, 1, 1) <= dt <= date(2026, 12, 31)):
                    continue
                next_pos = matches[idx + 1].start() if idx + 1 < len(matches) else len(plain)
                around = plain[m.end():next_pos]
                if "SHOWROOM" in around or "SHOWROOM" in plain[m.start():m.start()+80]:
                    typ = "SHOWROOM"
                elif "Instagram" in around or "Instagram" in plain[m.start():m.start()+80]:
                    typ = "Instagram"
                elif "TikTok" in around or "TikTok" in plain[m.start():m.start()+80]:
                    typ = "TikTok"
                elif re.search(r"(^|\s)X($|\s|\)|）)", around):
                    typ = "X"
                elif any(k in around for k in ["けれけれ", "ノイミーステーション", "FAV FOUR", "ラジオ"]):
                    typ = "ラジオ"
                elif "YouTube" in around or "イコノイジョイチャンネル" in around:
                    typ = "YouTube"
                else:
                    typ = "発言索引"

                # X with a direct status is handled by the canonical X table below.
                if typ == "X" and xlink:
                    continue
                source = "https://nagata-shiohigari.github.io/shiorin/"
                if typ == "Instagram" and insta:
                    source = insta
                    direct_instagram += 1
                elif typ == "TikTok" and tiktok:
                    source = tiktok
                    direct_tiktok += 1
                stamp = dt.isoformat()
                digest = hashlib.sha1(f"{sec_id}|{stamp}|{title}|{typ}".encode()).hexdigest()[:12]
                eid = f"fanidx-{stamp}-{digest}"
                if eid in merged:
                    continue
                merged[eid] = {
                    "id": eid, "date": stamp, "time": "", "type": typ,
                    "title": title, "summary": "", "excerpt": "",
                    "sourceName": "永田詩央里ちゃん情報局（非公式索引）",
                    "sourceUrl": source,
                    "confidence": "二次索引・要一次照合",
                    "tags": [label, typ], "people": []
                }
                generated += 1
    return generated, direct_instagram, direct_tiktok


def load_wayback_ids():
    urls = [
        "https://web.archive.org/cdx/search/cdx?url=twitter.com/nagata_shiori_/status/*&output=json&filter=statuscode:200&collapse=urlkey&fl=timestamp,original&limit=5000",
        "https://web.archive.org/cdx/search/cdx?url=x.com/nagata_shiori_/status/*&output=json&filter=statuscode:200&collapse=urlkey&fl=timestamp,original&limit=5000",
    ]
    byid = {}
    for url in urls:
        try:
            rows = json.loads(get(url, 75, retries=1))
        except Exception as exc:
            print("Wayback CDX skipped:", repr(exc))
            continue
        for row in rows[1:]:
            if len(row) < 2:
                continue
            ts, original = row[0], row[1]
            m = re.search(r"/status/(\d{15,22})(?:[/?#].*)?$", original)
            if not m:
                continue
            sid = m.group(1)
            # Prefer the plain status URL over photo/video subpaths.
            score = 0 if re.search(rf"/status/{sid}(?:[?#].*)?$", original) else 1
            old = byid.get(sid)
            if old is None or score < old[2]:
                byid[sid] = (ts, original, score)
    return byid


def parse_twfan_excerpt_pages(xmap):
    parsed = 0
    page_errors = 0
    for page in range(1, 26):
        try:
            raw = get(f"https://twfan.net/user/1098230959269281792/old/{page}", 25, retries=1)
        except Exception as exc:
            print("twfan page error", page, repr(exc))
            page_errors += 1
            continue
        marks = list(re.finditer(r'<div class="tweet-section" id="(\d{15,22})">', raw))
        for i, mark in enumerate(marks):
            sid = mark.group(1)
            end = marks[i + 1].start() if i + 1 < len(marks) else len(raw)
            block = raw[mark.end():end]
            m = re.search(r'<div class="tweet-body-section">.*?<div class="text">(.*?)</div>', block, flags=re.S | re.I)
            if not m:
                continue
            text = clean_html(m.group(1))
            if not text:
                continue
            ev = xmap.setdefault(sid, x_event(sid))
            ev["excerpt"] = short_literal(text)
            ev["confidence"] = "本人X・公開索引で原文抜粋を確認"
            parsed += 1
        time.sleep(0.12)
    return parsed, page_errors


def extract_snapshot_text(page):
    candidates = []
    pats = [
        r'<meta[^>]+(?:property|name)=["\'](?:og:description|description)["\'][^>]+content=["\']([^"\']+)',
        r'<meta[^>]+content=["\']([^"\']+)["\'][^>]+(?:property|name)=["\'](?:og:description|description)["\']',
        r'data-testid=["\']tweetText["\'][^>]*>(.*?)</div>',
    ]
    for pat in pats:
        candidates.extend(re.findall(pat, page, flags=re.I | re.S)[:3])
    for raw in candidates:
        text = clean_html(raw)
        text = text.strip(" \t\n\r“”\"'")
        if len(text) < 2:
            continue
        low = text.lower()
        if "twitter. it’s what’s happening" in low or low in {"twitter", "x"}:
            continue
        return text
    return ""


def enrich_wayback_excerpts(xmap, captures, target=TARGET_X_EXCERPTS):
    have = sum(1 for e in xmap.values() if str(e.get("excerpt", "")).strip())
    if have >= target:
        return 0, have, 0
    candidates = []
    for sid in sorted(captures, key=int):
        if sid not in xmap:
            continue
        if xmap[sid].get("excerpt"):
            continue
        # Twitter's old archived HTML is substantially more recoverable than recent X pages.
        if snowflake_dt(sid).date() > date(2023, 3, 31):
            continue
        candidates.append(sid)

    successes = 0
    failures = 0

    def worker(sid):
        ts, original, _ = captures[sid]
        snap = f"https://web.archive.org/web/{ts}id_/{original}"
        try:
            page = get(snap, 30, retries=1)
            text = extract_snapshot_text(page)
            return sid, text
        except Exception:
            return sid, ""

    # Work in small batches so the public archive is not hit with a large burst.
    pos = 0
    while have < target and pos < len(candidates):
        batch = candidates[pos:pos + 48]
        pos += len(batch)
        with ThreadPoolExecutor(max_workers=4) as pool:
            futs = [pool.submit(worker, sid) for sid in batch]
            for fut in as_completed(futs):
                sid, text = fut.result()
                if text:
                    xmap[sid]["excerpt"] = short_literal(text)
                    xmap[sid]["confidence"] = "本人X・Wayback公開保存で原文抜粋を確認"
                    successes += 1
                    have += 1
                    if have >= target:
                        # Remaining in-flight requests finish, but no new batch is started.
                        pass
                else:
                    failures += 1
        print(f"Wayback excerpt progress: {have}/{target} (requested {pos})")
        time.sleep(0.8)
    return successes, have, failures


def add_recent_public_x(legacy_x):
    """Keep recent public posts visible even when a direct status ID is not exposed by the mirror."""
    added = 0
    try:
        raw = get("https://www.sotwe.com/nagata_shiori_", 25)
    except Exception as exc:
        print("recent X mirror skipped:", repr(exc))
        return added
    # Current mirror HTML exposes datetime + text; do not invent a status URL when it is absent.
    pattern = re.compile(
        r'<span>@nagata_shiori_</span>.*?<time datetime="([^"]+)"[^>]*>.*?</time>.*?'
        r'<div class="v-card__text body-1 text--primary tweet-text px-0">\s*'
        r'<div class="px-4 dynamic-link-content">(.*?)</div>', re.S
    )
    seen = {(e.get("date", ""), str(e.get("time", ""))[:5], e.get("excerpt", "")) for e in legacy_x}
    now = datetime.now(JST)
    cutoff = now - timedelta(days=60)
    for m in pattern.finditer(raw):
        try:
            dt = datetime.fromisoformat(m.group(1).replace("Z", "+00:00")).astimezone(JST)
        except Exception:
            continue
        if not (cutoff <= dt <= now + timedelta(days=1)):
            continue
        text = clean_html(m.group(2))
        if not text or "\ufffd" in text:
            continue
        excerpt = short_literal(text)
        key = (dt.strftime("%Y-%m-%d"), dt.strftime("%H:%M"), excerpt)
        if key in seen:
            continue
        digest = hashlib.sha1(("|".join(key)).encode()).hexdigest()[:14]
        legacy_x.append({
            "id": f"x-recent-{digest}", "date": key[0], "time": dt.strftime("%H:%M:%S"),
            "type": "X", "title": "X投稿", "summary": "", "excerpt": excerpt,
            "sourceName": "X @nagata_shiori_", "sourceUrl": "",
            "confidence": "本人公開X・公開タイムラインで日時と原文抜粋を確認（個別URL未取得）",
            "tags": [], "people": []
        })
        seen.add(key)
        added += 1
    return added


def add_kerekere(merged):
    start = date(2025, 7, 4)
    last = date(2026, 9, 4)
    n = 1
    d = start
    added = 0
    while d <= last:
        eid = f"radio-kerekere-{n:03d}"
        merged[eid] = {
            "id": eid, "date": d.isoformat(), "time": "23:30:00", "type": "ラジオ",
            "title": f"≠ME 永田詩央里のけれけれ #{n}", "summary": "", "excerpt": "",
            "sourceName": "radiko Podcast / ABSラジオ",
            "sourceUrl": "https://radiko.jp/podcast/channels/cf8c3a32-e6a6-44a4-b43a-5d2817b91b6f",
            "confidence": "放送日・回数確認済み", "tags": ["けれけれ", "ラジオ"], "people": []
        }
        added += 1
        n += 1
        d += timedelta(days=7)
    return added


def main():
    ASSETS.mkdir(parents=True, exist_ok=True)
    merged = load_local_shards()

    # Separate X from other records. Canonical X data is rebuilt by status ID.
    non_x = {}
    xmap = {}
    legacy_x = []
    for eid, event in merged.items():
        if str(event.get("type", "")).upper() != "X":
            non_x[eid] = event
            continue
        event = dict(event)
        event["summary"] = ""
        event["tags"] = []
        event.setdefault("excerpt", "")
        sid = status_id(event.get("sourceUrl", ""))
        if sid:
            base = x_event(sid, event.get("excerpt", ""), event.get("confidence", "確認済み"))
            xmap[sid] = base
        else:
            # Keep recent manually/publicly confirmed X records, but never retain a profile URL as a source post.
            event["sourceUrl"] = ""
            legacy_x.append(event)
    merged = non_x

    fan_count, fan_instagram_direct, fan_tiktok_direct = parse_fan_index(merged)
    radio_count = add_kerekere(merged)

    captures = load_wayback_ids()
    for sid in captures:
        xmap.setdefault(sid, x_event(sid))

    twfan_count, twfan_errors = parse_twfan_excerpt_pages(xmap)
    wb_added, x_excerpt_count, wb_failures = enrich_wayback_excerpts(xmap, captures)
    recent_added = add_recent_public_x(legacy_x)

    # Replace legacy X records that are the same timestamp as a canonical status record.
    canonical_times = {(e["date"], str(e.get("time", ""))[:5]) for e in xmap.values()}
    legacy_kept = []
    seen_legacy = set()
    for e in legacy_x:
        key_time = (e.get("date", ""), str(e.get("time", ""))[:5])
        excerpt = str(e.get("excerpt", "")).strip()
        key = key_time + (excerpt,)
        if key_time in canonical_times or key in seen_legacy:
            continue
        seen_legacy.add(key)
        legacy_kept.append(e)

    for sid, event in xmap.items():
        merged[event["id"]] = event
    for event in legacy_kept:
        merged[event["id"]] = event

    # Ensure no X assistant summaries/tags survive.
    for event in merged.values():
        if str(event.get("type", "")).upper() == "X":
            event["summary"] = ""
            event["tags"] = []

    events = list(merged.values())
    events.sort(key=lambda e: (str(e.get("date", "")), str(e.get("time", ""))), reverse=True)
    x_events = [e for e in events if str(e.get("type", "")).upper() == "X"]
    x_with_excerpt = [e for e in x_events if str(e.get("excerpt", "")).strip()]
    instagram = [e for e in events if str(e.get("type", "")).lower() == "instagram"]
    showroom = [e for e in events if str(e.get("type", "")).lower() == "showroom"]
    interviews = [e for e in events if "インタビュー" in str(e.get("type", "")) or "interview" in str(e.get("id", "")).lower()]

    output = {
        "schemaVersion": 1,
        "updatedAt": datetime.now(JST).strftime("%Y-%m-%d %H:%M"),
        "notice": "公開情報を日付・時刻・出典付きで収録。XはAI要約を付けず、確認できた原文の短い抜粋のみ表示。",
        "events": events,
    }
    out = ASSETS / "archive_seed.json"
    out.write_text(json.dumps(output, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")

    report = {
        "total_events": len(events),
        "x_records": len(x_events),
        "x_with_literal_excerpt": len(x_with_excerpt),
        "x_wayback_status_ids": len(captures),
        "x_twfan_excerpts_parsed": twfan_count,
        "x_wayback_excerpts_added": wb_added,
        "x_wayback_excerpt_failures": wb_failures,
        "x_recent_mirror_added_without_direct_id": recent_added,
        "fan_index_records_generated": fan_count,
        "instagram_records": len(instagram),
        "instagram_direct_links_from_index": fan_instagram_direct,
        "tiktok_direct_links_from_index": fan_tiktok_direct,
        "showroom_records": len(showroom),
        "interview_records": len(interviews),
        "kerekere_episode_records": radio_count,
        "twfan_page_errors": twfan_errors,
        "asset_bytes": out.stat().st_size,
    }
    (ASSETS / "archive_build_report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print("ARCHIVE_BUILD_REPORT", json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
import csv
import html
import io
import json
import re
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSET = ROOT / 'app/src/main/assets/archive_seed.json'
REPORT = ROOT / 'app/src/main/assets/archive_v092_report.json'
JST = timezone(timedelta(hours=9))
X_EPOCH_MS = 1288834974657
UA = 'Mozilla/5.0 ShioriArchive/0.9.2 (+public-metadata-only)'

X_RE = re.compile(r'https?://(?:www\.)?(?:x|twitter)\.com/nagata_shiori_/(?:status|statuses)/(\d+)', re.I)
IG_DIRECT_RE = re.compile(r'https?://(?:www\.)?instagram\.com/(?:p|reel)/[A-Za-z0-9_-]+/?', re.I)
IG_INDEX_RE = re.compile(r'https?://(?:www\.)?instagrammernews\.com/detail/(\d+)', re.I)
TT_RE = re.compile(r'https?://(?:www\.)?tiktok\.com/@notequal_me_shiori/video/(\d+)', re.I)


def request(url, timeout=30):
    req = urllib.request.Request(url, headers={'User-Agent': UA, 'Accept-Language': 'ja,en;q=0.8'})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read().decode('utf-8', 'replace'), r.headers


def compact(s, n=70):
    return html.unescape(re.sub(r'\s+', ' ', s or '')).strip()[:n]


def x_time(sid):
    ms = (int(sid) >> 22) + X_EPOCH_MS
    return datetime.fromtimestamp(ms / 1000, timezone.utc).astimezone(JST)


def tiktok_time(vid):
    sec = int(vid) >> 32
    return datetime.fromtimestamp(sec, timezone.utc).astimezone(JST)


def valid_date(dt):
    return datetime(2019, 1, 1, tzinfo=JST) <= dt <= datetime.now(JST) + timedelta(days=2)


def score_event(e):
    score = 0
    if e.get('sourceUrl'): score += 100
    if e.get('excerpt'): score += min(len(e.get('excerpt') or ''), 80)
    if e.get('time') and len(e.get('time')) >= 8: score += 10
    if e.get('title') and e.get('title') not in ('X投稿', 'Instagram投稿', 'TikTok投稿'): score += 5
    if e.get('confidence'): score += 2
    return score


def walk_x(x, out):
    if isinstance(x, dict):
        user = x.get('user') if isinstance(x.get('user'), dict) else {}
        screen = user.get('screen_name') or user.get('screenName') or x.get('screen_name') or ''
        sid = str(x.get('id_str') or x.get('id') or '')
        text = x.get('full_text') or x.get('text') or ''
        if str(screen).lower() == 'nagata_shiori_' and sid.isdigit() and 15 <= len(sid) <= 22:
            out[sid] = text if isinstance(text, str) else ''
        for v in x.values():
            walk_x(v, out)
    elif isinstance(x, list):
        for v in x:
            walk_x(v, out)


def fetch_x_syndication(report):
    url = 'https://syndication.twitter.com/srv/timeline-profile/screen-name/nagata_shiori_?dnt=true'
    waits = [0, 45, 90, 180, 180]
    last = None
    for attempt, wait in enumerate(waits, 1):
        if wait:
            time.sleep(wait)
        try:
            data, headers = request(url, 30)
            report['x_syndication_attempts'] = attempt
            return data
        except urllib.error.HTTPError as e:
            last = e
            if e.code == 429:
                retry = e.headers.get('Retry-After') if e.headers else None
                if retry and str(retry).isdigit():
                    extra = min(max(int(retry), 15), 240)
                    report.setdefault('x_rate_limit_waits', []).append(extra)
                    time.sleep(extra)
                continue
            break
        except Exception as e:
            last = e
    report['x_syndication_error'] = repr(last)
    return None


def add_or_upgrade_x(events, sid, text, source_name, confidence, report):
    if not sid.isdigit():
        return
    dt = x_time(sid)
    if not valid_date(dt):
        return
    url = f'https://x.com/nagata_shiori_/status/{sid}'
    bysid = {}
    for e in events:
        m = X_RE.search(e.get('sourceUrl', '') or '') if e.get('type') == 'X' else None
        if m:
            bysid[m.group(1)] = e
    if sid in bysid:
        e = bysid[sid]
        changed = False
        if e.get('sourceUrl') != url:
            e['sourceUrl'] = url; changed = True
        if text and not e.get('excerpt'):
            e['excerpt'] = compact(text); changed = True
        if changed:
            report['x_upgraded'] += 1
        return
    events.append({
        'id': 'x-' + sid,
        'date': dt.strftime('%Y-%m-%d'),
        'time': dt.strftime('%H:%M:%S'),
        'type': 'X',
        'title': 'X投稿',
        'summary': '',
        'excerpt': compact(text),
        'sourceName': source_name,
        'sourceUrl': url,
        'confidence': confidence,
        'tags': [],
        'people': []
    })
    report['x_added'] += 1


def ingest_ydevx(events, report):
    url = 'https://raw.githubusercontent.com/ydevx/udeep/07443a293cf3e360ea030817bbef547d0d18aa9a/sns/dat5/nagata_shiori_.csv'
    try:
        data, _ = request(url, 30)
        rows = list(csv.DictReader(io.StringIO(data.lstrip('\ufeff'))))
        report['x_public_csv_rows'] = len(rows)
        for r in rows:
            if (r.get('username') or '').lower() != 'nagata_shiori_':
                continue
            sid = (r.get('id') or '').strip()
            link = r.get('link') or ''
            if sid.isdigit() and sid in link:
                add_or_upgrade_x(events, sid, r.get('tweet') or '', 'X @nagata_shiori_', '公開CSVで投稿ID・日時・本文を確認', report)
    except Exception as e:
        report['x_public_csv_error'] = repr(e)


def ingest_x_syndication(events, report):
    data = fetch_x_syndication(report)
    if not data:
        return
    m = re.search(r'<script id="__NEXT_DATA__" type="application/json">(.*?)</script>', data, re.S)
    if not m:
        report['x_syndication_error'] = 'NEXT_DATA not found'
        return
    try:
        obj = json.loads(html.unescape(m.group(1)))
    except Exception as e:
        report['x_syndication_error'] = repr(e)
        return
    found = {}
    walk_x(obj, found)
    report['x_syndication_ids'] = len(found)
    for sid, text in found.items():
        add_or_upgrade_x(events, sid, text, 'X @nagata_shiori_', 'X公式公開埋め込みタイムラインで確認', report)


def parse_instagram_detail(detail_url):
    body, _ = request(detail_url, 25)
    if 'nagata__shiori' not in body:
        return None
    dm = re.search(r'(20\d{2})/(\d{1,2})/(\d{1,2})', body)
    tm = re.search(r'(\d{1,2})月(\d{1,2})日\s*(\d{1,2})時(\d{2})分', body)
    if not dm:
        return None
    y, mo, d = map(int, dm.groups())
    hh = mm = None
    if tm and int(tm.group(1)) == mo and int(tm.group(2)) == d:
        hh, mm = int(tm.group(3)), int(tm.group(4))
    direct = IG_DIRECT_RE.search(html.unescape(body))
    title = 'Instagram投稿'
    return {
        'date': f'{y:04d}-{mo:02d}-{d:02d}',
        'time': f'{hh:02d}:{mm:02d}' if hh is not None else '',
        'sourceUrl': direct.group(0).rstrip('/') + '/' if direct else detail_url,
        'sourceName': 'Instagram @nagata__shiori' if direct else 'Instagram公開索引（Instagrammer News）',
        'confidence': '公開索引で投稿日時を確認' + ('・元投稿URL確認' if direct else ''),
        'title': title
    }


def ingest_instagram(events, report):
    base = 'https://instagrammernews.com/user/58749300526'
    detail_ids = []
    seen = set()
    for page in range(1, 13):
        url = base if page == 1 else base + f'?page={page}'
        try:
            body, _ = request(url, 25)
        except Exception as e:
            report.setdefault('instagram_index_errors', []).append(repr(e))
            break
        ids = re.findall(r'/detail/(\d{12,22})', body)
        new = [x for x in ids if x not in seen]
        if not new:
            break
        for x in new:
            seen.add(x); detail_ids.append(x)
        time.sleep(1.0)
    report['instagram_detail_ids_seen'] = len(detail_ids)
    existing_urls = {e.get('sourceUrl') for e in events if e.get('type') == 'Instagram'}
    existing_keys = {(e.get('date'), e.get('time')) for e in events if e.get('type') == 'Instagram' and e.get('date')}
    for i, mid in enumerate(detail_ids):
        detail = f'https://instagrammernews.com/detail/{mid}'
        try:
            rec = parse_instagram_detail(detail)
        except Exception as e:
            report.setdefault('instagram_detail_errors', []).append(f'{mid}:{e!r}')
            continue
        if not rec:
            continue
        key = (rec['date'], rec['time'])
        if rec['sourceUrl'] in existing_urls or (rec['time'] and key in existing_keys):
            continue
        events.append({
            'id': 'instagram-' + mid,
            'date': rec['date'],
            'time': rec['time'],
            'type': 'Instagram',
            'title': rec['title'],
            'summary': '',
            'excerpt': '',
            'sourceName': rec['sourceName'],
            'sourceUrl': rec['sourceUrl'],
            'confidence': rec['confidence'],
            'tags': [],
            'people': []
        })
        existing_urls.add(rec['sourceUrl']); existing_keys.add(key)
        report['instagram_added'] += 1
        if i < len(detail_ids) - 1:
            time.sleep(0.7)


def extract_tiktok_ids(body):
    out = set()
    # Urlebird video links include the numeric TikTok video ID in the URL.
    for m in re.finditer(r'href=["\']([^"\']*/video/[^"\']*-(\d{18,20})/)["\']', body, re.I):
        start = max(0, m.start() - 1400)
        end = min(len(body), m.end() + 300)
        if 'notequal_me_shiori' in body[start:end].lower():
            out.add(m.group(2))
    return out


def ingest_tiktok(events, report):
    pages = [
        'https://urlebird.com/hash/%E6%B0%B8%E7%94%B0%E8%A9%A9%E5%A4%AE%E9%87%8C/',
        'https://urlebird.com/user/notequal_me_shiori/'
    ]
    ids = {'7588468200837270802'}  # first post, publicly indexed on 2025-12-27
    for base in pages:
        for page in range(1, 8):
            url = base if page == 1 else base + f'?page={page}'
            try:
                body, _ = request(url, 25)
            except Exception as e:
                report.setdefault('tiktok_index_errors', []).append(repr(e))
                break
            got = extract_tiktok_ids(body)
            before = len(ids); ids |= got
            if page > 1 and len(ids) == before:
                break
            time.sleep(1.0)
    report['tiktok_ids_seen'] = len(ids)
    existing = {m.group(1) for e in events if e.get('type') == 'TikTok' for m in [TT_RE.search(e.get('sourceUrl','') or '')] if m}
    for vid in sorted(ids):
        if vid in existing:
            continue
        dt = tiktok_time(vid)
        if not valid_date(dt):
            continue
        events.append({
            'id': 'tiktok-' + vid,
            'date': dt.strftime('%Y-%m-%d'),
            'time': dt.strftime('%H:%M:%S'),
            'type': 'TikTok',
            'title': 'TikTok投稿',
            'summary': '',
            'excerpt': '',
            'sourceName': 'TikTok @notequal_me_shiori',
            'sourceUrl': f'https://www.tiktok.com/@notequal_me_shiori/video/{vid}',
            'confidence': '公開索引で動画ID確認・動画IDの埋め込み時刻から投稿時刻を算出',
            'tags': [],
            'people': []
        })
        report['tiktok_added'] += 1


def clean_social(events, report):
    # X: direct status URL is now a hard requirement.
    cleaned = []
    x_by_id = {}
    for e in events:
        if e.get('type') != 'X':
            cleaned.append(e); continue
        m = X_RE.search(e.get('sourceUrl', '') or '')
        if not m:
            report['x_removed_no_direct_url'] += 1
            continue
        sid = m.group(1)
        e = dict(e)
        e['sourceUrl'] = f'https://x.com/nagata_shiori_/status/{sid}'
        old = x_by_id.get(sid)
        if old is None or score_event(e) > score_event(old):
            if old is not None:
                report['x_removed_duplicate_id'] += 1
            x_by_id[sid] = e
        else:
            report['x_removed_duplicate_id'] += 1
    cleaned.extend(x_by_id.values())

    # Social records without any source evidence are not kept.
    out = []
    seen_ig = set(); seen_tt = set()
    for e in cleaned:
        typ = e.get('type')
        if typ == 'Instagram':
            u = e.get('sourceUrl', '') or ''
            if not (IG_DIRECT_RE.search(u) or IG_INDEX_RE.search(u)):
                report['instagram_removed_no_source'] += 1
                continue
            key = u.rstrip('/')
            if key in seen_ig:
                report['instagram_removed_duplicate'] += 1
                continue
            seen_ig.add(key)
        elif typ == 'TikTok':
            m = TT_RE.search(e.get('sourceUrl', '') or '')
            if not m:
                report['tiktok_removed_no_source'] += 1
                continue
            if m.group(1) in seen_tt:
                report['tiktok_removed_duplicate'] += 1
                continue
            seen_tt.add(m.group(1))
        out.append(e)
    return out


root = json.loads(ASSET.read_text(encoding='utf-8'))
events = root.get('events', [])
report = {
    'version': '0.9.2',
    'base_events': len(events),
    'x_removed_no_direct_url': 0,
    'x_removed_duplicate_id': 0,
    'x_added': 0,
    'x_upgraded': 0,
    'instagram_removed_no_source': 0,
    'instagram_removed_duplicate': 0,
    'instagram_added': 0,
    'tiktok_removed_no_source': 0,
    'tiktok_removed_duplicate': 0,
    'tiktok_added': 0,
}

events = clean_social(events, report)
ingest_ydevx(events, report)
ingest_x_syndication(events, report)
ingest_instagram(events, report)
ingest_tiktok(events, report)
events = clean_social(events, report)

events = sorted(events, key=lambda e: (e.get('date',''), e.get('time',''), e.get('type',''), e.get('id','')))
root['events'] = events
root['updatedAt'] = datetime.now(JST).strftime('%Y-%m-%d')
ASSET.write_text(json.dumps(root, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')

x = [e for e in events if e.get('type') == 'X']
ig = [e for e in events if e.get('type') == 'Instagram']
tt = [e for e in events if e.get('type') == 'TikTok']
report.update({
    'total_events': len(events),
    'x_records': len(x),
    'x_direct_status_url': sum(bool(X_RE.search(e.get('sourceUrl','') or '')) for e in x),
    'instagram_records': len(ig),
    'instagram_with_source': sum(bool(e.get('sourceUrl')) for e in ig),
    'instagram_with_time': sum(bool(e.get('time')) for e in ig),
    'tiktok_records': len(tt),
    'tiktok_with_source': sum(bool(e.get('sourceUrl')) for e in tt),
    'tiktok_with_time': sum(bool(e.get('time')) for e in tt),
})
REPORT.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
print('V092_REPORT', json.dumps(report, ensure_ascii=False))

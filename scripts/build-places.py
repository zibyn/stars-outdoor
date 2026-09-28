#!/usr/bin/env python3
"""地名索引 for offline 搜索 (spec §2.10, issue #18): reads a Photon JSON dump (jsonl) on stdin, keeps the
places hikers look for, writes them to one SQLite table. The server clips it per offline package.
Usage: zstd -dc photon-dump-china-….jsonl.zst | scripts/build-places.py places.sqlite"""
import json, os, re, sqlite3, sys

KEEP = {
    "place": None,  # every value: city … hamlet, locality, island
    "natural": {"peak", "volcano", "saddle", "ridge", "cliff", "cave_entrance", "glacier", "spring", "water", "valley", "mountain_range"},
    "tourism": {"attraction", "viewpoint", "camp_site", "alpine_hut", "wilderness_hut", "picnic_site"},
    "waterway": {"waterfall"},
    "mountain_pass": None,
    "leisure": {"nature_reserve"},
    "boundary": {"national_park", "protected_area"},
    "amenity": {"shelter"},
}

out = sys.argv[1]
if os.path.exists(out):
    os.remove(out)
db = sqlite3.connect(out)
db.execute("CREATE TABLE places (name TEXT NOT NULL, name_zh TEXT, name_en TEXT, kind TEXT NOT NULL, lon REAL NOT NULL, lat REAL NOT NULL, ele REAL, importance REAL NOT NULL, detail TEXT)")
rows = []
for line in sys.stdin:
    o = json.loads(line)
    if o.get("type") != "Place":
        continue
    p = o["content"][0]
    values = KEEP.get(p.get("osm_key"), ())
    names = p.get("name") or {}
    if values is not None and p.get("osm_value") not in values or not names.get("name"):
        continue
    address = p.get("address") or {}
    detail = []
    for k in ("state", "city", "county"):
        v = address.get(k + ":zh") or address.get(k)
        if isinstance(v, str) and v not in detail:
            detail.append(v)
    ele = re.match(r"-?\d+(\.\d+)?", str((p.get("extra") or {}).get("ele", "")))
    lon, lat = p["centroid"]
    rows.append((names["name"], names.get("name:zh"), names.get("name:en"), p["osm_value"], round(lon, 6), round(lat, 6),
                 float(ele[0]) if ele else None, p.get("importance") or 0, " ".join(detail) or None))
db.executemany("INSERT INTO places VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", rows)
db.commit()
db.execute("VACUUM")
print(f"{len(rows)} places", file=sys.stderr)

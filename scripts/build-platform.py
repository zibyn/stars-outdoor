#!/usr/bin/env python3
"""平台轨迹 (spec §2.8, §3.3 step 6): official open trail data as one GeoJSON FeatureCollection whose features
have `name` and `source` (shown with each line in the app), lines only.
Usage: scripts/build-platform.py platform.geojson hk-trails.geojson [tw-trails.geojson]
  hk-trails.geojson: AFCD 郊野公园远足径 as served by the CSDI portal (WGS-84).
  tw-trails.geojson: 林业保育署 自然步道轨迹图 KMZs merged by ogrmerge.py, with the KMZ's name in `file`."""
import json, re, sys

HK = "香港渔农自然护理署（DATA.GOV.HK）"
TW = "台湾林业及自然保育署（政府資料開放授權條款第1版）"


def rounded(c):  # 6 decimals, ~0.1 m
    return [rounded(x) for x in c] if isinstance(c[0], list) else [round(v, 6) for v in c]


def lines(path):
    with open(path, encoding="utf-8") as f:
        features = json.load(f)["features"]
    for f in features:
        g = f.get("geometry") or {}
        if g.get("type") in ("LineString", "MultiLineString"):
            yield {"type": g["type"], "coordinates": rounded(g["coordinates"])}, f.get("properties") or {}


out, hk, tw = sys.argv[1], sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else None
features = []
for g, p in lines(hk):
    name = (p.get("TRAIL_NAME_TC") or "") + (f" 第{p['SECTION_NO']}段" if p.get("SECTION_NO") not in (None, "", "0") else "")
    features.append({"type": "Feature", "properties": {"name": name, "source": HK}, "geometry": g})
if tw:
    for g, p in lines(tw):
        # The placemark's name, else the KMZ's ("114_鈺鼎步道" → 鈺鼎步道).
        name = (p.get("Name") or "").strip() or re.sub(r"^\d+_", "", p.get("file") or "")
        features.append({"type": "Feature", "properties": {"name": name, "source": TW}, "geometry": g})
with open(out, "w", encoding="utf-8") as f:
    json.dump({"type": "FeatureCollection", "features": features}, f, ensure_ascii=False, separators=(",", ":"))
print(f"{len(features)} 平台轨迹", file=sys.stderr)

#!/usr/bin/env python3
"""平台轨迹 (spec §2.8, ADR 0005) from official open trail data, as SQL that replaces the open-data rows of
platform_tracks (server/platformtracks.go) with these: `name` and `source` (shown with each line in the app),
lines only. Re-runnable; promoted 公开轨迹 are left alone.
Usage: scripts/build-platform.py platform.sql hk-trails.geojson [tw-trails.geojson]
  then, once the server has made its schema: psql < platform.sql (deploy/README.md)
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


def lit(v):
    return "'" + v.replace("'", "''") + "'"


with open(out, "w", encoding="utf-8") as f:
    sources = sorted({x["properties"]["source"] for x in features})
    f.write(f"BEGIN;\nDELETE FROM platform_tracks WHERE promoted_id IS NULL AND source IN ({', '.join(map(lit, sources))});\n")
    for x in features:
        g = json.dumps(x["geometry"], separators=(",", ":"))
        f.write(f"INSERT INTO platform_tracks (name, source, geom) VALUES ({lit(x['properties']['name'])}, {lit(x['properties']['source'])}, ST_Multi(ST_GeomFromGeoJSON({lit(g)})));\n")
    f.write("COMMIT;\n")
print(f"{len(features)} 平台轨迹", file=sys.stderr)

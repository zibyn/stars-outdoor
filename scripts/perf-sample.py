#!/usr/bin/env python3
"""Sample GPX for the on-device smoothness check (#36): N 标注 scattered around the start view plus one long 轨迹.
Usage: scripts/perf-sample.py [waypoints=1000] [track points=20000] > perf.gpx  → share/open it with the app."""
import datetime, math, random, sys

LAT, LON = 33.96, 107.77  # the app's initial camera target (Taibai Shan)
nw = int(sys.argv[1]) if len(sys.argv) > 1 else 1000
nt = int(sys.argv[2]) if len(sys.argv) > 2 else 20000
rnd = random.Random(36)
T0 = datetime.datetime(2026, 9, 1)

print('<?xml version="1.0" encoding="UTF-8"?>\n<gpx version="1.1" creator="perf-sample" xmlns="http://www.topografix.com/GPX/1/1">')
for i in range(nw):  # within ~±5 km of the start view
    print(f'<wpt lat="{LAT + rnd.uniform(-.045, .045):.6f}" lon="{LON + rnd.uniform(-.055, .055):.6f}"><name>标注{i + 1}</name></wpt>')
print('<trk><name>长轨迹</name><trkseg>')
for i in range(nt):  # a ~5 km-radius wobbly spiral, ~430 km, 1 point per ~20 m
    a = i / nt * 12 * math.pi
    r = .01 + .035 * i / nt + .002 * math.sin(i / 7)
    print(f'<trkpt lat="{LAT + r * math.sin(a):.6f}" lon="{LON + 1.2 * r * math.cos(a):.6f}"><ele>{2000 + 500 * math.sin(a):.0f}</ele>'
          f'<time>{T0 + datetime.timedelta(seconds=15 * i):%Y-%m-%dT%H:%M:%SZ}</time></trkpt>')  # ~5 km/h
print('</trkseg></trk>\n</gpx>')

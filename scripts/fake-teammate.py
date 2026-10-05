#!/usr/bin/env python3
"""A fake 队友 for testing 队伍 on the emulator (#135): logs in with a 测试号 (TEST_LOGINS on the server),
joins by the 4-digit code, walks a line reporting positions, and says a few things in the 队伍对话.

Usage: scripts/fake-teammate.py CODE [--phone 13900000002] [--name 小李] [--lat 33.96 --lon 107.78]
           [--steps 60] [--every 5] [--stop-sharing-at 40] [--leave]
Without --create it joins team CODE; with --create (CODE ignored, pass -) it makes a team and prints its code."""
import argparse, json, math, os, time, urllib.error, urllib.request, uuid

p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
p.add_argument("code")
p.add_argument("--api", default=os.environ.get("STARS_API", "https://outdoor.starsdom.com:9443"))
p.add_argument("--phone", default="13900000002")
p.add_argument("--sms", default="123456", help="the fixed code in TEST_LOGINS")
p.add_argument("--name", default="小李")
p.add_argument("--lat", type=float, default=33.96)  # Taibai Shan, where the emulator's geo fix usually is
p.add_argument("--lon", type=float, default=107.78)
p.add_argument("--steps", type=int, default=60, help="positions to send, ~50 m apart")
p.add_argument("--every", type=float, default=5, help="seconds between positions")
p.add_argument("--stop-sharing-at", type=int, help="step at which to 停止共享")
p.add_argument("--leave", action="store_true", help="退出队伍 at the end")
p.add_argument("--create", action="store_true", help="make the team instead of joining")
a = p.parse_args()

HEAD = {"X-Device-Id": str(uuid.uuid5(uuid.NAMESPACE_DNS, a.phone)), "X-Client-Version": "1", "Content-Type": "application/json"}


def call(method, path, body=None, token=None):
    h = dict(HEAD, **({"Authorization": "Bearer " + token} if token else {}))
    req = urllib.request.Request(a.api + "/v1" + path, json.dumps(body).encode() if body is not None else None, h, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read()
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        raise SystemExit(f"{method} {path}: {e.code} {e.read().decode()}")


# No /auth/code: a 测试号 needs none, and on a server without TEST_LOGINS asking would text a real number.
tok = call("POST", "/auth/login", {"phone": a.phone, "code": a.sms})["token"]
team = call("POST", "/teams", {"name": a.name}, tok) if a.create else call("POST", "/teams/join", {"code": a.code, "name": a.name}, tok)
tid = team["id"]
print(f"队伍 {tid}，加入码 {team['code']}，{len(team['members'])} 人")
say = lambda text: call("POST", f"/teams/{tid}/messages", {"kind": "text", "text": text}, tok)
say("我到了，跟在后面")

bearing = math.radians(40)  # walk north-east, ~50 m a step
for i in range(a.steps):
    if i == a.stop_sharing_at:
        call("PUT", f"/teams/{tid}/sharing", {"sharing": False}, tok)
        say("先停一下共享，省电")
        print("停止共享")
    d = i * 50 / 111_000
    lat, lon = a.lat + d * math.cos(bearing), a.lon + d * math.sin(bearing) / math.cos(math.radians(a.lat))
    if a.stop_sharing_at is None or i < a.stop_sharing_at:
        call("POST", f"/teams/{tid}/positions", {"positions": [{"time": int(time.time()), "lat": lat, "lon": lon, "battery": max(5, 80 - i)}]}, tok)
    if i == a.steps // 2:
        call("POST", f"/teams/{tid}/messages", {"kind": "location", "lat": lat, "lon": lon}, tok)
        say("这里有水源")
    print(f"{i + 1}/{a.steps} {lat:.5f},{lon:.5f}", flush=True)
    time.sleep(a.every)

if a.leave:
    say("我先撤了")
    call("POST", f"/teams/{tid}/leave", token=tok)
    print("退出队伍")

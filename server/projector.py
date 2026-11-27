#!/usr/bin/env python3
"""
Projector bridge — lets the home server control the PIXPAQ projector over ADB (wireless debugging),
so Claude can say "play Netflix", "volume down", "turn it off".

Local HTTP API on 127.0.0.1:9830 (only this machine can reach it):
  GET  /status                     connected?, screen on?, current app, volume
  POST /key     {"key": "home"}    remote buttons (see KEYS), {"repeat": n}
  POST /open    {"app": "netflix"} open an installed app by name
  GET  /apps                       installed apps
  POST /volume  {"level": 0-15} | {"action": "up|down|mute"}
  POST /youtube {"query": "..."} | {"url": "..."}
  POST /text    {"text": "..."}    type into the focused field
  POST /pair    {"host":..,"port":..,"code":..}  one-time pairing

Finds the projector via the Projector Helper app (port 8765), which reports the current ADB port.
"""
import ipaddress
import json
import os
import re
import shlex
import socket
import subprocess
import threading
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

STATE = os.environ.get("PROJECTOR_STATE", "/state")
PORT = int(os.environ.get("PROJECTOR_PORT", "9830"))
HELPER_PORT = 8765
ADB = os.environ.get("ADB", "adb")
CFG = os.path.join(STATE, "projector.json")

KEYS = {
    "home": 3, "back": 4, "up": 19, "down": 20, "left": 21, "right": 22, "ok": 23, "enter": 66,
    "volume_up": 24, "volume_down": 25, "mute": 164, "power": 26, "sleep": 223, "wakeup": 224,
    "menu": 82, "settings": 176, "play_pause": 85, "play": 126, "pause": 127, "stop": 86,
    "next": 87, "previous": 88, "rewind": 89, "fast_forward": 90, "search": 84, "delete": 67,
}

FRIENDLY = {
    "netflix": ["com.netflix.ninja", "com.netflix.mediaclient"],
    "youtube": ["com.google.android.youtube.tv", "com.google.android.youtube", "com.liskovsoft.smarttubetv"],
    "prime video": ["com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient"],
    "hotstar": ["in.startv.hotstar", "in.startv.hotstar.dplus.tv"],
    "jiocinema": ["com.jio.media.ondemand", "com.jio.media.stb.ondemand"],
    "spotify": ["com.spotify.tv.android", "com.spotify.music"],
    "settings": ["com.android.tv.settings", "com.android.settings"],
    "files": ["com.android.documentsui"],
}


def log(*a):
    print(time.strftime("%Y-%m-%d %H:%M:%S"), *a, flush=True)


def load_cfg():
    try:
        with open(CFG) as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def save_cfg(c):
    os.makedirs(STATE, exist_ok=True)
    with open(CFG + ".tmp", "w") as f:
        json.dump(c, f)
    os.replace(CFG + ".tmp", CFG)


def adb(*args, timeout=15):
    env = dict(os.environ, HOME=STATE)   # adb keeps its key in $HOME/.android — kept in the state folder
    r = subprocess.run([ADB, *args], capture_output=True, text=True, timeout=timeout, env=env)
    return r.returncode, (r.stdout + r.stderr).strip()


class Projector:
    def __init__(self):
        self.lock = threading.Lock()
        self.cfg = load_cfg()
        self.serial = None
        self.apps_cache = (0, [])

    # ---------------------------------------------------------------- finding & connecting
    def _helper(self, ip, timeout=1.5):
        try:
            with urllib.request.urlopen(f"http://{ip}:{HELPER_PORT}/info", timeout=timeout) as r:
                j = json.loads(r.read())
            return j if j.get("app") == "ProjectorHelper" else None
        except Exception:
            return None

    def _my_net(self):
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            s.connect(("1.1.1.1", 80))
            ip = s.getsockname()[0]
        finally:
            s.close()
        return ipaddress.ip_network(ip + "/24", strict=False)

    def _scan(self):
        def probe(ip):
            try:
                with socket.create_connection((str(ip), HELPER_PORT), timeout=0.4):
                    return str(ip)
            except OSError:
                return None
        with ThreadPoolExecutor(64) as ex:
            for ip in ex.map(probe, self._my_net().hosts()):
                if ip and self._helper(ip):
                    return ip
        return None

    def connect(self):
        """Make sure adb is connected; returns the serial (ip:port)."""
        if self.serial:
            code, out = adb("-s", self.serial, "shell", "echo ok", timeout=6)
            if code == 0 and out.endswith("ok"):
                return self.serial
            adb("disconnect", self.serial, timeout=5)
            self.serial = None
        ip = self.cfg.get("ip")
        info = self._helper(ip) if ip else None
        if not info:
            ip = self._scan()
            info = self._helper(ip) if ip else None
        if not info:
            raise RuntimeError("Projector not found on the Wi-Fi — is it switched on?")
        ip = info.get("ip") or ip
        port = info.get("port")
        if not port:
            # ask the helper to switch wireless debugging back on, then try again
            try:
                urllib.request.urlopen(f"http://{ip}:{HELPER_PORT}/enable", timeout=3).read()
            except Exception:
                pass
            time.sleep(3)
            port = (self._helper(ip) or {}).get("port")
        if not port:
            raise RuntimeError("Projector is on but wireless debugging is off — open Projector Remote once to wake it")
        serial = f"{ip}:{port}"
        code, out = adb("connect", serial, timeout=10)
        if "connected" not in out or "failed" in out or "unable" in out.lower():
            if "authentication" in out.lower() or "failed" in out.lower() or "refused" in out.lower():
                raise RuntimeError("The server isn't paired with the projector yet — run: bash ~/Projects/ProjectorRemote/server/pair.sh")
            raise RuntimeError("Couldn't connect to the projector: " + out[-200:])
        code, out = adb("-s", serial, "shell", "echo ok", timeout=8)
        if not out.endswith("ok"):
            raise RuntimeError("The server isn't paired with the projector yet — run: bash ~/Projects/ProjectorRemote/server/pair.sh")
        if self.cfg.get("ip") != ip:
            self.cfg["ip"] = ip
            save_cfg(self.cfg)
        self.serial = serial
        log("connected", serial)
        return serial

    def sh(self, cmd, timeout=15):
        with self.lock:
            serial = self.connect()
            code, out = adb("-s", serial, "shell", cmd, timeout=timeout)
            if code != 0 and "error" in out.lower():
                self.serial = None
                serial = self.connect()
                code, out = adb("-s", serial, "shell", cmd, timeout=timeout)
            return out

    # ---------------------------------------------------------------- actions
    def status(self):
        power = self.sh("dumpsys power | grep -m1 -E 'mWakefulness=|Display Power: state='")
        awake = "Awake" in power or "state=ON" in power
        focus = self.sh("dumpsys window | grep -m1 -E 'mCurrentFocus|mFocusedApp'")
        m = re.search(r"([a-zA-Z0-9_.]+)/", focus)
        pkg = m.group(1) if m else None
        vol = self.volume_get()
        res = {"connected": True, "projector": self.serial, "screen_on": awake, "current_app": self.friendly(pkg) if pkg else None,
               "current_package": pkg, "volume": vol}
        try:
            res["now_playing"] = self.now_playing(pkg)
        except Exception:
            res["now_playing"] = None
        return res

    def now_playing(self, top_pkg=None):
        """Active media session: app, title, playing/paused, position."""
        out = self.sh("dumpsys media_session; echo '##UP'; cat /proc/uptime")
        media, _, up = out.partition("##UP")
        try:
            uptime_ms = int(float(up.split()[0]) * 1000)
        except Exception:
            uptime_ms = None
        sessions, cur = [], None
        for raw in media.splitlines():
            l = raw.strip()
            if l.startswith("package="):
                cur = {"pkg": l[8:].strip(), "active": False, "state": -1, "pos": -1, "speed": 1.0, "updated": -1,
                       "title": None, "sub": None}
                sessions.append(cur)
            elif cur is None:
                continue
            elif l.startswith("active="):
                cur["active"] = l[7:].strip() == "true"
            elif l.startswith("state=PlaybackState"):
                for k, cast in (("state", int), ("position", int), ("speed", float), ("updated", int)):
                    m = re.search(r"[{ ,]%s=(-?[0-9.]+)" % k, l)
                    if m:
                        try:
                            cur["pos" if k == "position" else k] = cast(float(m.group(1)))
                        except ValueError:
                            pass
            elif l.startswith("metadata:") and "description=" in l:
                parts = [p.strip() for p in l.split("description=", 1)[1].split(", ")]
                clean = lambda v: None if (v is None or v in ("", "null")) else v
                if len(parts) >= 3:
                    cur["title"], cur["sub"] = clean(", ".join(parts[:-2])), clean(parts[-2])
                else:
                    cur["title"], cur["sub"] = clean(parts[0]), clean(parts[1] if len(parts) > 1 else None)
        pick = (next((x for x in sessions if x["state"] in (3, 6)), None)
                or next((x for x in sessions if x["active"] and x["state"] == 2), None)
                or next((x for x in sessions if x["state"] == 2 and x["pkg"] == top_pkg), None))
        if not pick:
            return None
        playing = pick["state"] in (3, 6)
        pos = pick["pos"] if pick["pos"] >= 0 else None
        if pos is not None and pick["state"] == 3 and uptime_ms and pick["updated"] > 0:
            pos += int(max(0, uptime_ms - pick["updated"]) * pick["speed"])
        r = {"app": self.friendly(pick["pkg"]), "title": pick["title"], "subtitle": pick["sub"],
             "state": "playing" if playing else "paused"}
        if pos is not None:
            s = pos // 1000
            r["position"] = "%d:%02d:%02d" % (s // 3600, s % 3600 // 60, s % 60) if s >= 3600 else "%d:%02d" % (s // 60, s % 60)
        return r

    def volume_get(self):
        out = self.sh("cmd media_session volume --stream 3 --get 2>/dev/null")
        m = re.search(r"volume is (\d+) in range \[(\d+)\.\.(\d+)\]", out)
        return {"level": int(m.group(1)), "max": int(m.group(3))} if m else None

    def volume(self, level=None, action=None):
        if action in ("up", "down", "mute"):
            self.key({"up": "volume_up", "down": "volume_down", "mute": "mute"}[action], 1)
        elif level is not None:
            v = self.volume_get() or {"max": 15}
            lv = max(0, min(int(level), v["max"]))
            self.sh(f"cmd media_session volume --stream 3 --set {lv}")
        return self.volume_get()

    def key(self, name, repeat=1):
        code = KEYS.get(name)
        if code is None:
            raise ValueError("unknown button " + name + ". Buttons: " + ", ".join(KEYS))
        repeat = max(1, min(int(repeat), 30))
        self.sh(" ; ".join([f"input keyevent {code}"] * repeat))
        return {"pressed": name, "times": repeat}

    def apps(self):
        if time.time() - self.apps_cache[0] < 600 and self.apps_cache[1]:
            return self.apps_cache[1]
        out = self.sh("cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER; "
                      "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER", timeout=25)
        comps = sorted({l.strip() for l in out.splitlines() if "/" in l and not l.strip().startswith("priority")})
        apps, seen = [], set()
        for c in comps:
            pkg = c.split("/")[0]
            if pkg in seen:
                continue
            seen.add(pkg)
            apps.append({"name": self.friendly(pkg), "package": pkg, "component": c})
        self.apps_cache = (time.time(), apps)
        return apps

    @staticmethod
    def friendly(pkg):
        for name, pkgs in FRIENDLY.items():
            if pkg in pkgs:
                return name.title()
        part = pkg.split(".")[-1]
        if part in ("tv", "android", "app", "ninja", "livingroom", "mediaclient", "ondemand") and len(pkg.split(".")) > 1:
            part = pkg.split(".")[-2]
        return part.replace("_", " ").title()

    def open(self, name):
        low = name.strip().lower()
        apps = self.apps()
        match = None
        for fname, pkgs in FRIENDLY.items():
            if low in fname or fname in low:
                match = next((a for p in pkgs for a in apps if a["package"] == p), None)
                if match:
                    break
        if not match:
            cands = [a for a in apps if low in a["name"].lower() or low in a["package"].lower()]
            match = cands[0] if cands else None
        if not match:
            raise ValueError(f"No app called '{name}' on the projector. Installed: " + ", ".join(a["name"] for a in apps))
        self.key("wakeup")
        self.sh(f"am start -n {shlex.quote(match['component'])} || monkey -p {match['package']} 1")
        return {"opened": match["name"], "package": match["package"]}

    def youtube(self, query=None, url=None):
        self.key("wakeup")
        pkg = next((p for p in FRIENDLY["youtube"] if any(a["package"] == p for a in self.apps())), None)
        if url:
            m = re.search(r"(?:v=|youtu\.be/|shorts/)([\w-]{11})", url)
            target = f"https://www.youtube.com/watch?v={m.group(1)}" if m else url
        else:
            target = "https://www.youtube.com/results?search_query=" + urllib.request.quote(query or "")
        self.sh(f"am start -a android.intent.action.VIEW -d {shlex.quote(target)} " + (pkg or ""))
        return {"opened": "YouTube", "target": target}

    def text(self, t):
        esc = t.replace("'", "").replace(" ", "%s")
        self.sh(f"input text '{esc}'")
        return {"typed": t}

    def pair(self, host, port, code):
        c, out = adb("pair", f"{host}:{int(port)}", str(code), timeout=30)
        ok = "Successfully paired" in out
        if ok:
            self.cfg["ip"] = host
            save_cfg(self.cfg)
            self.serial = None
        return {"paired": ok, "message": out[-300:]}


P = Projector()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _send(self, code, obj):
        b = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def _run(self, fn):
        try:
            self._send(200, fn())
        except (ValueError, RuntimeError) as e:
            self._send(409, {"error": str(e)})
        except subprocess.TimeoutExpired:
            P.serial = None
            self._send(504, {"error": "The projector didn't answer in time"})
        except Exception as e:
            log("error", repr(e))
            self._send(500, {"error": "bridge error: " + repr(e)[:200]})

    def do_GET(self):
        if self.path == "/status":
            return self._run(P.status)
        if self.path == "/apps":
            return self._run(P.apps)
        if self.path == "/hello":
            return self._send(200, {"app": "ProjectorBridge"})
        self._send(404, {"error": "not found"})

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        try:
            b = json.loads(self.rfile.read(n) or b"{}")
        except ValueError:
            return self._send(400, {"error": "bad json"})
        routes = {
            "/key": lambda: P.key(b.get("key", ""), b.get("repeat", 1)),
            "/open": lambda: P.open(b.get("app", "")),
            "/volume": lambda: P.volume(b.get("level"), b.get("action")),
            "/youtube": lambda: P.youtube(b.get("query"), b.get("url")),
            "/text": lambda: P.text(b.get("text", "")),
            "/pair": lambda: P.pair(b["host"], b["port"], b["code"]),
        }
        if self.path in routes:
            return self._run(routes[self.path])
        self._send(404, {"error": "not found"})


if __name__ == "__main__":
    os.makedirs(STATE, exist_ok=True)
    adb("start-server", timeout=20)
    log(f"Projector bridge on 127.0.0.1:{PORT}")
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()

#!/usr/bin/env bash
# One-time pairing of the home server with the projector (like pairing the phone app).
#
# On the projector (use the Projector Remote app as the remote):
#   Settings → System → Developer options → Wireless debugging → "Pair device with pairing code"
# It shows a 6-digit code and an address like 192.168.1.9:37123 — type them below.
set -euo pipefail
echo "On the projector open: Wireless debugging → Pair device with pairing code"
read -r -p "IP address and port shown under the code (e.g. 192.168.1.9:37123): " addr
read -r -p "6-digit pairing code: " code
host="${addr%%:*}"; port="${addr##*:}"
python3 - "$host" "$port" "$code" <<'PY'
import json, sys, urllib.request
host, port, code = sys.argv[1:4]
req = urllib.request.Request("http://127.0.0.1:9830/pair", data=json.dumps({"host": host, "port": int(port), "code": code}).encode(),
                             headers={"Content-Type": "application/json"}, method="POST")
try:
    r = json.loads(urllib.request.urlopen(req, timeout=40).read())
except urllib.error.HTTPError as e:
    r = json.loads(e.read())
print("Paired ✓ — Claude can control the projector now." if r.get("paired") else "Pairing failed: " + str(r.get("message") or r.get("error")))
if r.get("paired"):
    try:
        s = json.loads(urllib.request.urlopen("http://127.0.0.1:9830/status", timeout=30).read())
        print("Projector:", s.get("projector"), "· screen on:", s.get("screen_on"), "· app:", s.get("current_app"))
    except Exception as e:
        print("(status check:", e, ")")
PY

#!/usr/bin/env bash
# Installs / updates the projector bridge on the home server.
#   bash ~/Projects/ProjectorRemote/server/install.sh
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p state && chmod 700 state
echo "Building and starting the projector bridge (sudo is needed for Docker)…"
sudo -v
sudo LOCAL_UID="$(id -u)" LOCAL_GID="$(id -g)" docker compose up -d --build
sleep 3
if python3 -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:9830/hello', timeout=5)" 2>/dev/null; then
  echo "Bridge is running."
else
  echo "Bridge did not start — logs:"; sudo docker logs --tail 30 brijendra-projector-bridge-1; exit 1
fi
cat <<EOT

Next (one time): pair the server with the projector
  bash ~/Projects/ProjectorRemote/server/pair.sh
EOT

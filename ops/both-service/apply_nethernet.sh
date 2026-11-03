#!/bin/bash
# Bedrock 26.60 対応 (NetherNet + 19132/tcp 公開) を Velocity に反映する。誰もいなくなってから一度だけ作り直す
cd "$(dirname "$0")"
ONLINE_FILE=./velocity/plugins/toro-proxy/online.txt
log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"; }
zero=0
for ((i = 0; i < 720; i++)); do
    n=-1
    if read -r c t < "$ONLINE_FILE" 2>/dev/null && [ $(( $(date +%s) - t )) -le 60 ]; then n=$c; fi
    if [ "$n" = 0 ]; then zero=$((zero + 1)); else zero=0; fi
    [ "$zero" -ge 2 ] && break
    sleep 60
done
if [ "$zero" -lt 2 ]; then log "12時間待っても無人にならなかったので見送り"; exit 1; fi
docker compose up -d --force-recreate toro-velocity && log "Velocity を作り直しました (NetherNet / 19132 tcp+udp)"

#!/bin/bash
set -euo pipefail
ONLINE_FILE=/home/service-user/velocity/plugins/toro-proxy/online.txt
LOG_FILE=/home/service-user/update_when_empty.log

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*" | tee -a "$LOG_FILE"; }

log "=== 無人時自動アップデート待機スクリプトを開始しました ==="

online_players() {
    local n t
    if [ ! -r "$ONLINE_FILE" ] || ! read -r n t < "$ONLINE_FILE"; then echo -1; return; fi
    if [ $(( $(date +%s) - t )) -le 60 ]; then echo "$n"; else echo -1; fi
}

zero_count=0
while true; do
    n=$(online_players)
    log "現在のオンライン人数: $n 人"
    if [ "$n" -eq 0 ]; then
        zero_count=$((zero_count + 1))
        log "プレイヤー 0 人を検知 ($zero_count / 2 回)"
        if [ "$zero_count" -ge 2 ]; then
            break
        fi
    else
        zero_count=0
    fi
    sleep 60
done

log "プレイヤーが 0 人であることを確認しました。アップデート処理を実行します。"

# 1. TORO-Proxy の更新
log "1. TORO-Proxy 1.2.0 を配置します..."
sudo -u prod-host rm -f /home/service-user/velocity/plugins/TORO-Proxy-*.jar
sudo -u prod-host cp /tmp/TORO-Proxy-1.2.0.jar /home/service-user/velocity/plugins/TORO-Proxy-1.2.0.jar
sudo -u prod-host chmod 664 /home/service-user/velocity/plugins/TORO-Proxy-1.2.0.jar
echo "1.2.0" | sudo -u prod-host tee /home/service-user/proxy-plugin.tag > /dev/null

# 2. update_velocity.sh の PROXY_TAG を 1.2.0 に更新
sed -i 's/PROXY_TAG=1.1.0/PROXY_TAG=1.2.0/' /home/service-user/update_velocity.sh

# 3. Velocity (toro-velocity) の再起動
log "2. Velocity (toro-velocity) を再起動します..."
cd /home/service-user
docker compose restart toro-velocity

# 4. map-web の更新と再ビルド・再起動
log "3. map-web を更新し、再ビルド・再起動します..."
mv /tmp/clients_lf.ts /home/server-user/map-web/server/utils/clients.ts
cd /home/server-user
docker compose build toro-map-web
docker compose up -d --no-deps toro-map-web

log "=== すべてのアップデートが正常に完了しました ==="

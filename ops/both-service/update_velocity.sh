#!/bin/bash
# 毎朝 05:25 に reboot.sh から呼ぶ。Geyser・Floodgate・ViaVersion・ViaBackwards と Velocity のイメージを先に取ってきて、
# 変わったものがあるときだけ、プロキシに誰もいなくなってから Velocity を作り直す (Velocity の再起動は全員が切れるため)
# 誰もいなくならなければ今日は見送り、明日また試す。変わったものが無ければ何も止めない
cd "$(dirname "$0")"
log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"; }

ONLINE_FILE=./velocity/plugins/toro-proxy/online.txt
IMAGE=ghcr.io/malken21/docker-minecraft-velocity:latest
WAIT_MINUTES=${WAIT_MINUTES:-360}

online_players() {
    local n t
    if [ ! -r "$ONLINE_FILE" ] || ! read -r n t < "$ONLINE_FILE"; then echo -1; return; fi
    if [ $(( $(date +%s) - t )) -le 60 ]; then echo "$n"; else echo -1; fi
}

if ! ping -w 10 8.8.8.8 &> /dev/null; then log "インターネットに接続できません"; exit 1; fi

# 動いている Velocity が読んでいる JAR を上書きしないよう、別の場所に取ってくる
UPD=./updates
rm -rf "$UPD"
mkdir -p "$UPD/velocity/plugins"
cp -p ./velocity/plugins/Geyser-Velocity.jar ./velocity/plugins/Floodgate-velocity.jar "$UPD/velocity/plugins/" 2> /dev/null
python3 getLatestGeyser.py velocity "$UPD/velocity/plugins/Geyser-Velocity.jar"
python3 getLatestFloodgate.py velocity "$UPD/velocity/plugins/Floodgate-velocity.jar"
(cd "$UPD" && python3 ../getVia.py)

changed=()
for jar in "$UPD"/velocity/plugins/*.jar; do
    name=$(basename "$jar")
    if ! python3 -m zipfile -t "$jar" > /dev/null 2>&1; then log "壊れているので使いません: $name"; continue; fi
    cmp -s "$jar" "./velocity/plugins/$name" || changed+=("$name")
done

docker compose pull -q
running=$(docker inspect -f '{{.Image}}' toro-velocity 2> /dev/null)
latest=$(docker image inspect -f '{{.Id}}' "$IMAGE" 2> /dev/null)
[ -n "$running" ] && [ "$running" != "$latest" ] && changed+=("image")

# limbo など、止まっているものだけ起こす (動いている Velocity は作り直さない)
docker compose up -d --no-recreate

if [ ${#changed[@]} -eq 0 ]; then
    log "更新はありません。Velocity は止めません"
    exit 0
fi
log "更新があります: ${changed[*]}。プロキシに誰もいなくなるのを待ちます (最大 ${WAIT_MINUTES} 分)"

zero=0
for ((i = 0; i < WAIT_MINUTES; i++)); do
    n=$(online_players)
    if [ "$n" = 0 ]; then zero=$((zero + 1)); else zero=0; fi
    [ "$zero" -ge 2 ] && break
    sleep 60
done
if [ "$zero" -lt 2 ]; then
    log "誰もいなくならなかったので、今日の更新は見送ります (最後の人数: $(online_players))"
    exit 0
fi

for name in "${changed[@]}"; do
    [ "$name" = image ] && continue
    cp "$UPD/velocity/plugins/$name" "./velocity/plugins/$name"
    log "入れました: $name"
done
docker compose up -d --force-recreate toro-velocity
log "Velocity を作り直しました"

#!/bin/bash
# scripts/*.sh から source する共通の関数。PROJECT_DIR を決めてから読む

# TORO-Proxy (Velocity のプラグイン) に人数を聞く。統合版の人も数える
# Velocity の配置先はリポジトリに書かず、環境変数 TORO_VELOCITY_DIR で渡す。
# 未設定なら PROJECT_DIR の ops.env (git 管理外) も読む。それでも無ければ止まる
if [ -z "${TORO_VELOCITY_DIR:-}" ] && [ -n "${PROJECT_DIR:-}" ] && [ -f "$PROJECT_DIR/ops.env" ]; then
    # shellcheck disable=SC1091
    . "$PROJECT_DIR/ops.env"
fi
: "${TORO_VELOCITY_DIR:?TORO_VELOCITY_DIR が未設定です。環境変数か ops.env で指定してください (ops/toroserver/ops.env.example 参照)}"
MC_CMD="$PROJECT_DIR/.agents/skills/toroserver-admin/scripts/mc_cmd.py"
STAGE_DIR="$PROJECT_DIR/staged"
APPLIED_DIR="$PROJECT_DIR/staged-applied"

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"; }

# TORO-Proxy のステータスエンドポイント (HTTP) で人数を聞く。統合版の人も数える
# 応答が無い・壊れているときは分からないので -1 を返す
STATUS_URL=${TORO_STATUS_URL:-http://127.0.0.1:18089/status}
online_players() {
    local out n
    out=$(curl -fsS --max-time 5 "$STATUS_URL" 2>/dev/null) || { echo -1; return; }
    n=$(printf '%s' "$out" | jq -r 'if (.online | type) == "number" then .online else empty end' 2>/dev/null)
    case "$n" in
        ''|*[!0-9]*) echo -1 ;;
        *) echo "$n" ;;
    esac
}

mc_cmd() { python3 "$MC_CMD" "$1" toro-main > /dev/null; }

announce() {
    local json
    json=$(python3 -c "import json, sys; print(json.dumps({'text': f'[TORO] {sys.argv[1]}', 'color': 'yellow'}))" "$1")
    mc_cmd "tellraw @a $json"
}

# 人がいるときだけ、再起動の前に知らせて待つ。いないときはすぐ返る
countdown() {
    local secs=${1:-60}
    if ! [[ "$secs" =~ ^[0-9]+$ ]]; then secs=60; fi
    local n
    n=$(online_players)
    if [ "$n" = 0 ]; then return; fi
    if [ "$secs" -gt 10 ]; then
        announce "${secs} 秒後に再起動します。再起動のあいだは待機所に移り、起動したら自動で戻ります。"
        sleep $((secs - 10))
    fi
    announce "10 秒後に再起動します。"
    sleep 10
}

# toro-main の起動が終わる (ログに Done が出る) まで待つ
wait_ready() {
    local since=$1 timeout=${2:-600} waited=0
    while [ "$waited" -lt "$timeout" ]; do
        if docker logs --since "$since" toro-main 2>&1 | grep -aq 'Done ('; then return 0; fi
        sleep 5
        waited=$((waited + 5))
    done
    return 1
}

# 起動を確かめ、staged を入れた直後に起動しなかったら元に戻して起動し直す
start_and_check() {
    local since
    since=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
    docker compose up -d --remove-orphans
    if wait_ready "$since" 600; then
        log "toro-main の起動を確認しました"
        rm -f "$APPLIED_DIR/PENDING"
        return 0
    fi
    if [ ! -f "$APPLIED_DIR/PENDING" ]; then
        log "toro-main が 10 分たっても起動しません (今回 staged から入れたものはありません)"
        return 1
    fi
    local dest
    dest=$(cat "$APPLIED_DIR/PENDING")
    log "toro-main が 10 分たっても起動しないので、今回入れたもの ($dest) を元に戻します"
    docker compose stop toro-main
    bash "$dest/rollback.sh"
    rm -f "$APPLIED_DIR/PENDING"
    since=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
    docker compose up -d --remove-orphans
    if wait_ready "$since" 600; then log "元に戻して起動しました"; else log "元に戻しても起動しません。手で確認してください"; fi
    return 1
}

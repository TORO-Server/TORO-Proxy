#!/bin/bash
# 計画した再起動。新しいイメージを先に取ってきて、人がいれば知らせ、toro-main だけを止めて staged を入れ、起動を確かめる
# 再起動のあいだ、プレイヤーは Velocity の limbo で待ち、起動したら TORO-Proxy が自動で戻す
# 使い方: ./restart.sh [知らせてから止めるまでの秒数 (既定 60)]
set -uo pipefail

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
PROJECT_DIR=$(cd "$SCRIPT_DIR/.." && pwd)
cd "$PROJECT_DIR"
source "$SCRIPT_DIR/lib.sh"

log "再起動を始めます"
docker compose pull -q || log "イメージの取得に失敗しました (今のイメージのまま続けます)"
countdown "${1:-60}"
docker compose stop toro-main
bash "$SCRIPT_DIR/apply_staged.sh" || log "staged を入れられませんでした (入れずに起動します)"
start_and_check

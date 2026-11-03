#!/bin/bash
set -e

SCRIPT_DIR=$(cd $(dirname $0) && pwd)
PROJECT_DIR=$(cd "$SCRIPT_DIR/.." && pwd)
cd "$PROJECT_DIR"
source "$SCRIPT_DIR/lib.sh"

LOG_FILE="$PROJECT_DIR/mirroring/backup.log"

echo "==========================================" >> "$LOG_FILE"
echo "[$(date '+%Y-%m-%d %H:%M:%S')] Starting Daily Backup Pipeline..." >> "$LOG_FILE"

# 0. Pull latest Docker images while server is online (zero-downtime pull)
echo "[$(date '+%Y-%m-%d %H:%M:%S')] Pulling latest Docker images..." >> "$LOG_FILE"
docker compose pull >> "$LOG_FILE" 2>&1 || true

# 1. 動いているうちに一度ミラーリングしておく。止めてからの rsync は差分だけになり、止まる時間が短くなる
echo "[$(date '+%Y-%m-%d %H:%M:%S')] Pre-syncing mirror while online..." >> "$LOG_FILE"
bash "$SCRIPT_DIR/mirroring.sh" "main_mirroring0530" >> "$LOG_FILE" 2>&1 || true

# 2. 人がいれば知らせてから止める。止めているあいだ、プレイヤーは limbo で待つ
countdown 60 >> "$LOG_FILE" 2>&1 || true
START_TIME=$(date +%s)
echo "[$(date '+%Y-%m-%d %H:%M:%S')] Stopping server..." >> "$LOG_FILE"
bash "$SCRIPT_DIR/stop.sh" >> "$LOG_FILE" 2>&1

# 3. Run Mirroring (rsync)
echo "[$(date '+%Y-%m-%d %H:%M:%S')] Running rsync mirroring..." >> "$LOG_FILE"
bash "$SCRIPT_DIR/mirroring.sh" "main_mirroring0530" >> "$LOG_FILE" 2>&1

# 4. staged/ に置いた更新を入れる (ミラーリングの後なので、バックアップは入れる前の状態)
bash "$SCRIPT_DIR/apply_staged.sh" >> "$LOG_FILE" 2>&1 || echo "[$(date '+%Y-%m-%d %H:%M:%S')] apply_staged failed (starting without it)" >> "$LOG_FILE"

# 5. Immediately Restart Server (起動しなければ、今回入れた staged を元に戻して起動し直す)
echo "[$(date '+%Y-%m-%d %H:%M:%S')] Restarting server..." >> "$LOG_FILE"
start_and_check >> "$LOG_FILE" 2>&1 || true
RESTART_TIME=$(date +%s)
DOWNTIME=$((RESTART_TIME - START_TIME))
echo "[$(date '+%Y-%m-%d %H:%M:%S')] Server back online! Total downtime: ${DOWNTIME} seconds." >> "$LOG_FILE"

# 6. ワールドと DB を Google ドライブへ送り、古いものを整理する (バックグラウンド)
echo "[$(date '+%Y-%m-%d %H:%M:%S')] Triggering background Google Drive backups..." >> "$LOG_FILE"
(
    bash "$SCRIPT_DIR/backup.sh" "main_mirroring0530" >> "$LOG_FILE" 2>&1 || echo "[$(date '+%Y-%m-%d %H:%M:%S')] world backup to Google Drive failed" >> "$LOG_FILE"
    bash "$SCRIPT_DIR/backup_db.sh" >> "$LOG_FILE" 2>&1 || echo "[$(date '+%Y-%m-%d %H:%M:%S')] DB backup to Google Drive failed" >> "$LOG_FILE"
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] Background backup completed." >> "$LOG_FILE"
) &

echo "[$(date '+%Y-%m-%d %H:%M:%S')] Daily Backup Pipeline initiated successfully." >> "$LOG_FILE"

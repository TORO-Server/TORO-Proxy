#!/bin/bash
# staged/ に置いた変更を main/ に入れる。toro-main が止まっているときに、daily_backup.sh と restart.sh から呼ぶ
#   staged/plugins/*.jar        plugins/ に入れる (同じ名前のものは置き換える)
#   staged/remove-plugins.txt   plugins/ から外す JAR の名前 (1 行に 1 つ。# 以降は無視)
#   staged/main/...             main/ の同じ場所に写す (設定ファイルなど)
# 入れたものと元の版は staged-applied/<時刻>/ に残し、rollback.sh で元に戻せる
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
PROJECT_DIR=$(cd "$SCRIPT_DIR/.." && pwd)
source "$SCRIPT_DIR/lib.sh"
MAIN="$PROJECT_DIR/main"

if docker ps --format '{{.Names}}' | grep -qx toro-main; then
    log "toro-main が動いているので staged は入れません"
    exit 1
fi

mkdir -p "$STAGE_DIR/plugins" "$STAGE_DIR/main"
has_jar=$(find "$STAGE_DIR/plugins" -maxdepth 1 -name '*.jar' -type f -print -quit)
has_file=$(find "$STAGE_DIR/main" -type f -print -quit)
has_remove=$(grep -v '^\s*\(#\|$\)' "$STAGE_DIR/remove-plugins.txt" 2>/dev/null | head -1 || true)
if [ -z "$has_jar$has_file$has_remove" ]; then
    log "staged: 入れるものはありません"
    exit 0
fi

dest="$APPLIED_DIR/$(date '+%Y%m%d-%H%M%S')"
mkdir -p "$dest/backup/plugins" "$dest/backup/main"
undo="$dest/undo.list"
: > "$undo"

# 元に戻す手順は 1 行ずつ書き、rollback.sh で逆の順に実行する。名前に ' があるものは扱わない
safe_name() { case "$1" in *\'*|*/..*|../*|..) return 1 ;; *) return 0 ;; esac; }

if [ -n "$has_remove" ]; then
    while read -r line || [ -n "$line" ]; do
        f=$(echo "${line%%#*}" | xargs)
        [ -z "$f" ] && continue
        if [[ "$f" == */* ]] || ! safe_name "$f"; then log "外す名前が不正なので飛ばします: $f"; continue; fi
        if [ -e "$MAIN/plugins/$f" ]; then
            mv "$MAIN/plugins/$f" "$dest/backup/plugins/$f"
            echo "mv '$dest/backup/plugins/$f' '$MAIN/plugins/$f'" >> "$undo"
            log "外しました: plugins/$f"
        else
            log "外す JAR がありません: plugins/$f"
        fi
    done < "$STAGE_DIR/remove-plugins.txt"
fi

for jar in "$STAGE_DIR"/plugins/*.jar; do
    [ -f "$jar" ] || continue
    f=$(basename "$jar")
    safe_name "$f" || { log "名前が不正なので飛ばします: $f"; continue; }
    if [ -e "$MAIN/plugins/$f" ]; then
        cp -p "$MAIN/plugins/$f" "$dest/backup/plugins/$f"
        echo "cp -p '$dest/backup/plugins/$f' '$MAIN/plugins/$f'" >> "$undo"
    else
        echo "rm -f '$MAIN/plugins/$f'" >> "$undo"
    fi
    cp "$jar" "$MAIN/plugins/$f"
    log "入れました: plugins/$f"
done

while IFS= read -r -d '' rel; do
    rel=${rel#./}
    safe_name "$rel" || { log "名前が不正なので飛ばします: $rel"; continue; }
    target="$MAIN/$rel"
    if [ -e "$target" ]; then
        mkdir -p "$dest/backup/main/$(dirname "$rel")"
        cp -p "$target" "$dest/backup/main/$rel"
        echo "cp -p '$dest/backup/main/$rel' '$target'" >> "$undo"
    else
        echo "rm -f '$target'" >> "$undo"
    fi
    mkdir -p "$(dirname "$target")"
    cp "$STAGE_DIR/main/$rel" "$target"
    log "入れました: $rel"
done < <(cd "$STAGE_DIR/main" && find . -type f -print0)

cat > "$dest/rollback.sh" <<EOF
#!/bin/bash
# $dest で入れたものを元に戻す。toro-main を止めてから実行する
set -e
tac '$undo' | bash -e
echo "元に戻しました: $dest"
EOF
chmod +x "$dest/rollback.sh"

# 入れ終わった staged の中身は、記録として移しておく
mkdir -p "$dest/input"
mv "$STAGE_DIR/plugins" "$STAGE_DIR/main" "$dest/input/"
[ -f "$STAGE_DIR/remove-plugins.txt" ] && mv "$STAGE_DIR/remove-plugins.txt" "$dest/input/"
mkdir -p "$STAGE_DIR/plugins" "$STAGE_DIR/main"

echo "$dest" > "$APPLIED_DIR/PENDING"
echo "$dest" > "$APPLIED_DIR/LAST"
log "staged を入れました。元に戻すには: toro-main を止めてから bash $dest/rollback.sh"

#!/bin/bash
# ops/ のスクリプトの試験 (CI と手元)。作業用のディレクトリの中だけで動かす。docker は偽物を使う
set -uo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
LIB=$ROOT/ops/toroserver/scripts/lib.sh
APPLY=$ROOT/ops/toroserver/scripts/apply_staged.sh
UPDATE=$ROOT/ops/both-service/update_velocity.sh
WORK=$(mktemp -d)
trap 'kill ${STUB_PID:-} 2> /dev/null; rm -rf "$WORK"' EXIT

fails=0
check() {
    if [ "$2" = "$3" ]; then
        echo "ok   $1"
    else
        echo "FAIL $1: 期待 [$2] 実際 [$3]"
        fails=$((fails + 1))
    fi
}

# --- online_players ---
fn() { sed -n '/^online_players() {$/,/^}$/p' "$1"; }
check "online_players が lib.sh と update_velocity.sh で同じ" "$(fn "$LIB")" "$(fn "$UPDATE")"
check "ステータスを問い合わせるスクリプトは 2 つだけ" 2 "$(grep -rl --include='*.sh' 'TORO_STATUS_URL' "$ROOT/ops" | wc -l | tr -d ' ')"

PROJECT_DIR=$WORK/project-online
export TORO_VELOCITY_DIR=$WORK/velocity

# TORO_STATUS_URL を向ける先の軽い HTTP スタブ。応答の中身はファイルで差し替える
STUB_BODY=$WORK/stub_body.json
cat > "$WORK/stub.py" << 'EOF'
import http.server, pathlib, sys
body_file = pathlib.Path(sys.argv[2])
class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        try:
            data = body_file.read_bytes()
        except OSError:
            data = b"{}"
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)
    def log_message(self, *args):
        pass
http.server.HTTPServer(("127.0.0.1", int(sys.argv[1])), Handler).serve_forever()
EOF
free_port() { python3 -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()'; }
STUB_PORT=$(free_port)
DEAD_PORT=$(free_port)
export TORO_STATUS_URL="http://127.0.0.1:$STUB_PORT/status"
printf '%s' '{"online":3,"epoch":1794155059}' > "$STUB_BODY"
python3 "$WORK/stub.py" "$STUB_PORT" "$STUB_BODY" &
STUB_PID=$!
for _ in $(seq 1 50); do
    curl -fsS --max-time 2 "$TORO_STATUS_URL" > /dev/null 2>&1 && break
    sleep 0.1
done

# shellcheck source=/dev/null
source "$LIB"

set_status() { printf '%s' "$1" > "$STUB_BODY"; }

set_status '{"online":3,"epoch":1794155059}'; check "正常な JSON で人数" 3 "$(online_players)"
set_status '{"online":0,"epoch":1794155059}'; check "0 人" 0 "$(online_players)"
set_status '{"epoch":1794155059}'; check "online が無い" -1 "$(online_players)"
set_status '{"online":"3","epoch":1794155059}'; check "online が数でない" -1 "$(online_players)"
set_status '{"online":-3,"epoch":1794155059}'; check "人数が負" -1 "$(online_players)"
set_status 'not json'; check "JSON でない" -1 "$(online_players)"
set_status ''; check "空の応答" -1 "$(online_players)"
STATUS_URL="http://127.0.0.1:$DEAD_PORT/status"; check "接続できない" -1 "$(online_players)"
STATUS_URL=$TORO_STATUS_URL

# --- PROXY_TAG ---
PROXY_TAG_VALUE=$(grep -oE '^PROXY_TAG=[0-9.]+' "$UPDATE" | cut -d= -f2)
GRADLE_VERSION=$(grep -oE '^version = "[0-9.]+"' "$ROOT/build.gradle.kts" | cut -d'"' -f2)
check "TORO-Proxy の版が build.gradle.kts と同じ" "$GRADLE_VERSION" "$PROXY_TAG_VALUE"

# --- apply_staged / rollback ---
PROJECT=$WORK/project
OUTSIDE=$WORK/outside
mkdir -p "$PROJECT/scripts" "$PROJECT/main/plugins" "$PROJECT/staged/plugins" "$PROJECT/staged/main" \
    "$PROJECT/staged-applied" "$OUTSIDE/dir" "$WORK/bin"
cp "$APPLY" "$LIB" "$PROJECT/scripts/"
cat > "$WORK/bin/docker" << 'EOF'
#!/bin/bash
# 偽物の docker。update_velocity.sh と apply_staged.sh が見る出力だけを返す
case "$1 ${2:-}" in
    "compose config") echo ghcr.io/toro-server/docker-minecraft-velocity:4.3.1 ;;
    "create "*) echo "$2" >> "$DOCKER_LOG"; echo cid ;;
    "cp "*) printf 'proxy\n' > "$3" ;;
    "inspect "*|"image inspect") echo same-image ;;
    "compose up") echo "$*" >> "$DOCKER_LOG" ;;
esac
exit 0
EOF
chmod +x "$WORK/bin/docker"
export PATH="$WORK/bin:$PATH"
export DOCKER_LOG=$WORK/docker.log

printf 'old\n' > "$PROJECT/main/plugins/normal.jar"
printf 'new\n' > "$PROJECT/staged/plugins/normal.jar"
mkdir -p "$PROJECT/staged/main/fresh"
printf 'fresh\n' > "$PROJECT/staged/main/fresh/a.yml"

has_links=0
if ln -s "$OUTSIDE/victim.jar" "$PROJECT/main/plugins/linked.jar" 2> /dev/null && [ -L "$PROJECT/main/plugins/linked.jar" ]; then
    has_links=1
    printf 'victim\n' > "$OUTSIDE/victim.jar"
    printf 'new\n' > "$PROJECT/staged/plugins/linked.jar"
    ln -s "$OUTSIDE/dir" "$PROJECT/main/config"
    mkdir -p "$PROJECT/staged/main/config"
    printf 'new\n' > "$PROJECT/staged/main/config/b.yml"
else
    echo "skip シンボリックリンクの試験 (この環境では作れない)"
fi

bash "$PROJECT/scripts/apply_staged.sh" > "$WORK/apply.log" 2>&1
check "apply_staged が成功" 0 "$?"
check "JAR を入れた" new "$(cat "$PROJECT/main/plugins/normal.jar")"
check "新しいファイルを入れた" fresh "$(cat "$PROJECT/main/fresh/a.yml")"
if [ "$has_links" = 1 ]; then
    check "リンクの先を書き換えない" victim "$(cat "$OUTSIDE/victim.jar")"
    check "リンク自身を置き換える" no "$([ -L "$PROJECT/main/plugins/linked.jar" ] && echo yes || echo no)"
    check "main/ の外には書かない" no "$([ -e "$OUTSIDE/dir/b.yml" ] && echo yes || echo no)"
fi

dest=$(cat "$PROJECT/staged-applied/LAST")
if [ "$has_links" = 1 ]; then
    # 入れた後でディレクトリがリンクに差し替えられても、main/ の外は消さない
    printf 'keep\n' > "$OUTSIDE/dir/a.yml"
    rm -rf "$PROJECT/main/fresh"
    ln -s "$OUTSIDE/dir" "$PROJECT/main/fresh"
fi
bash "$dest/rollback.sh" > "$WORK/rollback.log" 2>&1
check "rollback が成功" 0 "$?"
check "元の JAR に戻す" old "$(cat "$PROJECT/main/plugins/normal.jar")"
if [ "$has_links" = 1 ]; then
    check "rollback は main/ の外を消さない" keep "$(cat "$OUTSIDE/dir/a.yml")"
    check "rollback でリンクも元に戻す" yes "$([ -L "$PROJECT/main/plugins/linked.jar" ] && echo yes || echo no)"
    check "rollback でもリンクの先を書き換えない" victim "$(cat "$OUTSIDE/victim.jar")"
else
    check "rollback で新しいファイルを消す" no "$([ -e "$PROJECT/main/fresh/a.yml" ] && echo yes || echo no)"
fi

# --- update_velocity ---
# 通信と待ち時間を偽物に置き換える。fetch_plugins.py の代わりに、固定した JAR が 2 つ取れたことにする
cat > "$WORK/bin/python3" << 'EOF'
#!/bin/bash
if [ "$1" = fetch_plugins.py ]; then
    printf 'new\n' > "$3/Floodgate-velocity.jar"
    printf 'new\n' > "$3/ViaVersion.jar"
fi
exit 0
EOF
printf '#!/bin/bash\nexit 0\n' > "$WORK/bin/ping"
printf '#!/bin/bash\nexit 0\n' > "$WORK/bin/sleep"
chmod +x "$WORK/bin/python3" "$WORK/bin/ping" "$WORK/bin/sleep"

both() {
    mkdir -p "$1/velocity"
    cp "$UPDATE" "$1/"
}
set_status '{"online":0,"epoch":1794155059}'

BOTH=$WORK/both
both "$BOTH"
mkdir -p "$BOTH/velocity/plugins/toro-proxy"
printf 'old\n' > "$BOTH/velocity/plugins/Floodgate-velocity.jar"
printf 'victim\n' > "$OUTSIDE/via.jar"
if ln -s "$OUTSIDE/via.jar" "$BOTH/velocity/plugins/ViaVersion.jar" 2> /dev/null && [ ! -L "$BOTH/velocity/plugins/ViaVersion.jar" ]; then
    rm -f "$BOTH/velocity/plugins/ViaVersion.jar"
fi
bash "$BOTH/update_velocity.sh" > "$WORK/update.log" 2>&1
check "update_velocity が成功" 0 "$?"
check "JAR を入れた (update_velocity)" new "$(cat "$BOTH/velocity/plugins/Floodgate-velocity.jar")"
check "TORO-Proxy を入れた" proxy "$(cat "$BOTH/velocity/plugins/TORO-Proxy.jar")"
check "リンクの先を書き換えない (update_velocity)" victim "$(cat "$OUTSIDE/via.jar")"
check "リンク自身を置き換える (update_velocity)" no "$([ -L "$BOTH/velocity/plugins/ViaVersion.jar" ] && echo yes || echo no)"
check "TORO-Proxy を PROXY_TAG の版で取る" 1 "$(grep -cE "^ghcr\\.io/toro-server/toro-proxy:${PROXY_TAG_VALUE}$" "$DOCKER_LOG")"
check "Velocity を作り直す" 1 "$(grep -c 'toro-velocity' "$DOCKER_LOG")"

if [ "$has_links" = 1 ]; then
    # plugins がリンクで外を指しているときは、何も入れない
    : > "$DOCKER_LOG"
    BOTH2=$WORK/both2
    both "$BOTH2"
    mkdir -p "$OUTSIDE/plugins/toro-proxy"
    ln -s "$OUTSIDE/plugins" "$BOTH2/velocity/plugins"
    bash "$BOTH2/update_velocity.sh" > "$WORK/update2.log" 2>&1
    check "plugins がリンクなら止まる" 1 "$?"
    check "plugins がリンクなら外に書かない" no "$([ -e "$OUTSIDE/plugins/ViaVersion.jar" ] && echo yes || echo no)"
    check "plugins がリンクなら作り直さない" 0 "$(grep -c 'toro-velocity' "$DOCKER_LOG")"
fi

if [ "$fails" -ne 0 ]; then
    echo "$fails 件失敗しました"
    cat "$WORK/apply.log" "$WORK/rollback.log" "$WORK/update.log" "$WORK/update2.log" 2> /dev/null
    exit 1
fi
echo "すべて通りました"

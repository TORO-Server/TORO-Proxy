# TORO-Proxy

TORO サーバーを、プレイヤーを切断せずに更新するための仕組みです。Velocity 4.3.1 のプラグインと、サーバー側のスクリプトから成ります。

**このリポジトリは private です。** `ops/` にサーバーのパスやユーザー名が入っているため、公開しないでください。

## 仕組み

```
プレイヤー ─▶ Velocity (toro-velocity) ─▶ toro (toro-main, Paper)
                     │
                     └─ toro が止まっている間 ─▶ limbo (toro-limbo, PicoLimbo)
```

- **toro-main の再起動中**: プレイヤーは limbo で待ちます。toro が起動すると、TORO-Proxy が自動で元のサーバーに戻します。待ち時間は、毎朝の再起動で 1〜2 分ほどです。
- **再起動とみなすもの**: Paper の停止メッセージ (`Server closed`、`Server is restarting`) によるキック、理由のない切断（落ちたとき）、繋がらないとき。
- **再起動とみなさないもの**: BAN、`/kick`、ホワイトリスト、満員などのキック。limbo を挟まずに、今までどおり切断します。
- **戻すときに断られた場合**: limbo にいる間に BAN された場合などです。3 回断られたら、その理由を表示して切断します。
- **limbo の隠蔽**: 待機用の `limbo` は、Velocity 組み込みの `/server` コマンドの一覧やタブ補完から完全に除外されます。また、手動で `/server limbo` やパケット等で接続しようとしても拒絶されます。
- **プロキシの人数**: `plugins/toro-proxy/online.txt` に 10 秒ごとに「人数 時刻」を書きます。統合版の人も数えます。サーバー側のスクリプトは、これを見て誰もいないときだけ Velocity を再起動します。
- **つながっている人の IP**: 人の出入りがあったとき（イベント駆動）に、全員の接続元の IP を `toro-map-web` (`clients-endpoint`、合言葉は `clients-token`) に送ります。直前と IP リストに変化がない場合は無駄な通信を行いません。
  assets.torosaba.net (MarumaSign の看板の写し) は、これで「いま遊んでいる人の Mod からの読み込みか」を確かめます。どう確かめるか (ログアウト後の猶予など) は toro-map-web が決めるので、変えても Velocity や Paper の再起動はいりません。
  入口の Velocity が見ている接続元なので、Paper の再起動中に limbo で待っている人も含みます。
- **ドメイン別 Ping レスポンス (ServerListPlus 完全移行)**: `mc.marumasa.dev` 等のバーチャルホストごとのサーバーリスト（MOTD、アイコン、バージョン表示、プレイヤー数・ホバー表示）を制御します。ServerListPlus を不要にし、`plugins/toro-proxy/ping.yml` でホストごとのルールを一元管理します。
  - `mc.marumasa.dev` からの接続時は、表示バージョンを「1.0.0から99.99.99」（プロトコル -1 で赤文字表示）、アイコンを 3.0式サーバーのアイコンにします。
  - `%gradient#hex1#hex2...%` によるグラデーション記法、`&a` 等の装飾コード、`%online%` / `%max%` プレースホルダーに対応。
  - 設定の変更は `/toroproxy reload` で即座に反映されます。

Velocity 自身の再起動だけは、全員が切断されます。そのため、更新があり、かつ誰もいなくなったときにしか再起動しません。

## プラグイン

```bash
gradle shadowJar
```

`build/libs/TORO-Proxy-<版>.jar` を `/home/service-user/velocity/plugins/` に置き、Velocity を再起動します。設定ファイルは `plugins/toro-proxy/config.properties` です。初回の起動で作られます。

`velocity.toml` には、limbo を後ろに足しておきます。

```toml
[servers]
	limbo = "toro-limbo:25565"
	try = ["toro", "limbo"]

[forced-hosts]
	"torosaba.net" = ["toro", "limbo"]
	"minuma.torosaba.net" = ["toro", "limbo"]
```

## イメージ (GHCR、非公開)

GitHub Actions が、試験に通った JAR を `ghcr.io/toro-server/toro-proxy` に置きます。中身は `/TORO-Proxy.jar` だけで、動かすものではありません。
タグは、`latest` (main の先頭)、`sha-<短い ID>`、リリース (`v1.2.3` のタグを打つと `1.2.3` と `1.2`) です。

サーバーの `update_velocity.sh` が、`PROXY_TAG` の版を取り出して `velocity/plugins/TORO-Proxy.jar` に入れます (ほかのプラグインと同じく、誰もいなくなってから)。上げるときは、タグを打って CI が終わってから、`PROXY_TAG` を書き換えます。

イメージは非公開なので、`update_velocity.sh` を動かすユーザー (prod-host の cron) で、先に `read:packages` だけの PAT で入っておきます。

```bash
echo "<read:packages だけの PAT>" | docker login ghcr.io -u <GitHub のユーザー名> --password-stdin
```

## サーバー側 (`ops/`)

| ファイル | 置き場所 | 役割 |
| --- | --- | --- |
| `both-service/docker-compose.yaml` | `/home/service-user/` | Velocity に limbo (PicoLimbo) を足したもの。limbo のポートは公開しません。 |
| `both-service/limbo/server.toml` | `/home/service-user/limbo/` | limbo の設定 (BungeeGuard)。トークンは `limbo.env` から渡します（`velocity/forwarding.secret` と同じ値。権限 640）。 |
| `both-service/update_velocity.sh` | `/home/service-user/` | 毎朝 05:25 に `reboot.sh` から呼ばれます。Geyser・Floodgate・Via・TORO-Proxy (GHCR のイメージから)・Velocity のイメージを別の場所に取ってきます。変わったものがあれば、誰もいなくなってから Velocity を作り直します。最大 6 時間待ち、それでも人がいればその日は見送ります。 |
| `toroserver/scripts/daily_backup.sh` | `/home/server-user/scripts/` | 毎朝 05:29 の処理です。動いているうちに一度ミラーリングしてから止めるので、止まる時間が短くなります。人がいれば 60 秒前に知らせ、`staged/` を入れてから起動を確かめます。 |
| `toroserver/scripts/restart.sh` | 同上 | 手で行う計画再起動です。止めるのは toro-main だけで、TiDB・地図・Tunnel は止めません。 |
| `toroserver/scripts/apply_staged.sh` | 同上 | `staged/` の JAR や設定を `main/` に入れます。元の版は `staged-applied/<時刻>/` に残します。 |
| `toroserver/scripts/lib.sh` | 同上 | 共通の関数です。人数の取得、ゲーム内への告知、起動の確認、起動しないときの自動の巻き戻しを持ちます。 |
| `toroserver/staged/README.md` | `/home/server-user/staged/` | staged の使い方です。 |

## 更新のしかた

- **プラグインや設定 (toro-main)**: `/home/server-user/staged/` に置きます。次の朝の再起動か、`./scripts/restart.sh` で入ります。プレイヤーは limbo で待つだけで、切断されません。
- **Paper 本体**: `docker-compose.yaml` のイメージのタグを変えてから `./scripts/restart.sh` を実行します。
- **Velocity のプラグイン**: Geyser・Floodgate・Via は毎朝自動で取ってきて、誰もいないときに入れます。それ以外のプラグインは `velocity/plugins/` に置き、誰もいないときに `docker restart toro-velocity` を実行します。
- **limbo**: `ghcr.io/quozul/picolimbo` のタグを固定しています。新しい Minecraft のクライアントは Velocity の ViaVersion が変換するため、急いで上げる必要はありません。

## 確かめたこと (2026-10-29、ローカル)

ローカルで Velocity 4.3.1、ViaVersion、ViaBackwards、Paper 26.3、PicoLimbo 1.15.0 を動かし、mineflayer (26.1) のボットで次を確かめました。

- Paper の `stop`: limbo に移り、起動 (`Done`) の約 4 秒後に toro に戻りました。
- Paper の強制終了: limbo に移りました。
- Paper が止まっている間のログイン: limbo に入り、起動後に戻りました。
- `/kick`、古いクライアント、ホワイトリスト: limbo に送らず、そのまま切断されました。
- limbo にいる間にホワイトリストで断られた場合: 3 回目で、その理由を表示して切断されました。
- PicoLimbo の BungeeGuard: 環境変数で渡したトークンで接続できました。

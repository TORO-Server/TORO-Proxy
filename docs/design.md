# 設計メモ

概要は [README.md](../README.md) にあります。この文書は、プラグインの動きの詳しい説明をまとめた、ただ 1 つの詳しい文書です。設定できる項目と既定値の正本は、初回の起動で作られる `config.properties` の元になる `ProxyConfig.kt` です。

## 再起動のあいだの扱い

- **ゲームサーバーの再起動中**: プレイヤーは limbo で待ちます。ゲームサーバーが起動すると、このプラグインが自動で元のサーバーに戻します。
- **再起動とみなすもの**: Paper の停止メッセージ (`Server closed`、`Server is restarting`) によるキック、理由のない切断 (落ちたとき)、つながらないとき。
- **再起動とみなさないもの**: BAN、`/kick`、ホワイトリスト、満員などのキック。limbo を挟まずに、今までどおり切断します。
- **戻すときに断られた場合**: limbo にいるあいだに BAN された場合などです。決まった回数 (設定 `max-return-failures`。初期値は 3) 断られたら、その理由を表示して切断します。
- **limbo の隠し方**: 待機用の `limbo` は、Velocity 組み込みの `/server` コマンドの一覧やタブ補完から除外します。手動で `/server limbo` やパケットで接続しようとしても、断ります。
- Velocity 自身の再起動だけは、全員が切断されます。そのため、更新があり、かつ誰もいなくなったときにしか再起動しません。

## 人数の記録

`plugins/toro-proxy/online.txt` に、10 秒ごとに「人数 時刻 (エポック秒)」を書きます (`TOROProxy.kt`)。統合版のプレイヤーも数えます。サーバー側のスクリプトは、これを見て誰もいないときだけ Velocity を再起動します。

## 接続元の IP の通知

人の出入りがあったときに、全員の接続元の IP を、地図サイトの画面のサービス (設定 `clients-endpoint`、合言葉は `clients-token`) に送ります。直前と同じ内容なら送りません。素材配信サイトは、これで「いま遊んでいる人の Mod からの読み込みか」を確かめます。確かめ方 (ログアウト後の猶予など) は受け取る側が決めるので、変えても Velocity や Paper の再起動は要りません。入口の Velocity が見ている接続元なので、Paper の再起動中に limbo で待っている人も含みます。

## ホストごとのサーバーリスト表示

接続に使ったホスト名ごとに、サーバーリストの表示 (MOTD、アイコン、バージョン表示、人数とホバーの表示) を変えられます。ホストごとのルールは `plugins/toro-proxy/ping.yml` に書きます (初期値は `ping-default.yml`)。

- `%gradient#hex1#hex2...%` による色のグラデーション、`&a` などの装飾コード、`%online%` / `%max%` の置き換えに対応しています。
- 設定の変更は `/toroproxy reload` で反映されます。

## Velocity のプラグインの固定

Geyser・Floodgate・ViaVersion・ViaBackwards は、`ops/both-service/plugins.lock.json` でバージョンと SHA-256 (ファイルの指紋) を固定しています。週に 1 度、`plugin-pins` の自動処理が新しいリリースを調べて、変更の提案を作ります (急ぐときは手動でも動かせます)。公開されているハッシュとの照合は `.github/scripts/plugin_pins.py` が行います。取るときは `ops/both-service/fetch_plugins.py` が SHA-256 を照合し、違うものは使いません。

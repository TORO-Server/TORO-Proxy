# TORO-Proxy

TORO サーバーを、プレイヤーを切断せずに更新するための仕組みです。Velocity (Minecraft のプロキシサーバー。プレイヤーと各サーバーの間に入る中継役) のプラグインと、サーバー側のスクリプトでできています。

ゲームサーバーが再起動しているあいだ、プレイヤーは待機用の小さなサーバー (limbo) で待ち、起動したら自動で元のサーバーに戻ります。

サーバー固有の値 (配置先のパスなど) はこの置き場に書かず、環境変数か、git に入れない設定ファイルで渡します ([ops/toroserver/ops.env.example](ops/toroserver/ops.env.example))。

## ビルド

```bash
gradle shadowJar
```

できた `build/libs/TORO-Proxy-<バージョン>.jar` を、Velocity の `plugins/` に置いて Velocity を再起動します。設定ファイルは初回の起動で作られます。使うバージョンは [Releases](https://github.com/TORO-Server/TORO-Proxy/releases) のタグが正本です。

## 関連文書

- 動きの詳しい説明 (再起動とみなすもの、人数の記録、ホストごとの表示の設定): [docs/design.md](docs/design.md)
- 毎朝の更新、手で行う再起動、`staged/` の使い方などの運用手順: [ServerImage-TORO の docs/RUNBOOK.md](https://github.com/TORO-Server/ServerImage-TORO/blob/main/docs/RUNBOOK.md)
- 文章の書き方: [WRITING-STYLE.md](https://github.com/TORO-Server/TORO-Website/blob/main/docs/WRITING-STYLE.md)

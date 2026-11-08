# staged — 次の再起動で入れる更新を置く場所

ここに置いたものは、toro-main が止まっているあいだに `scripts/apply_staged.sh` が `main/` に入れます。
次のどちらかの再起動で入ります。

- 毎朝の `scripts/daily_backup.sh`。
- 手で実行する `./scripts/restart.sh [秒]`。人がいれば知らせてから止めます。既定は 60 秒前です。

再起動のあいだ、プレイヤーは Velocity の limbo で待ちます。起動したら TORO-Proxy が自動で元のサーバーに戻します。

| 置く場所 | 入れ方 |
| --- | --- |
| `plugins/*.jar` | `main/plugins/` に入れます。同じ名前のファイルは置き換えます。 |
| `remove-plugins.txt` | `main/plugins/` から外す JAR の名前を、1 行に 1 つ書きます。`#` から後ろは無視します。 |
| `main/...` | `main/` の同じ場所に写します。例: `main/plugins/BlueMap/core.conf`、`main/server.properties` |

- 入れたものと元の版は `staged-applied/<時刻>/` に残ります。`input/` が置いたもの、`backup/` が元の版です。
- 元に戻すときは、toro-main を止めてから `bash staged-applied/<時刻>/rollback.sh` を実行し、起動します。
- 入れた直後に toro-main が 10 分たっても起動しないとき (ログに `Done (` が出ないとき) は、自動で元に戻して起動し直します。
- JAR の名前を変えて入れ替えるときは、新しい JAR を `plugins/` に置き、古い名前を `remove-plugins.txt` に書きます。

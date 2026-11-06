#!/bin/bash
# 毎朝 05:25 (本番ホストの cron)。以前は毎日 Velocity を止めて起動し直していたが、
# 今は更新があるときだけ、誰もいなくなってから作り直す。無条件に作り直すときは stop.sh と start.sh を使う
cd "$(dirname "$0")" || exit 1
bash update_velocity.sh

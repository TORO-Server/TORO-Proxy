#!/usr/bin/env python3
"""docker-compose.yaml のイメージの決まりを確かめる (CI と手元。標準ライブラリだけ)。

  1. build: を使わない (本番でイメージを作らない。GitHub Actions が作ったものを使う)
  2. すべてのイメージにタグがあり、latest ではない
  3. ghcr.io/toro-server/ (自前) 以外は @sha256: で中身まで固定する

使い方: python3 scripts/check-images.py [compose ファイル...]  (既定は docker-compose.yaml)
"""
import re
import sys

OWN = "ghcr.io/toro-server/"
DIGEST = re.compile(r"@sha256:[0-9a-f]{64}$")


def problems(path):
    for no, raw in enumerate(open(path, encoding="utf-8"), 1):
        line = raw.split(" #", 1)[0].rstrip()
        if re.match(r"^\s+build:", line):
            yield no, "build: は使わない (イメージは GitHub Actions で作る)"
            continue
        m = re.match(r"^\s+image:\s*(\S+)$", line)
        if not m:
            continue
        ref = m.group(1)
        name = DIGEST.sub("", ref)
        tag = name.rsplit(":", 1)[1] if ":" in name.rsplit("/", 1)[-1] else ""
        if not tag:
            yield no, f"{ref}: タグが無い"
        elif tag == "latest":
            yield no, f"{ref}: latest は使わない"
        elif not ref.startswith(OWN) and not DIGEST.search(ref):
            yield no, f"{ref}: 自前以外は @sha256: で固定する"


def main():
    paths = sys.argv[1:] or ["docker-compose.yaml"]
    bad = 0
    for path in paths:
        for no, msg in problems(path):
            print(f"{path}:{no}: {msg}")
            bad += 1
    if bad:
        sys.exit(1)
    print("OK: イメージの決まりを守っています")


main()

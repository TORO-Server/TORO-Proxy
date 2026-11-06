#!/usr/bin/env python3
"""plugins.lock.json で固定した版の JAR を取ってくる (標準ライブラリだけ)。

使い方: python3 fetch_plugins.py <plugins.lock.json> <置き場所> <今の plugins/>

  - 今の plugins/ にある JAR が固定したハッシュと同じなら、取りに行かない
  - 取ったものが固定したハッシュと違えば、置き場所に残さない
  - 1 つでも取れなければ終了コード 1 (取れたものは置き場所に残る)

版を上げるときは plugins.lock.json を PR で変える (.github/scripts/plugin_pins.py)。
"""
import hashlib
import http.client
import json
import os
import re
import stat
import sys
import urllib.request

# 一番大きい Geyser でも 100 MB は超えない
MAX_BYTES = 256 * 1024 * 1024
TIMEOUT_SECONDS = 120
USER_AGENT = "Mozilla/5.0"

NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*\.jar$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")


def sha256_of(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def current_sha256(path):
    """今の JAR のハッシュ。plugins/ はコンテナが書けるので、通常のファイルでないもの (リンクなど) は読まない"""
    try:
        if not stat.S_ISREG(os.lstat(path).st_mode):
            return None
    except OSError:
        return None
    return sha256_of(path)


def download(url, path):
    """path に保存して、中身の SHA-256 を返す"""
    digest = hashlib.sha256()
    size = 0
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=TIMEOUT_SECONDS) as res, open(path, "wb") as out:
        for chunk in iter(lambda: res.read(1024 * 1024), b""):
            size += len(chunk)
            if size > MAX_BYTES:
                raise ValueError(f"{MAX_BYTES} バイトを超えました")
            digest.update(chunk)
            out.write(chunk)
    return digest.hexdigest()


def fetch(name, pin, dest, current):
    """取れた、または取らなくてよければ True"""
    url = pin.get("url", "") if isinstance(pin, dict) else ""
    want = pin.get("sha256", "") if isinstance(pin, dict) else ""
    if not NAME.match(name) or not SHA256.match(want) or not url.startswith("https://"):
        print(f"{name}: plugins.lock.json の書き方が正しくありません")
        return False
    if current_sha256(os.path.join(current, name)) == want:
        return True
    tmp = os.path.join(dest, name + ".part")
    try:
        got = download(url, tmp)
    except (OSError, ValueError, http.client.HTTPException) as e:
        print(f"{name}: 取れませんでした: {e}")
        if os.path.exists(tmp):
            os.remove(tmp)
        return False
    if got != want:
        print(f"{name}: 固定したハッシュと違うので使いません")
        os.remove(tmp)
        return False
    os.replace(tmp, os.path.join(dest, name))
    print(f"{name}: {pin.get('version', '?')} を取りました")
    return True


def main():
    if len(sys.argv) != 4:
        print(__doc__)
        return 2
    lock_path, dest, current = sys.argv[1:]
    with open(lock_path, encoding="utf-8") as f:
        lock = json.load(f)
    results = [fetch(name, pin, dest, current) for name, pin in lock.items()]
    return 0 if all(results) else 1


if __name__ == "__main__":
    sys.exit(main())

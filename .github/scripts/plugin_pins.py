#!/usr/bin/env python3
"""ops/both-service/plugins.lock.json を確かめる・新しい版に書き換える (CI と手元。標準ライブラリだけ)。

使い方:
  python3 .github/scripts/plugin_pins.py check    固定した版を配布元の公開ハッシュと比べ、取ったものとも比べる
  python3 .github/scripts/plugin_pins.py update   配布元の最新のリリースに書き換える (上げるのは PR で)

公開ハッシュは、Geyser と Floodgate がビルドの API の downloads、ViaVersion と ViaBackwards が
GitHub Releases のアセットの digest です。環境変数 GH_TOKEN があれば GitHub API の回数制限に使います。

source が manual のエントリは、配布元に固定できる公開ハッシュが無いもの (CI スナップショット等) で、
lock には実測した sha256 だけを持つ。check では対象外 (SKIP) とし、update ではそのまま保持する。
配置時の照合は ops/both-service/fetch_plugins.py が行い、一致しない場合は自動で取り直さず失敗する。
"""
import hashlib
import json
import os
import pathlib
import re
import sys
import urllib.request

LOCK = pathlib.Path(__file__).resolve().parents[2] / "ops/both-service/plugins.lock.json"
GEYSER_API = "https://download.geysermc.org/v2/projects"
GITHUB_API = "https://api.github.com/repos"
USER_AGENT = "Mozilla/5.0"
TIMEOUT_SECONDS = 120
SHA256 = re.compile(r"^[0-9a-f]{64}$")
VERSION = re.compile(r"^[0-9]+(\.[0-9]+)+$")


def api_json(url):
    headers = {"User-Agent": USER_AGENT}
    token = os.environ.get("GH_TOKEN")
    if token and url.startswith(GITHUB_API):
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=TIMEOUT_SECONDS) as res:
        return json.load(res)


def downloaded_sha256(url):
    digest = hashlib.sha256()
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=TIMEOUT_SECONDS) as res:
        for chunk in iter(lambda: res.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def geyser_build(project, version="latest", build="latest"):
    data = api_json(f"{GEYSER_API}/{project}/versions/{version}/builds/{build}")
    version, build = str(data["version"]), int(data["build"])
    return {
        "source": f"geysermc:{project}",
        "version": version,
        "build": build,
        "url": f"{GEYSER_API}/{project}/versions/{version}/builds/{build}/downloads/velocity",
        "sha256": data["downloads"]["velocity"]["sha256"],
    }


def github_release(repo, tag="latest"):
    path = "latest" if tag == "latest" else f"tags/{tag}"
    data = api_json(f"{GITHUB_API}/{repo}/releases/{path}")
    if data.get("prerelease") or data.get("draft"):
        raise ValueError(f"{repo} {data.get('tag_name')}: リリース版ではありません")
    version = data["tag_name"]
    jar = f"{repo.split('/')[-1]}-{version}.jar"
    asset = next((a for a in data["assets"] if a["name"] == jar), None)
    if asset is None or not str(asset.get("digest", "")).startswith("sha256:"):
        raise ValueError(f"{repo} {version}: {jar} の公開ハッシュがありません")
    return {
        "source": f"github:{repo}",
        "version": version,
        "url": asset["browser_download_url"],
        "sha256": asset["digest"].removeprefix("sha256:"),
    }


def published(pin, latest=False):
    """配布元が公開している情報。latest なら最新のリリース、そうでなければ固定した版"""
    kind, _, where = pin["source"].partition(":")
    if kind == "geysermc":
        return geyser_build(where) if latest else geyser_build(where, pin["version"], pin["build"])
    if kind == "github":
        return github_release(where) if latest else github_release(where, pin["version"])
    raise ValueError(f"{pin['source']}: 知らない配布元です")


def validated(pin):
    if pin["source"] == "manual":
        if not SHA256.match(pin["sha256"]):
            raise ValueError(f"{pin['source']}: 配布元の値の形が想定と違います")
        return pin
    if not VERSION.match(pin["version"]) or not SHA256.match(pin["sha256"]) or not pin["url"].startswith("https://"):
        raise ValueError(f"{pin['source']}: 配布元の値の形が想定と違います")
    return pin


def check(lock):
    bad = 0
    for name, pin in lock.items():
        if pin["source"] == "manual":
            validated(pin)
            print(f"SKIP {name}: manual ({pin['version']})、照合は配置時に fetch_plugins.py が行います")
            continue
        up = validated(published(pin))
        got = downloaded_sha256(pin["url"])
        if pin != up or got != pin["sha256"]:
            print(f"NG {name}: 固定 {pin['sha256']} / 配布元 {up['sha256']} / 取ったもの {got}")
            bad += 1
        else:
            print(f"OK {name}: {pin['version']} {got}")
    return 1 if bad else 0


def update(lock):
    new = {}
    for name, pin in lock.items():
        if pin["source"] == "manual":
            new[name] = pin
            continue
        latest = validated(published(pin, latest=True))
        if latest != pin:
            got = downloaded_sha256(latest["url"])
            if got != latest["sha256"]:
                raise ValueError(f"{name}: 取ったものが公開ハッシュと違います ({got})")
            print(f"{name}: {pin['version']} -> {latest['version']}")
        new[name] = latest
    LOCK.write_text(json.dumps(new, indent=2) + "\n", encoding="utf-8", newline="\n")
    return 0


def main():
    if sys.argv[1:] not in (["check"], ["update"]):
        print(__doc__)
        return 2
    lock = json.loads(LOCK.read_text(encoding="utf-8"))
    return check(lock) if sys.argv[1] == "check" else update(lock)


if __name__ == "__main__":
    sys.exit(main())

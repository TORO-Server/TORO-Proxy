from urllib import request
import json
import sys
import hashlib
import os


# どのサーバー用の Floodgate をダウンロードするか
TPYE = sys.argv[1]
# Floodgate の jarファイルパス
PATH = sys.argv[2]


def getLatest_sha256(type: str):
    # URL
    URL = f"https://download.geysermc.org/v2/projects/floodgate/versions/latest/builds/latest"
    # GETリクエスト 送信
    req = request.Request(URL, headers={'User-Agent': 'Mozilla/5.0'})
    with request.urlopen(req) as response:
        data = json.load(response)
    return data["downloads"][type]["sha256"]


def getFile_sha256(path: str):
    hash = hashlib.sha256()
    try:
        with open(path, "rb") as f:
            # ファイルをチャンクごとに読み込んでハッシュを計算
            for byte_block in iter(lambda: f.read(4096), b""):
                hash.update(byte_block)
    except FileNotFoundError:
        return None
    # sha256 のハッシュ値を return
    return hash.hexdigest()


def downloadLatest(type: str, path: str):
    # URL
    URL = f"https://download.geysermc.org/v2/projects/floodgate/versions/latest/builds/latest/downloads/{type}"
    # フォルダが存在しない場合は作成
    dir_path = os.path.dirname(path)
    if not os.path.exists(dir_path):
        os.makedirs(dir_path)
    # ファイル ダウンロード
    req = request.Request(URL, headers={'User-Agent': 'Mozilla/5.0'})
    binary = request.urlopen(req).read()
    with open(PATH, mode="wb") as f:
        f.write(binary)


# 最新版の Floodgate のファイルのハッシュ値
sha256_cloud = getLatest_sha256(TPYE)
# ローカルにある Floodgate のファイルのハッシュ値
sha256_local = getFile_sha256(PATH)

# 最新版のFloodgateとローカルにあるFloodgateのハッシュ値が違っていたら
if sha256_cloud != sha256_local:
    # Floodgate をダウンロードする
    print(f"Floodgate {TPYE.capitalize()} Download...")
    downloadLatest(TPYE, PATH)
    print(f"{PATH} Done")

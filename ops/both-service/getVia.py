import urllib.request
import json
import hashlib
import sys


def download_and_hash(job_name, save_filename):
    """
    指定されたジョブ名の最新ビルドをダウンロードし、ハッシュを計算する関数
    """
    base_url = f"https://ci.viaversion.com/job/{job_name}/lastSuccessfulBuild"
    api_url = f"{base_url}/api/json"

    print(f"[{job_name}] 情報取得開始: {api_url}")

    try:
        # APIからJSONデータを取得
        req = urllib.request.Request(
            api_url, headers={'User-Agent': 'Mozilla/5.0'})
        with urllib.request.urlopen(req) as response:
            data = json.loads(response.read().decode('utf-8'))

        # アーティファクト(.jar)の特定
        artifact_path = None
        for artifact in data.get('artifacts', []):
            fname = artifact.get('fileName', '')
            if fname.endswith('.jar') and 'sources' not in fname and 'javadoc' not in fname:
                artifact_path = artifact.get('relativePath')
                break

        if not artifact_path:
            print(f"[{job_name}] エラー: 有効なアーティファクト(.jar)が見つかりませんでした。")
            return

        download_url = f"{base_url}/artifact/{artifact_path}"

        # ファイルのダウンロード
        print(f"[{job_name}] ダウンロード中... -> {save_filename}")
        dl_req = urllib.request.Request(download_url, headers={'User-Agent': 'Mozilla/5.0'})
        with urllib.request.urlopen(dl_req) as dl_response, open(save_filename, 'wb') as out_file:
            out_file.write(dl_response.read())

        # ハッシュ値(SHA-256)の計算
        sha256_hash = hashlib.sha256()
        with open(save_filename, "rb") as f:
            for byte_block in iter(lambda: f.read(4096), b""):
                sha256_hash.update(byte_block)

        hash_value = sha256_hash.hexdigest()

        print(f"[{job_name}] 完了: SHA-256: {hash_value}")
        print("-" * 40)

    except Exception as e:
        print(f"[{job_name}] 例外発生: {e}")


def main():
    # 対象リスト: (CI上のジョブ名, 保存ファイル名)
    targets = [
        ("ViaVersion", "./velocity/plugins/ViaVersion.jar"),
        ("ViaBackwards", "./velocity/plugins/ViaBackwards.jar")
    ]

    print("-" * 40)
    for job, filename in targets:
        download_and_hash(job, filename)


if __name__ == "__main__":
    main()

"""预取 1.7.10 原版 client.jar / server.jar 到 RFG 的缓存目录。

背景
----
RFG 的 :downloadVanillaJars 任务（底层是 de.undercouch download 插件）默认 socket 超时
较短，从 Mojang CDN 拉 ~10MB 的 jar 在慢速链路上很容易 `SocketTimeoutException: Read timed out`。

但 RFG 对这两个文件用的是 `overwrite(false)`，也就是**文件已存在就跳过下载**，
并且会用 Mojang 版本清单里的 SHA1 做校验。所以只要我们提前把文件放到正确位置、
且 SHA1 完全一致，构建就能直接通过。

目标目录（来自 Utilities.getCacheRoot → gradle.getGradleUserHomeDir()/caches/retro_futura_gradle）：
    <GRADLE_USER_HOME>/caches/retro_futura_gradle/mc-vanilla/1.7.10/{client.jar,server.jar}

GRADLE_USER_HOME 依次取：--gradle-home 参数、环境变量 GRADLE_USER_HOME、~/.gradle。
"""
import argparse
import hashlib
import os
import sys
import time
import urllib.request

UA = {"User-Agent": "Mozilla/5.0"}
VERSION_MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest.json"
FALLBACK_VERSION_JSON = "https://piston-meta.mojang.com/v1/packages/ed5d8789ed29872ea2ef1c348302b0c55e3f3468/1.7.10.json"


def get_json(url, timeout=60):
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=timeout) as r:
        import json
        return json.load(r)


def sha1_of(path):
    h = hashlib.sha1()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def download(url, dest, expected_sha1, attempts=5):
    for attempt in range(1, attempts + 1):
        try:
            print(f"  [{attempt}/{attempts}] GET {url}", flush=True)
            t = time.time()
            req = urllib.request.Request(url, headers=UA)
            with urllib.request.urlopen(req, timeout=180) as r, open(dest, "wb") as f:
                total = 0
                while True:
                    chunk = r.read(1 << 16)
                    if not chunk:
                        break
                    f.write(chunk)
                    total += len(chunk)
            dt = time.time() - t
            got = sha1_of(dest)
            if got != expected_sha1:
                print(f"      SHA1 mismatch ({got} != {expected_sha1}), retrying", flush=True)
                os.remove(dest)
                continue
            print(f"      OK {total/1048576:.2f} MB in {dt:.0f}s "
                  f"({total/dt/1048576:.2f} MB/s), sha1 verified", flush=True)
            return True
        except Exception as e:
            print(f"      FAIL {type(e).__name__}: {e}", flush=True)
            time.sleep(3)
    return False


def resolve_gradle_home(explicit):
    if explicit:
        return explicit
    env = os.environ.get("GRADLE_USER_HOME")
    if env:
        return env
    return os.path.join(os.path.expanduser("~"), ".gradle")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--gradle-home", help="GRADLE_USER_HOME（默认取环境变量或 ~/.gradle）")
    args = ap.parse_args()

    dest_dir = os.path.join(resolve_gradle_home(args.gradle_home),
                            "caches", "retro_futura_gradle", "mc-vanilla", "1.7.10")
    os.makedirs(dest_dir, exist_ok=True)
    print("目标目录: %s" % dest_dir, flush=True)

    # 找到 1.7.10 的版本 json
    try:
        manifest = get_json(VERSION_MANIFEST)
        entry = next(v for v in manifest["versions"] if v["id"] == "1.7.10")
        version_json = get_json(entry["url"])
    except Exception as e:
        print(f"取版本清单失败({e})，回退到固定 URL", flush=True)
        version_json = get_json(FALLBACK_VERSION_JSON)

    ok = True
    for key in ("client", "server"):
        info = version_json["downloads"][key]
        dest = os.path.join(dest_dir, f"{key}.jar")
        if os.path.exists(dest) and sha1_of(dest) == info["sha1"]:
            print(f"{key}.jar 已存在且 SHA1 正确，跳过", flush=True)
            continue
        print(f"下载 {key}.jar -> {dest}", flush=True)
        if not download(info["url"], dest, info["sha1"]):
            ok = False

    print("DONE" if ok else "FAILED", flush=True)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())

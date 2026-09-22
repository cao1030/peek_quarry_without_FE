"""校验构建产物：解包、检查字节码版本、对比 reobf 前后的符号引用。

用法：
    python tools/verify_jar.py
    python tools/verify_jar.py --libs build/libs --javap /path/to/javap

不写死任何机器路径：
  * 产物目录默认是 <仓库根>/build/libs
  * javap 依次找 --javap 参数、JAVA_HOME/bin/javap、PATH 上的 javap
"""
import argparse
import glob
import os
import re
import shutil
import subprocess
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_OUT = os.path.join(ROOT, "build", "jarcheck")

# 默认检查这几个类的符号引用（按需用 --class 覆盖）
DEFAULT_CLASSES = ("com.peek.quarry.PeekQuarry", "com.peek.quarry.TileEntityEnderTouch")


def find_javap(explicit):
    if explicit:
        return explicit
    home = os.environ.get("JAVA_HOME")
    if home:
        for name in ("javap.exe", "javap"):
            p = os.path.join(home, "bin", name)
            if os.path.isfile(p):
                return p
    for name in ("javap", "javap.exe"):
        p = shutil.which(name)
        if p:
            return p
    return None


def pick_jars(libs):
    """返回 (reobf_jar, dev_jar)；找不到就是 None。"""
    all_jars = sorted(glob.glob(os.path.join(libs, "*.jar")))
    dev = [p for p in all_jars if p.endswith("-dev.jar")]
    reobf = [p for p in all_jars
             if not p.endswith("-dev.jar") and not p.endswith("-sources.jar")]
    return (reobf[0] if reobf else None), (dev[0] if dev else None)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--libs", default=os.path.join(ROOT, "build", "libs"))
    ap.add_argument("--out", default=DEFAULT_OUT)
    ap.add_argument("--javap")
    ap.add_argument("--class", dest="classes", action="append",
                    help="要检查符号引用的类（可重复）")
    args = ap.parse_args()

    reobf_jar, dev_jar = pick_jars(args.libs)
    if not reobf_jar:
        print("在 %s 里找不到 jar，先构建一次（gradlew build）" % args.libs)
        return 1

    shutil.rmtree(args.out, ignore_errors=True)
    targets = []
    if reobf_jar:
        targets.append(("reobf", reobf_jar))
    if dev_jar:
        targets.append(("dev", dev_jar))

    for tag, path in targets:
        d = os.path.join(args.out, tag)
        os.makedirs(d, exist_ok=True)
        with zipfile.ZipFile(path) as z:
            z.extractall(d)
        print("%-6s %s" % (tag, os.path.basename(path)))

    print()
    print("=== class 文件版本 (52 = Java 8) ===")
    bad = 0
    for tag, _ in targets:
        for root, _, files in os.walk(os.path.join(args.out, tag)):
            for f in sorted(files):
                if not f.endswith(".class"):
                    continue
                with open(os.path.join(root, f), "rb") as fh:
                    head = fh.read(8)
                major = int.from_bytes(head[6:8], "big")
                ok = major == 52
                bad += 0 if ok else 1
                print("  %-6s %-46s major=%d  %s"
                      % (tag, f, major, "Java 8 OK" if ok else "!! NOT Java 8 !!"))

    javap = find_javap(args.javap)
    if not javap:
        print()
        print("找不到 javap，跳过符号引用检查（设 JAVA_HOME 或用 --javap 指定）")
        return 1 if bad else 0

    classes = args.classes or list(DEFAULT_CLASSES)
    print()
    print("=== 符号引用对比 ===")
    print("  reobf jar 里 MC 成员应已被再混淆成 SRG 名（func_/field_），")
    print("  类名应保持可读的 net/minecraft/...（和整合包里其它 mod 一致）。")
    for tag, label in (("dev", "开发环境 jar（应为 MCP 名）"), ("reobf", "最终 jar（应为 SRG 名）")):
        cp = os.path.join(args.out, tag)
        if not os.path.isdir(cp):
            continue
        print("  %s:" % label)
        for cls in classes:
            p = subprocess.run([javap, "-p", "-c", "-cp", cp, cls],
                               capture_output=True, text=True, errors="replace")
            if p.returncode != 0:
                continue
            srg = sorted(set(re.findall(r"\b(?:func|field)_\d+_[A-Za-z0-9_]+", p.stdout)))
            print("    %-46s SRG 名 %d 个 %s" % (cls, len(srg), srg[:4]))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())

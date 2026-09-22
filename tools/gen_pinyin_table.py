"""从 Unihan 数据生成「末影之触」搜索框用的汉字→拼音表。

数据来源：Unihan 数据库里的 `Unihan_Readings.txt`。

最方便的拿法：装过 **REI（Roughly Enough Items）** 的现代版本整合包里就有现成的，
在 `<版本目录>/config/roughlyenoughitems/unihan.zip`。也可以用官方 Unihan 包自行打包。

优先用 kHanyuPinyin（带多音字，例如 行 -> háng,xíng），没有时退回 kMandarin。
只保留 CJK 基本区 U+4E00-U+9FFF（20901 个），扩展区和扩展 A 的罕见字在
MC 的中文语言文件里不会出现，带上只会白白撑大 jar。

输出的每一行是： <汉字><TAB><读音1>,<读音2>,...

用法：
    python tools/gen_pinyin_table.py --unihan <unihan.zip 路径>
    python tools/gen_pinyin_table.py            # 会去读环境变量 UNIHAN_ZIP
"""
import argparse
import os
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DEFAULT_OUT = os.path.join(ROOT, "src", "main", "resources", "assets", "peek_quarry", "pinyin.txt")

# 只认环境变量，不写死任何机器的路径
UNIHAN_CANDIDATES = [
    os.environ.get("UNIHAN_ZIP", ""),
]

CJK_START, CJK_END = 0x4E00, 0x9FFF

# 每个字最多保留几个读音，防止数据膨胀
MAX_READINGS = 4

# 带声调符号 -> 无调 ASCII。ü 一律记成 v（拼音输入法的习惯，绿 = lv）
TONE_MAP = {
    "\u0101": "a", "\u00e1": "a", "\u01ce": "a", "\u00e0": "a",   # ā á ǎ à
    "\u0113": "e", "\u00e9": "e", "\u011b": "e", "\u00e8": "e",   # ē é ě è
    "\u012b": "i", "\u00ed": "i", "\u01d0": "i", "\u00ec": "i",   # ī í ǐ ì
    "\u014d": "o", "\u00f3": "o", "\u01d2": "o", "\u00f2": "o",   # ō ó ǒ ò
    "\u016b": "u", "\u00fa": "u", "\u01d4": "u", "\u00f9": "u",   # ū ú ǔ ù
    "\u01d6": "v", "\u01d8": "v", "\u01da": "v", "\u01dc": "v",   # ǖ ǘ ǚ ǜ
    "\u00fc": "v",                                                # ü
    "\u0144": "n", "\u01f9": "n",                                 # ń ǹ
    "\u1e5f": "m",                                                # ṟ 之类极少见，兜底
}


def strip_tone(reading):
    out = []
    for ch in reading:
        out.append(TONE_MAP.get(ch, ch))
    return "".join(out)


def find_unihan(explicit):
    if explicit:
        return explicit
    for path in UNIHAN_CANDIDATES:
        if os.path.isfile(path):
            return path
    return None


def load_readings(zip_path):
    """返回 {汉字: [无调读音, ...]}，读音已去重、去调、按 kHanyuPinyin 顺序优先。"""
    with zipfile.ZipFile(zip_path) as z:
        text = z.read("Unihan_Readings.txt").decode("utf-8", "replace")

    hanyu = {}
    mandarin = {}
    for line in text.splitlines():
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) < 3:
            continue
        code, field, value = parts[0], parts[1], parts[2]
        if field not in ("kHanyuPinyin", "kMandarin"):
            continue
        try:
            ch = chr(int(code[2:], 16))
        except ValueError:
            continue
        if field == "kHanyuPinyin":
            hanyu[ch] = value.strip()
        else:
            mandarin[ch] = value.strip()

    table = {}
    for ch in set(hanyu) | set(mandarin):
        code = ord(ch)
        if not (CJK_START <= code <= CJK_END):
            continue
        readings = []
        raw = hanyu.get(ch)
        if raw:
            # 形如 "20811.060:háng,xíng,xíng" —— 冒号前是《汉语大字典》页码索引，丢掉
            payload = raw.split(":", 1)[1] if ":" in raw else raw
            extra = payload.split(",")
        else:
            extra = []
        # 顺序很重要：kMandarin 是现代规范读音，最可能就是用户会打的；
        # kHanyuPinyin 是按《汉语大字典》页码排的，会把生僻音放前面
        # （比如 草 -> zào 排在 cǎo 前），所以只能作为补充。
        readings = mandarin.get(ch, "").split() + extra

        clean = []
        for r in readings:
            r = strip_tone(r.strip().lower())
            if not r or not r.isascii() or not r.isalpha():
                continue
            if r not in clean:
                clean.append(r)
            # ü 的两种输入习惯都收：lv / lu
            if "v" in r:
                alt = r.replace("v", "u")
                if alt not in clean:
                    clean.append(alt)
        if clean:
            table[ch] = clean[:MAX_READINGS]
    return table


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--unihan", help="path to REI's unihan.zip")
    ap.add_argument("--out", default=DEFAULT_OUT)
    args = ap.parse_args()

    src = find_unihan(args.unihan)
    if not src:
        sys.exit("unihan.zip not found; pass --unihan <path>")

    table = load_readings(src)

    lines = []
    for ch in sorted(table):
        lines.append("%s\t%s" % (ch, ",".join(table[ch])))
    blob = "\n".join(lines) + "\n"

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8", newline="\n") as f:
        f.write(blob)

    poly = sum(1 for v in table.values() if len(v) > 1)
    print("source : %s" % src)
    print("chars  : %d  (multi-reading: %d)" % (len(table), poly))
    print("wrote  : %s  (%d bytes raw)" % (args.out, len(blob.encode("utf-8"))))
    for probe in ("\u77f3", "\u5934", "\u7eff", "\u884c", "\u91cd", "\u6c34"):
        r = table.get(probe)
        print("  U+%04X -> %s" % (ord(probe), ",".join(r) if r else "(none)"))


if __name__ == "__main__":
    main()

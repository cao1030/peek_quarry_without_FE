"""把原版命令方块贴图染成绿色，作为「末影之触」方块的占位贴图。

为什么需要这个脚本：
    1.7.10 原版只有橙色的命令方块贴图（绿色的连锁命令方块是 1.9+ 才有的），
    所以这里直接从 vanilla client.jar 里取出 command_block.png，
    把橙色系像素的色相旋转到绿色，灰白像素（中间那块"面包板"）原样保留。

依赖：只用标准库（zlib + struct + colorsys + zipfile），不需要 Pillow。

用法：
    python tools/recolor_command_block.py                 # 自动寻找 vanilla client.jar
    python tools/recolor_command_block.py --hue 0.33      # 指定色相 (0~1)，0.33 约等于 120 度
    python tools/recolor_command_block.py --jar <路径>    # 手动指定 client.jar

产物：
    src/main/resources/assets/peek_quarry/textures/blocks/ender_touch.png
"""
import argparse
import colorsys
import os
import struct
import sys
import zlib
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

VANILLA_ENTRY = "assets/minecraft/textures/blocks/command_block.png"
DEFAULT_OUT = os.path.join(
    ROOT, "src", "main", "resources", "assets", "peek_quarry", "textures", "blocks", "ender_touch.png"
)

# 自动搜索 vanilla client.jar 时看这些候选路径
JAR_CANDIDATES = [
    os.path.join(os.environ.get("GRADLE_USER_HOME", ""), "caches", "retro_futura_gradle",
                 "mc-vanilla", "1.7.10", "client.jar"),
    os.path.join(os.path.expanduser("~"), ".gradle", "caches", "retro_futura_gradle",
                 "mc-vanilla", "1.7.10", "client.jar"),
]


# --------------------------------------------------------------------------
# 最小 PNG 读写（8 位色深，非隔行）
# --------------------------------------------------------------------------
def _paeth(a, b, c):
    p = a + b - c
    pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
    if pa <= pb and pa <= pc:
        return a
    return b if pb <= pc else c


def _unfilter(raw, width, height, bpp):
    stride = width * bpp
    out = bytearray(stride * height)
    pos = 0
    prev = bytearray(stride)
    for y in range(height):
        ftype = raw[pos]
        pos += 1
        line = bytearray(raw[pos:pos + stride])
        pos += stride
        if ftype == 1:      # Sub
            for i in range(bpp, stride):
                line[i] = (line[i] + line[i - bpp]) & 0xFF
        elif ftype == 2:    # Up
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 0xFF
        elif ftype == 3:    # Average
            for i in range(stride):
                left = line[i - bpp] if i >= bpp else 0
                line[i] = (line[i] + ((left + prev[i]) >> 1)) & 0xFF
        elif ftype == 4:    # Paeth
            for i in range(stride):
                left = line[i - bpp] if i >= bpp else 0
                upleft = prev[i - bpp] if i >= bpp else 0
                line[i] = (line[i] + _paeth(left, prev[i], upleft)) & 0xFF
        elif ftype != 0:
            raise ValueError("unknown PNG filter type %d" % ftype)
        out[y * stride:(y + 1) * stride] = line
        prev = line
    return out


def read_png(blob):
    """返回 (width, height, [(r, g, b, a), ...])。"""
    if blob[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("not a PNG")
    pos = 8
    idat = bytearray()
    palette = None
    trns = None
    width = height = depth = ctype = interlace = None
    while pos < len(blob):
        (length,) = struct.unpack(">I", blob[pos:pos + 4])
        tag = blob[pos + 4:pos + 8]
        data = blob[pos + 8:pos + 8 + length]
        pos += 12 + length
        if tag == b"IHDR":
            width, height, depth, ctype, _comp, _filt, interlace = struct.unpack(">IIBBBBB", data)
        elif tag == b"PLTE":
            palette = [tuple(data[i:i + 3]) for i in range(0, len(data), 3)]
        elif tag == b"tRNS":
            trns = data
        elif tag == b"IDAT":
            idat.extend(data)
        elif tag == b"IEND":
            break

    if depth != 8:
        raise ValueError("only 8-bit PNGs are supported (got %s)" % depth)
    if interlace:
        raise ValueError("interlaced PNGs are not supported")

    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[ctype]
    raw = _unfilter(zlib.decompress(bytes(idat)), width, height, channels)

    px = []
    for i in range(width * height):
        o = i * channels
        if ctype == 0:
            g = raw[o]
            px.append((g, g, g, 255))
        elif ctype == 2:
            px.append((raw[o], raw[o + 1], raw[o + 2], 255))
        elif ctype == 3:
            r, g, b = palette[raw[o]]
            a = trns[raw[o]] if trns and raw[o] < len(trns) else 255
            px.append((r, g, b, a))
        elif ctype == 4:
            g = raw[o]
            px.append((g, g, g, raw[o + 1]))
        else:
            px.append((raw[o], raw[o + 1], raw[o + 2], raw[o + 3]))
    return width, height, px


def _chunk(tag, data):
    return (struct.pack(">I", len(data)) + tag + data
            + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))


def write_png(width, height, px):
    """按 RGBA(色型 6) 写回 PNG。"""
    raw = bytearray()
    for y in range(height):
        raw.append(0)
        for x in range(width):
            raw.extend(px[y * width + x])
    return (b"\x89PNG\r\n\x1a\n"
            + _chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
            + _chunk(b"IDAT", zlib.compress(bytes(raw), 9))
            + _chunk(b"IEND", b""))


# --------------------------------------------------------------------------
# 染色
# --------------------------------------------------------------------------
def to_green(px, hue, sat_floor=0.08):
    """把有颜色的像素的色相搬到 hue，保留原来的饱和度与明度；近灰像素不动。"""
    out = []
    for (r, g, b, a) in px:
        h, s, v = colorsys.rgb_to_hsv(r / 255.0, g / 255.0, b / 255.0)
        if a == 0 or s < sat_floor:
            out.append((r, g, b, a))
            continue
        nr, ng, nb = colorsys.hsv_to_rgb(hue, s, v)
        out.append((round(nr * 255), round(ng * 255), round(nb * 255), a))
    return out


def find_jar(explicit):
    if explicit:
        return explicit
    for path in JAR_CANDIDATES:
        if path and os.path.isfile(path):
            return path
    return None


def main():
    ap = argparse.ArgumentParser(description="recolor the vanilla command block texture to green")
    ap.add_argument("--jar", help="path to the vanilla 1.7.10 client.jar")
    ap.add_argument("--hue", type=float, default=0.33, help="target hue 0..1 (default 0.33 ~= 120deg green)")
    ap.add_argument("--out", default=DEFAULT_OUT, help="output png path")
    args = ap.parse_args()

    jar = find_jar(args.jar)
    if not jar or not os.path.isfile(jar):
        sys.exit("vanilla client.jar not found; pass --jar <path>")

    with zipfile.ZipFile(jar) as z:
        blob = z.read(VANILLA_ENTRY)

    w, h, px = read_png(blob)
    green = to_green(px, args.hue)

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    data = write_png(w, h, green)
    with open(args.out, "wb") as f:
        f.write(data)

    print("source : %s :: %s (%dx%d)" % (jar, VANILLA_ENTRY, w, h))
    print("hue    : %.3f" % args.hue)
    print("wrote  : %s (%d bytes)" % (args.out, len(data)))


if __name__ == "__main__":
    main()

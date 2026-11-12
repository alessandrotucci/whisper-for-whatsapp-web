"""Genera le icone PNG dell'estensione: disco verde con un'onda sonora bianca."""
import math
import os
import struct
import sys
import zlib

BARS = [0.30, 0.55, 0.85, 0.55, 0.30]


def pixel(x, y, n):
    c = (n - 1) / 2
    r = n / 2
    d = math.hypot(x - c, y - c)
    if d > r:
        return (0, 0, 0, 0)
    a = max(0.0, min(1.0, r - d))
    base = (18, 140, 126)
    bw = n / 11
    gap = n / 22
    total = len(BARS) * bw + (len(BARS) - 1) * gap
    x0 = (n - total) / 2
    for i, h in enumerate(BARS):
        bx = x0 + i * (bw + gap)
        hh = h * n * 0.62
        if bx <= x < bx + bw and abs(y - c) <= hh / 2:
            return (255, 255, 255, int(255 * a))
    return base + (int(255 * a),)


def png(n):
    raw = b"".join(b"\x00" + b"".join(bytes(pixel(x + 0.5, y + 0.5, n)) for x in range(n)) for y in range(n))

    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)

    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", n, n, 8, 6, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")


out = sys.argv[1]
for n in (16, 32, 48, 128):
    with open(os.path.join(out, "icon%d.png" % n), "wb") as f:
        f.write(png(n))

#!/usr/bin/env python3
"""Generate test PNG images (pure stdlib) for AlbumMarker emulator acceptance."""
import struct
import zlib
import os
import sys


def write_png(path, width, height, pixels):
    """pixels: list of rows, each row list of (r,g,b)."""
    def chunk(tag, data):
        c = tag + data
        return struct.pack(">I", len(data)) + c + struct.pack(">I", zlib.crc32(c) & 0xFFFFFFFF)

    raw = b""
    for row in pixels:
        raw += b"\x00" + b"".join(struct.pack("BBB", *px) for px in row)
    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 9))
    png += chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)


def gradient_with_shapes(name, w, h, base, accent):
    pixels = []
    for y in range(h):
        row = []
        for x in range(w):
            t = (x + y) / (w + h)
            r = int(base[0] * (1 - t) + accent[0] * t)
            g = int(base[1] * (1 - t) + accent[1] * t)
            b = int(base[2] * (1 - t) + accent[2] * t)
            # 中心圆
            cx, cy = w / 2, h / 2
            if (x - cx) ** 2 + (y - cy) ** 2 < (min(w, h) / 4) ** 2:
                r, g, b = 240, 240, 240
            # 底部横带
            if y > h * 0.85:
                r, g, b = 30, 30, 30
            row.append((r, g, b))
        pixels.append(row)
    write_png(name, w, h, pixels)


def main():
    out_dir = sys.argv[1] if len(sys.argv) > 1 else "."
    os.makedirs(out_dir, exist_ok=True)
    gradient_with_shapes(os.path.join(out_dir, "test_photo_1.png"), 640, 480, (200, 60, 60), (60, 60, 200))
    gradient_with_shapes(os.path.join(out_dir, "test_photo_2.png"), 480, 640, (60, 180, 90), (240, 200, 60))
    gradient_with_shapes(os.path.join(out_dir, "test_photo_3.png"), 512, 512, (150, 120, 200), (200, 80, 140))
    print("generated 3 png in", out_dir)


if __name__ == "__main__":
    main()

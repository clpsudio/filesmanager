#!/usr/bin/env python3
"""Generate the two DWG test fixtures used by the unit tests.

Written for this project (CLP Studio); no third-party drawing data is used.

Each file is a minimal AutoCAD DWG "prefix": the 6-byte version string, the
pointer at 0x0D to the image section, and the image section itself (start
sentinel, size, entry table, a dummy header entry, the preview image and the
end sentinel), laid out the way AutoCAD writes it. There is no drawing data
after the preview: the app only reads the version and the preview image.

  sample_r2018_preview.dwg  AC1032 (AutoCAD 2018), PNG preview (entry code 6)
  sample_r2000_preview.dwg  AC1015 (AutoCAD 2000), 8-bit BMP/DIB preview (code 2)

Run from the repository root:  python3 scripts/test-fixtures/make-dwg-fixtures.py
The output is deterministic (same bytes on every run).
"""
import os
import struct
import zlib

OUT = os.path.join("app", "src", "test", "resources", "fmt")
START = bytes([0x1F, 0x25, 0x6D, 0x07, 0xD4, 0x36, 0x28, 0x28, 0x9D, 0x57, 0xCA, 0x3F, 0x9D, 0x44, 0x10, 0x2B])
END = bytes([0xE0, 0xDA, 0x92, 0xF8, 0x2B, 0xC9, 0xD7, 0xD7, 0x62, 0xA8, 0x35, 0xC0, 0x62, 0xBB, 0xEF, 0xD4])


def floor_plan(w, h):
    """A simple made-up floor plan: outer walls, two inner walls, a door gap. 0 = paper, 1 = line."""
    px = [[0] * w for _ in range(h)]

    def hline(y, x0, x1):
        for t in (0, 1):
            for x in range(x0, x1 + 1):
                px[y + t][x] = 1

    def vline(x, y0, y1):
        for t in (0, 1):
            for y in range(y0, y1 + 1):
                px[y][x + t] = 1

    m = 10
    hline(m, m, w - m - 1)
    hline(h - m - 2, m, w - m - 1)
    vline(m, m, h - m - 1)
    vline(w - m - 2, m, h - m - 1)
    mid = w // 2
    vline(mid, m, h // 2 - 8)          # inner wall with a door gap
    vline(mid, h // 2 + 8, h - m - 1)
    hline(h // 2, mid, w - m - 1)      # splits the right half in two rooms
    for i in range(0, 16):             # a door swing, drawn as a diagonal
        px[h // 2 - 8 + i][mid + 2 + i] = 1
    return px


def png(w, h):
    px = floor_plan(w, h)
    raw = b"".join(b"\x00" + bytes(
        c for v in row for c in ((20, 40, 90) if v else (250, 250, 247))) for row in px)

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def dib8(w, h):
    """BITMAPINFOHEADER + 256-colour palette + bottom-up 8-bit rows (what AutoCAD 2000 stores)."""
    px = floor_plan(w, h)
    row = (w + 3) // 4 * 4
    header = struct.pack("<IiiHHIIiiII", 40, w, h, 1, 8, 0, row * h, 0, 0, 256, 0)
    palette = bytearray(256 * 4)
    palette[0:4] = bytes((247, 250, 250, 0))   # index 0: paper (B, G, R, 0)
    palette[4:8] = bytes((90, 40, 20, 0))      # index 1: line
    pixels = b"".join(bytes(r) + b"\x00" * (row - w) for r in reversed(px))
    return header + bytes(palette) + pixels


def dwg(version, code, image):
    head = bytearray(0x100)
    head[0:6] = version.encode("ascii")
    section = len(head)
    struct.pack_into("<I", head, 0x0D, section)
    entries = 2
    table = 16 + 4 + 1 + entries * 9
    header_start = section + table
    header = bytes(80)                         # AutoCAD's "header" entry; contents unused here
    image_start = header_start + len(header)
    body = header + image
    size = 1 + entries * 9 + len(body)
    sec = START + struct.pack("<I", size) + bytes([entries])
    sec += bytes([1]) + struct.pack("<II", header_start, len(header))
    sec += bytes([code]) + struct.pack("<II", image_start, len(image))
    return bytes(head) + sec + body + END


def main():
    os.makedirs(OUT, exist_ok=True)
    files = {
        "sample_r2018_preview.dwg": dwg("AC1032", 6, png(180, 124)),
        "sample_r2000_preview.dwg": dwg("AC1015", 2, dib8(184, 126)),
    }
    for name, data in files.items():
        with open(os.path.join(OUT, name), "wb") as f:
            f.write(data)
        print(name, len(data), "bytes")


if __name__ == "__main__":
    main()

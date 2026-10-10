#!/usr/bin/env python3
"""Is this PNG a picture of something, or a blank frame?

  shot.py <png>

Prints one line — "<W>x<H> colours=<n> non-black=<p>% VALID|BLANK" — or
"UNREADABLE: <why>", and exits 0 only for VALID. A device screenshot counts as
render evidence only if it is VALID: on a headless emulator `screencap` has
returned all-black frames before (run 34669956183), and a black PNG is a file,
not a picture. VALID is deliberately modest — more than a handful of colours and
some non-black pixels — so it says the frame was rendered, not that it is right;
what it shows is for a person to look at.

Standard library only (the CI runner has no Pillow): 8-bit RGB/RGBA, all five
PNG filters.
"""
import struct
import sys
import zlib


def pixels(path):
    data = open(path, 'rb').read()
    if data[:8] != b'\x89PNG\r\n\x1a\n':
        raise ValueError('not a PNG')
    pos, idat, w = 8, b'', None
    while pos < len(data):
        n, kind = struct.unpack('>I4s', data[pos:pos + 8])
        body = data[pos + 8:pos + 8 + n]
        if kind == b'IHDR':
            w, h, depth, ctype = struct.unpack('>IIBB', body[:10])
            if depth != 8 or ctype not in (2, 6):
                raise ValueError(f'unsupported PNG: depth {depth}, colour type {ctype}')
            bpp = 3 if ctype == 2 else 4
        elif kind == b'IDAT':
            idat += body
        pos += 12 + n
    if w is None:
        raise ValueError('no IHDR')
    raw = zlib.decompress(idat)
    stride = w * bpp
    prev = bytearray(stride)
    rows = []
    for y in range(h):
        f = raw[y * (stride + 1)]
        line = bytearray(raw[y * (stride + 1) + 1:(y + 1) * (stride + 1)])
        for i in range(stride):
            a = line[i - bpp] if i >= bpp else 0
            b = prev[i]
            c = prev[i - bpp] if i >= bpp else 0
            if f == 1:
                line[i] = (line[i] + a) & 0xFF
            elif f == 2:
                line[i] = (line[i] + b) & 0xFF
            elif f == 3:
                line[i] = (line[i] + ((a + b) >> 1)) & 0xFF
            elif f == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[i] = (line[i] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 0xFF
        rows.append(bytes(line))
        prev = line
    return w, h, bpp, rows


def main():
    try:
        w, h, bpp, rows = pixels(sys.argv[1])
    except Exception as e:  # noqa: BLE001 — any failure to read is the answer
        print(f'UNREADABLE: {e}')
        return 1
    colours, nonblack, total = set(), 0, 0
    for row in rows[::4]:                      # every 4th row, every 4th pixel
        for x in range(0, w, 4):
            px = row[x * bpp:x * bpp + 3]
            colours.add(px)
            total += 1
            nonblack += px != b'\x00\x00\x00'
    pct = 100.0 * nonblack / max(total, 1)
    verdict = 'VALID' if len(colours) > 8 and nonblack > 0 else 'BLANK'
    print(f'{w}x{h} colours={len(colours)} non-black={pct:.1f}% {verdict}')
    return 0 if verdict == 'VALID' else 1


if __name__ == '__main__':
    sys.exit(main())

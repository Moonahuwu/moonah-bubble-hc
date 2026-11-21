"""Makes textures/gui/eidolon.png: the Strange Matter icon recoloured as a rainbow (hue sweeps diagonally across it,
brightness and transparency kept). No libraries needed. Run from the bubble-neoforge folder."""
import colorsys
import struct
import zlib

SRC = "src/main/resources/assets/bubble/textures/gui/strange_matter.png"
DST = "src/main/resources/assets/bubble/textures/gui/eidolon.png"


def read_png(path):
    data = open(path, "rb").read()
    i, idat, w, h = 8, b"", 0, 0
    while i < len(data):
        n = struct.unpack(">I", data[i:i + 4])[0]
        t = data[i + 4:i + 8]
        body = data[i + 8:i + 8 + n]
        if t == b"IHDR":
            w, h, depth, ctype = struct.unpack(">IIBB", body[:10])
            assert depth == 8 and ctype == 6, "expects 8-bit RGBA"
        elif t == b"IDAT":
            idat += body
        i += 12 + n
    raw = zlib.decompress(idat)
    stride = w * 4
    rows, prev, pos = [], bytearray(stride), 0
    for _ in range(h):
        f = raw[pos]
        line = bytearray(raw[pos + 1:pos + 1 + stride])
        pos += 1 + stride
        for x in range(stride):
            a = line[x - 4] if x >= 4 else 0
            b = prev[x]
            c = prev[x - 4] if x >= 4 else 0
            if f == 1:
                line[x] = (line[x] + a) & 255
            elif f == 2:
                line[x] = (line[x] + b) & 255
            elif f == 3:
                line[x] = (line[x] + (a + b) // 2) & 255
            elif f == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[x] = (line[x] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        rows.append(line)
        prev = line
    return w, h, rows


def write_png(path, w, h, rows):
    raw = b"".join(b"\x00" + bytes(r) for r in rows)

    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)

    open(path, "wb").write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
                           + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


w, h, rows = read_png(SRC)
out = []
for y, row in enumerate(rows):
    line = bytearray(row)
    for x in range(w):
        r, g, b, a = row[x * 4:x * 4 + 4]
        if a == 0:
            continue
        _, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
        hue = ((x + y) / (w + h) * 1.0) % 1.0
        nr, ng, nb = colorsys.hsv_to_rgb(hue, max(0.65, s), min(1.0, v * 1.1 + 0.05))
        line[x * 4:x * 4 + 3] = bytes((int(nr * 255), int(ng * 255), int(nb * 255)))
    out.append(line)
write_png(DST, w, h, out)
print("wrote", DST)

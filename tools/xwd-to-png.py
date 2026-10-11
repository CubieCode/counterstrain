"""Convert an Xvfb -fbdir framebuffer (XWD, 24/32-bit ZPixmap) to PNG.

Standard library only, so it runs on foundry without extra packages:
    python3 -I tools/xwd-to-png.py .xvfb/Xvfb_screen0 out.png
"""
import struct
import sys
import zlib


def read_xwd(path):
    with open(path, "rb") as f:
        data = f.read()
    header_size = struct.unpack(">I", data[0:4])[0]
    # Xvfb writes host byte order; the version field (7) tells us which.
    order = ">" if struct.unpack(">I", data[4:8])[0] == 7 else "<"
    fields = struct.unpack(order + "25I", data[:100])
    (_, _, pixmap_format, depth, width, height, _, byte_order, _, _, _,
     bits_per_pixel, bytes_per_line, _, red_mask, green_mask, blue_mask,
     _, ncolors) = fields[:19]
    if pixmap_format != 2 or bits_per_pixel not in (24, 32):
        raise SystemExit(f"unsupported XWD: format {pixmap_format}, {bits_per_pixel} bpp")
    offset = header_size + ncolors * 12
    bpp = bits_per_pixel // 8
    pixel_order = "<" if byte_order == 0 else ">"

    def shift(mask):
        return (mask & -mask).bit_length() - 1

    rs, gs, bs = shift(red_mask), shift(green_mask), shift(blue_mask)
    rows = []
    for y in range(height):
        start = offset + y * bytes_per_line
        line = data[start:start + width * bpp]
        row = bytearray(b"\x00")
        for x in range(width):
            px = int.from_bytes(line[x * bpp:(x + 1) * bpp], "little" if pixel_order == "<" else "big")
            row += bytes(((px & red_mask) >> rs, (px & green_mask) >> gs, (px & blue_mask) >> bs))
        rows.append(bytes(row))
    return width, height, b"".join(rows)


def write_png(path, width, height, raw):
    def chunk(kind, body):
        return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body))

    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n")
        f.write(chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)))
        f.write(chunk(b"IDAT", zlib.compress(raw, 6)))
        f.write(chunk(b"IEND", b""))


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("usage: xwd-to-png.py INPUT.xwd OUTPUT.png")
    w, h, pixels = read_xwd(sys.argv[1])
    write_png(sys.argv[2], w, h, pixels)

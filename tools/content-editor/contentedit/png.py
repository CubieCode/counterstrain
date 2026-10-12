"""PNG reading and writing, sharing tools/check-provenance.py's decoder and marker rule
so the editor and the CI check can never disagree about what a placeholder is."""
import struct
import zlib

from .checker import provenance

PngError = provenance.PngError
SIGNATURE = b"\x89PNG\r\n\x1a\n"


class Image:
    """RGBA pixels as rows of (r, g, b, a) tuples."""

    def __init__(self, width, height, rows):
        self.width, self.height, self.rows = width, height, rows

    def pixel(self, x, y):
        return self.rows[y][x]


def header(data):
    """(width, height, interlaced) from the IHDR chunk, or PngError."""
    if data[:8] != SIGNATURE:
        raise PngError("not a PNG file")
    if len(data) < 33 or data[12:16] != b"IHDR":
        raise PngError("missing IHDR")
    width, height, _, _, _, _, interlace = struct.unpack(">IIBBBBB", data[16:29])
    if width == 0 or height == 0:
        raise PngError("zero-sized image")
    return width, height, bool(interlace)


def decode(data):
    """Decode a whole PNG (any colour type the checker supports) to an Image."""
    width, height, _ = header(data)
    if width * height > 4096 * 4096:
        raise PngError(f"{width}×{height} is too large to decode here")
    w, h, rows = provenance.png_top_left_bytes(data, max(width, height))
    if len(rows) != h or any(len(r) != w for r in rows):
        raise PngError("truncated image data")
    return Image(w, h, rows)


def marker(data):
    """(marker_size, has_marker) exactly as tools/check-provenance.py computes them."""
    return provenance.has_marker_bytes(data)


def marker_size(width):
    return provenance.marker_size(width)


def encode(image):
    """Write an Image as an 8-bit RGBA, non-interlaced PNG."""
    raw = b"".join(b"\x00" + b"".join(bytes(px) for px in row) for row in image.rows)

    def chunk(kind, body):
        return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", image.width, image.height, 8, 6, 0, 0, 0)
    return SIGNATURE + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")

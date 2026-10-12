"""PNG reading and writing, sharing tools/check-provenance.py's decoder and marker rule
so the editor and the CI check can never disagree about what a placeholder is."""
import importlib.util
import os
import struct
import tempfile
import zlib

_CHECKER = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
                        "check-provenance.py")
_spec = importlib.util.spec_from_file_location("check_provenance", _CHECKER)
provenance = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(provenance)

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


def _with_file(data, fn):
    fd, path = tempfile.mkstemp(suffix=".png", prefix="content-editor-")
    try:
        with os.fdopen(fd, "wb") as f:
            f.write(data)
        return fn(path)
    finally:
        os.unlink(path)


def decode(data):
    """Decode a whole PNG (any colour type the checker supports) to an Image."""
    width, height, _ = header(data)
    w, h, rows = _with_file(data, lambda p: provenance.png_top_left(p, max(width, height)))
    if len(rows) != h or any(len(r) != w for r in rows):
        raise PngError("truncated image data")
    return Image(w, h, rows)


def marker(data):
    """(marker_size, has_marker) exactly as tools/check-provenance.py computes them."""
    return _with_file(data, provenance.has_marker)


def marker_size(width):
    return provenance.marker_size(width)


def encode(image):
    """Write an Image as an 8-bit RGBA, non-interlaced PNG."""
    raw = b"".join(b"\x00" + b"".join(bytes(px) for px in row) for row in image.rows)

    def chunk(kind, body):
        return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", image.width, image.height, 8, 6, 0, 0, 0)
    return SIGNATURE + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")

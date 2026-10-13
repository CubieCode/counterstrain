"""Check player-facing assets against the content provenance ledger.

    python3 -I tools/check-provenance.py [--root DIR] [--ledger FILE] [--verbose]
    python3 -I tools/check-provenance.py --self-test

Run through tools/check-provenance.sh. Standard library only (PNGs are decoded
with zlib). Fails when:

- an image, sound or model under a scanned tree has no row in the ledger;
- a `texture` row's PNG carries the placeholder marker (the 2x2 #FF00FF
  top-left square, scaled with width / 16) while its status is `final`, or
  lacks it while its status is `placeholder`;
- a row points at a repository file that does not exist, unless its status is
  `removed` (and a `removed` row whose file still exists fails too; a removed
  `file:key` row fails only while a JSON file still has that key, or a key
  with that prefix for a `*` row);
- a row has an unknown status, or two rows name the same file or the same text ID
  (an exact `file:key` row overriding a `file:prefix*` row is fine: the most
  specific row wins).

Scanned trees: mod/src/main/resources/assets/, mod/src/generated/resources/assets/
and pack/ (except pack/mods/, packwiz metadata). Gitignored files are skipped.
Assets are picked by extension, so configs, scripts and packwiz TOML never
count. The content-provenance skill documents the rules.
"""
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import zlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FIXTURES = os.path.join(ROOT, "tools", "tests", "provenance")

SCAN_ROOTS = ("mod/src/main/resources/assets/", "mod/src/generated/resources/assets/", "pack/")
SKIP_PREFIXES = ("pack/mods/",)
IMAGE_EXT = (".png", ".jpg", ".jpeg", ".gif", ".webp")
SOUND_EXT = (".ogg", ".wav", ".mp3", ".flac")
MODEL_EXT = (".obj", ".bbmodel", ".geo.json")
# Block and item models: any JSON under assets/<namespace>/models/.
MODEL_DIR = re.compile(r"(^|/)assets/[^/]+/models/.+\.json$")
# Ledger rows name repository files under these roots; other IDs (store pages) are not files.
REPO_PATH_ROOTS = ("mod/", "pack/", "docs/")
STATUSES = ("placeholder", "draft", "final", "removed")
MAGENTA = (255, 0, 255, 255)


def kind(rel):
    low = rel.lower()
    if low.endswith(IMAGE_EXT):
        return "image"
    if low.endswith(SOUND_EXT):
        return "sound"
    if low.endswith(MODEL_EXT) or MODEL_DIR.search(rel):
        return "model"
    return None


def candidate_files(root):
    """Repository files, minus gitignored ones when root is a git work tree."""
    out = subprocess.run(["git", "-C", root, "ls-files", "--cached", "--others", "--exclude-standard"],
                         capture_output=True, text=True)
    if out.returncode == 0:
        return [p for p in out.stdout.splitlines() if os.path.isfile(os.path.join(root, p))]
    files = []
    for dirpath, _, names in os.walk(root):
        for name in names:
            files.append(os.path.relpath(os.path.join(dirpath, name), root).replace(os.sep, "/"))
    return files


def scanned_assets(root):
    return sorted(p for p in candidate_files(root)
                  if p.startswith(SCAN_ROOTS) and not p.startswith(SKIP_PREFIXES) and kind(p))


def parse_row(line):
    """The stripped cells of a ledger table row, or None for anything else (prose, header, separator).

    Shared with the content editor (tools/content-editor/contentedit/ledger.py) so both agree on what a row is.
    """
    if not line.startswith("|"):
        return None
    cells = [c.strip() for c in line.strip().strip("|").split("|")]
    if len(cells) < 5 or cells[0] in ("Path / ID",) or set(cells[0]) <= set("-: "):
        return None
    return cells


def row_path_id(cell):
    """A row's Path / ID: the backticked part of the first cell, else the whole cell."""
    ticked = re.search(r"`([^`]+)`", cell)
    return (ticked.group(1) if ticked else cell).strip()


def parse_ledger(path):
    """Yield (line_no, path_id, type, status) for each table row."""
    with open(path, encoding="utf-8") as f:
        lines = f.read().splitlines()
    for line_no, line in enumerate(lines, 1):
        cells = parse_row(line)
        if cells is None:
            continue
        yield line_no, row_path_id(cells[0]), cells[1].strip("` ").lower(), cells[3].strip("` ").lower()


# --- PNG ---------------------------------------------------------------------

class PngError(Exception):
    pass


def png_top_left(path, size):
    """Return (width, height, rows) where rows holds RGBA tuples for the top-left size x size pixels."""
    with open(path, "rb") as f:
        return png_top_left_bytes(f.read(), size)


def png_top_left_bytes(data, size):
    """png_top_left for PNG bytes already in memory (the content editor checks uploads this way)."""
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise PngError("not a PNG file")
    pos, idat, palette, trns, header = 8, [], None, None, None
    while pos + 8 <= len(data):
        length, ctype = struct.unpack(">I4s", data[pos:pos + 8])
        chunk = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if ctype == b"IHDR":
            header = struct.unpack(">IIBBBBB", chunk)
        elif ctype == b"PLTE":
            palette = [tuple(chunk[i:i + 3]) for i in range(0, len(chunk), 3)]
        elif ctype == b"tRNS":
            trns = chunk
        elif ctype == b"IDAT":
            idat.append(chunk)
        elif ctype == b"IEND":
            break
    if header is None:
        raise PngError("missing IHDR")
    width, height, depth, color, _, _, interlace = header
    if interlace:
        raise PngError("interlaced PNGs are not supported; save it non-interlaced")
    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}.get(color)
    if channels is None or depth not in (1, 2, 4, 8, 16):
        raise PngError(f"unsupported colour type {color} / bit depth {depth}")
    if color == 3 and palette is None:
        raise PngError("indexed PNG without a palette")
    try:
        raw = zlib.decompress(b"".join(idat))
    except zlib.error as e:
        raise PngError(f"corrupt image data ({e})")
    bpp = max(1, channels * depth // 8)
    stride = (width * channels * depth + 7) // 8
    size_y, size_x = min(size, height), min(size, width)
    prev, rows = bytearray(stride), []
    for y in range(size_y):
        start = y * (stride + 1)
        if start + 1 + stride > len(raw):
            raise PngError("truncated image data")
        ftype, line = raw[start], bytearray(raw[start + 1:start + 1 + stride])
        for i in range(stride):
            a = line[i - bpp] if i >= bpp else 0
            b, c = prev[i], (prev[i - bpp] if i >= bpp else 0)
            if ftype == 1:
                line[i] = (line[i] + a) & 255
            elif ftype == 2:
                line[i] = (line[i] + b) & 255
            elif ftype == 3:
                line[i] = (line[i] + (a + b) // 2) & 255
            elif ftype == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[i] = (line[i] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
            elif ftype != 0:
                raise PngError(f"bad filter type {ftype}")
        prev = line
        rows.append([pixel(line, x, color, depth, channels, palette, trns) for x in range(size_x)])
    return width, height, rows


def sample(line, index, depth):
    if depth == 8:
        return line[index]
    if depth == 16:
        return line[2 * index]  # high byte
    per_byte = 8 // depth
    shift = 8 - depth * (index % per_byte + 1)
    value = (line[index // per_byte] >> shift) & ((1 << depth) - 1)
    return value * 255 // ((1 << depth) - 1)


def pixel(line, x, color, depth, channels, palette, trns):
    if color == 3:
        per_byte = 8 // depth if depth < 8 else 1
        if depth < 8:
            shift = 8 - depth * (x % per_byte + 1)
            index = (line[x // per_byte] >> shift) & ((1 << depth) - 1)
        else:
            index = line[x]
        if index >= len(palette):
            raise PngError("palette index out of range")
        alpha = trns[index] if trns and index < len(trns) else 255
        return palette[index] + (alpha,)
    s = [sample(line, x * channels + c, depth) for c in range(channels)]
    if color == 0:
        return (s[0], s[0], s[0], 255)
    if color == 4:
        return (s[0], s[0], s[0], s[1])
    if color == 2:
        return (s[0], s[1], s[2], 255)
    return tuple(s)


def marker_size(width):
    """2 px at 16 px wide, scaled proportionally (4 at 32, 8 at 64); at least 1."""
    return max(1, round(2 * width / 16))


def has_marker(path):
    with open(path, "rb") as f:
        return has_marker_bytes(f.read())


def has_marker_bytes(data):
    """(marker size, marked) for PNG bytes: the solid #FF00FF top-left square scaled with the width."""
    header = data[:24]
    if len(header) < 24 or header[:8] != b"\x89PNG\r\n\x1a\n":
        raise PngError("not a PNG file")
    width, height = struct.unpack(">II", header[16:24])
    size = marker_size(width)
    if size > width or size > height:
        raise PngError(f"{width}x{height} is too small for a {size}x{size} marker")
    _, _, rows = png_top_left_bytes(data, size)
    return size, all(px == MAGENTA for row in rows for px in row)


# --- Check -------------------------------------------------------------------

def has_text_key(path, key):
    """Whether the JSON object in ``path`` has ``key`` (any key with its prefix for ``prefix*``); False if not JSON."""
    try:
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
    except (OSError, ValueError):
        return False
    if not isinstance(data, dict):
        return False
    if key.endswith("*"):
        return any(k.startswith(key[:-1]) for k in data)
    return key in data


def check(root, ledger):
    failures = []
    ledger_rel = os.path.relpath(ledger, root) if ledger.startswith(root + os.sep) else ledger

    def fail(where, message):
        failures.append(f"{where}: {message}")

    rows, text_rows = {}, {}
    for line_no, path_id, ctype, status in parse_ledger(ledger):
        where = f"{ledger_rel}:{line_no}"
        if status not in STATUSES:
            fail(where, f"`{path_id}` has unknown status '{status}' (use {', '.join(STATUSES)})")
            continue
        file_part = path_id.split(":", 1)[0]
        if not file_part.startswith(REPO_PATH_ROOTS):
            continue
        on_disk = os.path.isfile(os.path.join(root, file_part))
        if status == "removed" and ":" in path_id:
            # A removed text row: its file stays, the key must be gone.
            if on_disk and has_text_key(os.path.join(root, file_part), path_id.split(":", 1)[1]):
                fail(where, f"`{path_id}` has status removed but its key is still in the file; set its real status")
        elif status == "removed":
            if on_disk:
                fail(where, f"`{file_part}` has status removed but the file still exists; set its real status")
        elif not on_disk:
            fail(where, f"`{file_part}` does not exist; restore it, fix the path, or set status removed "
                        "with a note saying when and why")
        if ":" in path_id:
            # A text row (file:key) never covers a whole asset file. An exact key row may override a
            # `*` row (the most specific row wins), but two rows for the same ID are ambiguous.
            if path_id in text_rows:
                fail(where, f"`{path_id}` already has a row at line {text_rows[path_id]}; edit that row instead")
            text_rows.setdefault(path_id, line_no)
            continue
        if path_id in rows:
            fail(where, f"`{path_id}` already has a row at line {rows[path_id][0]}; edit that row instead")
            continue
        rows[path_id] = (line_no, ctype, status)

    for rel in scanned_assets(root):
        row = rows.get(rel)
        if row is None:
            fail(rel, f"{kind(rel)} has no row in {ledger_rel} (content-provenance skill)")
            continue
        line_no, ctype, status = row
        where = f"{rel} ({ledger_rel}:{line_no})"
        if status == "removed":
            continue  # already reported above
        if ctype != "texture" or not rel.lower().endswith(".png") or status not in ("placeholder", "final"):
            continue
        try:
            size, marked = has_marker(os.path.join(root, rel))
        except PngError as e:
            fail(where, f"cannot read texture for the placeholder marker check: {e}")
            continue
        if status == "final" and marked:
            fail(where, f"status final but the {size}x{size} #FF00FF placeholder marker is present; "
                        "remove the marker from final art")
        elif status == "placeholder" and not marked:
            fail(where, f"status placeholder but the {size}x{size} #FF00FF placeholder marker is missing "
                        "from the top-left corner; add it or update the row")
    return failures


def self_test():
    """Run every case under tools/tests/provenance/cases/ against a temporary tree and ledger."""
    cases_dir = os.path.join(FIXTURES, "cases")
    images = os.path.join(FIXTURES, "images")
    problems = []
    for case in sorted(os.listdir(cases_dir)):
        case_dir = os.path.join(cases_dir, case)
        with tempfile.TemporaryDirectory(prefix="provenance-") as tmp:
            with open(os.path.join(case_dir, "tree.txt"), encoding="utf-8") as f:
                for line in f:
                    line = line.split("#", 1)[0].strip()
                    if not line:
                        continue
                    dest, _, source = (part.strip() for part in line.partition("<-"))
                    target = os.path.join(tmp, dest)
                    os.makedirs(os.path.dirname(target), exist_ok=True)
                    if source:
                        shutil.copyfile(os.path.join(images, source), target)
                    else:
                        open(target, "wb").close()
            ledger = os.path.join(tmp, "docs", "CONTENT-PROVENANCE.md")
            os.makedirs(os.path.dirname(ledger), exist_ok=True)
            shutil.copyfile(os.path.join(case_dir, "ledger.txt"), ledger)
            got = check(tmp, ledger)
        with open(os.path.join(case_dir, "expect.txt"), encoding="utf-8") as f:
            expected = [l.strip() for l in f if l.strip() and not l.startswith("#")]
        ok = len(got) == len(expected) and all(any(e in g for g in got) for e in expected)
        if not ok:
            problems.append(f"{case}: expected {len(expected)} failure(s) matching {expected}, got {got}")
        print(f"{'ok  ' if ok else 'FAIL'} {case}: {len(got)} failure(s)")
        if VERBOSE:
            for g in got:
                print(f"      {g}")
    for p in problems:
        print(f"FAIL {p}", file=sys.stderr)
    return 1 if problems else 0


def main(argv):
    root, ledger = ROOT, None
    args = iter(argv)
    for arg in args:
        if arg == "--root":
            root = os.path.abspath(next(args))
        elif arg == "--ledger":
            ledger = os.path.abspath(next(args))
        elif arg == "--self-test":
            return self_test()
        elif arg != "--verbose":
            print(f"unknown argument {arg}", file=sys.stderr)
            return 2
    ledger = ledger or os.path.join(root, "docs", "CONTENT-PROVENANCE.md")
    failures = check(root, ledger)
    for f in failures:
        print(f"FAIL {f}", file=sys.stderr)
    if VERBOSE or not failures:
        print(f"provenance: {len(scanned_assets(root))} asset(s) scanned, {len(failures)} failure(s)")
    return 1 if failures else 0


VERBOSE = "--verbose" in sys.argv

if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

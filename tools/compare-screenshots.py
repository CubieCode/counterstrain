"""Compare a harness scene's screenshots with its reviewed references.

    python3 -I tools/compare-screenshots.py --scene <id> --actual DIR --out DIR
        [--references DIR] [--threshold 24] [--max-diff-ratio 0.0005] [--accept]
    python3 -I tools/compare-screenshots.py --self-test

Run through tools/compare-screenshots.sh. Standard library only (PNGs are
decoded and written with zlib). Each <shot>.png in the scene's reference
directory (tools/visual-references/<scene>/ by default) is compared with the
same name in --actual. A pixel differs when any RGBA channel differs by more
than --threshold (0-255); a shot passes when at most --max-diff-ratio of its
pixels differ. For every compared shot it writes <shot>-diff.png into --out
(the actual image dimmed to grey, differing pixels red, smaller differences
yellow) and a summary in compare-result.txt.

A reference shot missing from --actual, an actual shot with no reference, or a
size mismatch counts as a difference. --accept copies the actual shots over the
scene's references after comparing; use it only for images someone reviewed.

Exit codes: 0 all shots within threshold, 1 at least one shot differs,
2 usage or tool error, 3 no reference exists for the scene.
"""
import argparse
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import zlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REFERENCES = os.path.join(ROOT, "tools", "visual-references")
SCENE_ID = re.compile(r"^[a-z0-9_]+(/[a-z0-9_]+)*$")
SHOT_NAME = re.compile(r"^[a-z0-9_]+\.png$")
CHANNELS = {0: 1, 2: 3, 4: 2, 6: 4}  # PNG colour type -> samples per pixel (8-bit only)

EXIT_OK, EXIT_DIFFERS, EXIT_ERROR, EXIT_NO_REFERENCE = 0, 1, 2, 3


class ToolError(Exception):
    pass


def shown(path):
    """Repository-relative path when inside the repository, else absolute."""
    path = os.path.abspath(path)
    return os.path.relpath(path, ROOT) if path.startswith(ROOT + os.sep) else path


def read_png(path):
    """Return (width, height, raw_idat, rgba_loader) for an 8-bit non-interlaced PNG."""
    try:
        with open(path, "rb") as f:
            data = f.read()
    except OSError as e:
        raise ToolError(f"{path}: {e.strerror}")
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ToolError(f"{path}: not a PNG")
    pos, idat, header = 8, [], None
    while pos + 8 <= len(data):
        length, kind = struct.unpack(">I4s", data[pos:pos + 8])
        body = data[pos + 8:pos + 8 + length]
        if kind == b"IHDR":
            header = struct.unpack(">IIBBBBB", body)
        elif kind == b"IDAT":
            idat.append(body)
        elif kind == b"IEND":
            break
        pos += 12 + length
    if header is None:
        raise ToolError(f"{path}: no IHDR chunk")
    width, height, depth, colour, _, _, interlace = header
    if depth != 8 or colour not in CHANNELS or interlace != 0:
        raise ToolError(f"{path}: unsupported PNG (bit depth {depth}, colour type {colour}, interlace {interlace});"
                        " expected 8-bit grey/RGB/RGBA, not interlaced")
    try:
        raw = zlib.decompress(b"".join(idat))
    except zlib.error as e:
        raise ToolError(f"{path}: corrupt image data ({e})")
    if len(raw) != height * (1 + width * CHANNELS[colour]):
        raise ToolError(f"{path}: image data has the wrong length")
    return width, height, colour, raw


def to_rgba(width, height, colour, raw):
    """Undo PNG row filters and expand to RGBA bytes."""
    bpp = CHANNELS[colour]
    stride = width * bpp
    out = bytearray(height * stride)
    prev = bytearray(stride)
    for y in range(height):
        start = y * (stride + 1)
        ftype = raw[start]
        line = bytearray(raw[start + 1:start + 1 + stride])
        if ftype == 1:
            for i in range(bpp, stride):
                line[i] = (line[i] + line[i - bpp]) & 0xFF
        elif ftype == 2:
            line = bytearray((a + b) & 0xFF for a, b in zip(line, prev))
        elif ftype == 3:
            for i in range(stride):
                left = line[i - bpp] if i >= bpp else 0
                line[i] = (line[i] + ((left + prev[i]) >> 1)) & 0xFF
        elif ftype == 4:
            for i in range(stride):
                a = line[i - bpp] if i >= bpp else 0
                b = prev[i]
                c = prev[i - bpp] if i >= bpp else 0
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[i] = (line[i] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 0xFF
        elif ftype != 0:
            raise ToolError(f"unknown PNG filter type {ftype}")
        out[y * stride:(y + 1) * stride] = line
        prev = line
    if colour == 6:
        return bytes(out)
    rgba = bytearray(width * height * 4)
    if colour == 2:
        for i in range(3):
            rgba[i::4] = out[i::3]
        rgba[3::4] = b"\xff" * (width * height)
    elif colour == 0:
        for i in range(3):
            rgba[i::4] = out
        rgba[3::4] = b"\xff" * (width * height)
    else:  # 4: grey + alpha
        for i in range(3):
            rgba[i::4] = out[0::2]
        rgba[3::4] = out[1::2]
    return bytes(rgba)


def write_png(path, width, height, rgb):
    def chunk(kind, body):
        return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body))

    stride = width * 3
    rows = b"".join(b"\x00" + rgb[y * stride:(y + 1) * stride] for y in range(height))
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n")
        f.write(chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)))
        f.write(chunk(b"IDAT", zlib.compress(rows, 6)))
        f.write(chunk(b"IEND", b""))


def compare_shot(ref_path, actual_path, diff_path, threshold):
    """Return a result dict for one shot and write its diff image."""
    rw, rh, rc, rraw = read_png(ref_path)
    aw, ah, ac, araw = read_png(actual_path)
    if (rw, rh) != (aw, ah):
        return {"status": "size-mismatch", "reference": f"{rw}x{rh}", "actual": f"{aw}x{ah}"}
    actual = to_rgba(aw, ah, ac, araw)
    # Identical filtered data means identical pixels; skip the per-pixel pass.
    reference = actual if (rc, rraw) == (ac, araw) else to_rgba(rw, rh, rc, rraw)
    pixels = aw * ah
    diff = bytearray(pixels * 3)
    # Grey, dimmed copy of the actual image as the diff background.
    grey = bytes((r * 77 + g * 150 + b * 29) >> 10 for r, g, b in zip(actual[0::4], actual[1::4], actual[2::4]))
    for i in range(3):
        diff[i::3] = grey
    differing = small = max_delta = 0
    if reference is not actual:
        for p in range(pixels):
            o = p * 4
            if actual[o:o + 4] == reference[o:o + 4]:
                continue
            delta = max(abs(actual[o + i] - reference[o + i]) for i in range(4))
            max_delta = max(max_delta, delta)
            if delta > threshold:
                differing += 1
                diff[p * 3:p * 3 + 3] = b"\xff\x00\x00"
            else:
                small += 1
                diff[p * 3:p * 3 + 3] = b"\xff\xd0\x00"
    write_png(diff_path, aw, ah, bytes(diff))
    return {"differing": differing, "below_threshold": small, "max_delta": max_delta,
            "ratio": differing / pixels, "diff": os.path.basename(diff_path)}


def shots_in(directory):
    if not os.path.isdir(directory):
        return []
    return sorted(n[:-4] for n in os.listdir(directory) if SHOT_NAME.match(n))


def run(scene, actual_dir, out_dir, references, threshold, max_ratio, accept):
    if not SCENE_ID.match(scene):
        raise ToolError(f"scene must look like area/subject: {scene}")
    if not os.path.isdir(actual_dir):
        raise ToolError(f"actual screenshots directory not found: {actual_dir}")
    ref_dir = os.path.join(references, scene)
    ref_shots = shots_in(ref_dir)
    os.makedirs(out_dir, exist_ok=True)
    lines = [f"scene={scene}", f"reference={shown(ref_dir)}",
             f"actual={actual_dir}", f"threshold={threshold}", f"max_diff_ratio={max_ratio}"]
    if not ref_shots:
        lines.append("status=no-reference")
        write_result(out_dir, lines)
        print(f"no reference for scene {scene} (expected {shown(ref_dir)}/<shot>.png)",
              file=sys.stderr)
        if accept:
            accept_shots(ref_dir, ref_shots, actual_dir, shots_in(actual_dir))
        return EXIT_NO_REFERENCE
    actual_shots = shots_in(actual_dir)
    failed = False
    for shot in sorted(set(ref_shots) | set(actual_shots)):
        ref_path = os.path.join(ref_dir, shot + ".png")
        actual_path = os.path.join(actual_dir, shot + ".png")
        if shot not in actual_shots:
            res = {"status": "missing"}
        elif shot not in ref_shots:
            res = {"status": "no-reference"}
        else:
            res = compare_shot(ref_path, actual_path, os.path.join(out_dir, shot + "-diff.png"), threshold)
            if "status" not in res:
                res["status"] = "pass" if res["ratio"] <= max_ratio else "differs"
                res["ratio"] = f"{res['ratio']:.6f}"
        failed |= res["status"] != "pass"
        fields = " ".join(f"{k}={v}" for k, v in res.items() if k != "status")
        lines.append(f"shot={shot} status={res['status']}" + (f" {fields}" if fields else ""))
        print(f"{shot}: {res['status']}" + (f" ({fields})" if fields else ""), file=sys.stderr)
    lines.append(f"status={'differs' if failed else 'pass'}")
    write_result(out_dir, lines)
    if accept:
        accept_shots(ref_dir, ref_shots, actual_dir, actual_shots)
    return EXIT_DIFFERS if failed else EXIT_OK


def accept_shots(ref_dir, ref_shots, actual_dir, actual_shots):
    """Make the actual shots the scene's reference, dropping shots the scene no longer takes."""
    if not actual_shots:
        raise ToolError(f"no <shot>.png files to accept in {actual_dir}")
    os.makedirs(ref_dir, exist_ok=True)
    for shot in ref_shots:
        if shot not in actual_shots:
            os.remove(os.path.join(ref_dir, shot + ".png"))
    for shot in actual_shots:
        shutil.copyfile(os.path.join(actual_dir, shot + ".png"), os.path.join(ref_dir, shot + ".png"))
    print(f"accepted {len(actual_shots)} shot(s) as the reference in {shown(ref_dir)}", file=sys.stderr)


def write_result(out_dir, lines):
    with open(os.path.join(out_dir, "compare-result.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")


def self_test():
    """Synthetic images: identical, sub-threshold noise, a changed block, missing and no reference."""
    w, h = 64, 32
    base = bytearray(b"\x40\x80\xc0" * (w * h))
    noisy = bytearray(base)
    noisy[0:3] = b"\x44\x80\xc0"  # one pixel, delta 4
    changed = bytearray(base)
    for y in range(8):
        for x in range(8):
            changed[(y * w + x) * 3:(y * w + x) * 3 + 3] = b"\xff\x00\xff"
    cases = []
    with tempfile.TemporaryDirectory() as tmp:
        refs = os.path.join(tmp, "refs")
        os.makedirs(os.path.join(refs, "t", "scene"))
        write_png(os.path.join(refs, "t", "scene", "a.png"), w, h, bytes(base))
        write_png(os.path.join(refs, "t", "scene", "b.png"), w, h, bytes(base))

        def actual(name, images):
            d = os.path.join(tmp, name)
            os.makedirs(d)
            for shot, rgb in images.items():
                write_png(os.path.join(d, shot + ".png"), w, h, bytes(rgb))
            return d

        def check(label, code_expected, scene, actual_dir, expect_diff=()):
            out = os.path.join(tmp, "out-" + label)
            code = run(scene, actual_dir, out, refs, 24, 0.0005, False)
            ok = code == code_expected and all(os.path.isfile(os.path.join(out, s + "-diff.png"))
                                               for s in expect_diff)
            ok &= os.path.isfile(os.path.join(out, "compare-result.txt"))
            cases.append((label, ok, code, code_expected))

        check("identical", EXIT_OK, "t/scene", actual("same", {"a": base, "b": base}), ("a", "b"))
        check("below-threshold", EXIT_OK, "t/scene", actual("noise", {"a": noisy, "b": base}), ("a",))
        check("changed", EXIT_DIFFERS, "t/scene", actual("changed", {"a": changed, "b": base}), ("a",))
        check("missing-shot", EXIT_DIFFERS, "t/scene", actual("missing", {"a": base}))
        check("extra-shot", EXIT_DIFFERS, "t/scene", actual("extra", {"a": base, "b": base, "c": base}))
        check("no-reference", EXIT_NO_REFERENCE, "t/other", actual("noref", {"a": base}))
    for label, ok, code, expected in cases:
        print(f"{'ok  ' if ok else 'FAIL'} {label}: exit {code} (expected {expected})")
    return EXIT_OK if all(ok for _, ok, _, _ in cases) else EXIT_DIFFERS


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--scene")
    parser.add_argument("--actual")
    parser.add_argument("--out")
    parser.add_argument("--references", default=REFERENCES)
    parser.add_argument("--threshold", type=int, default=24)
    parser.add_argument("--max-diff-ratio", type=float, default=0.0005)
    parser.add_argument("--accept", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    try:
        args = parser.parse_args()
    except SystemExit:
        return EXIT_ERROR
    if args.self_test:
        return self_test()
    if not (args.scene and args.actual and args.out):
        print("usage: compare-screenshots --scene <id> --actual <dir> --out <dir>", file=sys.stderr)
        return EXIT_ERROR
    if not 0 <= args.threshold <= 255 or not 0 <= args.max_diff_ratio <= 1:
        print("--threshold must be 0-255 and --max-diff-ratio 0-1", file=sys.stderr)
        return EXIT_ERROR
    try:
        return run(args.scene, args.actual, args.out, args.references, args.threshold,
                   args.max_diff_ratio, args.accept)
    except ToolError as e:
        print(f"error: {e}", file=sys.stderr)
        return EXIT_ERROR


if __name__ == "__main__":
    sys.exit(main())

"""Checks a replacement texture must pass before the editor writes it (P1.5).

A valid, non-interlaced PNG; the original's size, or its aspect ratio at a
power-of-two scale; an animation strip only with a matching `.mcmeta`; and the
placeholder marker present exactly when the chosen status is `placeholder`.
"""
import json

from . import png

MAX_BYTES = 1024 * 1024


def check(id_, label, ok, detail, state=None):
    return {"id": id_, "label": label, "state": state or ("pass" if ok else "fail"), "detail": detail}


def power_of_two(ratio):
    """True when ratio is 2**k for some integer k (including k < 0 and k == 0)."""
    if ratio <= 0:
        return False
    while ratio >= 2:
        ratio /= 2
    while ratio < 1:
        ratio *= 2
    return ratio == 1


def parse_mcmeta(text):
    """The `animation` object of an .mcmeta, or raise ValueError."""
    try:
        data = json.loads(text)
    except json.JSONDecodeError as e:
        raise ValueError(f"invalid JSON: {e}")
    if not isinstance(data, dict) or not isinstance(data.get("animation"), dict):
        raise ValueError("no \"animation\" object")
    return data["animation"]


def frame_size(width, height, animation):
    """(frame width, frame height, frame count) for a strip described by an .mcmeta animation object."""
    fw = animation.get("width")
    fh = animation.get("height")
    if fw is None and fh is None:
        fw = fh = min(width, height)
    fw, fh = fw or width, fh or height
    if not (isinstance(fw, int) and isinstance(fh, int)) or fw <= 0 or fh <= 0:
        raise ValueError("frame width and height must be positive integers")
    if width % fw or height % fh:
        raise ValueError(f"{width}×{height} is not a whole number of {fw}×{fh} frames")
    return fw, fh, (width // fw) * (height // fh)


def validate(data, status, original=None, original_mcmeta=None, mcmeta=None, marker_rule=True):
    """Run every check. Returns (checks, info); a write needs no check in state 'fail'.

    data: the uploaded bytes. status: the row's status after the write.
    original: the current file's bytes, or None for a new row. marker_rule: False for gui, icon and other
    images, which mark placeholders with a visible "PLACEHOLDER" label instead of the #FF00FF corner.
    original_mcmeta / mcmeta: text of the .mcmeta beside the current file and the one
    that will sit beside the new file (an uploaded one, else the current one).
    """
    checks, info = [], {"bytes": len(data)}
    if len(data) > MAX_BYTES:
        return [check("png", "Valid PNG", False, f"{len(data)} bytes; the limit is {MAX_BYTES}")], info
    try:
        width, height, interlaced = png.header(data)
        if interlaced:
            raise png.PngError("interlaced PNGs are not supported; save it non-interlaced")
        image = png.decode(data)
    except png.PngError as e:
        return [check("png", "Valid PNG", False, str(e))], info
    info.update(width=width, height=height)
    checks.append(check("png", "Valid PNG", True, f"{width}×{height}, {len(data)} B, non-interlaced"))

    sidecar = mcmeta if mcmeta is not None else original_mcmeta
    animation = None
    if sidecar is not None:
        try:
            animation = parse_mcmeta(sidecar)
        except ValueError as e:
            checks.append(check("mcmeta", "Animation", False, f".mcmeta: {e}"))
            return checks, info
    try:
        fw, fh, frames = frame_size(width, height, animation) if animation is not None else (width, height, 1)
    except ValueError as e:
        checks.append(check("mcmeta", "Animation", False, str(e)))
        return checks, info
    info.update(frame_width=fw, frame_height=fh, frames=frames)
    if animation is None and height > width and height % width == 0:
        checks.append(check("mcmeta", "Animation", False,
                            f"{width}×{height} looks like a {height // width}-frame strip but there is no .mcmeta; "
                            "add one beside the texture"))
    elif animation is not None:
        checks.append(check("mcmeta", "Animation", True, f"{frames} frame(s) of {fw}×{fh} with a matching .mcmeta"))
    else:
        checks.append(check("mcmeta", "Animation", True, "single frame, no .mcmeta needed"))

    if original is not None:
        try:
            ow, oh, _ = png.header(original)
            ofw, ofh, _ = frame_size(ow, oh, parse_mcmeta(original_mcmeta)) if original_mcmeta else (ow, oh, 1)
        except (png.PngError, ValueError) as e:
            checks.append(check("size", "Size", True, f"current file unreadable ({e}); size not compared", "info"))
        else:
            if (fw, fh) == (ofw, ofh):
                checks.append(check("size", "Size", True, f"{fw}×{fh}, same as the original"))
            elif fw * ofh == fh * ofw and power_of_two(fw / ofw):
                checks.append(check("size", "Size", True,
                                    f"{fw}×{fh}: the original's aspect ratio at a power-of-two scale of {ofw}×{ofh}"))
            else:
                checks.append(check("size", "Size", False,
                                    f"{fw}×{fh} is neither {ofw}×{ofh} nor its aspect ratio at a power-of-two scale"))

    size, marked = png.marker(data)
    if not marker_rule:
        checks.append(check("marker", "Placeholder label", True,
                            "not a block, item or entity texture: placeholders carry a visible PLACEHOLDER label "
                            "instead of the corner marker; check it by eye", "info"))
        info["marker"] = marked
        info["image"] = image
        return checks, info
    if status == "placeholder":
        checks.append(check("marker", "Placeholder marker", marked,
                            f"{size}×{size} #FF00FF marker {'present' if marked else 'missing'}; "
                            "placeholder textures need it in the top-left corner"))
    else:
        checks.append(check("marker", "Placeholder marker", not marked,
                            f"{size}×{size} #FF00FF marker {'present' if marked else 'absent'}; "
                            f"a {status} texture must not carry it"))
    info["marker"] = marked
    info["image"] = image
    return checks, info


def passed(checks):
    return all(c["state"] != "fail" for c in checks)

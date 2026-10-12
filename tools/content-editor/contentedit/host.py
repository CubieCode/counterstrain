"""Reference compositing for host overlay patterns (P2.2), for the editor's host preview.

Mirrors mod/src/main/resources/assets/remission/shaders/core/host_overlay.fsh and
HostOverlayLayer: the stage pattern repeats at one pattern texel per mob texel
across the mob's own texture, is skipped where the mob texture's alpha is below
0.1, is drawn at overlayIntensity opacity (patterns with alpha under 0.004
after that are discarded) and blends over the mob; from stage 3 the full-bright
veins_glow pattern is drawn over it the same way. Lighting, fog and the hurt
flash are left out. If the shader or the layer changes, change this and its
tests (tools/tests/content_editor/test_host.py) with it.
"""
from .png import Image

HOST_DIR = "mod/src/main/resources/assets/remission/textures/entity/host/"
STAGE_PATTERNS = ("stage_1.png", "stage_2.png", "stage_3.png")
GLOW_PATTERN = "veins_glow.png"
GLOW_STAGE = 3
MASK_ALPHA = 0.1
DISCARD_ALPHA = 0.004


def is_host_pattern(path):
    return path.startswith(HOST_DIR) and path[len(HOST_DIR):] in STAGE_PATTERNS + (GLOW_PATTERN,)


def stage_pattern(stage):
    """The pattern file for a stage (1 or more); stages above 3 use stage 3's, as HostOverlayLayer does."""
    return STAGE_PATTERNS[min(stage, len(STAGE_PATTERNS)) - 1]


def _blend(base, pattern, intensity):
    pr, pg, pb, pa = pattern
    alpha = pa / 255 * intensity
    if alpha < DISCARD_ALPHA:
        return base
    r, g, b, a = base
    mix = lambda dst, src: round(src * alpha + dst * (1 - alpha))
    return (mix(r, pr), mix(g, pg), mix(b, pb), a)


def composite(mob, pattern, glow=None, intensity=1.0):
    """The mob texture as it looks with a host overlay drawn over it."""
    intensity = max(0.0, min(1.0, float(intensity)))
    rows = []
    for y in range(mob.height):
        row = []
        for x in range(mob.width):
            px = mob.rows[y][x]
            if px[3] / 255 >= MASK_ALPHA and intensity > 0:
                px = _blend(px, pattern.rows[y % pattern.height][x % pattern.width], intensity)
                if glow is not None:
                    px = _blend(px, glow.rows[y % glow.height][x % glow.width], intensity)
            row.append(px)
        rows.append(row)
    return Image(mob.width, mob.height, rows)


# --- Built-in mannequins ------------------------------------------------------
# Neutral grey bodies on the vanilla humanoid (64×64) and quadruped (64×32) UV
# layouts, generated here so the preview works without the vanilla jar. They are
# dev-tool images, never shipped, so they have no ledger row.

def box_faces(u, v, w, h, d):
    """(x, y, width, height, shade) for each face of a model box at texture offset (u, v)."""
    return [(u + d, v, w, d, 1.12), (u + d + w, v, w, d, 0.8), (u, v + d, d, h, 0.9),
            (u + d, v + d, w, h, 1.0), (u + d + w, v + d, d, h, 0.9), (u + 2 * d + w, v + d, w, h, 0.85)]


def _paint(width, height, boxes):
    rows = [[(0, 0, 0, 0)] * width for _ in range(height)]
    for (u, v, w, h, d), colour in boxes:
        for fx, fy, fw, fh, shade in box_faces(u, v, w, h, d):
            for y in range(fy, fy + fh):
                for x in range(fx, fx + fw):
                    grain = ((x * 7 + y * 13) % 5 - 2) * 3
                    rows[y][x] = tuple(max(0, min(255, int(c * shade) + grain)) for c in colour) + (255,)
    return Image(width, height, rows)


SKIN, CLOTH, LEGS = (150, 148, 156), (104, 108, 120), (86, 84, 98)


def mannequin(kind="humanoid"):
    if kind == "quadruped":
        return _paint(64, 32, [((0, 0, 8, 8, 8), SKIN), ((28, 8, 10, 16, 8), CLOTH), ((0, 16, 4, 6, 4), LEGS)])
    return _paint(64, 64, [((0, 0, 8, 8, 8), SKIN), ((16, 16, 8, 12, 4), CLOTH), ((40, 16, 4, 12, 4), SKIN),
                           ((0, 16, 4, 12, 4), LEGS), ((16, 48, 4, 12, 4), LEGS), ((32, 48, 4, 12, 4), SKIN)])


# Mobs the preview offers: id -> (label, model kind, vanilla texture or None for the mannequin).
MOBS = {
    "mannequin": ("Mannequin (built in)", "humanoid", None),
    "mannequin_quadruped": ("Quadruped mannequin (built in)", "quadruped", None),
    "zombie": ("Zombie", "humanoid", "entity/zombie/zombie"),
    "husk": ("Husk", "humanoid", "entity/zombie/husk"),
    "drowned": ("Drowned", "humanoid", "entity/zombie/drowned"),
    "steve": ("Player (Steve)", "humanoid", "entity/player/wide/steve"),
    "pig": ("Pig", "quadruped", "entity/pig/pig"),
}

"""Shared helpers: import the editor library and build a temporary repository with a bare origin."""
import datetime
import os
import shutil
import subprocess
import sys
import tempfile
import textwrap

HERE = os.path.dirname(os.path.abspath(__file__))
TOOLS = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, os.path.join(TOOLS, "content-editor"))

from contentedit import png  # noqa: E402

MAGENTA = (255, 0, 255, 255)
TODAY = datetime.date(2026, 10, 12)
LANG = "mod/src/main/resources/assets/remission/lang/en_us.json"
TEX = "mod/src/main/resources/assets/remission/textures/"
HOST = TEX + "entity/host/"
GEN_MODEL = "mod/src/generated/resources/assets/remission/models/block/stone.json"
HAND_MODEL = "mod/src/main/resources/assets/remission/models/item/thing.json"


def solid(width, height, colour=(120, 120, 120, 255), marker=False):
    rows = [[colour] * width for _ in range(height)]
    if marker:
        size = png.marker_size(width)
        for y in range(size):
            for x in range(size):
                rows[y][x] = MAGENTA
    return png.encode(png.Image(width, height, rows))


def image(width, height, fn):
    return png.encode(png.Image(width, height, [[fn(x, y) for x in range(width)] for y in range(height)]))


LANG_TEXT = textwrap.dedent("""\
    {
      "block.remission.stone": "Festered Stone",
      "event.remission.surge.warning.title": "The Strain stirs",
      "event.remission.surge.warning.subtitle": "A surge is coming",
      "event.remission.surge.bar.warning": "Surge incoming",
      "event.remission.surge.bar.active": "Surge",
      "commands.remission.surge.start.success": "Surge from %s, %s, %s (intensity %s) in %s ticks"
    }
    """)

LEDGER_TEXT = textwrap.dedent(f"""\
    # Content provenance

    Intro prose that must survive edits.

    ## Textures

    | Path / ID | Type | Origin | Status | Notes |
    | --- | --- | --- | --- | --- |
    | `{TEX}block/stone.png` | texture | ai (test) | placeholder | Stone (P4.2): reads as stripes. |
    | `{HOST}stage_1.png` | texture | ai (test) | placeholder | Stage 1 (P2.2). |
    | `{HOST}stage_2.png` | texture | ai (test) | placeholder | Stage 2 (P2.2). |
    | `{HOST}stage_3.png` | texture | ai (test) | placeholder | Stage 3 (P2.2). |
    | `{HOST}veins_glow.png` | texture | ai (test) | placeholder | Glow (P2.2). |

    ## Models

    | Path / ID | Type | Origin | Status | Notes |
    | --- | --- | --- | --- | --- |
    | `{GEN_MODEL}` | model | procedural (datagen) | draft | cube_all (P4.2). |
    | `{HAND_MODEL}` | model | human (test) | draft | Hand model. |

    ## Sounds

    None yet.

    | Path / ID | Type | Origin | Status | Notes |
    | --- | --- | --- | --- | --- |

    ## Lang strings

    | Path / ID | Type | Origin | Status | Notes |
    | --- | --- | --- | --- | --- |
    | `{LANG}:block.remission.stone` | lang | ai (test) | draft | Block name (P4.2). |
    | `{LANG}:event.remission.surge.warning.*` | lang | ai (test) | draft | Surge warning (P4.3). |
    | `{LANG}:event.remission.surge.bar.*` | lang | ai (test) | draft | Boss bars (P4.3). |
    | `{LANG}:commands.remission.surge.*` | lang | ai (test) | draft | Surge commands (P4.3). |
    """)


def git(cwd, *args):
    return subprocess.run(["git", "-C", cwd, *args], check=True, capture_output=True, text=True).stdout


class TempRepo:
    """A repository with main pushed to a bare origin, plus stub check scripts that pass."""

    def __init__(self):
        self.tmp = tempfile.mkdtemp(prefix="content-editor-test-")
        self.origin = os.path.join(self.tmp, "origin.git")
        self.root = os.path.join(self.tmp, "repo")
        subprocess.run(["git", "init", "-q", "--bare", "-b", "main", self.origin], check=True)
        subprocess.run(["git", "init", "-q", "-b", "main", self.root], check=True)
        git(self.root, "config", "user.name", "Tester")
        git(self.root, "config", "user.email", "tester@example.invalid")
        git(self.root, "config", "commit.gpgsign", "false")
        files = {
            "docs/CONTENT-PROVENANCE.md": LEDGER_TEXT,
            LANG: LANG_TEXT,
            ".gitignore": ".local/\n",
            GEN_MODEL: '{\n  "parent": "minecraft:block/cube_all",\n  "textures": {\n    "all": "remission:block/stone"\n  }\n}',
            HAND_MODEL: '{\n  "parent": "minecraft:item/generated",\n  "textures": {\n    "layer0": "remission:block/stone"\n  }\n}\n',
            "tools/check-provenance.sh": "#!/bin/sh\necho provenance ok\n",
            "tools/check-docs.py": "print('check-docs: ok')\n",
        }
        for rel, text in files.items():
            self.write(rel, text.encode("utf-8"))
        os.chmod(os.path.join(self.root, "tools/check-provenance.sh"), 0o755)
        self.write(TEX + "block/stone.png", solid(16, 16, marker=True))
        for i, shade in ((1, 60), (2, 120), (3, 180)):
            self.write(f"{HOST}stage_{i}.png", solid(32, 32, (shade, 40, shade, 128), marker=True))
        self.write(HOST + "veins_glow.png", solid(32, 32, (140, 220, 60, 255), marker=True))
        git(self.root, "add", "-A")
        git(self.root, "commit", "-q", "-m", "fixture")
        git(self.root, "remote", "add", "origin", self.origin)
        git(self.root, "push", "-q", "origin", "main")
        git(self.root, "fetch", "-q", "origin")

    def write(self, rel, data):
        path = os.path.join(self.root, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb") as f:
            f.write(data)

    def cleanup(self):
        subprocess.run(["git", "-C", self.root, "worktree", "prune"], capture_output=True)
        shutil.rmtree(self.tmp, ignore_errors=True)

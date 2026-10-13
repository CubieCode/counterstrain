"""A scripted edit session on a copy of this repository: the real ledger, lang file and
textures, then the real batch checks (tools/check-provenance.sh, check-docs, git diff --check).

No network: the copy's origin is a bare repository made on disk. The copy is built from the
committed files (git archive) in a fresh repository, so it works from CI's shallow checkout too.
"""
import io
import os
import shutil
import subprocess
import tarfile
import tempfile
import unittest

import fixture
from contentedit import png
from contentedit.service import Editor

ROOT = os.path.dirname(fixture.TOOLS)
LANG = "mod/src/main/resources/assets/remission/lang/en_us.json"
TEX = "mod/src/main/resources/assets/remission/textures/"


def git(cwd, *args):
    return subprocess.run(["git", "-C", cwd, *args], check=True, capture_output=True, text=True).stdout


@unittest.skipUnless(os.path.isdir(os.path.join(ROOT, ".git")) or os.path.isfile(os.path.join(ROOT, ".git")),
                     "needs a git checkout of the repository")
class SessionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp(prefix="content-editor-session-")
        cls.clone = os.path.join(cls.tmp, "repo")
        origin = os.path.join(cls.tmp, "origin.git")
        archive = subprocess.run(["git", "-C", ROOT, "archive", "--format=tar", "HEAD"], check=True,
                                 capture_output=True).stdout
        with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
            tar.extractall(cls.clone, filter="data")
        subprocess.run(["git", "init", "-q", "-b", "main", cls.clone], check=True)
        # Uncommitted work in the checkout under test (this editor's own files) goes into the clone too.
        status = git(ROOT, "status", "--porcelain", "--untracked-files=all")
        for line in status.splitlines():
            rel = line[3:].split(" -> ")[-1]
            src, dst = os.path.join(ROOT, rel), os.path.join(cls.clone, rel)
            if os.path.isfile(src):
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                shutil.copy2(src, dst)
            elif os.path.exists(dst):
                os.unlink(dst)
        git(cls.clone, "config", "user.name", "Session")
        git(cls.clone, "config", "user.email", "session@example.invalid")
        git(cls.clone, "config", "commit.gpgsign", "false")
        git(cls.clone, "add", "-A")
        git(cls.clone, "commit", "-q", "--allow-empty", "-m", "session base")
        subprocess.run(["git", "init", "-q", "--bare", "-b", "main", origin], check=True)
        git(cls.clone, "remote", "add", "origin", origin)
        git(cls.clone, "push", "-q", "origin", "main")
        git(cls.clone, "fetch", "-q", "origin")

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def test_edit_session_passes_the_batch_checks(self):
        editor = Editor(self.clone, clock=lambda: fixture.TODAY)
        editor.workspace.start("session", today=fixture.TODAY)
        ledger = editor.ledger()

        # Replace one placeholder texture with a draft (no marker, same size).
        stone = TEX + "block/festered_stone.png"
        with open(os.path.join(editor.root, stone), "rb") as f:
            image = png.decode(f.read())
        rows = [[(110 + (x * 3 + y * 5) % 20, 104, 112, 255) for x in range(image.width)] for y in range(image.height)]
        staged = editor.stage_upload(stone, png.encode(png.Image(image.width, image.height, rows)), status="draft")
        self.assertTrue(staged["ok"], staged["checks"])
        editor.write_upload(staged["id"], "draft", note="Replaced in a scripted session.")

        # Write two key placeholders: one as a draft, one as final (en_us.json values start as their keys).
        editor.lang_save(LANG, "event.remission.surge.bar.warning", "The Strain gathers")
        editor.lang_save(LANG, "event.remission.surge.warning.title", "The Strain stirs", status="final")
        ledger = editor.ledger()
        self.assertIsNotNone(ledger.find(f"{LANG}:event.remission.surge.bar.warning"))
        self.assertEqual(ledger.find(f"{LANG}:event.remission.surge.warning.title").status, "final")

        results, tree = editor.workspace.run_checks()
        self.assertTrue(all(r["ok"] for r in results), "\n".join(f"{r['cmd']}: {r['output']}" for r in results if not r["ok"]))
        editor.workspace.commit("Content: Scripted session", tree)
        self.assertEqual(len(editor.batch()["changes"]), 3)
        self.assertIn("`" + stone + "`: placeholder → draft", editor.pr_body())


if __name__ == "__main__":
    unittest.main()

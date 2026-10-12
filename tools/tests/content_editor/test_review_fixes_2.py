"""Regression tests for the second PR #60 code review."""
import os
import struct
import subprocess
import unittest
import zlib

import fixture
from contentedit import lang, models, png, props, textures
from contentedit.ledger import Ledger, LedgerError
from contentedit.service import Editor, EditorError

LANG = fixture.LANG


class StandaloneTest(unittest.TestCase):
    def test_row_types_are_checked(self):
        ledger = Ledger(fixture.LEDGER_TEXT)
        with self.assertRaises(LedgerError):
            ledger.add_row("Textures", "mod/src/main/resources/assets/remission/textures/block/x.png",
                           "texture | x\n| `pack/evil.png` | texture | human | final", "human (t)", "draft", "n")
        with self.assertRaises(LedgerError):
            ledger.add_row("Textures", "mod/src/main/resources/assets/remission/textures/block/x.png",
                           "picture", "human (t)", "draft", "n")
        self.assertEqual(ledger.text(), fixture.LEDGER_TEXT)

    def test_models_with_odd_textures_fail_checks_instead_of_crashing(self):
        class Resolver:
            def exists(self, *a):
                return True
        for body in (b'{"textures": ["a"]}', b'{"textures": "a"}', b'{"elements": {}}'):
            checks, _ = models.validate(body, Resolver())
            self.assertIn("fail", [c["state"] for c in checks], body)
        self.assertEqual(models.texture_refs({"textures": ["a"]}), [])

    def test_huge_dimensions_are_refused_before_decoding(self):
        def chunk(kind, data):
            return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
        bomb = (png.SIGNATURE + chunk(b"IHDR", struct.pack(">IIBBBBB", 16384, 16384, 8, 6, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(b"\0" * 1000)) + chunk(b"IEND", b""))
        checks, _ = textures.validate(bomb, "draft")
        self.assertEqual(checks[0]["state"], "fail")
        self.assertIn("too large", checks[0]["detail"])

    def test_byte_exact_literals(self):
        f = lang.LangFile('{\n  "a": "\\u00a7cRed",\n  "b": "§x"\n}\n')
        self.assertFalse(f.ascii)
        edited = f.with_value("a", "Blue")
        self.assertEqual(edited.with_literal("a", f.literals["a"]).text, f.text)
        p = props.PropsFile("k=caf\\u00e9\n")
        self.assertEqual(p.with_value("k", "tea").with_raw("k", p.raw["k"]).text, p.text)


class EditorFixesTest(unittest.TestCase):
    def setUp(self):
        self.repo = fixture.TempRepo()
        self.editor = Editor(self.repo.root, clock=lambda: fixture.TODAY)
        self.editor.workspace.start("fixes2", today=fixture.TODAY)

    def tearDown(self):
        self.repo.cleanup()

    def test_reads_work_while_the_index_is_locked(self):
        ws = self.editor.workspace
        lock = os.path.join(ws.worktree, fixture.git(ws.worktree, "rev-parse", "--git-path", "index.lock").strip())
        self.editor.lang_save(LANG, "block.remission.stone", "Rotten Stone")
        open(lock, "w").close()  # as if a commit were running
        try:
            self.assertTrue(ws.uncommitted())
            self.assertEqual(len(self.editor.batch()["changes"]), 1)
            self.assertTrue(any(r["pending"] for r in self.editor.rows()))
        finally:
            os.unlink(lock)

    def test_split_rows_copy_the_shared_rows_type(self):
        path = self.editor.path("docs/CONTENT-PROVENANCE.md")
        with open(path, encoding="utf-8") as f:
            text = f.read().replace(f"`{LANG}:event.remission.surge.bar.*` | lang |", f"`{LANG}:event.remission.surge.bar.*` | metadata |")
        with open(path, "w", encoding="utf-8") as f:
            f.write(text)
        self.editor.lang_save(LANG, "event.remission.surge.bar.warning", "The Strain gathers")
        self.assertEqual(self.editor.ledger().find(f"{LANG}:event.remission.surge.bar.warning").type, "metadata")

    def test_a_same_day_re_edit_still_shows_in_the_batch(self):
        self.editor.lang_save(LANG, "block.remission.stone", "Rotten Stone")
        _, tree = self.editor.workspace.run_checks()
        self.editor.workspace.commit("Content: first edit", tree)
        # Make that commit the base, as if it had merged to main the same day.
        fixture.git(self.editor.root, "push", "-q", "origin", "HEAD:main")
        fixture.git(self.editor.root, "fetch", "-q", "origin")
        self.assertEqual(self.editor.batch()["changes"], [])
        self.editor.lang_save(LANG, "block.remission.stone", "Rotten Stone, again")
        changes = self.editor.batch()["changes"]
        self.assertEqual(len(changes), 1)
        self.assertTrue(changes[0]["contentOnly"])
        self.assertEqual(changes[0]["strings"][0]["after"], "Rotten Stone, again")
        self.assertTrue(next(r for r in self.editor.rows() if r["id"] == f"{LANG}:block.remission.stone")["pending"])
        self.editor.undo(f"{LANG}:block.remission.stone")
        self.assertEqual(self.editor.batch()["changes"], [])

    def test_undo_restores_lang_and_properties_bytes(self):
        # The fixture's lang file is ASCII: give main a \u escape and a raw non-ASCII character first.
        path = os.path.join(self.editor.root, LANG)
        with open(path, encoding="utf-8") as f:
            text = f.read().replace('"Festered Stone"', '"Festered \\u00a7Stone"').replace('"Surge"', '"Surge §"')
        with open(path, "w", encoding="utf-8") as f:
            f.write(text)
        _, tree = self.editor.workspace.run_checks()
        self.editor.workspace.commit("main: escapes", tree)
        fixture.git(self.editor.root, "push", "-q", "origin", "HEAD:main")
        fixture.git(self.editor.root, "fetch", "-q", "origin")
        self.editor.lang_save(LANG, "block.remission.stone", "Plain")
        self.editor.properties_save("mod/gradle.properties:mod_description", "Hunt it — now")
        self.editor.undo(f"{LANG}:block.remission.stone")
        self.editor.undo("mod/gradle.properties:mod_description")
        self.assertEqual(fixture.git(self.editor.root, "status", "--porcelain").strip(), "")

    def test_properties_base_is_read_as_latin_1(self):
        raw = b"# Mod metadata\nmod_id=remission\nmod_description=Caf\xe9 of the Strain\nmod_version=0.1.0\n"
        with open(os.path.join(self.editor.root, "mod/gradle.properties"), "wb") as f:
            f.write(raw)
        _, tree = self.editor.workspace.run_checks()
        self.editor.workspace.commit("main: latin-1", tree)
        fixture.git(self.editor.root, "push", "-q", "origin", "HEAD:main")
        fixture.git(self.editor.root, "fetch", "-q", "origin")
        detail = self.editor.row_detail("mod/gradle.properties:mod_description")
        self.assertEqual((detail["value"], detail["base"]), ("Café of the Strain", "Café of the Strain"))
        self.assertEqual(self.editor.batch()["changes"], [])

    def test_undo_after_a_pack_refresh_refreshes_the_index_again(self):
        ws = self.editor.workspace
        pack_lang = "pack/kubejs/assets/counterstrain/lang/en_us.json"
        self.editor.lang_save(pack_lang, "quest.counterstrain.crash", "The crash site")
        ws.run_checks()  # refreshes pack/index.toml for the edit
        self.editor.undo(f"{pack_lang}:quest.counterstrain.crash")
        results, _ = ws.run_checks()
        self.assertIn("tools/pack.sh refresh", [r["cmd"] for r in results])
        self.assertTrue(all(r["ok"] for r in results), results)
        self.assertEqual(fixture.git(ws.worktree, "status", "--porcelain").strip(), "")  # index back to main's

    def test_mods_toml_literal_values_refuse_triple_quotes(self):
        template = "mod/src/main/templates/META-INF/neoforge.mods.toml"
        os.makedirs(os.path.dirname(self.editor.path(template)), exist_ok=True)
        with open(self.editor.path(template), "w", encoding="utf-8") as f:
            f.write("description='''${mod_description}'''\n")
        for bad in ("Cure the Strain '''for good'''", "Cure the Strain, it said '"):
            with self.assertRaises(EditorError):
                self.editor.properties_save("mod/gradle.properties:mod_description", bad)
        self.editor.properties_save("mod/gradle.properties:mod_description", "Cure the Strain, nidus by nidus")

    def test_removed_rows_are_read_only(self):
        path = self.editor.path("docs/CONTENT-PROVENANCE.md")
        with open(path, encoding="utf-8") as f:
            text = f.read().replace("| `docs/LORE.md` (premise, arc) | lore | ai (test) | draft |",
                                    "| `docs/LORE.md` (premise, arc) | lore | ai (test) | removed |")
        with open(path, "w", encoding="utf-8") as f:
            f.write(text)
        for call in (lambda: self.editor.accept_row("docs/LORE.md"),
                     lambda: self.editor.markdown_save("docs/LORE.md", "# New\n")):
            with self.assertRaises(EditorError):
                call()

    def test_add_row_refuses_unknown_types(self):
        with self.assertRaises(EditorError):
            self.editor.add_row("mod/src/main/resources/assets/remission/textures/block/y.png", "texture | evil", "Textures", "n")


if __name__ == "__main__":
    unittest.main()

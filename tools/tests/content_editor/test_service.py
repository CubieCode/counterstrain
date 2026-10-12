"""Editor operations against a temporary repository: guards, ledger updates, uploads, batch and undo."""
import os
import unittest

import fixture
from contentedit import png
from contentedit.repo import RepoError
from contentedit.service import Editor, EditorError

LANG, TEX, HOST = fixture.LANG, fixture.TEX, fixture.HOST
STONE = TEX + "block/stone.png"


class ServiceTest(unittest.TestCase):
    def setUp(self):
        self.repo = fixture.TempRepo()
        self.editor = Editor(self.repo.root, clock=lambda: fixture.TODAY)

    def tearDown(self):
        self.repo.cleanup()

    def start(self):
        return self.editor.workspace.start("surge-text", today=fixture.TODAY)

    def read(self, rel):
        with open(os.path.join(self.editor.root, rel), encoding="utf-8") as f:
            return f.read()

    def row(self, path_id):
        return self.editor.ledger().find(path_id)

    # --- guards --------------------------------------------------------------

    def test_nothing_is_written_before_a_batch_starts(self):
        with self.assertRaises(RepoError):
            self.editor.lang_save(LANG, "block.remission.stone", "Rotten Stone")
        self.assertIn('"Festered Stone"', self.read(LANG))

    def test_batch_is_a_content_worktree_from_origin_main(self):
        branch = self.start()
        self.assertEqual(branch, "content/2026-10-12-surge-text")
        self.assertNotEqual(self.editor.root, self.repo.root)
        self.assertEqual(self.editor.workspace.branch(), branch)
        self.assertEqual(fixture.git(self.repo.root, "rev-parse", "--abbrev-ref", "HEAD").strip(), "main")
        with self.assertRaises(RepoError):
            self.editor.workspace.start("Bad Name!")
        # Starting the same batch again (a double submit) resumes it instead of failing.
        self.assertEqual(self.editor.workspace.start("surge-text", today=fixture.TODAY), branch)
        self.assertEqual(self.editor.workspace.branch(), branch)

    def test_generated_and_unledgered_paths_are_refused(self):
        self.start()
        with self.assertRaises(EditorError) as ctx:
            self.editor.stage_upload(fixture.GEN_MODEL, b"{}")
        self.assertIn("datagen", str(ctx.exception))
        ledger = self.editor.ledger()
        for bad in ("mod/src/main/resources/assets/remission/textures/block/other.png", "../outside.png",
                    "/etc/passwd", "mod/src/generated/resources/assets/remission/models/block/stone.json"):
            with self.assertRaises(EditorError):
                self.editor._target(bad, ledger)
        with self.assertRaises(EditorError):
            self.editor.lang_save(LANG, "no.such.key", "x")

    # --- lang ----------------------------------------------------------------

    def test_replacing_a_string_with_its_own_row(self):
        self.start()
        self.editor.lang_save(LANG, "block.remission.stone", "Rotten Stone")
        self.assertIn('  "block.remission.stone": "Rotten Stone",\n', self.read(LANG))
        row = self.row(f"{LANG}:block.remission.stone")
        self.assertEqual((row.origin, row.status), ("human (Tester, 2026-10-12)", "draft"))

    def test_replacing_one_key_of_a_shared_row_adds_an_exact_row(self):
        self.start()
        self.editor.lang_save(LANG, "event.remission.surge.bar.warning", "The Strain gathers")
        shared = self.row(f"{LANG}:event.remission.surge.bar.*")
        exact = self.row(f"{LANG}:event.remission.surge.bar.warning")
        self.assertEqual((shared.origin, shared.status), ("ai (test)", "draft"))
        self.assertEqual((exact.origin, exact.status, exact.notes), ("human (Tester, 2026-10-12)", "draft", shared.notes))
        self.assertEqual(exact.index, shared.index + 1)
        # The shared row now governs only the other key, so editing it updates the shared row itself.
        self.editor.lang_save(LANG, "event.remission.surge.bar.active", "Surge!")
        shared = self.row(f"{LANG}:event.remission.surge.bar.*")
        self.assertEqual(shared.origin, "human (Tester, 2026-10-12)")
        self.assertIsNone(self.row(f"{LANG}:event.remission.surge.bar.active"))

    def test_argument_changes_are_refused_and_nothing_is_written(self):
        self.start()
        before_lang, before_ledger = self.read(LANG), self.read("docs/CONTENT-PROVENANCE.md")
        with self.assertRaises(EditorError):
            self.editor.lang_save(LANG, "commands.remission.surge.start.success", "From %s, %s, %s in %s ticks")
        self.assertEqual((self.read(LANG), self.read("docs/CONTENT-PROVENANCE.md")), (before_lang, before_ledger))

    def test_accepting_one_key_and_a_whole_row(self):
        self.start()
        self.editor.lang_accept(LANG, key="event.remission.surge.warning.title")
        exact = self.row(f"{LANG}:event.remission.surge.warning.title")
        self.assertEqual((exact.status, exact.origin), ("final", "ai (test)"))
        self.assertTrue(exact.notes.endswith("Accepted as final by Tester, 2026-10-12."))
        self.editor.lang_accept(LANG, row_id=f"{LANG}:commands.remission.surge.*")
        whole = self.row(f"{LANG}:commands.remission.surge.*")
        self.assertEqual(whole.status, "final")
        # Accepting again is refused and never repeats the note.
        with self.assertRaises(EditorError):
            self.editor.lang_accept(LANG, key="event.remission.surge.warning.title")
        with self.assertRaises(EditorError):
            self.editor.lang_accept(LANG, row_id=f"{LANG}:commands.remission.surge.*")
        self.assertEqual(self.row(f"{LANG}:commands.remission.surge.*").notes.count("Accepted as final"), 1)
        self.assertIn("Surge from %s", self.read(LANG))  # text untouched

    # --- uploads -------------------------------------------------------------

    def test_texture_upload_is_checked_then_written_with_its_row(self):
        self.start()
        staged = self.editor.stage_upload(STONE, fixture.solid(16, 16, (90, 60, 120, 255)), status="draft")
        self.assertTrue(staged["ok"], staged["checks"])
        self.editor.write_upload(staged["id"], "draft", note="Replaced: irregular cracks.")
        row = self.row(STONE)
        self.assertEqual((row.origin, row.status), ("human (Tester, 2026-10-12)", "draft"))
        self.assertTrue(row.notes.endswith("Replaced: irregular cracks."))
        with open(os.path.join(self.editor.root, STONE), "rb") as f:
            self.assertEqual(png.decode(f.read()).pixel(0, 0), (90, 60, 120, 255))

    def test_writing_with_a_status_the_marker_contradicts_is_refused(self):
        self.start()
        staged = self.editor.stage_upload(STONE, fixture.solid(16, 16), status="placeholder")
        self.assertFalse(staged["ok"])
        with self.assertRaises(EditorError):
            self.editor.write_upload(staged["id"], "placeholder")
        with self.assertRaises(EditorError):
            self.editor.write_upload(staged["id"], "final", origin={"kind": "licensed", "source": "x"})
        self.assertEqual(self.row(STONE).status, "placeholder")

    def test_licensed_origin(self):
        self.start()
        staged = self.editor.stage_upload(STONE, fixture.solid(16, 16), status="final")
        self.editor.write_upload(staged["id"], "final", origin={"kind": "licensed", "source": "Kenney", "license": "CC0"})
        self.assertEqual(self.row(STONE).origin, "licensed (Kenney, CC0)")

    def test_model_upload_must_parse_and_name_existing_textures(self):
        self.start()
        bad = self.editor.stage_upload(fixture.HAND_MODEL, b'{"textures": {"layer0": "remission:item/nope"}}')
        self.assertFalse(bad["ok"])
        good = self.editor.stage_upload(fixture.HAND_MODEL, b'{"textures": {"layer0": "remission:block/stone"}}')
        self.assertTrue(good["ok"], good["checks"])
        self.assertFalse(self.editor.stage_upload(fixture.HAND_MODEL, b"{not json")["ok"])

    # --- previews, batch and undo ----------------------------------------------

    def test_host_preview_uses_the_staged_upload(self):
        self.start()
        on_disk = png.decode(self.editor.host_png(2))
        staged = self.editor.stage_upload(HOST + "stage_2.png", fixture.solid(32, 32, (0, 0, 255, 255)), status="draft")
        preview = png.decode(self.editor.host_png(2, upload_id=staged["id"]))
        head_front = (9, 9)
        self.assertNotEqual(on_disk.pixel(*head_front), preview.pixel(*head_front))
        self.assertEqual(preview.pixel(*head_front)[:3], (0, 0, 255))
        self.assertEqual(preview.pixel(0, 0)[3], 0)  # transparent mannequin texels stay empty

    def test_batch_lists_changed_rows_and_undo_restores_them(self):
        self.start()
        self.editor.lang_save(LANG, "event.remission.surge.bar.warning", "The Strain gathers")
        staged = self.editor.stage_upload(STONE, fixture.solid(16, 16), status="draft")
        self.editor.write_upload(staged["id"], "draft")
        summary = self.editor.batch()
        changed = {c["row"]["path"]: c for c in summary["changes"]}
        self.assertEqual(set(changed), {STONE, f"{LANG}:event.remission.surge.bar.warning"})
        self.assertTrue(changed[f"{LANG}:event.remission.surge.bar.warning"]["new"])
        self.assertEqual(changed[f"{LANG}:event.remission.surge.bar.warning"]["strings"],
                         [{"key": "event.remission.surge.bar.warning", "before": "Surge incoming",
                           "after": "The Strain gathers"}])
        self.assertIn(f"`{STONE}`: placeholder → draft", self.editor.pr_body())
        self.editor.undo(f"{LANG}:event.remission.surge.bar.warning")
        self.editor.undo(STONE)
        self.assertEqual(self.editor.batch()["changes"], [])
        self.assertEqual(self.read("docs/CONTENT-PROVENANCE.md"), fixture.LEDGER_TEXT)
        self.assertEqual(self.read(LANG), fixture.LANG_TEXT)

    def test_checks_commit_and_refuse_a_stale_tree(self):
        self.start()
        self.editor.lang_save(LANG, "block.remission.stone", "Rotten Stone")
        results, tree = self.editor.workspace.run_checks()
        self.assertTrue(all(r["ok"] for r in results), results)
        self.editor.lang_save(LANG, "block.remission.stone", "Rotten Stone, again")
        with self.assertRaises(RepoError):
            self.editor.workspace.commit("Content: Rename festered stone", tree)
        _, tree = self.editor.workspace.run_checks()
        self.editor.workspace.commit("Content: Rename festered stone", tree)
        self.assertEqual(fixture.git(self.editor.root, "log", "-1", "--format=%s").strip(), "Content: Rename festered stone")
        summary = self.editor.batch()
        self.assertEqual(len(summary["changes"]), 1)  # still differs from main after the commit
        self.assertFalse(summary["uncommitted"])
        self.assertEqual([c["subject"] for c in summary["commits"]], ["Content: Rename festered stone"])
        self.editor.lang_save(LANG, "block.remission.stone", "Rotten Stone, third")
        self.assertTrue(self.editor.batch()["uncommitted"])


if __name__ == "__main__":
    unittest.main()

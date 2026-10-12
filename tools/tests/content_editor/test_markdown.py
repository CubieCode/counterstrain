"""Markdown text rows: normalising saves, link warnings, the batch diff, and the editor operations."""
import os
import unittest

import fixture
from contentedit import markdown
from contentedit.service import Editor, EditorError

LORE = "docs/LORE.md"


class MarkdownModuleTest(unittest.TestCase):
    def test_normalize(self):
        self.assertEqual(markdown.normalize("# T  \r\n\r\nText\t\n\n\n"), b"# T\n\nText\n")
        for bad in ("a\x00b", "  \n\n", 5):
            with self.assertRaises(ValueError):
                markdown.normalize(bad)
        with self.assertRaises(ValueError):
            markdown.normalize("x" * (markdown.MAX_BYTES + 1))

    def test_link_warnings(self):
        repo = fixture.TempRepo()
        try:
            text = ("[ok](CONTENT-PROVENANCE.md) [anchor](DESIGN.md#glossary) [web](https://example.com) [here](#arc)\n"
                    "![img](missing.png) [up](../outside.md)\n")
            warnings = markdown.link_warnings(text, LORE, repo.root)
            self.assertEqual(len(warnings), 3, warnings)  # DESIGN.md is missing in the fixture, plus the image and ../
            self.assertTrue(all(w.startswith(("line 1", "line 2")) for w in warnings))
        finally:
            repo.cleanup()

    def test_diff(self):
        lines = markdown.diff("a\nb\n", "a\nc\n", LORE)
        self.assertIn("-b", lines)
        self.assertIn("+c", lines)


class MarkdownEditorTest(unittest.TestCase):
    def setUp(self):
        self.repo = fixture.TempRepo()
        self.editor = Editor(self.repo.root, clock=lambda: fixture.TODAY)

    def tearDown(self):
        self.repo.cleanup()

    def test_save_writes_the_file_and_its_row(self):
        with self.assertRaises(Exception):
            self.editor.markdown_save(LORE, "# New")  # read-only before a batch
        self.editor.workspace.start("lore", today=fixture.TODAY)
        detail = self.editor.row_detail(LORE)
        self.assertEqual((detail["kind"], detail["text"]), ("markdown", fixture.LORE_TEXT))
        row, warnings = self.editor.markdown_save(LORE, "# Lore outline\r\n\r\nRewritten.   \n[x](nope.md)\n", "draft")
        with open(os.path.join(self.editor.root, LORE), encoding="utf-8") as f:
            self.assertEqual(f.read(), "# Lore outline\n\nRewritten.\n[x](nope.md)\n")
        self.assertEqual((row.origin, row.status), ("human (Tester, 2026-10-12)", "draft"))
        self.assertEqual(len(warnings), 1)
        with self.assertRaises(EditorError):
            self.editor.markdown_save(LORE, "# Lore outline\n\nRewritten.\n[x](nope.md)\n")  # unchanged
        change = self.editor.batch()["changes"][0]
        self.assertEqual(change["kind"], "markdown")
        self.assertIn("+Rewritten.", change["diff"])
        self.editor.undo(LORE)
        with open(os.path.join(self.editor.root, LORE), encoding="utf-8") as f:
            self.assertEqual(f.read(), fixture.LORE_TEXT)
        self.assertEqual(self.editor.batch()["changes"], [])

    def test_accept_as_final_once(self):
        self.editor.workspace.start("lore", today=fixture.TODAY)
        row = self.editor.accept_row(LORE)
        self.assertEqual(row.status, "final")
        self.assertTrue(row.notes.endswith("Accepted as final by Tester, 2026-10-12."))
        with self.assertRaises(EditorError):
            self.editor.accept_row(LORE)
        with self.assertRaises(EditorError):
            self.editor.accept_row(fixture.TEX + "block/stone.png")


if __name__ == "__main__":
    unittest.main()

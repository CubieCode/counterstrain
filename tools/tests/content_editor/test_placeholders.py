"""Lang key placeholders: a value equal to its key (plus ` %1$s …` for arguments) is not written yet."""
import os
import unittest

import fixture
from contentedit import lang
from contentedit.service import Editor, EditorError

LANG = fixture.LANG
TITLE = "event.remission.surge.warning.title"
START = "commands.remission.surge.start.success"


class PlaceholderTest(unittest.TestCase):
    def test_detection(self):
        self.assertEqual(lang.placeholder("a.b", {1: "s", 2: "s"}), "a.b %1$s %2$s")
        self.assertTrue(lang.is_placeholder("a.b", "a.b"))
        self.assertTrue(lang.is_placeholder("a.b", "a.b %1$s %2$s"))
        self.assertFalse(lang.is_placeholder("a.b", "A b"))
        self.assertTrue(lang.is_placeholder("a.b", "a.b %s %s"))
        self.assertFalse(lang.is_placeholder("a.b", "a.b of %s"))
        self.assertEqual(lang.needs("a.b %1$s %2$s"), "%1$s, %2$s")
        self.assertEqual(lang.needs("a.b"), "")


class PlaceholderServiceTest(unittest.TestCase):
    def setUp(self):
        self.repo = fixture.TempRepo()
        self.editor = Editor(self.repo.root, clock=lambda: fixture.TODAY)
        self.editor.workspace.start("lang-keys", today=fixture.TODAY)
        path = os.path.join(self.editor.root, LANG)
        with open(path, encoding="utf-8") as f:
            text = f.read()
        text = text.replace('"The Strain stirs"', f'"{TITLE}"')
        text = text.replace('"Surge from %s, %s, %s (intensity %s) in %s ticks"', f'"{START} %1$s %2$s %3$s %4$s %5$s"')
        with open(path, "w", encoding="utf-8") as f:
            f.write(text)

    def tearDown(self):
        self.repo.cleanup()

    def detail_key(self, row_id, key):
        return next(k for k in self.editor.row_detail(row_id)["keys"] if k["key"] == key)

    def test_keys_report_placeholder_description_and_arguments(self):
        title = self.detail_key(f"{LANG}:event.remission.surge.warning.*", TITLE)
        self.assertTrue(title["placeholder"])
        self.assertEqual(title["description"], "Surge warning (P4.3).")
        self.assertEqual(title["needs"], "")
        start = self.detail_key(f"{LANG}:commands.remission.surge.*", START)
        self.assertTrue(start["placeholder"])
        self.assertEqual(start["needs"], "%1$s, %2$s, %3$s, %4$s, %5$s")
        written = self.detail_key(f"{LANG}:event.remission.surge.warning.*", "event.remission.surge.warning.subtitle")
        self.assertFalse(written["placeholder"])
        rows = {r["id"]: r for r in self.editor.rows()}
        self.assertEqual(rows[f"{LANG}:event.remission.surge.warning.*"]["placeholders"], 1)
        self.assertEqual(rows[f"{LANG}:block.remission.stone"]["placeholders"], 0)

    def test_accepting_a_placeholder_is_refused(self):
        with self.assertRaises(EditorError) as ctx:
            self.editor.lang_accept(LANG, key=TITLE)
        self.assertIn("not written yet", str(ctx.exception))
        for row_id in (f"{LANG}:event.remission.surge.warning.*", f"{LANG}:commands.remission.surge.*"):
            with self.assertRaises(EditorError):
                self.editor.lang_accept(LANG, row_id=row_id)
            with self.assertRaises(EditorError):
                self.editor.accept_row(row_id)
        self.assertEqual(self.editor.ledger().find(f"{LANG}:event.remission.surge.warning.*").status, "draft")

    def test_saving_the_placeholder_itself_is_refused(self):
        with self.assertRaises(EditorError) as ctx:
            self.editor.lang_save(LANG, START, f"{START} %s %s %s %s %s")
        self.assertIn("placeholder", str(ctx.exception))

    def test_wrong_arguments_name_what_the_string_needs(self):
        with self.assertRaises(EditorError) as ctx:
            self.editor.lang_save(LANG, START, "A surge from %s")
        message = str(ctx.exception)
        self.assertIn("needs %1$s, %2$s, %3$s, %4$s, %5$s", message)
        self.assertIn("Surge commands (P4.3).", message)

    def test_writing_a_placeholder_records_it_as_human(self):
        self.editor.lang_save(LANG, TITLE, "The Strain wakes", status="final")
        row = self.editor.ledger().find(f"{LANG}:{TITLE}")
        self.assertEqual((row.origin, row.status), ("human (Tester, 2026-10-12)", "final"))
        self.assertFalse(self.detail_key(f"{LANG}:{TITLE}", TITLE)["placeholder"])

    def test_review_refuses_a_placeholder(self):
        self.editor.lang_save(LANG, TITLE, "The Strain wakes")
        with self.assertRaises(EditorError):
            self.editor.review(f"{LANG}:{TITLE}", TITLE)


if __name__ == "__main__":
    unittest.main()

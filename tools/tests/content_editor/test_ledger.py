import unittest

import fixture
from contentedit.ledger import Ledger, LedgerError, append_note

LANG = fixture.LANG


class LedgerTest(unittest.TestCase):
    def setUp(self):
        self.ledger = Ledger(fixture.LEDGER_TEXT)

    def test_parses_rows_with_sections_and_items(self):
        row = self.ledger.find(fixture.TEX + "block/stone.png")
        self.assertEqual((row.type, row.status, row.section, row.items), ("texture", "placeholder", "Textures", ["P4.2"]))
        bars = self.ledger.find(f"{LANG}:event.remission.surge.bar.*")
        self.assertTrue(bars.wildcard)
        self.assertEqual(bars.file, LANG)

    def test_unchanged_text_round_trips(self):
        self.assertEqual(self.ledger.text(), fixture.LEDGER_TEXT)

    def test_set_fields_changes_only_that_line(self):
        row = self.ledger.find(fixture.TEX + "block/stone.png")
        self.ledger.set_fields(row, origin="human (Tester, 2026-10-12)", status="draft")
        before, after = fixture.LEDGER_TEXT.split("\n"), self.ledger.text().split("\n")
        diff = [i for i, (a, b) in enumerate(zip(before, after)) if a != b]
        self.assertEqual(diff, [row.index])
        self.assertIn("| human (Tester, 2026-10-12) | draft | Stone (P4.2)", after[row.index])

    def test_exact_row_overrides_wildcard(self):
        bars = self.ledger.find(f"{LANG}:event.remission.surge.bar.*")
        self.assertIs(self.ledger.owner(LANG, "event.remission.surge.bar.warning"), bars)
        exact = self.ledger.insert_after(bars, f"{LANG}:event.remission.surge.bar.warning", "lang",
                                         "human (Tester, 2026-10-12)", "draft", bars.notes)
        self.assertEqual(exact.index, bars.index + 1)
        self.assertEqual(self.ledger.owner(LANG, "event.remission.surge.bar.warning").path_id, exact.path_id)
        self.assertEqual(self.ledger.owner(LANG, "event.remission.surge.bar.active").path_id, bars.path_id)
        self.assertEqual([r.path_id for r in self.ledger.overrides(self.ledger.find(bars.path_id))], [exact.path_id])

    def test_second_exact_row_goes_below_the_first(self):
        bars = self.ledger.find(f"{LANG}:event.remission.surge.bar.*")
        first = self.ledger.insert_after(bars, f"{LANG}:event.remission.surge.bar.warning", "lang", "o", "draft", "n")
        bars = self.ledger.find(bars.path_id)
        second = self.ledger.insert_after(bars, f"{LANG}:event.remission.surge.bar.active", "lang", "o", "draft", "n")
        self.assertEqual(second.index, first.index + 1)

    def test_duplicates_bad_cells_and_statuses_are_refused(self):
        bars = self.ledger.find(f"{LANG}:event.remission.surge.bar.*")
        with self.assertRaises(LedgerError):
            self.ledger.insert_after(bars, bars.path_id, "lang", "o", "draft", "n")
        with self.assertRaises(LedgerError):
            self.ledger.set_fields(bars, notes="a | b")
        with self.assertRaises(LedgerError):
            self.ledger.set_fields(bars, status="approved")

    def test_add_row_appends_to_an_empty_section_table(self):
        row = self.ledger.add_row("Sounds", "mod/src/main/resources/assets/remission/sounds/drip.ogg", "sound",
                                  "human (Tester)", "draft", "Drip.")
        self.assertEqual(row.section, "Sounds")
        self.assertTrue(self.ledger.lines[row.index - 1].startswith("| ---"))

    def test_append_note(self):
        self.assertEqual(append_note("Block name", "Accepted as final by T, 2026-10-12."),
                         "Block name. Accepted as final by T, 2026-10-12.")


if __name__ == "__main__":
    unittest.main()

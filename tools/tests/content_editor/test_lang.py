import unittest

import fixture  # noqa: F401  (puts the editor library on sys.path)
from contentedit import lang


class FormatArgsTest(unittest.TestCase):
    def test_sequential_and_positional(self):
        self.assertEqual(lang.format_args("%s and %s"), {1: "s", 2: "s"})
        self.assertEqual(lang.format_args("%2$s before %1$s"), {1: "s", 2: "s"})
        self.assertEqual(lang.format_args("100%% done"), {})

    def test_same_set_reordered_is_fine(self):
        self.assertIsNone(lang.args_problem("%s at %s", "%2$s holds %1$s"))

    def test_count_change_is_refused(self):
        problem = lang.args_problem("a %s %s %s %s %s", "a %s %s %s %s")
        self.assertIn("5 in the current string, 4 in the replacement", problem)


class LangFileTest(unittest.TestCase):
    def setUp(self):
        self.file = lang.LangFile(fixture.LANG_TEXT)

    def test_edit_keeps_order_and_layout(self):
        edited = self.file.with_value("event.remission.surge.bar.warning", "The Strain gathers")
        self.assertEqual(edited.keys(), self.file.keys())
        old_lines, new_lines = fixture.LANG_TEXT.split("\n"), edited.text.split("\n")
        changed = [i for i, (a, b) in enumerate(zip(old_lines, new_lines)) if a != b]
        self.assertEqual(len(changed), 1)
        self.assertEqual(new_lines[changed[0]], '  "event.remission.surge.bar.warning": "The Strain gathers",')
        self.assertTrue(edited.text.endswith("}\n"))

    def test_last_key_keeps_no_trailing_comma(self):
        edited = self.file.with_value("commands.remission.surge.start.success",
                                      "From %s, %s, %s at %s, in %s ticks")
        self.assertIn('"From %s, %s, %s at %s, in %s ticks"\n}', edited.text)

    def test_quotes_and_unicode_are_escaped_as_json(self):
        edited = self.file.with_value("block.remission.stone", 'Stone "festered" – vile')
        self.assertIn('"Stone \\"festered\\" – vile"', edited.text)
        self.assertEqual(edited.get("block.remission.stone"), 'Stone "festered" – vile')

    def test_changed_arguments_are_refused(self):
        with self.assertRaises(lang.LangError) as ctx:
            self.file.with_value("commands.remission.surge.start.success", "From %s, %s, %s in %s ticks")
        self.assertIn("Arguments changed", str(ctx.exception))

    def test_invalid_json_and_duplicates_are_refused(self):
        with self.assertRaises(lang.LangError):
            lang.LangFile('{"a": "x",}')
        with self.assertRaises(lang.LangError):
            lang.LangFile('{"a": "x", "a": "y"}')

    def test_unknown_key(self):
        with self.assertRaises(lang.LangError):
            self.file.with_value("nope", "x")


if __name__ == "__main__":
    unittest.main()

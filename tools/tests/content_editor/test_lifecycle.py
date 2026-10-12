"""Properties rows, vocabulary checks, the optional local reviewer, and the batch lifecycle
(update from main, finish). The reviewer talks to a fake Ollama on a random port."""
import json
import os
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer

import fixture
from contentedit import glossary, llm, props
from contentedit.repo import RepoError
from contentedit.service import Editor, EditorError

PROPS = "mod/gradle.properties:mod_description"


class PropsTest(unittest.TestCase):
    def test_edit_in_place(self):
        f = props.PropsFile(fixture.PROPS_TEXT)
        edited = f.with_value("mod_description", "Hunt the Strain back to its nidi.")
        self.assertEqual(edited.text, fixture.PROPS_TEXT.replace("Hunt the Strain, a living plague.",
                                                                 "Hunt the Strain back to its nidi."))
        for bad in ("two\nlines", "  ", None):
            with self.assertRaises(props.PropsError):
                f.with_value("mod_description", bad)
        with self.assertRaises(props.PropsError):
            f.with_value("nope", "x")


class VocabularyTest(unittest.TestCase):
    def test_avoided_words_and_glossary_terms(self):
        hits = glossary.check("The corruption spreads.\nTainted land and infected mobs.")
        self.assertEqual([(h["term"].lower(), h["line"]) for h in hits],
                         [("corruption", 1), ("tainted", 2), ("infected mobs", 2)])
        self.assertEqual(glossary.check("The Strain festers; hosts rise from the nidi."), [])
        design = "## Glossary\n\n| Concept | Term | IDs |\n| --- | --- | --- |\n| X | **the Strain** | a |\n| Y | **nidus** (plural **nidi**) | b |\n\n## Pillars\n"
        self.assertEqual(glossary.terms(design), ["the Strain", "nidus", "nidi"])
        self.assertEqual(glossary.used("Destroy the nidi of the Strain.", ["the Strain", "nidus", "nidi"]), ["the Strain", "nidi"])


class FakeOllama(BaseHTTPRequestHandler):
    reply = {"issues": [{"quote": "living plague", "problem": "plague is not a glossary term"},
                        {"quote": "words that are not there", "problem": "hallucinated"}]}

    def log_message(self, *args):
        pass

    def do_GET(self):
        body = json.dumps({"models": [{"name": "qwen2.5:14b"}]}).encode()
        self.send_response(200); self.end_headers(); self.wfile.write(body)

    def do_POST(self):
        request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        assert request["format"] == "json" and "Never rewrite" in request["messages"][0]["content"]
        body = json.dumps({"message": {"content": json.dumps(self.reply)}}).encode()
        self.send_response(200); self.end_headers(); self.wfile.write(body)


class ReviewerTest(unittest.TestCase):
    def test_review_keeps_only_real_quotes(self):
        server = HTTPServer(("127.0.0.1", 0), FakeOllama)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            reviewer = llm.Reviewer(f"http://127.0.0.1:{server.server_port}", "qwen2.5:14b")
            self.assertTrue(reviewer.available())
            issues = reviewer.review("Hunt the Strain, a living plague.", "metadata", "notes", "glossary")
            self.assertEqual([i["quote"] for i in issues], ["living plague"])
            self.assertFalse(llm.Reviewer(f"http://127.0.0.1:{server.server_port}", "other-model").available())
        finally:
            server.shutdown()
        self.assertFalse(llm.Reviewer(enabled=False).available())
        with self.assertRaises(RuntimeError):
            llm.Reviewer("http://127.0.0.1:9", enabled=True).review("x", "lang", "", "")


class LifecycleTest(unittest.TestCase):
    def setUp(self):
        self.repo = fixture.TempRepo()
        self.editor = Editor(self.repo.root, clock=lambda: fixture.TODAY)
        self.editor.workspace.start("life", today=fixture.TODAY)

    def tearDown(self):
        self.repo.cleanup()

    def commit_batch(self, message="Content: test"):
        _, tree = self.editor.workspace.run_checks()
        self.editor.workspace.commit(message, tree)

    def push_to_main(self, rel, text):
        self.repo.write(rel, text.encode())
        fixture.git(self.repo.root, "commit", "-qam", f"main: {rel}")
        fixture.git(self.repo.root, "push", "-q", "origin", "main")

    def test_properties_save_accept_and_undo(self):
        row = self.editor.properties_save(PROPS, "Hunt the Strain back to its nidi.")
        self.assertEqual((row.origin, row.status), ("human (Tester, 2026-10-12)", "draft"))
        detail = self.editor.row_detail(PROPS)
        self.assertEqual((detail["kind"], detail["value"], detail["gate"]), ("properties", "Hunt the Strain back to its nidi.", True))
        self.assertEqual(self.editor.batch()["changes"][0]["strings"][0]["before"], "Hunt the Strain, a living plague.")
        self.editor.undo(PROPS)
        with open(os.path.join(self.editor.root, "mod/gradle.properties"), encoding="utf-8") as f:
            self.assertEqual(f.read(), fixture.PROPS_TEXT)
        self.assertEqual(self.editor.accept_row(PROPS).status, "final")

    def test_update_from_main_rebases_or_reports_conflicts(self):
        self.editor.lang_save(fixture.LANG, "block.remission.stone", "Rotten Stone")
        with self.assertRaises(RepoError):
            self.editor.workspace.update_from_main()  # uncommitted changes
        self.commit_batch()
        self.push_to_main("docs/LORE.md", fixture.LORE_TEXT + "\nMore lore.\n")
        self.assertEqual(self.editor.workspace.behind(), 1)
        self.assertTrue(self.editor.workspace.update_from_main())
        self.assertEqual(self.editor.workspace.behind(), 0)
        with open(os.path.join(self.editor.root, "docs/LORE.md"), encoding="utf-8") as f:
            self.assertIn("More lore.", f.read())
        # Main changes the same line: the rebase is aborted and the batch is left as it was.
        head = fixture.git(self.editor.root, "rev-parse", "HEAD")
        self.push_to_main(fixture.LANG, fixture.LANG_TEXT.replace('"Festered Stone"', '"Stone of the Strain"'))
        with self.assertRaises(RepoError) as ctx:
            self.editor.workspace.update_from_main()
        self.assertIn("en_us.json", str(ctx.exception))
        self.assertEqual(fixture.git(self.editor.root, "rev-parse", "HEAD"), head)
        self.assertFalse(self.editor.workspace.uncommitted())

    def test_finish_needs_a_closed_pr_or_the_name_to_discard(self):
        path, branch = self.editor.workspace.worktree, self.editor.workspace.branch()
        with self.assertRaises(RepoError):
            self.editor.workspace.finish()
        with self.assertRaises(RepoError):
            self.editor.workspace.finish("content/wrong")
        self.editor.workspace.finish(branch)
        self.assertFalse(os.path.exists(path))
        self.assertFalse(self.editor.workspace.writable)
        self.assertEqual(fixture.git(self.repo.root, "branch", "--list", branch).strip(), "")


if __name__ == "__main__":
    unittest.main()

"""Regression tests for the fourth PR #60 code review."""
import http.client
import json
import os
import threading
import unittest
from http.server import ThreadingHTTPServer

import fixture
import server
from contentedit import host, llm, textures
from contentedit.service import Editor, EditorError


class StandaloneTest(unittest.TestCase):
    def test_wide_gui_images_skip_the_marker(self):
        checks, _ = textures.validate(fixture.solid(182, 5), "draft", marker_rule=False)
        self.assertNotIn("fail", [c["state"] for c in checks])

    def test_stages_start_at_one(self):
        for bad in (0, -5):
            with self.assertRaises(ValueError):
                host.stage_pattern(bad)

    def test_prompt_placeholders_are_filled_once(self):
        captured = {}

        class Fake(llm.Reviewer):
            def available(self):
                return True

        reviewer = Fake()

        def fake_urlopen(req, timeout=0):
            captured["prompt"] = json.loads(req.data)["messages"][0]["content"]
            raise OSError("stop here")
        original = llm.urllib.request.urlopen
        llm.urllib.request.urlopen = fake_urlopen
        try:
            with self.assertRaises(RuntimeError):
                reviewer.review("THE TEXT", "lang", "notes mention {text} literally", "glossary")
        finally:
            llm.urllib.request.urlopen = original
        self.assertEqual(captured["prompt"].count("THE TEXT"), 1)
        self.assertIn("notes mention {text} literally", captured["prompt"])


class EditorTest(unittest.TestCase):
    def setUp(self):
        self.repo = fixture.TempRepo()
        self.editor = Editor(self.repo.root, clock=lambda: fixture.TODAY)
        self.editor.workspace.start("fixes4", today=fixture.TODAY)

    def tearDown(self):
        self.repo.cleanup()

    def test_unchanged_markdown_save_is_refused_even_when_tidying_would_change_bytes(self):
        path = self.editor.path("docs/LORE.md")
        with open(path, "wb") as f:
            f.write(fixture.LORE_TEXT.replace("\n", "\r\n").rstrip("\r\n").encode())  # CRLF, no final newline
        with self.assertRaises(EditorError):
            self.editor.markdown_save("docs/LORE.md", fixture.LORE_TEXT)
        self.assertEqual(self.editor.ledger().find("docs/LORE.md").origin, "ai (test)")

    def test_mcmeta_only_for_textures(self):
        with self.assertRaises(EditorError):
            self.editor.stage_upload(fixture.HAND_MODEL, b'{"textures": {}}', mcmeta="{}")

    def test_host_preview_refuses_bad_stages(self):
        for bad in (0, -5):
            with self.assertRaises(EditorError):
                self.editor.host_png(bad)


class ServerTest(unittest.TestCase):
    def setUp(self):
        self.repo = fixture.TempRepo()
        self.app = server.App(Editor(self.repo.root, clock=lambda: fixture.TODAY), "tok", lan=False)
        server.Handler.app = self.app
        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self.repo.cleanup()

    def request(self, method, path, headers=None, body=None):
        conn = http.client.HTTPConnection("127.0.0.1", self.httpd.server_port, timeout=5)
        conn.putrequest(method, path)
        for k, v in {"X-Editor-Token": "tok", **(headers or {})}.items():
            conn.putheader(k, v)
        conn.endheaders()
        if body:
            conn.send(body)
        res = conn.getresponse()
        data = res.read()
        conn.close()
        return res.status, data

    def test_negative_content_length_is_refused_without_hanging(self):
        status, _ = self.request("POST", "/api/vocabulary", {"Content-Length": "-1"})
        self.assertEqual(status, 400)
        self.assertFalse(self.app.lock.locked())

    def test_a_failed_check_run_is_not_reported_as_passed(self):
        self.app.checked = (None, [{"cmd": "x", "ok": False}])
        status, body = self.request("GET", "/api/state")
        self.assertEqual((status, json.loads(body)["checked"]), (200, False))

    def test_bad_host_stage_is_a_400(self):
        self.assertEqual(self.request("GET", "/api/host?stage=-5")[0], 400)


if __name__ == "__main__":
    unittest.main()

"""Regression tests for the PR #60 code review findings."""
import http.client
import os
import threading
import time
import unittest
from http.server import ThreadingHTTPServer

import fixture
import server
from contentedit import props
from contentedit.repo import RepoError, pr_url
from contentedit.service import Editor, EditorError

LANG, TEX = fixture.LANG, fixture.TEX


class ServerTest(unittest.TestCase):
    """The HTTP layer: static files stay inside their folders, and reads never wait for writes."""

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

    def get(self, path, token=False, headers=None):
        conn = http.client.HTTPConnection("127.0.0.1", self.httpd.server_port, timeout=5)
        conn.request("GET", path, headers={**({"X-Editor-Token": "tok"} if token else {}), **(headers or {})})
        res = conn.getresponse()
        body = res.read()
        conn.close()
        return res.status, body

    def test_static_paths_cannot_leave_their_folder(self):
        self.assertEqual(self.get("/static/app.js?t=tok")[0], 200)
        for evil in ("/static//etc/passwd", "/static/../server.py", "/static/%2e%2e/server.py",
                     "/vendor//etc/hostname", "/static/../../../../etc/passwd"):
            status, body = self.get(evil, token=True)
            self.assertEqual(status, 404, evil)
            self.assertNotIn(b"root:", body)

    def test_every_request_needs_the_token(self):
        for path in ("/", "/index.html", "/static/app.js", "/vendor/deepslate-0.27.2.umd.js", "/api/state"):
            self.assertEqual(self.get(path)[0], 403, path)
        status, body = self.get("/?t=tok")
        self.assertEqual(status, 200)
        self.assertIn(b'content="tok"', body)
        # A non-ASCII token is refused with a 403, not a crashed request thread.
        self.assertEqual(self.get("/?t=%C3%A9")[0], 403)
        self.assertEqual(self.get("/api/state", headers={"X-Editor-Token": "caf\xe9"})[0], 403)

    def test_files_download_as_attachments_on_request(self):
        conn = http.client.HTTPConnection("127.0.0.1", self.httpd.server_port, timeout=5)
        conn.request("GET", f"/api/file?path={TEX}block/stone.png&download=1", headers={"X-Editor-Token": "tok"})
        res = conn.getresponse()
        body = res.read()
        conn.close()
        self.assertEqual(res.status, 200)
        self.assertEqual(res.getheader("Content-Type"), "image/png")
        self.assertEqual(res.getheader("Content-Disposition"), f'attachment; filename="stone.png"')
        self.assertTrue(body.startswith(b"\x89PNG"))
        conn = http.client.HTTPConnection("127.0.0.1", self.httpd.server_port, timeout=5)
        conn.request("GET", f"/api/file?path={TEX}block/stone.png", headers={"X-Editor-Token": "tok"})
        res = conn.getresponse()
        res.read()
        conn.close()
        self.assertIsNone(res.getheader("Content-Disposition"))  # previews stay inline

    def test_reads_do_not_wait_for_a_running_write(self):
        with self.app.lock:  # as if Run checks or a push were holding the write lock
            start = time.monotonic()
            status, _ = self.get("/api/state", token=True)
            self.assertEqual(status, 200)
            self.assertLess(time.monotonic() - start, 3)


class ServiceFixesTest(unittest.TestCase):
    def setUp(self):
        self.repo = fixture.TempRepo()
        self.editor = Editor(self.repo.root, clock=lambda: fixture.TODAY)
        self.editor.workspace.start("fixes", today=fixture.TODAY)

    def tearDown(self):
        self.repo.cleanup()

    def test_saving_an_unchanged_string_is_refused(self):
        with self.assertRaises(EditorError):
            self.editor.lang_save(LANG, "block.remission.stone", "Festered Stone", "final")
        row = self.editor.ledger().find(f"{LANG}:block.remission.stone")
        self.assertEqual((row.origin, row.status), ("ai (test)", "draft"))

    def test_undo_removes_a_new_rows_asset(self):
        new = TEX + "block/brand_new.png"
        self.editor.add_row(new, "texture", "Textures", "A new block.")
        staged = self.editor.stage_upload(new, fixture.solid(16, 16), status="draft")
        self.editor.write_upload(staged["id"], "draft")
        self.assertTrue(os.path.exists(os.path.join(self.editor.root, new)))
        self.editor.undo(new)
        self.assertFalse(os.path.exists(os.path.join(self.editor.root, new)))
        self.assertIsNone(self.editor.ledger().find(new))

    def test_listing_a_batch_leaves_the_index_alone_and_counts_new_files(self):
        new = TEX + "block/brand_new.png"
        self.editor.add_row(new, "texture", "Textures", "A new block.")
        self.editor.write_upload(self.editor.stage_upload(new, fixture.solid(16, 16), status="draft")["id"], "draft")
        summary = self.editor.batch()
        entry = next(f for f in summary["files"] if f["path"] == new)
        self.assertEqual((entry["status"], entry["added"]), ("A", "-"))  # binary, counted on first load
        self.assertEqual(fixture.git(self.editor.root, "diff", "--cached", "--name-only").strip(), "")
        self.assertEqual(self.editor.pr_body(summary), self.editor.pr_body())

    def test_finish_protects_work_a_closed_pr_does_not_contain(self):
        ws = self.editor.workspace
        branch = ws.branch()
        head = fixture.git(ws.worktree, "rev-parse", "HEAD").strip()
        ws.pr = lambda: {"state": "CLOSED", "url": "https://example.invalid/pull/1", "headRefOid": head}
        self.editor.lang_save(LANG, "block.remission.stone", "Rotten Stone")
        with self.assertRaises(RepoError):
            ws.finish()  # uncommitted edits
        _, tree = ws.run_checks()
        ws.commit("Content: later work", tree)
        with self.assertRaises(RepoError):
            ws.finish()  # a commit the PR never had
        ws.finish(branch)  # typing the name discards it
        self.assertFalse(ws.writable)

    def test_finish_without_extra_work_needs_no_confirmation(self):
        ws = self.editor.workspace
        head = fixture.git(ws.worktree, "rev-parse", "HEAD").strip()
        ws.pr = lambda: {"state": "MERGED", "url": "https://example.invalid/pull/2", "headRefOid": head}
        ws.finish()
        self.assertFalse(ws.writable)

    def test_properties_keep_non_ascii_as_escapes(self):
        self.editor.properties_save("mod/gradle.properties:mod_description", "Hunt the Strain — back to its nidi")
        with open(os.path.join(self.editor.root, "mod/gradle.properties"), "rb") as f:
            raw = f.read()
        self.assertIn(b"mod_description=Hunt the Strain \\u2014 back to its nidi", raw)
        self.assertTrue(all(b < 128 for b in raw))
        self.assertEqual(props.PropsFile(raw.decode("iso-8859-1")).get("mod_description"), "Hunt the Strain — back to its nidi")


class PrUrlTest(unittest.TestCase):
    def test_finds_the_url_in_stdout_or_stderr(self):
        self.assertEqual(pr_url("\nhttps://github.com/CubieCode/counterstrain/pull/61\n"),
                         "https://github.com/CubieCode/counterstrain/pull/61")
        self.assertEqual(pr_url("Creating pull request...\nhttps://github.com/o/r/pull/7"), "https://github.com/o/r/pull/7")
        self.assertIsNone(pr_url(""))


if __name__ == "__main__":
    unittest.main()

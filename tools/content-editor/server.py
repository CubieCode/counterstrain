"""Local content editor (P1.5): replace AI-generated player-facing content without hand-editing files.

    python3 -I tools/content-editor/server.py [--port N] [--lan] [--worktree PATH] [--reviewer NAME]

Run through tools/content-editor.sh. Binds 127.0.0.1 unless --lan. Every request,
pages included, carries the random token printed at start (open the printed URL;
the page sends it for you).
"""
import argparse
import base64
import json
import mimetypes
import os
import secrets
import sys
import threading
import traceback
import urllib.parse
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from contentedit import lang, llm  # noqa: E402
from contentedit.ledger import LedgerError  # noqa: E402
from contentedit.png import PngError  # noqa: E402
from contentedit.repo import RepoError, split_id  # noqa: E402
from contentedit.service import Editor, EditorError  # noqa: E402

TOOL_ROOT = os.path.dirname(os.path.dirname(HERE))
STATIC = os.path.join(HERE, "static")
VENDOR = os.path.join(HERE, "vendor")
MAX_BODY = 12 * 1024 * 1024
REFUSED = (EditorError, RepoError, LedgerError, lang.LangError, PngError, ValueError)


class App:
    def __init__(self, editor, token, lan):
        self.editor, self.token, self.lan = editor, token, lan
        self.lock = threading.Lock()
        self.checked = None  # (tree, results) from the last batch check


class Handler(BaseHTTPRequestHandler):
    server_version = "content-editor"
    app = None  # set in main()
    LOCKLESS = {"post_review", "post_vocabulary"}

    def log_message(self, fmt, *args):
        if os.environ.get("CONTENT_EDITOR_LOG"):
            super().log_message(fmt, *args)

    # --- plumbing ------------------------------------------------------------

    def send(self, status, body, ctype="application/json; charset=utf-8", cache=False):
        if isinstance(body, (dict, list)):
            body = json.dumps(body).encode("utf-8")
        elif isinstance(body, str):
            body = body.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store" if not cache else "max-age=3600")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.end_headers()
        self.wfile.write(body)

    def fail(self, status, message):
        self.send(status, {"error": message})

    def query(self):
        return {k: v[-1] for k, v in urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query).items()}

    def authorised(self, api):
        app = self.app
        if not app.lan:
            host = (self.headers.get("Host") or "").rsplit(":", 1)[0].strip("[]")
            if host not in ("127.0.0.1", "localhost", "::1"):
                return False  # DNS rebinding: only the loopback names reach a loopback-bound editor
        # Every request needs the token, pages included: the page embeds it, so serving the page without it
        # would hand it to any local process. Compared as bytes (compare_digest rejects non-ASCII str).
        given = self.headers.get("X-Editor-Token") or self.query().get("t", "")
        return secrets.compare_digest(given.encode("utf-8", "replace"), app.token.encode("ascii"))

    def body(self):
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            raise EditorError("bad Content-Length")
        if length < 0:
            raise EditorError("bad Content-Length")  # rfile.read(-1) would block until the client hangs up
        if length > MAX_BODY:
            raise EditorError(f"request too large ({length} bytes; the limit is {MAX_BODY})")
        data = self.rfile.read(length) if length else b"{}"
        try:
            return json.loads(data or b"{}")
        except json.JSONDecodeError as e:
            raise EditorError(f"bad request body: {e}")

    # --- routing -------------------------------------------------------------

    def do_GET(self):
        self.route("GET")

    def do_POST(self):
        self.route("POST")

    def route(self, method):
        path = urllib.parse.urlsplit(self.path).path
        api = path.startswith("/api/")
        if not self.authorised(api):
            return self.fail(HTTPStatus.FORBIDDEN, "missing or wrong token (use the URL printed at start)")
        try:
            if not api:
                return self.static(path) if method == "GET" else self.fail(HTTPStatus.METHOD_NOT_ALLOWED, "GET only")
            handler = getattr(self, f"{method.lower()}_{path[5:].replace('/', '_')}", None)
            if handler is None:
                return self.fail(HTTPStatus.NOT_FOUND, f"no route {method} {path}")
            # Reads never wait: writes are atomic, so a GET sees a file before or after a save. Writes are
            # serialized, and the model review only reads, so a slow review or check run never freezes the page.
            with self.app.editor.workspace.pinned_base():
                if method == "GET" or handler.__name__ in self.LOCKLESS:
                    result = handler()
                else:
                    with self.app.lock:
                        result = handler()
            if result is not None:
                self.send(HTTPStatus.OK, result)
        except REFUSED as e:
            self.fail(HTTPStatus.CONFLICT if isinstance(e, RepoError) else HTTPStatus.BAD_REQUEST, str(e))
        except Exception as e:  # report, keep serving
            traceback.print_exc()
            self.fail(HTTPStatus.INTERNAL_SERVER_ERROR, f"{type(e).__name__}: {e}")

    def static(self, path):
        if path in ("/", "/index.html"):
            with open(os.path.join(STATIC, "index.html"), encoding="utf-8") as f:
                page = f.read().replace("__TOKEN__", self.app.token)
            return self.send(HTTPStatus.OK, page, "text/html; charset=utf-8")
        for prefix, base in (("/static/", STATIC), ("/vendor/", VENDOR)):
            if path.startswith(prefix):
                # Resolve and require the result to stay inside the folder: `//etc/passwd`, `..` and symlinks
                # out of it are refused.
                root = os.path.realpath(base)
                full = os.path.realpath(os.path.join(root, path[len(prefix):].lstrip("/")))
                if os.path.commonpath([root, full]) != root or not os.path.isfile(full):
                    break
                ctype = mimetypes.guess_type(full)[0] or "application/octet-stream"
                if full.endswith(".js"):
                    ctype = "text/javascript; charset=utf-8"
                with open(full, "rb") as f:
                    data = f.read()
                if prefix == "/static/":
                    data = data.replace(b"__TOKEN__", self.app.token.encode("ascii"))  # module imports carry it too
                return self.send(HTTPStatus.OK, data, ctype, cache=prefix == "/vendor/")
        self.fail(HTTPStatus.NOT_FOUND, "not found")

    def binary(self, data, name):
        if data is None:
            return self.fail(HTTPStatus.NOT_FOUND, "not found")
        ctype = "image/png" if name.endswith(".png") else "audio/ogg" if name.endswith(".ogg") else \
            "application/json" if name.endswith((".json", ".mcmeta")) else "application/octet-stream"
        self.send(HTTPStatus.OK, data, ctype)

    # --- reads ---------------------------------------------------------------

    def get_state(self):
        state = self.app.editor.state()
        state["lan"] = self.app.lan
        state["checked"] = bool(self.app.checked and self.app.checked[0])  # a failed run stores (None, results)
        return state

    def get_rows(self):
        return {"rows": self.app.editor.rows()}

    def get_row(self):
        return self.app.editor.row_detail(self.query().get("id", ""))

    def get_file(self):
        q = self.query()
        rel = q.get("path", "")
        if not self.app.editor.readable(rel):
            raise EditorError(f"`{rel}` is not a ledgered or asset path")
        self.binary(self.app.editor.read(rel, q.get("rev", "worktree")), rel)

    def get_upload(self):
        self.binary(self.app.editor.upload_bytes(self.query().get("id", "")), ".bin")

    def get_res(self):
        q = self.query()
        kind = q.get("kind")
        if kind not in ("models", "textures"):
            raise EditorError("kind must be models or textures")
        namespace, path = split_id(q.get("id", ""))
        ext = ".json" if kind == "models" else ".png"
        data, _ = self.app.editor.resolver.read(namespace, kind, path, ext, q.get("rev", "worktree"))
        self.binary(data, ext)

    def get_host(self):
        q = self.query()
        data = self.app.editor.host_png(int(q.get("stage", "1")), q.get("mob", "mannequin"),
                                        float(q.get("intensity", "1")), q.get("rev", "worktree"),
                                        q.get("upload") or None, q.get("glow", "1") != "0")
        self.binary(data, ".png")

    def get_batch(self):
        summary = self.app.editor.batch()
        summary["prBody"] = self.app.editor.pr_body(summary)
        summary["checks"] = self.app.checked[1] if self.app.checked else None
        return summary

    # --- writes --------------------------------------------------------------

    def post_batch_start(self):
        branch = self.app.editor.workspace.start(self.body().get("slug", ""))
        self.app.checked = None
        return {"branch": branch, "state": self.get_state()}

    def post_batch_resume(self):
        self.app.editor.workspace.resume(self.body().get("path", ""))
        self.app.checked = None
        return {"state": self.get_state()}

    def post_lang_save(self):
        b = self.body()
        row = self.app.editor.lang_save(b.get("file", ""), b.get("key", ""), b.get("value"), b.get("status", "draft"))
        self.app.checked = None
        return {"row": row.to_dict()}

    def post_lang_accept(self):
        b = self.body()
        row = self.app.editor.lang_accept(b.get("file"), key=b.get("key"), row_id=b.get("row"))
        self.app.checked = None
        return {"row": row.to_dict()}

    def post_markdown_save(self):
        b = self.body()
        row, warnings = self.app.editor.markdown_save(b.get("row", ""), b.get("text"), b.get("status", "draft"))
        self.app.checked = None
        return {"row": row.to_dict(), "warnings": warnings}

    def post_accept(self):
        row = self.app.editor.accept_row(self.body().get("row", ""))
        self.app.checked = None
        return {"row": row.to_dict()}

    def post_properties_save(self):
        b = self.body()
        row = self.app.editor.properties_save(b.get("row", ""), b.get("value"), b.get("status", "draft"))
        self.app.checked = None
        return {"row": row.to_dict()}

    def post_vocabulary(self):
        return self.app.editor.vocabulary(self.body().get("text"))

    def post_review(self):
        b = self.body()
        return self.app.editor.review(b.get("row", ""), b.get("text") or "")

    def post_batch_update(self):
        changed = self.app.editor.workspace.update_from_main()
        self.app.checked = None
        return {"changed": changed}

    def post_batch_finish(self):
        branch, pr = self.app.editor.workspace.finish(self.body().get("discard"))
        self.app.checked = None
        return {"branch": branch, "pr": pr}

    def post_upload(self):
        b = self.body()
        try:
            data = base64.b64decode(b.get("data", ""), validate=True)
        except ValueError:
            raise EditorError("upload data must be base64")
        return self.app.editor.stage_upload(b.get("row", ""), data, b.get("status"), b.get("mcmeta"))

    def post_upload_write(self):
        b = self.body()
        row = self.app.editor.write_upload(b.get("id", ""), b.get("status", ""), b.get("origin"), b.get("note"))
        self.app.checked = None
        return {"row": row.to_dict()}

    def post_rows_add(self):
        b = self.body()
        row = self.app.editor.add_row(b.get("path", ""), b.get("type", ""), b.get("section", ""), b.get("notes", ""))
        self.app.checked = None
        return {"row": row.to_dict()}

    def post_batch_undo(self):
        self.app.editor.undo(self.body().get("row", ""))
        self.app.checked = None
        return {"ok": True}

    def post_batch_check(self):
        results, tree = self.app.editor.workspace.run_checks()
        self.app.checked = (tree, results) if all(r["ok"] for r in results) else (None, results)
        return {"checks": results, "ok": self.app.checked[0] is not None}

    def post_batch_commit(self):
        if not self.app.checked or not self.app.checked[0]:
            raise EditorError("run the checks and get them all passing first")
        sha = self.app.editor.workspace.commit(self.body().get("message", ""), self.app.checked[0])
        self.app.checked = None  # a later commit needs a fresh check run
        return {"commit": sha}

    def post_batch_pr(self):
        b = self.body()
        title = (b.get("title") or "").strip()
        if not title:
            raise EditorError("the PR needs a title")
        url, updated = self.app.editor.workspace.open_pr(title, self.app.editor.pr_body(), draft=b.get("draft", True))
        return {"url": url, "updated": updated}


def main(argv):
    parser = argparse.ArgumentParser(prog="tools/content-editor.sh", description=__doc__.split("\n\n")[0])
    parser.add_argument("--port", type=int, default=int(os.environ.get("COUNTERSTRAIN_EDITOR_PORT", "8765")))
    parser.add_argument("--lan", action="store_true", help="bind every interface (token required on every request)")
    parser.add_argument("--worktree", help="resume an existing content/<date>-<slug> worktree")
    parser.add_argument("--reviewer", help="name recorded in origins and acceptance notes (default: git user.name)")
    parser.add_argument("--llm-url", default=os.environ.get("CONTENT_EDITOR_LLM_URL", "http://127.0.0.1:11434"),
                        help="Ollama URL for the optional text review (default: %(default)s)")
    parser.add_argument("--llm-model", default=os.environ.get("CONTENT_EDITOR_LLM_MODEL", "qwen2.5:14b"),
                        help="Ollama model for the optional text review (default: %(default)s)")
    parser.add_argument("--no-llm", action="store_true", help="turn the optional text review off")
    args = parser.parse_args(argv)

    editor = Editor(TOOL_ROOT, worktree=args.worktree, reviewer=args.reviewer,
                    llm_reviewer=llm.Reviewer(args.llm_url, args.llm_model, enabled=not args.no_llm))
    token = secrets.token_urlsafe(18)
    Handler.app = App(editor, token, args.lan)
    host = "0.0.0.0" if args.lan else "127.0.0.1"
    server = ThreadingHTTPServer((host, args.port), Handler)
    server.daemon_threads = True
    shown = os.uname().nodename if args.lan else "127.0.0.1"
    state = editor.workspace
    print(f"Content editor: http://{shown}:{args.port}/?t={token}", flush=True)
    print(f"  reading {state.root}" + ("" if state.writable else " (read-only until you start a batch)"), flush=True)
    if not args.lan:
        print(f"  from a PC: ssh -L {args.port}:127.0.0.1:{args.port} foundry, then open the URL above", flush=True)
    if editor.llm.available():
        print(f"  text review: {args.llm_model} via {args.llm_url} (advisory; --no-llm turns it off)", flush=True)
    if editor.resolver.jar() is None:
        print("  vanilla models and mob textures unavailable: run ./gradlew build in mod/ first", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

"""The editor's operations, independent of HTTP: listing rows, editing lang strings,
staging and writing uploads, the host preview and the batch summary.

Every write goes through `Editor._target`, which only allows files a ledger row
names (plus a texture's `.mcmeta` sidecar and the lang and ledger files the rows
point at), never generated output, and only inside the content worktree.
"""
import datetime
import json
import os
import posixpath
import secrets
import time

from . import host, lang, models, png, textures
from .ledger import Ledger, LedgerError, append_note
from .repo import RepoError, Resolver, Workspace, git, show

LEDGER = "docs/CONTENT-PROVENANCE.md"
GENERATED = "mod/src/generated/"
NEW_ROW_PREFIXES = ("mod/src/main/resources/assets/", "pack/")
SOUND_BYTES = 8 * 1024 * 1024
MAX_UPLOADS = 20


class EditorError(ValueError):
    """A refused request; the message is shown to the user."""


def normalize(rel):
    rel = (rel or "").replace("\\", "/")
    if not rel or rel.startswith("/") or ":" in rel.split("/", 1)[0]:
        raise EditorError(f"`{rel}` is not a repository path")
    norm = posixpath.normpath(rel)
    if norm.startswith("../") or norm == ".." or norm != rel:
        raise EditorError(f"`{rel}` is not a plain repository path")
    return norm


def kind_of(row):
    """What the editor can do with a row: lang, texture, model, sound, or None (view only)."""
    if row.key is not None:
        return "lang" if row.file.endswith(".json") and "/lang/" in row.file else None
    if row.file.endswith(".png"):
        return "texture"
    if row.file.endswith(".ogg"):
        return "sound"
    if row.file.endswith(".json") and "/models/" in row.file:
        return "model"
    return None


class Editor:
    def __init__(self, tool_root, worktree=None, reviewer=None, clock=None):
        self.workspace = Workspace(tool_root, worktree)
        self.resolver = Resolver(self.workspace)
        self.reviewer = reviewer
        self.clock = clock or datetime.date.today
        self.uploads = {}

    # --- basics --------------------------------------------------------------

    @property
    def root(self):
        return self.workspace.root

    def who(self):
        if self.reviewer:
            return self.reviewer
        name = git(self.root, "config", "user.name", check=False).stdout.strip()
        return name or "unknown"

    def today(self):
        return self.clock().isoformat()

    def path(self, rel):
        return os.path.join(self.root, rel)

    def ledger(self):
        return Ledger.load(self.path(LEDGER))

    def base_ledger(self):
        data = show(self.root, self.workspace.base(), LEDGER)
        return Ledger(data.decode("utf-8")) if data is not None else Ledger("")

    def read(self, rel, rev="worktree"):
        rel = normalize(rel)
        if rev == "base":
            return show(self.root, self.workspace.base(), rel)
        try:
            with open(self.path(rel), "rb") as f:
                return f.read()
        except OSError:
            return None

    def readable(self, rel):
        """Files the UI may fetch: anything a row names, its .mcmeta, and assets under the scanned trees."""
        rel = normalize(rel)
        ledger = self.ledger()
        files = ledger.files()
        return (rel in files or (rel.endswith(".mcmeta") and rel[:-7] in files)
                or rel.startswith(("mod/src/main/resources/assets/", "mod/src/generated/resources/assets/", "pack/")))

    def _target(self, rel, ledger, allow_new=False):
        """The absolute path to write `rel` to, or EditorError when the editor must not write it."""
        root = self.workspace.require_batch()
        rel = normalize(rel)
        if rel.startswith(GENERATED):
            raise EditorError(f"`{rel}` is datagen output; change its provider or add a hand-authored file under "
                              "mod/src/main/resources/ instead")
        named = rel in ledger.files() or (rel.endswith(".mcmeta") and rel[:-7] in ledger.files())
        if not named and not (allow_new and rel.startswith(NEW_ROW_PREFIXES)):
            raise EditorError(f"`{rel}` is not named by a ledger row; the editor only writes ledgered paths")
        return os.path.join(root, rel)

    def _write(self, rel, data, ledger, allow_new=False):
        target = self._target(rel, ledger, allow_new)
        os.makedirs(os.path.dirname(target), exist_ok=True)
        with open(target, "wb") as f:
            f.write(data)

    def _save_ledger(self, ledger):
        self.workspace.require_batch()
        ledger.save(self.path(LEDGER))

    def human_origin(self):
        return f"human ({self.who()}, {self.today()})"

    def origin(self, spec):
        spec = spec or {}
        if spec.get("kind", "human") == "human":
            return self.human_origin()
        if spec.get("kind") == "licensed":
            source, licence = (spec.get("source") or "").strip(), (spec.get("license") or "").strip()
            if not source or not licence:
                raise EditorError("a licensed origin needs both a source and a licence")
            return f"licensed ({source}, {licence})"
        raise EditorError("origin must be human or licensed")

    def accepted_note(self):
        return f"Accepted as final by {self.who()}, {self.today()}."

    # --- listing -------------------------------------------------------------

    def state(self):
        ws = self.workspace
        return {"batch": ws.writable, "branch": ws.branch(), "worktree": ws.worktree, "root": ws.root,
                "base": ws.base()[:9], "reviewer": self.who(), "batches": ws.batches(),
                "vanilla": self.resolver.jar() is not None, "today": self.today()}

    def rows(self):
        ledger, base = self.ledger(), self.base_ledger()
        out = []
        for row in ledger.rows:
            item = row.to_dict()
            item["kind"] = kind_of(row)
            item["editable"] = item["kind"] is not None and not row.generated and row.status != "removed"
            before = base.find(row.path_id)
            item["pending"] = before is None or (before.origin, before.status, before.notes) != \
                (row.origin, row.status, row.notes)
            item["new"] = before is None
            if row.wildcard:
                item["overridden"] = len(ledger.overrides(row))
                try:
                    item["keyCount"] = len(ledger.keys(row, lang.LangFile.load(self.path(row.file)).keys()))
                except (OSError, lang.LangError):
                    item["keyCount"] = None
            item["host"] = host.is_host_pattern(row.file)
            out.append(item)
        return out

    def row_detail(self, row_id):
        ledger = self.ledger()
        row = ledger.find(row_id)
        if row is None:
            raise EditorError(f"no ledger row `{row_id}`")
        detail = {"row": row.to_dict(), "kind": kind_of(row), "base": self.workspace.base()[:9]}
        detail["row"]["kind"] = detail["kind"]
        kind = detail["kind"]
        if kind == "lang":
            detail["keys"] = self._lang_keys(ledger, row)
            detail["siblings"] = [r.path_id for r in ledger.rows if r.file == row.file and r.items == row.items
                                  and r.items] or [row.path_id]
        elif kind == "texture":
            data = self.read(row.file)
            detail["exists"] = data is not None
            if data is not None:
                try:
                    w, h, _ = png.header(data)
                    size, marked = png.marker(data)
                    detail.update(width=w, height=h, bytes=len(data), marker=marked, markerSize=size)
                except png.PngError as e:
                    detail["error"] = str(e)
            detail["mcmeta"] = (self.read(row.file + ".mcmeta") or b"").decode("utf-8", "replace") or None
            detail["usedBy"] = self._models_using(row.file)
            detail["host"] = host.is_host_pattern(row.file)
            if detail["host"]:
                detail["mobs"] = [{"id": k, "label": v[0], "kind": v[1],
                                   "available": v[2] is None or self.resolver.jar() is not None}
                                  for k, v in host.MOBS.items()]
        elif kind == "model":
            data = self.read(row.file)
            detail["json"] = data.decode("utf-8", "replace") if data is not None else None
            detail["modelId"] = self._model_id(row.file)
            if data is not None:
                try:
                    model = json.loads(data)
                    detail["textures"] = [self._texture_info(ledger, var, ref) for var, ref in models.texture_refs(model)]
                except json.JSONDecodeError as e:
                    detail["error"] = str(e)
        elif kind == "sound":
            data = self.read(row.file)
            detail["bytes"] = len(data) if data is not None else None
        return detail

    def _lang_keys(self, ledger, row):
        current = lang.LangFile.load(self.path(row.file))
        base_data = show(self.root, self.workspace.base(), row.file)
        base = lang.LangFile(base_data.decode("utf-8")) if base_data else None
        keys = []
        for key in ledger.keys(row, current.keys()):
            owner = ledger.owner(row.file, key)
            value = current.get(key)
            keys.append({"key": key, "value": value, "base": base.values.get(key) if base else None,
                         "args": len(lang.format_args(value)), "owner": owner.path_id if owner else None,
                         "ownerStatus": owner.status if owner else None,
                         "ownerOrigin": owner.origin if owner else None,
                         "line": next((i + 1 for i, l in enumerate(current.text.split("\n")) if f'"{key}"' in l), None)})
        return keys

    def _model_id(self, rel):
        parts = rel.split("/")
        if "assets" in parts and "models" in parts:
            a = parts.index("assets")
            m = parts.index("models")
            return f"{parts[a + 1]}:{'/'.join(parts[m + 1:])[:-5]}"
        return None

    def _texture_id(self, rel):
        parts = rel.split("/")
        if "assets" in parts and "textures" in parts:
            a, t = parts.index("assets"), parts.index("textures")
            return f"{parts[a + 1]}:{'/'.join(parts[t + 1:])[:-4]}"
        return None

    def _models_using(self, texture_rel):
        tex_id = self._texture_id(texture_rel)
        if not tex_id:
            return []
        short = tex_id.split(":", 1)[1] if tex_id.startswith("minecraft:") else None
        users = []
        for tree in ("mod/src/main/resources/assets", "mod/src/generated/resources/assets"):
            base = self.path(tree)
            for dirpath, _, names in os.walk(base):
                for name in names:
                    if not name.endswith(".json") or "/models/" not in dirpath.replace(os.sep, "/") + "/":
                        continue
                    full = os.path.join(dirpath, name)
                    try:
                        with open(full, encoding="utf-8") as f:
                            model = json.load(f)
                    except (OSError, ValueError):
                        continue
                    refs = [ref for _, ref in models.texture_refs(model)] if isinstance(model, dict) else []
                    if tex_id in refs or (short and short in refs):
                        rel = os.path.relpath(full, self.root).replace(os.sep, "/")
                        users.append({"path": rel, "modelId": self._model_id(rel),
                                      "generated": rel.startswith(GENERATED)})
        return sorted(users, key=lambda u: (not u["path"].startswith(GENERATED + "resources/assets/remission/models/block"),
                                            u["path"]))

    def _texture_info(self, ledger, var, ref):
        namespace, _, path = ref.rpartition(":")
        namespace = namespace or "minecraft"
        rel = self.resolver.repo_path(namespace, "textures", path, ".png")
        row = ledger.find(rel) if rel else None
        return {"var": var, "id": f"{namespace}:{path}", "path": rel, "row": row.path_id if row else None,
                "status": row.status if row else None,
                "exists": bool(rel) or self.resolver.exists(namespace, "textures", path, ".png")}

    # --- lang ----------------------------------------------------------------

    def lang_save(self, file, key, value, status="draft"):
        """Replace one string; the governing row records the change (an exact-key row for a shared `*` row)."""
        if status not in ("draft", "final"):
            raise EditorError("a replaced string is draft or final")
        ledger = self.ledger()
        owner = ledger.owner(file, key)
        if owner is None:
            raise EditorError(f"no ledger row covers `{file}:{key}`; add one first")
        current = lang.LangFile.load(self.path(file))
        try:
            edited = current.with_value(key, value)
        except lang.LangError as e:
            raise EditorError(str(e))
        target = self._target(file, ledger)
        origin = self.human_origin()
        row = self._row_for_key(ledger, owner, file, key, current.keys(), origin, status)
        if row is owner or row.path_id == owner.path_id:
            ledger.set_fields(owner, origin=origin, status=status)
        edited.save(target)
        self._save_ledger(ledger)
        return ledger.find(f"{file}:{key}") or ledger.find(owner.path_id)

    def _row_for_key(self, ledger, owner, file, key, all_keys, origin, status, notes=None):
        """The row a one-key change is recorded on: the owner itself unless it is a `*` row governing other keys."""
        if not owner.wildcard:
            return owner
        others = [k for k in ledger.keys(owner, all_keys) if k != key and ledger.owner(file, k) is owner]
        if not others:
            return owner
        return ledger.insert_after(owner, f"{file}:{key}", "lang", origin, status,
                                   notes if notes is not None else owner.notes)

    def lang_accept(self, file, key=None, row_id=None):
        """Accept a string (or every string of a row) unchanged as final, recording the reviewer and date."""
        ledger = self.ledger()
        note = self.accepted_note()
        if row_id is not None:
            row = ledger.require(row_id)
            if kind_of(row) != "lang":
                raise EditorError(f"`{row_id}` is not a lang row")
            if row.status == "final":
                raise EditorError(f"`{row.key}` is already final")
            self._target(row.file, ledger)
            ledger.set_fields(row, status="final", notes=append_note(row.notes, note))
            self._save_ledger(ledger)
            return ledger.find(row_id)
        owner = ledger.owner(file, key)
        if owner is None:
            raise EditorError(f"no ledger row covers `{file}:{key}`")
        if owner.status == "final":
            raise EditorError(f"`{key}` is already final (row `{owner.key}`)")
        self._target(file, ledger)
        all_keys = lang.LangFile.load(self.path(file)).keys()
        if key not in all_keys:
            raise EditorError(f"no key '{key}' in {file}")
        row = self._row_for_key(ledger, owner, file, key, all_keys, owner.origin, "final",
                                append_note(owner.notes, note))
        if row.path_id == owner.path_id:
            ledger.set_fields(owner, status="final", notes=append_note(owner.notes, note))
        self._save_ledger(ledger)
        return ledger.find(f"{file}:{key}") or ledger.find(owner.path_id)

    # --- uploads -------------------------------------------------------------

    def stage_upload(self, row_id, data, status=None, mcmeta=None):
        """Check an upload against a row without writing it. Returns {id, checks, info}."""
        ledger = self.ledger()
        row = ledger.require(row_id)
        kind = kind_of(row)
        if row.generated:
            raise EditorError(f"`{row.file}` is datagen output and read-only here")
        if kind not in ("texture", "model", "sound"):
            raise EditorError(f"`{row_id}` takes no file uploads")
        checks, info = self._validate(kind, row, data, status or row.status, mcmeta)
        info.pop("image", None)
        if len(self.uploads) >= MAX_UPLOADS:
            oldest = min(self.uploads, key=lambda k: self.uploads[k]["at"])
            del self.uploads[oldest]
        upload_id = secrets.token_hex(8)
        self.uploads[upload_id] = {"row": row_id, "data": data, "mcmeta": mcmeta, "at": time.time(), "kind": kind}
        return {"id": upload_id, "checks": checks, "info": info, "ok": textures.passed(checks)}

    def _validate(self, kind, row, data, status, mcmeta):
        if kind == "texture":
            original = self.read(row.file)
            original_meta = self.read(row.file + ".mcmeta")
            return textures.validate(data, status, original=original,
                                     original_mcmeta=original_meta.decode("utf-8", "replace") if original_meta else None,
                                     mcmeta=mcmeta)
        if kind == "model":
            checks, _ = models.validate(data, self.resolver)
            return checks, {"bytes": len(data)}
        if len(data) > SOUND_BYTES:
            return [textures.check("ogg", "Ogg Vorbis", False, f"{len(data)} bytes; the limit is {SOUND_BYTES}")], {}
        ok = data[:4] == b"OggS" and b"vorbis" in data[:64]
        return [textures.check("ogg", "Ogg Vorbis", ok, "Ogg Vorbis stream" if ok else
                               "not an Ogg Vorbis file (Minecraft plays .ogg Vorbis only)")], {"bytes": len(data)}

    def write_upload(self, upload_id, status, origin=None, note=None):
        upload = self.uploads.get(upload_id)
        if upload is None:
            raise EditorError("that upload has expired; choose the file again")
        ledger = self.ledger()
        row = ledger.require(upload["row"])
        if status not in ("placeholder", "draft", "final"):
            raise EditorError("status must be placeholder, draft or final")
        checks, _ = self._validate(upload["kind"], row, upload["data"], status, upload["mcmeta"])
        if not textures.passed(checks):
            failed = "; ".join(f"{c['label']}: {c['detail']}" for c in checks if c["state"] == "fail")
            raise EditorError(f"the upload fails its checks for status {status}: {failed}")
        origin_text = self.origin(origin)
        self._write(row.file, upload["data"], ledger)
        if upload["mcmeta"] is not None:
            self._write(row.file + ".mcmeta", upload["mcmeta"].encode("utf-8"), ledger)
        notes = append_note(row.notes, note) if note and note.strip() else row.notes
        ledger.set_fields(row, origin=origin_text, status=status, notes=notes)
        self._save_ledger(ledger)
        del self.uploads[upload_id]
        return ledger.find(row.path_id)

    def upload_bytes(self, upload_id):
        upload = self.uploads.get(upload_id)
        return upload["data"] if upload else None

    def add_row(self, rel, type_, section, notes):
        """A new row for an asset the user is about to add under the mod's assets/ or pack/."""
        ledger = self.ledger()
        rel = normalize(rel)
        self._target(rel, ledger, allow_new=True)
        try:
            row = ledger.add_row(section, rel, type_, self.human_origin(), "draft", notes or "Added in the content editor.")
        except LedgerError as e:
            raise EditorError(str(e))
        self._save_ledger(ledger)
        return row

    # --- host preview ----------------------------------------------------------

    def host_png(self, stage, mob="mannequin", intensity=1.0, rev="worktree", upload_id=None, glow=True):
        """The mob texture with stage `stage`'s overlay, as a PNG."""
        if mob not in host.MOBS:
            raise EditorError(f"unknown mob '{mob}'")
        _, model_kind, texture = host.MOBS[mob]
        if texture is None:
            base_image = host.mannequin(model_kind)
        else:
            data, _ = self.resolver.read("minecraft", "textures", texture, ".png")
            if data is None:
                raise EditorError("vanilla mob textures need the client resources jar; run ./gradlew build in mod/")
            base_image = png.decode(data)
        upload = self.uploads.get(upload_id) if upload_id else None
        upload_path = None
        if upload:
            upload_path = self.ledger().require(upload["row"]).file

        def pattern(name):
            rel = host.HOST_DIR + name
            if upload_path == rel:
                return png.decode(upload["data"])
            data = self.read(rel, rev)
            if data is None:
                raise EditorError(f"`{rel}` is missing")
            return png.decode(data)

        glow_image = pattern(host.GLOW_PATTERN) if glow and stage >= host.GLOW_STAGE else None
        image = host.composite(base_image, pattern(host.stage_pattern(stage)), glow_image, intensity)
        return png.encode(image)

    # --- batch ---------------------------------------------------------------

    def batch(self):
        """Every row that differs from the base ledger, with what changed, plus the changed files."""
        ledger, base = self.ledger(), self.base_ledger()
        changes = []
        for row in ledger.rows:
            before = base.find(row.path_id)
            if before is not None and (before.origin, before.status, before.notes) == (row.origin, row.status, row.notes):
                continue
            change = {"row": row.to_dict(), "kind": kind_of(row), "new": before is None,
                      "before": before.to_dict() if before else None}
            if kind_of(row) == "lang":
                change["strings"] = self._changed_strings(ledger, row)
            changes.append(change)
        stats = self.workspace.numstat()
        files = [{"status": s, "path": p, "added": stats.get(p, ("", ""))[0], "deleted": stats.get(p, ("", ""))[1]}
                 for s, p in self.workspace.changed_files()]
        return {"changes": changes, "files": files, "branch": self.workspace.branch(),
                "base": self.workspace.base()[:9], "worktree": self.workspace.worktree,
                "uncommitted": self.workspace.uncommitted(), "commits": self.workspace.commits()}

    def _changed_strings(self, ledger, row):
        try:
            current = lang.LangFile.load(self.path(row.file))
        except (OSError, lang.LangError):
            return []
        base_data = show(self.root, self.workspace.base(), row.file)
        base = lang.LangFile(base_data.decode("utf-8")).values if base_data else {}
        out = []
        for key in ledger.keys(row, current.keys()):
            if ledger.owner(row.file, key) is not row:
                continue
            if base.get(key) != current.values[key]:
                out.append({"key": key, "before": base.get(key), "after": current.values[key]})
        return out

    def undo(self, row_id):
        """Put a row and the content it governs back as they are at the base commit."""
        root = self.workspace.require_batch()
        ledger, base = self.ledger(), self.base_ledger()
        row = ledger.require(row_id)
        before = base.find(row_id)
        if kind_of(row) == "lang":
            strings = self._changed_strings(ledger, row)
            if strings:
                current = lang.LangFile.load(self.path(row.file))
                for s in strings:
                    if s["before"] is not None:
                        current = current.with_value(s["key"], s["before"])
                current.save(self._target(row.file, ledger))
        elif kind_of(row) in ("texture", "model", "sound"):
            for rel in (row.file, row.file + ".mcmeta"):
                data = show(root, self.workspace.base(), rel)
                target = os.path.join(root, rel)
                if data is not None:
                    self._write(rel, data, ledger, allow_new=True)
                elif os.path.exists(target) and rel != row.file:
                    os.unlink(target)
        if before is None:
            del ledger.lines[row.index]
            ledger._parse()
        else:
            ledger.lines[row.index] = base.lines[before.index]
            ledger._parse()
        self._save_ledger(ledger)

    def pr_body(self):
        summary = self.batch()
        lines = ["Replaces player-facing content through the local content editor (`tools/content-editor.sh`).", "",
                 "Changed ledger rows:"]
        for c in summary["changes"]:
            row, before = c["row"], c["before"]
            if before is None:
                what = f"new row, {row['origin']}, {row['status']}"
            else:
                parts = []
                if before["status"] != row["status"]:
                    parts.append(f"{before['status']} → {row['status']}")
                if before["origin"] != row["origin"]:
                    parts.append(row["origin"])
                if before["notes"] != row["notes"] and not parts:
                    parts.append("notes updated")
                what = ", ".join(parts) or "updated"
            lines.append(f"- `{row['path']}`: {what}")
        lines += ["", "Checks run before the commit: `tools/check-provenance.sh`, `python3 -I tools/check-docs.py`, "
                      "`git diff --check`.", "",
                  "A batch that touches `en_us.json` or texture references rebases and reruns its checks before merge."]
        return "\n".join(lines) + "\n"

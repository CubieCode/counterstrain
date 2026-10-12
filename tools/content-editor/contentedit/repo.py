"""Git side of the editor: the content worktree and branch, the batch checks, commit and PR.

Edits only ever happen in a dedicated worktree on a `content/<date>-<slug>`
branch made from origin/main. Nothing here pushes to main, merges or publishes.
"""
import datetime
import glob
import os
import re
import subprocess
import tempfile
import time
import zipfile

SLUG = re.compile(r"^[a-z0-9][a-z0-9-]{0,39}$")
BRANCH_PREFIX = "content/"
CHECKS = (
    ("tools/check-provenance.sh", ["tools/check-provenance.sh"]),
    ("python3 -I tools/check-docs.py", ["python3", "-I", "tools/check-docs.py"]),
    ("git diff --check", ["git", "diff", "--cached", "--check"]),
)


class RepoError(RuntimeError):
    pass


def git(cwd, *args, check=True, binary=False, timeout=120):
    out = subprocess.run(["git", "-C", cwd, *args], capture_output=True, text=not binary, timeout=timeout)
    if check and out.returncode != 0:
        err = out.stderr if not binary else out.stderr.decode("utf-8", "replace")
        raise RepoError(f"git {' '.join(args)}: {err.strip()}")
    return out


def show(cwd, rev, path):
    """Bytes of `path` at `rev`, or None when it does not exist there."""
    out = git(cwd, "show", f"{rev}:{path}", check=False, binary=True)
    return out.stdout if out.returncode == 0 else None


class Workspace:
    """The checkout the tool runs from plus, once a batch starts, its content worktree."""

    def __init__(self, tool_root, worktree=None):
        self.tool_root = tool_root
        self.state_dir = os.path.join(tool_root, ".local", "content-editor")
        self.worktree = None
        if worktree:
            self.resume(worktree)
        else:
            saved = self._read_state()
            if saved and os.path.isdir(saved):
                try:
                    self.resume(saved)
                except RepoError:
                    self.worktree = None

    # --- state ---------------------------------------------------------------

    def _state_file(self):
        return os.path.join(self.state_dir, "active")

    def _read_state(self):
        try:
            with open(self._state_file(), encoding="utf-8") as f:
                return f.read().strip()
        except OSError:
            return None

    def _write_state(self):
        os.makedirs(self.state_dir, exist_ok=True)
        with open(self._state_file(), "w", encoding="utf-8") as f:
            f.write(self.worktree or "")

    @property
    def root(self):
        """Where the editor reads from: the content worktree, or the tool's checkout before a batch starts."""
        return self.worktree or self.tool_root

    @property
    def writable(self):
        return self.worktree is not None

    def branch(self):
        if not self.worktree:
            return None
        return git(self.worktree, "rev-parse", "--abbrev-ref", "HEAD").stdout.strip()

    def upstream(self):
        for ref in ("origin/main", "main"):
            if git(self.tool_root, "rev-parse", "--verify", "--quiet", ref, check=False).returncode == 0:
                return ref
        raise RepoError("neither origin/main nor main exists")

    def base(self):
        """The commit the batch branched from; 'on disk' previews read files at this commit."""
        root = self.root
        if not self.worktree:
            return git(root, "rev-parse", "HEAD").stdout.strip()
        return git(root, "merge-base", "HEAD", self.upstream()).stdout.strip()

    def batches(self):
        """Existing content worktrees: [{path, branch}]."""
        out = git(self.tool_root, "worktree", "list", "--porcelain").stdout
        found, path = [], None
        for line in out.splitlines():
            if line.startswith("worktree "):
                path = line[9:]
            elif line.startswith("branch refs/heads/" + BRANCH_PREFIX):
                found.append({"path": path, "branch": line[len("branch refs/heads/"):]})
        return found

    def resume(self, path):
        path = os.path.abspath(path)
        branch = git(path, "rev-parse", "--abbrev-ref", "HEAD").stdout.strip()
        if not branch.startswith(BRANCH_PREFIX):
            raise RepoError(f"{path} is on {branch}, not a {BRANCH_PREFIX}<date>-<slug> branch")
        self.worktree = path
        self._write_state()

    def start(self, slug, today=None, fetch=True):
        """Create the content worktree and branch content/<date>-<slug> from origin/main."""
        if not SLUG.match(slug or ""):
            raise RepoError("the batch name must be 1-40 lower-case letters, digits or hyphens")
        name = f"{(today or datetime.date.today()).isoformat()}-{slug}"
        branch = BRANCH_PREFIX + name
        path = os.path.join(self.state_dir, "worktrees", name)
        if os.path.exists(path):
            # Starting the same batch twice (a double submit, or the same name later that day) resumes it.
            existing = git(path, "rev-parse", "--abbrev-ref", "HEAD", check=False).stdout.strip()
            if existing != branch:
                raise RepoError(f"{path} exists but is not on {branch}; remove it or pick another name")
            self.resume(path)
            return branch
        if fetch:
            git(self.tool_root, "fetch", "--quiet", "origin", "main", check=False, timeout=60)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        git(self.tool_root, "worktree", "add", "--quiet", "-b", branch, path, self.upstream())
        self.resume(path)
        return branch

    # --- batch ---------------------------------------------------------------

    def require_batch(self):
        if not self.worktree:
            raise RepoError("start a batch first: edits go to a content/<date>-<slug> worktree, never this checkout")
        branch = self.branch()
        if not branch.startswith(BRANCH_PREFIX):
            raise RepoError(f"the content worktree is on {branch}; refusing to write")
        return self.worktree

    def changed_files(self):
        """[(status, path)] for every file that differs from the base, committed or not."""
        if not self.worktree:
            return []
        root = self.worktree
        git(root, "add", "--intent-to-add", "--all")
        out = git(root, "diff", "--name-status", self.base()).stdout
        return [tuple(line.split("\t", 1)) for line in out.splitlines() if line.strip()]

    def numstat(self):
        if not self.worktree:
            return {}
        out = git(self.worktree, "diff", "--numstat", self.base()).stdout
        stats = {}
        for line in out.splitlines():
            added, deleted, path = line.split("\t", 2)
            stats[path] = (added, deleted)
        return stats

    def run_checks(self):
        root = self.require_batch()
        git(root, "add", "--all")
        results = []
        for label, cmd in CHECKS:
            start = time.monotonic()
            try:
                out = subprocess.run(cmd, cwd=root, capture_output=True, text=True, timeout=300)
                ok, output = out.returncode == 0, (out.stdout + out.stderr).strip()
            except (OSError, subprocess.TimeoutExpired) as e:
                ok, output = False, str(e)
            results.append({"cmd": label, "ok": ok, "output": output, "seconds": round(time.monotonic() - start, 1)})
        tree = git(root, "write-tree").stdout.strip()
        return results, tree

    def commit(self, message, checked_tree):
        root = self.require_batch()
        message = (message or "").strip()
        if not message or "\n" in message:
            raise RepoError("the commit message must be one non-empty line")
        git(root, "add", "--all")
        tree = git(root, "write-tree").stdout.strip()
        if tree != checked_tree:
            raise RepoError("files changed since the checks ran; run them again")
        if git(root, "diff", "--cached", "--quiet", check=False).returncode == 0:
            raise RepoError("nothing to commit")
        git(root, "commit", "--quiet", "-m", message)
        return git(root, "rev-parse", "--short", "HEAD").stdout.strip()

    def open_pr(self, title, body, draft=True):
        """Push the content branch and open a PR against main. Never pushes main."""
        root = self.require_batch()
        branch = self.branch()
        if branch in ("main", "master") or not branch.startswith(BRANCH_PREFIX):
            raise RepoError(f"refusing to push {branch}")
        if git(root, "status", "--porcelain").stdout.strip():
            raise RepoError("commit the batch before opening a PR")
        git(root, "push", "--quiet", "-u", "origin", f"{branch}:{branch}", timeout=120)
        with tempfile.NamedTemporaryFile("w", suffix=".md", delete=False, encoding="utf-8") as f:
            f.write(body)
            body_file = f.name
        try:
            cmd = ["gh", "pr", "create", "--base", "main", "--head", branch, "--title", title, "--body-file", body_file]
            if draft:
                cmd.append("--draft")
            out = subprocess.run(cmd, cwd=root, capture_output=True, text=True, timeout=120)
        finally:
            os.unlink(body_file)
        if out.returncode != 0:
            raise RepoError(f"gh pr create: {out.stderr.strip()}")
        return out.stdout.strip().splitlines()[-1]


class Resolver:
    """Finds models and textures the 3D preview needs: the repo's assets, then vanilla's.

    Vanilla assets come from the NeoForge client resources jar that ModDevGradle
    leaves under mod/build/moddev/artifacts/; they are read at run time and never
    copied into the repository.
    """

    TREES = ("mod/src/main/resources/assets", "mod/src/generated/resources/assets", "pack/kubejs/assets")

    def __init__(self, workspace):
        self.workspace = workspace
        self._jar = None

    def jar(self):
        if self._jar is None:
            found = sorted(glob.glob(os.path.join(self.workspace.tool_root, "mod", "build", "moddev", "artifacts",
                                                  "*client-extra*.jar")))
            self._jar = zipfile.ZipFile(found[-1]) if found else False
        return self._jar or None

    def repo_path(self, namespace, kind, path, ext):
        for tree in self.TREES:
            rel = f"{tree}/{namespace}/{kind}/{path}{ext}"
            if os.path.isfile(os.path.join(self.workspace.root, rel)):
                return rel
        return None

    def read(self, namespace, kind, path, ext, rev="worktree"):
        """(bytes, source) or (None, None). rev 'base' reads repo files at the batch's base commit."""
        if not re.match(r"^[a-z0-9_.-]+$", namespace) or not re.match(r"^[a-z0-9_./-]+$", path) or ".." in path:
            return None, None
        if rev == "base":
            for tree in self.TREES:
                rel = f"{tree}/{namespace}/{kind}/{path}{ext}"
                data = show(self.workspace.root, self.workspace.base(), rel)
                if data is not None:
                    return data, rel
        else:
            rel = self.repo_path(namespace, kind, path, ext)
            if rel:
                with open(os.path.join(self.workspace.root, rel), "rb") as f:
                    return f.read(), rel
        jar = self.jar()
        if jar is not None:
            name = f"assets/{namespace}/{kind}/{path}{ext}"
            try:
                return jar.read(name), "vanilla:" + name
            except KeyError:
                pass
        return None, None

    def exists(self, namespace, kind, path, ext):
        """True, False, or None when only vanilla could have it and the jar is missing."""
        if self.repo_path(namespace, kind, path, ext):
            return True
        jar = self.jar()
        if jar is None:
            return None if namespace == "minecraft" else False
        try:
            jar.getinfo(f"assets/{namespace}/{kind}/{path}{ext}")
            return True
        except KeyError:
            return False


def split_id(resource_id, default_namespace="minecraft"):
    namespace, _, path = resource_id.rpartition(":")
    return (namespace or default_namespace), path

"""In-game checks from the editor: one harness scene run at a time, in the background.

The run is `tools/run-client-headless.sh --scene items/held --scene-arg item=<id>` in the
editor's workspace (the batch worktree, or the checkout before a batch starts), so it shows
the files written there. The client script takes the shared GPU lock, builds the mod and
quits after the scene's shots; the editor only reads its log and screenshots.
"""
import json
import os
import re
import subprocess
import threading
import time

SCENE = "items/held"
SCENE_FILE = f"mod/src/harness/resources/harness/scenes/{SCENE}.json"
ITEM_ID = re.compile(r"^[a-z0-9_.-]+:[a-z0-9_./-]+$")
SHOT_NAME = re.compile(r"^[a-z0-9_]+$")
TIMEOUT_S = 600
LOG_TAIL = 40


class HarnessError(ValueError):
    """A refused run; the message is shown to the user."""


def _read(path):
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            return f.read()
    except OSError:
        return ""


class HeldRun:
    """The latest held-item run: queued, running, then done or failed. Holds one run at a time."""

    def __init__(self, popen=subprocess.Popen, clock=time.time):
        self.popen, self.clock = popen, clock
        self.lock = threading.Lock()
        self.job = None

    def shots_for(self, root):
        try:
            with open(os.path.join(root, SCENE_FILE), encoding="utf-8") as f:
                return [s["name"] for s in json.load(f)["shots"]]
        except (OSError, ValueError, KeyError, TypeError):
            return None

    def start(self, root, item, row=None):
        if not ITEM_ID.match(item or ""):
            raise HarnessError(f"`{item}` is not an item id")
        script = os.path.join(root, "tools", "run-client-headless.sh")
        shots = self.shots_for(root)
        if shots is None or not os.path.isfile(script):
            raise HarnessError(f"this checkout has no {SCENE} scene yet; update the batch from main first")
        with self.lock:
            if self.job and self.job["state"] in ("starting", "running"):
                raise HarnessError("an in-game run is already going; wait for it to finish")
            log_dir = os.path.join(root, ".local", "content-editor")
            os.makedirs(log_dir, exist_ok=True)
            log_path = os.path.join(log_dir, "held-run.log")
            log = open(log_path, "w", encoding="utf-8")
            try:
                proc = self.popen([script, "--scene", SCENE, "--scene-arg", f"item={item}", "--timeout", str(TIMEOUT_S)],
                                  cwd=root, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
                                  start_new_session=True)
            finally:
                log.close()
            self.job = {"state": "running", "item": item, "row": row, "root": root, "log": log_path,
                        "shots": shots, "started": self.clock(), "ended": None, "code": None, "proc": proc}
            threading.Thread(target=self._wait, args=(self.job,), daemon=True).start()
            return self.status()

    def _wait(self, job):
        code = job["proc"].wait()
        with self.lock:
            job["code"], job["ended"] = code, self.clock()
            job["state"] = "done" if code == 0 else "failed"

    def _run_dir(self, job):
        m = re.search(r"output in (\S+)$", _read(job["log"]), re.M)
        return m.group(1) if m else None

    def status(self):
        job = self.job
        if job is None:
            return {"state": "idle"}
        log = _read(job["log"])
        run_dir = self._run_dir(job)
        console = _read(os.path.join(run_dir, "console.log")) if run_dir else ""
        saved = [s for s in job["shots"] if run_dir and os.path.isfile(os.path.join(run_dir, "screenshots", f"{s}.png"))]
        if job["state"] == "running":
            if not run_dir:
                phase = ("gpu", "Waiting for the GPU: another lane or CI run holds it") if "GPU busy" in log \
                    else ("start", "Starting")
            elif "Scene harness enabled" not in console:
                phase = ("build", "Building Remission and starting Minecraft")
            elif "world loaded" not in console:
                phase = ("world", "Creating the test world")
            else:
                phase = ("shots", f"Taking screenshots ({len(saved)} of {len(job['shots'])})")
        elif job["state"] == "done":
            phase = ("done", "Done")
        else:
            phase = ("failed", "Failed")
        end = job["ended"] or self.clock()
        return {"state": job["state"], "phase": phase[0], "message": phase[1], "item": job["item"], "row": job["row"],
                "started": round(job["started"]),
                "elapsed": round(end - job["started"]), "shots": saved, "expected": job["shots"],
                "log": "\n".join(log.rstrip("\n").split("\n")[-LOG_TAIL:]) if job["state"] == "failed" else None}

    def shot(self, name):
        job = self.job
        if job is None or not SHOT_NAME.match(name or "") or name not in job["shots"]:
            return None
        run_dir = self._run_dir(job)
        if not run_dir:
            return None
        try:
            with open(os.path.join(run_dir, "screenshots", f"{name}.png"), "rb") as f:
                return f.read()
        except OSError:
            return None

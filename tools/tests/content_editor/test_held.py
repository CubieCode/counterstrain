"""The held-item views: which item a row shows, and the in-game run of the items/held scene."""
import json
import os
import time
import unittest

import fixture
from contentedit import harness
from contentedit.service import Editor, EditorError

TEX = fixture.TEX
SCENE = {"args": {"item": "remission:scanner"}, "setup": [],
         "shots": [{"name": n, "camera": {"pos": [0, 0, 0], "yaw": 0, "pitch": 0}} for n in ("first_person", "third_person_front")]}
# Stands in for tools/run-client-headless.sh: logs like it, writes the shots, records its arguments.
FAKE_CLIENT = """#!/bin/sh
run="$PWD/.local/runs/client-test"
mkdir -p "$run/screenshots"
echo "$@" > "$run/args"
echo "[run-client-headless.sh] Launching client at 1920x1080, scene items/held (timeout 600s); output in $run" >&2
if [ -n "$FAKE_FAIL" ]; then echo "error: the game crashed" >&2; exit 1; fi
printf 'Scene harness enabled\\nScene items/held: world loaded, running setup\\n' > "$run/console.log"
for s in first_person third_person_front; do printf 'PNG %s' "$s" > "$run/screenshots/$s.png"; done
"""


def wait(run, timeout=10):
    end = time.time() + timeout
    while run.status()["state"] == "running" and time.time() < end:
        time.sleep(0.05)
    return run.status()


class HeldTest(unittest.TestCase):
    def setUp(self):
        self.repo = fixture.TempRepo()
        self.editor = Editor(self.repo.root, clock=lambda: fixture.TODAY)
        os.environ.pop("FAKE_FAIL", None)

    def tearDown(self):
        os.environ.pop("FAKE_FAIL", None)
        self.repo.cleanup()

    def add_harness(self):
        self.repo.write(harness.SCENE_FILE, json.dumps(SCENE).encode("utf-8"))
        self.repo.write("tools/run-client-headless.sh", FAKE_CLIENT.encode("utf-8"))
        os.chmod(os.path.join(self.repo.root, "tools/run-client-headless.sh"), 0o755)

    def test_a_texture_shows_the_item_model_that_uses_it(self):
        detail = self.editor.row_detail(TEX + "block/stone.png")
        self.assertEqual(detail["held"], {"modelId": "remission:item/thing", "item": "remission:thing"})

    def test_a_block_model_shows_its_block_item(self):
        self.assertIsNone(self.editor._held(["remission:block/stone"]))  # no item/stone model in the fixture
        self.repo.write("mod/src/generated/resources/assets/remission/models/item/stone.json",
                        b'{"parent": "remission:block/stone"}')
        self.assertEqual(self.editor._held(["remission:block/stone"]),
                         {"modelId": "remission:item/stone", "item": "remission:stone"})
        self.assertIsNone(self.editor._held(["remission:entity/host"]))

    def test_a_run_reports_its_phases_and_serves_its_shots(self):
        self.add_harness()
        started = self.editor.held_run_start(TEX + "block/stone.png")
        self.assertEqual((started["state"], started["item"]), ("running", "remission:thing"))
        st = wait(self.editor.held_run)
        self.assertEqual(st["state"], "done")
        self.assertEqual(st["shots"], ["first_person", "third_person_front"])
        self.assertEqual(self.editor.held_run.shot("first_person"), b"PNG first_person")
        for bad in ("third_person_back", "../args", ""):
            self.assertIsNone(self.editor.held_run.shot(bad), bad)
        with open(os.path.join(self.repo.root, ".local/runs/client-test/args"), encoding="utf-8") as f:
            self.assertEqual(f.read().split(), ["--scene", "items/held", "--scene-arg", "item=remission:thing", "--timeout", "600"])

    def test_a_failed_run_keeps_the_end_of_its_output(self):
        self.add_harness()
        os.environ["FAKE_FAIL"] = "1"
        self.editor.held_run_start(TEX + "block/stone.png")
        st = wait(self.editor.held_run)
        self.assertEqual(st["state"], "failed")
        self.assertIn("the game crashed", st["log"])

    def test_runs_are_refused_without_the_scene_or_an_item(self):
        with self.assertRaisesRegex(EditorError, "no items/held scene"):
            self.editor.held_run_start(TEX + "block/stone.png")
        with self.assertRaisesRegex(EditorError, "no item to hold"):
            self.editor.held_run_start(TEX + "entity/host/stage_1.png")
        with self.assertRaises(harness.HarnessError):
            harness.HeldRun().start(self.repo.root, "item=evil,other=1")

    def test_one_run_at_a_time(self):
        self.add_harness()
        run = harness.HeldRun()
        run.job = {"state": "running"}
        with self.assertRaisesRegex(harness.HarnessError, "already going"):
            run.start(self.repo.root, "remission:thing")


if __name__ == "__main__":
    unittest.main()

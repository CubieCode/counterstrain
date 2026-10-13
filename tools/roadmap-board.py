"""Regenerate ROADMAP.md's status board and collapse finished items.

    python3 -I tools/roadmap-board.py [--check]

The board (between the `roadmap-board` markers) is built from each item's
`- Status:` and `- PR:` fields, which stay the source of truth. Items whose
status is `done` have their fields folded into a <details> block under the
heading; any other status unfolds them. Run it after changing a Status line.
--check rewrites nothing and exits 1 if the file is out of date
(tools/check-docs.py runs it).
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PATH = os.path.join(ROOT, "ROADMAP.md")
START, END = "<!-- roadmap-board:start -->", "<!-- roadmap-board:end -->"
PHASE = re.compile(r"^## Phase (\d+): (.+)$")
ITEM = re.compile(r"^### (P\d+\.\d+): (.+)$")
PR_NUMBER = re.compile(r"/pull/(\d+)")


def anchor(heading):
    """GitHub's heading anchor: lowercase, punctuation dropped, spaces to hyphens."""
    return re.sub(r"[^\w\- ]", "", heading.lower()).replace(" ", "-")


def pr_text(pr):
    numbers = PR_NUMBER.findall(pr)
    return ", ".join(f"#{n}" for n in numbers) if numbers else pr


def unfold(lines):
    """Drop the <details> wrapper this script adds, wherever it is."""
    out, i = [], 0
    while i < len(lines):
        if lines[i] == "<details>" and i + 1 < len(lines) and lines[i + 1].startswith("<summary>"):
            i += 3 if i + 2 < len(lines) and lines[i + 2] == "" else 2
            continue
        if lines[i] == "</details>":
            if out and out[-1] == "":
                out.pop()
            i += 1
            continue
        out.append(lines[i])
        i += 1
    return out


def parse(lines):
    """Phases with their items; each item records the line span of its fields."""
    phases, current, item = [], None, None
    for n, line in enumerate(lines):
        if m := PHASE.match(line):
            current = {"num": m.group(1), "title": m.group(2), "items": []}
            phases.append(current)
            item = None
        elif m := ITEM.match(line):
            item = {"id": m.group(1), "title": m.group(2), "heading": line[4:], "line": n,
                    "start": None, "end": None, "status": "", "pr": ""}
            current["items"].append(item)
        elif line.startswith(("## ", "Exit criteria")):
            item = None
        elif item is not None and (line.startswith("- ") or line.startswith("  ")) and line.strip():
            item["start"] = n if item["start"] is None else item["start"]
            item["end"] = n
            if m := re.match(r"^- Status: `([^`]*)`", line):
                item["status"] = m.group(1)
            elif line.startswith("- PR:"):
                item["pr"] = line[5:].strip()
    return phases


def board(phases):
    out = [START, "", "| Phase | Done | Open items |", "| --- | --- | --- |"]
    for phase in phases:
        items = phase["items"]
        done = sum(i["status"] == "done" for i in items)
        open_items = ", ".join(f"[{i['id']}](#{anchor(i['heading'])}) {i['status']}"
                               for i in items if i["status"] != "done")
        heading = f"Phase {phase['num']}: {phase['title']}"
        title = f"[{phase['num']}. {phase['title']}](#{anchor(heading)})"
        out.append(f"| {title} | {done}/{len(items)} | {open_items} |")
    out += ["", "<details>", "<summary>All items</summary>", "",
            "| Item | Status | PR |", "| --- | --- | --- |"]
    for phase in phases:
        for i in phase["items"]:
            out.append(f"| [{i['id']}: {i['title']}](#{anchor(i['heading'])}) | {i['status']} | {pr_text(i['pr'])} |")
    out += ["", "</details>", "", END]
    return out


def render(text):
    lines = unfold(text.split("\n"))
    phases = parse(lines)
    # Fold done items, last first so earlier line numbers stay valid.
    for item in sorted((i for p in phases for i in p["items"]), key=lambda i: -i["line"]):
        if item["status"] != "done" or item["start"] is None:
            continue
        pr = pr_text(item["pr"])
        summary = f"<summary>Done{': ' + pr if pr else ''}</summary>"
        lines[item["end"] + 1:item["end"] + 1] = ["", "</details>"]
        lines[item["start"]:item["start"]] = ["<details>", summary, ""]
    if START in lines and END in lines:
        lines[lines.index(START):lines.index(END) + 1] = board(phases)
    else:
        sys.exit(f"ROADMAP.md: add the {START} and {END} markers where the board goes")
    return "\n".join(lines)


def main():
    with open(PATH, encoding="utf-8") as f:
        text = f.read()
    new = render(text)
    if "--check" in sys.argv:
        if new != text:
            print("ROADMAP.md: status board or folded items out of date; run python3 -I tools/roadmap-board.py")
            sys.exit(1)
        return
    if new != text:
        with open(PATH, "w", encoding="utf-8") as f:
            f.write(new)
        print("ROADMAP.md updated")


if __name__ == "__main__":
    main()

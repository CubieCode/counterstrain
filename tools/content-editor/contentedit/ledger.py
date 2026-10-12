"""Read and edit docs/CONTENT-PROVENANCE.md rows in place.

Only the table rows the editor touches change; every other line, including
section prose and row order, is written back byte for byte. Rows are never
deleted. Text rows (`file:key`, optionally ending in `*`) follow the
most-specific-row rule: an exact `file:key` row overrides a matching `*` row.
"""
import re

from . import files
from .checker import provenance

STATUSES = ("placeholder", "draft", "final", "removed")
# The Type column's values (docs/CONTENT-PROVENANCE.md, "Columns").
TYPES = ("texture", "model", "gui", "particle", "sound", "icon", "lang", "quest", "lore", "journal", "book",
         "store-copy", "store-image", "metadata")
ITEM = re.compile(r"\bP\d{1,2}\.\d{1,2}\b")
GENERATED = "mod/src/generated/"


class LedgerError(ValueError):
    pass


def check_cell(value, name):
    """Cells are split on `|` and live on one line, so neither may appear in them."""
    if "|" in value or "\n" in value or "\r" in value:
        raise LedgerError(f"{name} may not contain '|' or line breaks")
    return value.strip()


class Row:
    def __init__(self, index, section, cells):
        self.index = index  # 0-based line index in the ledger
        self.section = section
        self.path_cell, self.type, self.origin, self.status, self.notes = cells[:5]
        self.path_id = provenance.row_path_id(self.path_cell)
        self.type = self.type.strip("` ").lower()
        self.status = self.status.strip("` ").lower()

    @property
    def line_no(self):
        return self.index + 1

    @property
    def file(self):
        return self.path_id.split(":", 1)[0]

    @property
    def key(self):
        """The text key part of a `file:key` row, or None for a whole-file row."""
        return self.path_id.split(":", 1)[1] if ":" in self.path_id else None

    @property
    def wildcard(self):
        return self.key is not None and self.key.endswith("*")

    def covers(self, key):
        if self.key is None:
            return False
        return key.startswith(self.key[:-1]) if self.wildcard else key == self.key

    @property
    def generated(self):
        return self.file.startswith(GENERATED)

    @property
    def items(self):
        return list(dict.fromkeys(ITEM.findall(self.notes)))

    def to_dict(self):
        return {"id": self.path_id, "path": self.path_id, "file": self.file, "key": self.key,
                "wildcard": self.wildcard, "type": self.type, "origin": self.origin, "status": self.status,
                "notes": self.notes, "section": self.section, "line": self.line_no, "items": self.items,
                "generated": self.generated}


def format_row(path_id, type_, origin, status, notes):
    if type_ not in TYPES:
        raise LedgerError(f"unknown type '{type_}' (use {', '.join(TYPES)})")
    if status not in STATUSES:
        raise LedgerError(f"unknown status '{status}'")
    if "`" in path_id:
        raise LedgerError("a path may not contain backticks")
    return f"| `{check_cell(path_id, 'path')}` | {type_} | {check_cell(origin, 'origin')} | {status} | {check_cell(notes, 'notes')} |"


class Ledger:
    def __init__(self, text):
        self.trailing_newline = text.endswith("\n")
        self.lines = text.splitlines()
        self._parse()

    @classmethod
    def load(cls, path):
        with open(path, encoding="utf-8") as f:
            return cls(f.read())

    def save(self, path):
        files.write(path, self.text().encode("utf-8"))

    def text(self):
        return "\n".join(self.lines) + ("\n" if self.trailing_newline else "")

    def _parse(self):
        self.rows, section = [], ""
        for i, line in enumerate(self.lines):
            if line.startswith("## "):
                section = line[3:].strip()
                continue
            cells = provenance.parse_row(line)  # the CI check's own definition of a row
            if cells is not None:
                self.rows.append(Row(i, section, cells))

    # --- lookups -------------------------------------------------------------

    def find(self, path_id):
        return next((r for r in self.rows if r.path_id == path_id), None)

    def require(self, path_id):
        row = self.find(path_id)
        if row is None:
            raise LedgerError(f"no ledger row for `{path_id}`")
        return row

    def files(self):
        """Every repository file a row names (whole-file or text rows)."""
        return {r.file for r in self.rows}

    def owner(self, file, key):
        """The row that governs `file:key`: an exact row, else the matching `*` row with the longest prefix."""
        best = None
        for row in self.rows:
            if row.file != file or not row.covers(key):
                continue
            if not row.wildcard:
                return row
            if best is None or len(row.key) > len(best.key):
                best = row
        return best

    def keys(self, row, all_keys):
        """Keys a text row matches, in file order."""
        return [k for k in all_keys if row.covers(k)]

    def overrides(self, row):
        """Exact-key rows that override keys of a `*` row."""
        if not row.wildcard:
            return []
        return [r for r in self.rows if r is not row and r.file == row.file and r.key is not None
                and not r.wildcard and row.covers(r.key)]

    # --- edits ---------------------------------------------------------------

    def set_fields(self, row, origin=None, status=None, notes=None):
        if status is not None and status not in STATUSES:
            raise LedgerError(f"unknown status '{status}' (use {', '.join(STATUSES)})")
        origin = row.origin if origin is None else check_cell(origin, "origin")
        status = row.status if status is None else status
        notes = row.notes if notes is None else check_cell(notes, "notes")
        self.lines[row.index] = f"| {row.path_cell} | {row.type} | {origin} | {status} | {notes} |"
        self._parse()
        return self.find(row.path_id)

    def insert_after(self, row, path_id, type_, origin, status, notes):
        """Add a row below `row` and below any exact-key rows already grouped under it."""
        if self.find(path_id) is not None:
            raise LedgerError(f"`{path_id}` already has a row")
        if status not in STATUSES:
            raise LedgerError(f"unknown status '{status}'")
        line = format_row(check_cell(path_id, "path"), type_, check_cell(origin, "origin"), status,
                          check_cell(notes, "notes"))
        at = row.index
        grouped = {r.index for r in self.overrides(row)}
        while at + 1 in grouped:
            at += 1
        self.lines.insert(at + 1, line)
        self._parse()
        return self.find(path_id)

    def add_row(self, section, path_id, type_, origin, status, notes):
        """Append a row to the table in `## section` (for a new asset under the mod's assets/ or pack/)."""
        if self.find(path_id) is not None:
            raise LedgerError(f"`{path_id}` already has a row")
        heading = next((i for i, l in enumerate(self.lines) if l.strip() == f"## {section}"), None)
        if heading is None:
            raise LedgerError(f"no '## {section}' section in the ledger")
        last = None
        for i in range(heading + 1, len(self.lines)):
            line = self.lines[i]
            if line.startswith("## "):
                break
            if line.startswith("|"):
                last = i
        if last is None:
            raise LedgerError(f"section '{section}' has no table")
        self.lines.insert(last + 1, format_row(check_cell(path_id, "path"), type_, check_cell(origin, "origin"),
                                               status, check_cell(notes, "notes")))
        self._parse()
        return self.find(path_id)


def append_note(notes, sentence):
    notes = notes.rstrip()
    sentence = sentence.strip()
    if sentence and sentence in notes:
        return notes
    if notes and not notes.endswith((".", "!", "?")):
        notes += "."
    return f"{notes} {sentence}".strip()

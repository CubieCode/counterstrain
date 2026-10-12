"""Markdown text rows (docs/LORE.md now; store copy, field journal entries and books once their
files exist): normalising a save, warning about broken relative links, and the batch diff."""
import difflib
import os
import posixpath
import re

MAX_BYTES = 256 * 1024
LINK = re.compile(r"!?\[[^\]]*\]\(\s*<?([^)\s>]+)>?(?:\s+\"[^\"]*\")?\s*\)")


def normalize(text):
    """UTF-8 bytes with LF line endings, no trailing whitespace (git diff --check) and one final newline."""
    if not isinstance(text, str):
        raise ValueError("text must be a string")
    if "\x00" in text:
        raise ValueError("text contains a NUL character")
    lines = [line.rstrip() for line in text.replace("\r\n", "\n").replace("\r", "\n").split("\n")]
    body = "\n".join(lines).strip("\n")
    if not body:
        raise ValueError("the file would be empty")
    data = (body + "\n").encode("utf-8")
    if len(data) > MAX_BYTES:
        raise ValueError(f"{len(data)} bytes; the limit is {MAX_BYTES}")
    return data


def link_warnings(text, rel, root):
    """Relative links in `text` (the file at repository path `rel`) whose target file does not exist."""
    warnings = []
    for match in LINK.finditer(text):
        target = match.group(1)
        if re.match(r"^[a-z][a-z0-9+.-]*:", target, re.I) or target.startswith("#"):
            continue  # external links and in-page anchors
        path = target.split("#", 1)[0]
        if not path:
            continue
        resolved = posixpath.normpath(posixpath.join(posixpath.dirname(rel), path))
        if resolved.startswith("../") or not os.path.exists(os.path.join(root, resolved)):
            line = text.count("\n", 0, match.start()) + 1
            warnings.append(f"line {line}: link to `{target}` points at a missing file")
    return warnings


def diff(before, after, rel, limit=400):
    """Unified diff lines between two versions of a text file, trimmed to `limit` lines."""
    lines = list(difflib.unified_diff(before.splitlines(), after.splitlines(), f"main:{rel}", rel, n=2, lineterm=""))
    if len(lines) > limit:
        lines = lines[:limit] + [f"… {len(lines) - limit} more lines"]
    return lines

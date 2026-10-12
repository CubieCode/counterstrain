"""Edit one value in a Minecraft lang JSON file without disturbing anything else.

The file keeps its key order, indentation and line layout: only the edited
value's string literal changes. Saves refuse invalid JSON, duplicate keys and a
changed set of format arguments.
"""
import json
import re

from . import files

# Minecraft's TranslatableContents format pattern: %s, %1$s and the %% escape.
FORMAT = re.compile(r"%(?:(\d+)\$)?([A-Za-z%]|$)")
STRING = r'"(?:[^"\\]|\\.)*"'
ENTRY = re.compile(r'^(\s*)(' + STRING + r')(\s*:\s*)(' + STRING + r')(\s*,?\s*)$')


class LangError(ValueError):
    pass


def format_args(text):
    """{argument index: conversion} for a string, as Minecraft numbers them (sequential %s from 1)."""
    args, next_index = {}, 1
    for match in FORMAT.finditer(text):
        position, conversion = match.groups()
        if conversion == "%":
            continue
        if position:
            index = int(position)
        else:
            index, next_index = next_index, next_index + 1
        args[index] = conversion or "(missing)"
    return args


def args_problem(old, new):
    """None when both strings use the same arguments, else a sentence saying what changed."""
    a, b = format_args(old), format_args(new)
    if a == b:
        return None
    if len(a) != len(b):
        return (f"Arguments changed: {len(a)} in the current string, {len(b)} in the replacement. "
                "Minecraft fills %s in order, so values would land in the wrong place.")
    return (f"Arguments changed: the current string uses {describe(a)}, the replacement uses {describe(b)}. "
            "Use %1$s-style positions to reorder arguments.")


def describe(args):
    return ", ".join(f"%{i}${c}" for i, c in sorted(args.items())) or "none"


def _pairs(text):
    seen = []

    def hook(items):
        keys = [k for k, _ in items]
        dupes = sorted({k for k in keys if keys.count(k) > 1})
        if dupes:
            raise LangError(f"duplicate keys: {', '.join(dupes)}")
        seen.append(items)
        return dict(items)

    try:
        data = json.loads(text, object_pairs_hook=hook)
    except json.JSONDecodeError as e:
        raise LangError(f"invalid JSON: {e}")
    if not isinstance(data, dict) or any(not isinstance(v, str) for v in data.values()):
        raise LangError("a lang file must be one JSON object of string values")
    return seen[-1] if seen else []


class LangFile:
    def __init__(self, text):
        self.text = text
        self.pairs = _pairs(text)
        self.values = dict(self.pairs)
        # Keep the file's escaping style: \u escapes only if the file already uses them for non-ASCII.
        self.ascii = "\\u" in text and not any(ord(c) > 127 for c in text)

    @classmethod
    def load(cls, path):
        with open(path, encoding="utf-8") as f:
            return cls(f.read())

    def keys(self):
        return [k for k, _ in self.pairs]

    def get(self, key):
        if key not in self.values:
            raise LangError(f"no key '{key}'")
        return self.values[key]

    def with_value(self, key, value):
        """A new LangFile with `key` set to `value`; the rest of the text is unchanged."""
        if not isinstance(value, str):
            raise LangError("value must be a string")
        old = self.get(key)
        problem = args_problem(old, value)
        if problem:
            raise LangError(problem)
        lines = self.text.split("\n")
        for i, line in enumerate(lines):
            m = ENTRY.match(line)
            if m and json.loads(m.group(2)) == key:
                literal = json.dumps(value, ensure_ascii=self.ascii)
                lines[i] = m.group(1) + m.group(2) + m.group(3) + literal + m.group(5)
                break
        else:
            raise LangError(f"'{key}' is not on a single line of its own; edit it by hand")
        edited = LangFile("\n".join(lines))
        if edited.keys() != self.keys():
            raise LangError("the edit would change the key order")
        changed = [k for k in self.keys() if self.values[k] != edited.values[k]]
        if changed not in ([key], []):
            raise LangError(f"the edit would change other keys: {', '.join(changed)}")
        return edited

    def save(self, path):
        files.write(path, self.text.encode("utf-8"))

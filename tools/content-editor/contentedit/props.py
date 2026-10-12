"""Edit one value in a Java .properties file (e.g. `mod/gradle.properties:mod_description`) in place.

Only single-line `key=value` (or `key = value`, `key: value`) entries are editable; the
separator, its spacing and every other line stay as they are. Like java.util.Properties (and
Gradle, which reads gradle.properties with it), the file is ISO-8859-1: anything outside ASCII is
written as a \\uXXXX escape, and existing escapes are decoded for display.
"""
import re

from . import files

ENTRY = re.compile(r"^(\s*)([^\s=:#!]+)(\s*[=:]\s*)(.*)$")
MAX_CHARS = 2000


class PropsError(ValueError):
    pass


def _unescape(raw):
    def one(m):
        if m.group(1):
            return chr(int(m.group(1), 16))
        return {"n": "\n", "t": "\t", "r": "\r", "f": "\f"}.get(m.group(2), m.group(2))
    text = re.sub(r"\\(?:u([0-9a-fA-F]{4})|(.))", one, raw)
    return text.encode("utf-16", "surrogatepass").decode("utf-16")  # join escaped surrogate pairs


def _escape(value):
    out = []
    for ch in value.replace("\\", "\\\\").lstrip():
        if ch == "\n":
            out.append("\\n")
        elif ord(ch) < 128:
            out.append(ch)
        else:  # ISO-8859-1 file: non-ASCII as UTF-16 \\u escapes (surrogate pairs above U+FFFF)
            units = ch.encode("utf-16-be")
            out += [f"\\u{int.from_bytes(units[i:i + 2], 'big'):04x}" for i in range(0, len(units), 2)]
    return "".join(out)


class PropsFile:
    def __init__(self, text):
        self.text = text
        self.values, self.lines = {}, {}
        for i, line in enumerate(text.split("\n")):
            if line.lstrip().startswith(("#", "!")):
                continue
            m = ENTRY.match(line)
            if m and not line.rstrip().endswith("\\"):
                self.values[m.group(2)] = _unescape(m.group(4).rstrip())
                self.lines[m.group(2)] = i

    @classmethod
    def load(cls, path):
        with open(path, encoding="iso-8859-1") as f:
            return cls(f.read())

    def keys(self):
        return list(self.values)

    def get(self, key):
        if key not in self.values:
            raise PropsError(f"no single-line key '{key}'")
        return self.values[key]

    def with_value(self, key, value):
        self.get(key)
        if not isinstance(value, str) or not value.strip():
            raise PropsError("the value must be a non-empty string")
        if "\n" in value or "\r" in value or len(value) > MAX_CHARS:
            raise PropsError(f"the value must be one line of at most {MAX_CHARS} characters")
        lines = self.text.split("\n")
        m = ENTRY.match(lines[self.lines[key]])
        lines[self.lines[key]] = m.group(1) + m.group(2) + m.group(3) + _escape(value.strip())
        edited = PropsFile("\n".join(lines))
        changed = [k for k in self.keys() if self.values[k] != edited.values.get(k)]
        if edited.keys() != self.keys() or changed not in ([key], []):
            raise PropsError("the edit would change other entries")
        return edited

    def save(self, path):
        files.write(path, self.text.encode("iso-8859-1"))

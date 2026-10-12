"""Edit one value in a Java .properties file (e.g. `mod/gradle.properties:mod_description`) in place.

Only single-line `key=value` (or `key = value`, `key: value`) entries are editable; the
separator, its spacing and every other line stay as they are.
"""
import re

ENTRY = re.compile(r"^(\s*)([^\s=:#!]+)(\s*[=:]\s*)(.*)$")
MAX_CHARS = 2000


class PropsError(ValueError):
    pass


def _unescape(raw):
    return re.sub(r"\\(.)", lambda m: {"n": "\n", "t": "\t"}.get(m.group(1), m.group(1)), raw)


def _escape(value):
    return value.replace("\\", "\\\\").lstrip().replace("\n", "\\n")


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
        with open(path, encoding="utf-8") as f:
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
        with open(path, "w", encoding="utf-8", newline="") as f:
            f.write(self.text)

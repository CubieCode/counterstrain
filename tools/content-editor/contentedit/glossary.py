"""Soft vocabulary checks for player-facing text against the glossary in docs/DESIGN.md.

AGENTS.md and the glossary say player-facing text uses the glossary's terms and never the
pack's inspirations' or Thaumcraft's. The glossary lists what to say, not what to avoid, so
AVOID below is a short, deliberately conservative heuristic list: a hit is a prompt to look,
never a refusal. Internal Java names may keep "corruption"; this only runs on text rows.
"""
import re

AVOID = (
    (r"\bcorrupt(?:ion|ions|ed|s|ing)?\b", "the Strain, Strain-infected or festering",
     "\"corruption\" is the internal system name; player-facing text says the Strain"),
    (r"\btaint(?:ed|s)?\b", "festering or the Strain", "a Thaumcraft term"),
    (r"\bflux\b", "miasma or the Strain", "a Thaumcraft term"),
    (r"\bvis\b", "(no equivalent; rephrase)", "a Thaumcraft term"),
    (r"\bblight(?:ed|s)?\b", "festering", "not a glossary term for infected land"),
    (r"\bsculk\b", "festering", "festering must never read as sculk"),
    (r"\bplague\b", "the Strain", "not a glossary term"),
    (r"\binfected (?:mobs?|creatures?|animals?)\b", "hosts", "infected mobs are hosts"),
    (r"\b(?:host|mob) variants?\b", "host types", "\"variant\" alone means a resistant mutation of the Strain"),
    (r"\bspreaders?\b", "nidus (plural nidi)", "spread sources are nidi"),
)


def check(text):
    """[{term, line, suggestion, reason}] for each avoided word in `text`."""
    hits = []
    for pattern, suggestion, reason in AVOID:
        for match in re.finditer(pattern, text, re.IGNORECASE):
            hits.append({"term": match.group(0), "line": text.count("\n", 0, match.start()) + 1,
                         "suggestion": suggestion, "reason": reason})
    return sorted(hits, key=lambda h: h["line"])


def section(design_text):
    """The '## Glossary' section of docs/DESIGN.md (for prompts), or ''."""
    match = re.search(r"^## Glossary\n(.*?)(?=^## )", design_text, re.M | re.S)
    return match.group(1).strip() if match else ""


def terms(design_text):
    """Bold terms from the glossary table, e.g. ['the Strain', 'nidus', 'nidi', 'hosts', ...]."""
    out = []
    for line in section(design_text).splitlines():
        if line.startswith("|") and not line.startswith("| ---"):
            cells = line.strip("|").split("|")
            if len(cells) >= 2:
                out += re.findall(r"\*\*([^*]+)\*\*", cells[1])
    return list(dict.fromkeys(out))


def used(text, glossary_terms):
    low = text.lower()
    return [t for t in glossary_terms if re.search(r"\b" + re.escape(t.lower()) + r"\b", low)]

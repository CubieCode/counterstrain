"""Optional advisory review of player-facing text by a local model through Ollama's HTTP API.

The model only points at problems (off-glossary vocabulary, tone against the row's notes,
unclear wording); it is told never to rewrite, and the editor never puts model text into a
file. That keeps provenance honest: anything a person saves is recorded as theirs. Standard
library only (urllib); nothing happens unless Ollama answers and has the model.
"""
import json
import time
import urllib.error
import urllib.request

PROMPT = """You review player-facing text for Remission, a Minecraft mod, and its modpack Counterstrain.
Check the text against the glossary and the writer's brief below. Report only real problems:
words that should be glossary terms, terms used with the wrong meaning, wording that clashes with the
brief, or unclear sentences. Never rewrite the text and never propose replacement sentences; describe
each problem in one short sentence. Quote the exact words from the text that each problem is about.
Reply with JSON only: {"issues": [{"quote": "...", "problem": "..."}]}. Reply {"issues": []} if the text is fine.

GLOSSARY
{glossary}

BRIEF (the ledger row's notes)
{notes}

TEXT ({kind})
{text}"""


class Reviewer:
    def __init__(self, url="http://127.0.0.1:11434", model="qwen2.5:14b", enabled=True):
        self.url, self.model, self.enabled = url.rstrip("/"), model, enabled
        self._available, self._checked = None, 0.0

    def available(self):
        """True when Ollama answers and has the model; cached for a minute."""
        if not self.enabled:
            return False
        if self._available is not None and time.monotonic() - self._checked < 60:
            return self._available
        try:
            with urllib.request.urlopen(f"{self.url}/api/tags", timeout=1.5) as res:
                names = {m.get("name") for m in json.load(res).get("models", [])}
            self._available = self.model in names or f"{self.model}:latest" in names
        except (OSError, ValueError):
            self._available = False
        self._checked = time.monotonic()
        return self._available

    def review(self, text, kind, notes, glossary):
        """[{quote, problem}] (quotes not found in the text are dropped), or raise RuntimeError."""
        if not self.available():
            raise RuntimeError(f"no local reviewer: Ollama at {self.url} with {self.model} is not reachable")
        prompt = PROMPT.replace("{glossary}", glossary[:8000]).replace("{notes}", notes or "(none)") \
            .replace("{kind}", kind).replace("{text}", text[:12000])
        body = json.dumps({"model": self.model, "stream": False, "format": "json", "keep_alive": "1m",
                           "options": {"temperature": 0.2, "num_ctx": 8192},
                           "messages": [{"role": "user", "content": prompt}]}).encode("utf-8")
        req = urllib.request.Request(f"{self.url}/api/chat", data=body, headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=180) as res:
                content = json.load(res)["message"]["content"]
        except (OSError, ValueError, KeyError) as e:
            raise RuntimeError(f"the reviewer failed: {e}")
        try:
            issues = json.loads(content).get("issues", [])
        except (ValueError, AttributeError):
            raise RuntimeError("the reviewer did not reply with JSON")
        low = text.lower()
        return [{"quote": str(i.get("quote", ""))[:300], "problem": str(i.get("problem", ""))[:500]}
                for i in issues if isinstance(i, dict) and str(i.get("quote", "")).strip()
                and str(i.get("quote")).lower() in low][:20]

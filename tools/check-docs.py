"""Check that docs, roadmap, READMEs and skills still match the repository.

    python3 -I tools/check-docs.py [--verbose]

Mechanical checks only (standard library, no network): versions quoted in
docs against build files, relative links, repository paths in backticks,
Remission class names, Gradle properties, tools and workflows mentioned,
skill metadata, and ROADMAP structure. Exit 1 on any failure. The docs-sync
skill covers the judgment part: whether the prose still describes the code.
"""
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
VERBOSE = "--verbose" in sys.argv
failures = []


def fail(where, message):
    failures.append(f"{where}: {message}")


def read(rel):
    with open(os.path.join(ROOT, rel), encoding="utf-8") as f:
        return f.read()


def exists(rel):
    return os.path.lexists(os.path.join(ROOT, rel))


def ignored(rel):
    """Gitignored runtime output (mod/run/, .local/, build/) is documented but never committed."""
    # Try the directory form too: `mod/run/` only matches directories, which may not exist yet.
    return any(subprocess.run(["git", "-C", ROOT, "check-ignore", "-q", "--no-index", candidate]).returncode == 0
               for candidate in (rel, rel.rstrip("/") + "/"))


def tracked_files():
    out = subprocess.run(["git", "-C", ROOT, "ls-files", "--cached", "--others", "--exclude-standard"], capture_output=True, text=True, check=True).stdout
    return [p for p in out.splitlines() if exists(p)]


def props(rel):
    values = {}
    for line in read(rel).splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, _, value = line.partition("=")
            values[key.strip()] = value.strip()
    return values


FILES = tracked_files()
# Vendored skills are audited separately (VENDORED.md); only project docs here.
DOCS = [p for p in FILES if p.endswith(".md") and not p.startswith(".agents/skills/minecraft-")
        and not p.startswith("pack/mods/")]
JAVA = [p for p in FILES if p.startswith("mod/src/") and p.endswith(".java")]
CLASSES = {os.path.basename(p)[:-5] for p in JAVA}
GRADLE = props("mod/gradle.properties")
# Optional -P properties the build reads without a gradle.properties default.
OPTIONAL_GRADLE = set(re.findall(r"findProperty\(['\"](\w+)['\"]\)", read("mod/build.gradle")))
SKILLS = sorted(d for d in os.listdir(os.path.join(ROOT, ".agents/skills"))
                if os.path.isfile(os.path.join(ROOT, ".agents/skills", d, "SKILL.md")))
PROJECT_SKILLS = [s for s in SKILLS if not s.startswith("minecraft-")]


def check_versions():
    readme = read("README.md")
    pack = read("pack/pack.toml")
    mdg = re.search(r"id 'net\.neoforged\.moddev' version '([^']+)'", read("mod/build.gradle")).group(1)
    wrapper = re.search(r"gradle-([0-9.]+)-bin\.zip", read("mod/gradle/wrapper/gradle-wrapper.properties")).group(1)
    sdk_java = re.search(r"^java=(.+)$", read(".sdkmanrc"), re.M).group(1)
    pack_neo = re.search(r'^neoforge = "([^"]+)"', pack, re.M).group(1)
    pack_mc = re.search(r'^minecraft = "([^"]+)"', pack, re.M).group(1)

    if pack_neo != GRADLE["neo_version"]:
        fail("pack/pack.toml", f"neoforge {pack_neo} != mod neo_version {GRADLE['neo_version']}")
    if pack_mc != GRADLE["minecraft_version"]:
        fail("pack/pack.toml", f"minecraft {pack_mc} != mod minecraft_version {GRADLE['minecraft_version']}")
    expected = {
        "NeoForge": GRADLE["neo_version"],
        "ModDevGradle": mdg,
        "Gradle wrapper": wrapper,
        "Parchment": GRADLE["parchment_mappings_version"],
        "Minecraft": GRADLE["minecraft_version"],
    }
    for label, version in expected.items():
        if version not in readme:
            fail("README.md", f"{label} version {version} not mentioned")
    for doc in ("docs/DEV-ENVIRONMENT.md",):
        if sdk_java not in read(doc):
            fail(doc, f".sdkmanrc java {sdk_java} not mentioned")
    if f"license=\"${{mod_license}}\"" in read("mod/src/main/templates/META-INF/neoforge.mods.toml"):
        if GRADLE["mod_license"] == "MIT" and "MIT" not in read("LICENSE").splitlines()[0]:
            fail("LICENSE", "mod_license is MIT but LICENSE is not the MIT text")

    # Every version in the README stack table that names a pack mod must match the pack.
    pack_mods = {}
    pack_files = {}
    for path in FILES:
        if path.startswith("pack/mods/") and path.endswith(".pw.toml"):
            text = read(path)
            name = re.search(r'^name = "([^"]+)"', text, re.M).group(1)
            filename = re.search(r'^filename = "([^"]+)"', text, re.M).group(1)
            pack_mods[name] = filename
            pack_files[os.path.basename(path)[:-len(".pw.toml")]] = filename
    row = re.search(r"^\| Companion mods \| (.+?) \|$", readme, re.M)
    if not row:
        fail("README.md", "missing 'Companion mods' row in the stack table")
    else:
        for entry in re.split(r",\s*|\s*\+\s*", row.group(1)):
            entry = entry.strip()
            if entry and not any(entry.lower() in name.lower() or name.lower() in entry.lower() for name in pack_mods):
                fail("README.md", f"companion mod '{entry}' is not in pack/mods")

    # Versions quoted anywhere in current docs (history files are exempt).
    current = [d for d in DOCS if not d.endswith(("CHANGELOG.md", "VENDORED.md"))]
    patterns = [
        (r"\b21\.1\.(\d{2,3})\b", GRADLE["neo_version"].split(".")[2], "NeoForge"),
        (r"(?:ModDevGradle|MDG) (2\.0\.\d+)", mdg, "ModDevGradle"),
        (r"(?<![A-Za-z])Gradle (\d+\.\d+\.\d+)", wrapper, "Gradle wrapper"),
        (r"Parchment (\d{4}\.\d{2}\.\d{2})", GRADLE["parchment_mappings_version"], "Parchment"),
    ]
    for doc in current:
        for line_no, line in enumerate(read(doc).splitlines(), 1):
            if "MDK" in line:  # comparisons with the upstream template's versions
                continue
            for pattern, want, label in patterns:
                for got in re.findall(pattern, line):
                    if got != want:
                        fail(f"{doc}:{line_no}", f"{label} version {got} != {want} in the build")
            for name, slug in MOD_NAMES.items():
                for got in re.findall(re.escape(name) + r" (\d[\w.+-]*\d)", line):
                    if slug in pack_files and got not in pack_files[slug]:
                        fail(f"{doc}:{line_no}", f"{name} {got} does not match pack/mods/{slug}.pw.toml ({pack_files[slug]})")


# Display names used in docs -> packwiz slugs, for version checks.
MOD_NAMES = {"KubeJS": "kubejs", "ProbeJS": "probejs", "FTB Quests": "ftb-quests-forge", "JEI": "jei",
             "EMI": "emi", "Mekanism": "mekanism", "AE2": "ae2", "Create": "create",
             "Ars Nouveau": "ars-nouveau", "JourneyMap": "journeymap"}

LINK = re.compile(r"\[[^\]]*\]\(([^)\s]+)\)")
TICK = re.compile(r"`([^`\n]+)`")
PATH_ROOTS = ("mod/", "pack/", "tools/", "docs/", ".agents/", ".github/", ".claude/")
PLACEHOLDER = re.compile(r"[<>*{}$|]|\.\.\.")


def check_links_and_paths():
    for doc in DOCS:
        text = read(doc)
        base = os.path.dirname(doc)
        for target in LINK.findall(text):
            if re.match(r"^[a-z]+:", target) or target.startswith("#"):
                continue
            rel = os.path.normpath(os.path.join(base, target.split("#")[0]))
            if not exists(rel):
                fail(doc, f"broken link {target}")
        for line_no, line in enumerate(text.splitlines(), 1):
            planned = "planned" in line.lower() or "phase" in line.lower() and "will" in line.lower()
            for token in TICK.findall(line):
                token = token.strip()
                first = token.split()[0] if token.split() else ""
                # Repository paths: tools/x.sh, docs/X.md, mod/..., etc.
                if first.startswith(PATH_ROOTS) and not PLACEHOLDER.search(first):
                    path = first.rstrip("/.,:;")
                    if not exists(path) and not exists(path.split(":")[0]) and not ignored(path) and not planned:
                        fail(f"{doc}:{line_no}", f"path `{path}` does not exist")
                # Remission classes, e.g. `RemissionRegistries.register(modBus)`.
                for cls in re.findall(r"\b(Remission[A-Z]\w*)", token):
                    if cls not in CLASSES and not planned:
                        fail(f"{doc}:{line_no}", f"class {cls} not found in mod/src")
                # Gradle properties passed as -Pname or named directly.
                for prop in re.findall(r"(?<!\w)-P(\w+)", token) + re.findall(r"^(dev_\w+|mod_\w+|neo_version)$", token):
                    if prop not in GRADLE and prop not in OPTIONAL_GRADLE:
                        fail(f"{doc}:{line_no}", f"Gradle property {prop} not in mod/gradle.properties")
                # Workflows by file name.
                for wf in re.findall(r"\b([\w-]+\.yml)\b", token):
                    if wf not in ("orca.yaml",) and not exists(f".github/workflows/{wf}") and not exists(wf):
                        fail(f"{doc}:{line_no}", f"workflow {wf} not found")


def check_tools_documented():
    readme = read("README.md") + read("AGENTS.md") + read("docs/DEV-ENVIRONMENT.md")
    for path in FILES:
        if path.startswith("tools/") and path != "tools/lib.sh" and path.endswith((".sh", ".py")):
            name = os.path.basename(path)
            if name not in readme and not any(name in read(s) for s in DOCS if s.startswith(".agents/")):
                fail(path, "not mentioned in README, AGENTS, DEV-ENVIRONMENT or any skill")
    for wf in os.listdir(os.path.join(ROOT, ".github/workflows")):
        if not any(wf in read(d) for d in DOCS):
            fail(f".github/workflows/{wf}", "not mentioned in any doc or skill")


def check_skills():
    link = os.path.join(ROOT, ".claude/skills")
    if not os.path.islink(link) or os.readlink(link) != "../.agents/skills":
        fail(".claude/skills", "must be a symlink to ../.agents/skills")
    for skill in SKILLS:
        text = read(f".agents/skills/{skill}/SKILL.md")
        meta = re.match(r"^---\n(.*?)\n---\n", text, re.S)
        if not meta:
            fail(skill, "SKILL.md has no frontmatter")
            continue
        name = re.search(r"^name:\s*(.+)$", meta.group(1), re.M)
        if not name or name.group(1).strip() != skill:
            fail(skill, "frontmatter name does not match the folder")
        if not re.search(r"^description:\s*\S", meta.group(1), re.M):
            fail(skill, "frontmatter description missing")
    vendored = read(".agents/skills/VENDORED.md")
    for skill in PROJECT_SKILLS:
        if f"`{skill}`" not in vendored:
            fail(".agents/skills/VENDORED.md", f"project skill {skill} missing from the precedence list")
    for skill in re.findall(r"`(minecraft-[\w-]+)`", vendored.split("## Not vendored")[0]):
        if skill not in SKILLS and skill != "minecraft-codex-skills":
            fail(".agents/skills/VENDORED.md", f"lists {skill}, which is not vendored")
    for doc in DOCS:
        for ref in re.findall(r"\(\.?\.?/?\.agents/skills/([\w-]+)/SKILL\.md\)", read(doc)):
            if ref not in SKILLS:
                fail(doc, f"links missing skill {ref}")


ITEM = re.compile(r"^### (P\d+\.\d+): (.+)$", re.M)
STATUSES = {"todo", "in-progress", "review", "done", "blocked"}


def check_roadmap():
    text = read("ROADMAP.md")
    items = ITEM.findall(text)
    ids = [i for i, _ in items]
    for dup in {i for i in ids if ids.count(i) > 1}:
        fail("ROADMAP.md", f"duplicate item {dup}")
    blocks = re.split(r"^### ", text, flags=re.M)[1:]
    for block in blocks:
        item_id = block.split(":")[0]
        status = re.search(r"^- Status: `([^`]+)`", block, re.M)
        if not status or status.group(1) not in STATUSES:
            fail("ROADMAP.md", f"{item_id} has a missing or invalid Status")
            continue
        pr = re.search(r"^- PR:(.*)$", block, re.M)
        if status.group(1) in ("review", "done") and (not pr or not pr.group(1).strip()):
            fail("ROADMAP.md", f"{item_id} is {status.group(1)} without a PR")
        deps = re.search(r"^- Depends on:(.*)$", block, re.M)
        for dep in re.findall(r"\bP\d+\.\d+\b", deps.group(1) if deps else ""):
            if dep not in ids:
                fail("ROADMAP.md", f"{item_id} depends on unknown item {dep}")
        skills = re.search(r"^- Skills:(.*)$", block, re.M)
        for skill in re.findall(r"[\w-]+", skills.group(1) if skills else ""):
            if skill not in SKILLS:
                fail("ROADMAP.md", f"{item_id} lists unknown skill {skill}")
    waves = text.split("## Safe waves", 1)[1].split("\n## ", 1)[0] if "## Safe waves" in text else ""
    for ref in re.findall(r"\bP\d+\.\d+\b", waves):
        if ref not in ids:
            fail("ROADMAP.md", f"Safe waves mention unknown item {ref}")
    for item_id in ids:
        if not item_id.startswith("P0.") and item_id not in waves:
            fail("ROADMAP.md", f"{item_id} is not in any safe wave")


for check in (check_versions, check_links_and_paths, check_tools_documented, check_skills, check_roadmap):
    before = len(failures)
    check()
    if VERBOSE:
        print(f"{check.__name__}: {len(failures) - before} problem(s)")

if failures:
    print(f"check-docs: {len(failures)} problem(s)")
    for line in failures:
        print(f"  {line}")
    sys.exit(1)
print(f"check-docs: ok ({len(DOCS)} docs, {len(SKILLS)} skills, roadmap and versions)")

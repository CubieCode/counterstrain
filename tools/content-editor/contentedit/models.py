"""Checks for hand-authored block and item model JSON (P1.5): it must parse, and every
texture it names must exist in the repository or in vanilla."""
import json

from .repo import split_id

MAX_BYTES = 256 * 1024


def check(id_, label, state, detail):
    return {"id": id_, "label": label, "state": state, "detail": detail}


def texture_refs(model):
    """[(variable, resource id)] for texture values that are not #references."""
    textures = model.get("textures") if isinstance(model, dict) else None
    if not isinstance(textures, dict):
        return []
    refs = []
    for var, value in textures.items():
        if isinstance(value, dict):
            value = value.get("sprite")
        if isinstance(value, str) and not value.startswith("#"):
            refs.append((var, value))
    return refs


def validate(data, resolver):
    """(checks, model or None). A write needs no check in state 'fail'."""
    if len(data) > MAX_BYTES:
        return [check("json", "Valid JSON", "fail", f"{len(data)} bytes; the limit is {MAX_BYTES}")], None
    try:
        model = json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as e:
        return [check("json", "Valid JSON", "fail", str(e))], None
    if not isinstance(model, dict):
        return [check("json", "Valid JSON", "fail", "a model must be a JSON object")], None
    checks = [check("json", "Valid JSON", "pass", f"{len(data)} B")]
    if "textures" in model and not isinstance(model["textures"], dict):
        checks.append(check("textures", "Textures", "fail", "\"textures\" must be an object of name → texture"))
        return checks, model
    if "elements" in model and not isinstance(model["elements"], list):
        checks.append(check("elements", "Elements", "fail", "\"elements\" must be a list"))
        return checks, model
    missing, unknown, found = [], [], []
    for var, ref in texture_refs(model):
        namespace, path = split_id(ref)
        exists = resolver.exists(namespace, "textures", path, ".png")
        (found if exists else unknown if exists is None else missing).append(f"{var} → {namespace}:{path}")
    if missing:
        checks.append(check("textures", "Textures", "fail", "missing: " + "; ".join(missing)))
    elif unknown:
        checks.append(check("textures", "Textures", "info",
                            "cannot verify vanilla textures without the client resources jar (run ./gradlew build in "
                            "mod/): " + "; ".join(unknown)))
    else:
        checks.append(check("textures", "Textures", "pass", "; ".join(found) or "names no textures"))
    parent = model.get("parent")
    if isinstance(parent, str) and not parent.startswith("builtin/"):
        namespace, path = split_id(parent)
        exists = resolver.exists(namespace, "models", path, ".json")
        state = "pass" if exists else "info" if exists is None else "fail"
        checks.append(check("parent", "Parent", state, f"{namespace}:{path}" + ("" if exists else " not found")))
    return checks, model

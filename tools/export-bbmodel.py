#!/usr/bin/env python3
"""Export a GeckoLib Blockbench project (.bbmodel) to GeckoLib geometry (.geo.json) and animations.

Usage: python3 -I tools/export-bbmodel.py MODEL.bbmodel [--geo OUT.geo.json] [--identifier geometry.name]
                                          [--animations OUT.animation.json]

A stand-in for Blockbench's own export (File > Export > Export GeckoLib Model) on machines without Blockbench, such as
foundry. It applies the Bedrock geometry conventions Blockbench's exporter applies and GeckoLib's loader undoes: X is
mirrored (a cube's origin x is minus its far x edge, pivots' x are negated) and bone and cube rotations about X and Y
are negated, while Z rotations are kept. Box UV cubes keep their offset and mirror flag; per-face UV cubes keep their
face rectangles. Elements and groups with export off are skipped. Animations are written as Blockbench writes them
(format 1.8.0, keyframe vectors with their lerp mode): Blockbench stores keyframes in the exported convention already,
so their values are copied unchanged.

Standard library only. Exit 0 on success, 1 on an input it cannot convert.
"""
import argparse
import json
import sys


def mirrored_pivot(point):
    return [-point[0], point[1], point[2]]


def exported_rotation(rotation):
    return [-rotation[0], -rotation[1], rotation[2]]


def number(value):
    """Whole numbers as ints so the output reads like Blockbench's."""
    value = round(float(value), 6)
    return int(value) if value == int(value) else value


def numbers(values):
    return [number(v) for v in values]


def compile_cube(element, bone_mirror):
    size = [element["to"][i] - element["from"][i] for i in range(3)]
    origin = list(element["from"])
    origin[0] = -(origin[0] + size[0])
    cube = {"origin": numbers(origin), "size": numbers(size)}
    if element.get("inflate"):
        cube["inflate"] = number(element["inflate"])
    rotation = element.get("rotation", [0, 0, 0])
    if any(rotation):
        cube["pivot"] = numbers(mirrored_pivot(element.get("origin", [0, 0, 0])))
        cube["rotation"] = numbers(exported_rotation(rotation))
    if element.get("box_uv", True):
        cube["uv"] = numbers(element.get("uv_offset", [0, 0]))
        mirror = bool(element.get("mirror_uv", False))
        if mirror != bone_mirror:
            cube["mirror"] = mirror
    else:
        faces = {}
        for face, data in element.get("faces", {}).items():
            if data.get("texture") is None or "uv" not in data:
                continue
            u0, v0, u1, v1 = data["uv"]
            entry = {"uv": numbers([u0, v0]), "uv_size": numbers([u1 - u0, v1 - v0])}
            faces[face] = entry
        cube["uv"] = faces
    return cube


def export(project, identifier):
    elements = {e["uuid"]: e for e in project["elements"] if e.get("type", "cube") == "cube"}
    resolution = project.get("resolution", {"width": 16, "height": 16})
    bones = []

    def walk(group, parent):
        if not group.get("export", True):
            return
        bone = {"name": group["name"]}
        if parent is not None:
            bone["parent"] = parent
        bone["pivot"] = numbers(mirrored_pivot(group.get("origin", [0, 0, 0])))
        rotation = group.get("rotation", [0, 0, 0])
        if any(rotation):
            bone["rotation"] = numbers(exported_rotation(rotation))
        mirror = bool(group.get("mirror_uv", False))
        if mirror:
            bone["mirror"] = True
        cubes = []
        children = []
        for child in group.get("children", []):
            if isinstance(child, str):
                element = elements.get(child)
                if element is not None and element.get("export", True):
                    cubes.append(compile_cube(element, mirror))
            elif isinstance(child, dict):
                children.append(child)
        if cubes:
            bone["cubes"] = cubes
        bones.append(bone)
        for child in children:
            walk(child, group["name"])

    for top in project["outliner"]:
        if isinstance(top, dict):
            walk(top, None)
        else:
            raise ValueError("cube outside any bone: GeckoLib needs every cube in a bone")

    names = [b["name"] for b in bones]
    duplicates = sorted({n for n in names if names.count(n) > 1})
    if duplicates:
        raise ValueError("duplicate bone names: " + ", ".join(duplicates))
    return {
        "format_version": "1.12.0",
        "minecraft:geometry": [{
            "description": {
                "identifier": identifier,
                "texture_width": resolution["width"],
                "texture_height": resolution["height"],
            },
            "bones": bones,
        }],
    }


def export_animations(project):
    animations = {}
    for animation in project.get("animations", []):
        out = {}
        if animation.get("loop") == "loop":
            out["loop"] = True
        elif animation.get("loop") == "hold":
            out["loop"] = "hold_on_last_frame"
        else:
            out["loop"] = False
        out["animation_length"] = number(animation.get("length", 0))
        bones = {}
        for animator in animation.get("animators", {}).values():
            if animator.get("type", "bone") != "bone":
                continue
            channels = {}
            for keyframe in sorted(animator.get("keyframes", []), key=lambda k: k["time"]):
                channel = keyframe["channel"]
                if channel not in ("rotation", "position", "scale"):
                    continue
                point = keyframe["data_points"][0]
                vector = [float(point[axis]) for axis in "xyz"]
                entry = {"vector": vector}
                if keyframe.get("interpolation", "linear") != "linear":
                    entry["lerp_mode"] = keyframe["interpolation"]
                channels.setdefault(channel, {})[f"{number(keyframe['time'])}"] = entry
            if channels:
                bones[animator["name"]] = channels
        out["bones"] = bones
        animations[animation["name"]] = out
    return {"format_version": "1.8.0", "animations": animations}


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("model")
    parser.add_argument("--geo", help="write the geometry here")
    parser.add_argument("--identifier", default=None, help="geometry identifier (default geometry.<model_identifier>)")
    parser.add_argument("--animations", help="write the project's animations here")
    args = parser.parse_args()
    if not args.geo and not args.animations:
        parser.error("nothing to write: give --geo, --animations or both")
    with open(args.model, encoding="utf-8") as f:
        project = json.load(f)
    if project.get("meta", {}).get("model_format") != "geckolib_model":
        print(f"{args.model}: not a GeckoLib project (meta.model_format)", file=sys.stderr)
        return 1
    if args.geo:
        identifier = args.identifier or "geometry." + (project.get("model_identifier") or "unknown")
        try:
            geometry = export(project, identifier)
        except ValueError as error:
            print(f"{args.model}: {error}", file=sys.stderr)
            return 1
        write(args.geo, geometry)
        bones = geometry["minecraft:geometry"][0]["bones"]
        print(f"{args.geo}: {len(bones)} bones, {sum(len(b.get('cubes', [])) for b in bones)} cubes")
    if args.animations:
        animations = export_animations(project)
        write(args.animations, animations)
        print(f"{args.animations}: {len(animations['animations'])} animations")
    return 0


def write(path, data):
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=1)
        f.write("\n")


if __name__ == "__main__":
    sys.exit(main())

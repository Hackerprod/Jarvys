"""Bounded original .riv writer proof, not an RML compiler or production SDK.

Format and type/property IDs are based on Rive's public format documentation
and MIT runtime headers/tests. See SOURCES.md and LICENSE-RIVE-MIT.txt.
No Rive CLI/editor, private code, or .riv input is used by this writer.
"""
from __future__ import annotations
import json, math, struct
from pathlib import Path

MAX_NODES = 96
MAX_TRACKS = 64
MAX_KEYS = 64
MAX_BYTES = 65536
TRANSFORMS = {"x": 13, "y": 14, "rotation": 15, "scaleX": 16, "scaleY": 17}

def uint(value):
    if type(value) is not int or not 0 <= value <= 0xffffffff:
        raise ValueError("unsigned integer outside uint32")
    out = bytearray()
    while value >= 128:
        out.append((value & 127) | 128)
        value >>= 7
    out.append(value)
    return bytes(out)

def number(value, low=-2048, high=2048):
    if type(value) not in (int, float) or not math.isfinite(value) or not low <= value <= high:
        raise ValueError("non-finite or out-of-bounds number")
    return float(value)

def text(value):
    if type(value) is not str or not 1 <= len(value.encode("utf-8")) <= 80:
        raise ValueError("invalid bounded name")
    data = value.encode("utf-8")
    return uint(len(data)) + data

class Binary:
    def __init__(self):
        self.data = bytearray()
        self.types = {}

    def obj(self, type_id, **props):
        self.data += uint(type_id)
        for key, (kind, value) in props.items():
            key = int(key)
            wire_type = {"u": 0, "b": 0, "s": 1, "f": 2, "c": 3}[kind]
            if key in self.types and self.types[key] != wire_type:
                raise ValueError("inconsistent property type")
            self.types[key] = wire_type
            self.data += uint(key)
            if kind == "u": self.data += uint(value)
            elif kind == "b":
                if type(value) is not bool: raise ValueError("expected boolean")
                self.data += bytes([value])
            elif kind == "s": self.data += text(value)
            elif kind == "f": self.data += struct.pack("<f", number(value))
            elif kind == "c":
                uint(value)
                self.data += struct.pack("<I", value)
        self.data += b"\0"

    def finish(self, major=7, minor=4):
        if (major, minor) != (7, 4):
            raise ValueError("proof writer supports only format 7.4")
        keys = sorted(self.types)
        toc = b"".join(uint(key) for key in keys) + b"\0"
        # Public RivBytes helper / RuntimeHeader: four two-bit fields per uint32.
        for start in range(0, len(keys), 4):
            packed = sum(self.types[key] << (2 * i) for i, key in enumerate(keys[start:start+4]))
            toc += struct.pack("<I", packed)
        output = b"RIVE" + uint(major) + uint(minor) + uint(0) + toc + self.data
        if len(output) > MAX_BYTES: raise ValueError("output byte budget exceeded")
        return output

def props(**values):
    return {str(k): v for k, v in values.items()}

def validate(scene):
    if type(scene) is not dict: raise ValueError("scene must be an object")
    if set(scene) != {"name", "nodes", "animations"}: raise ValueError("unknown scene keys")
    text(scene["name"])
    nodes = scene["nodes"]
    if type(nodes) is not list: raise ValueError("nodes must be an array")
    if not 1 <= len(nodes) <= MAX_NODES: raise ValueError("node count budget")
    names = {"Artboard"}
    depth = {"Artboard": 0}
    for node in nodes:
        if type(node) is not dict: raise ValueError("node must be an object")
        kind = node.get("kind")
        allowed = {"kind", "name", "parent", *TRANSFORMS}
        if kind in ("ellipse", "rectangle"):
            allowed |= {"width", "height", "color"}
            if kind == "rectangle": allowed.add("radius")
        elif kind != "group": raise ValueError("unsupported geometry")
        if set(node) - allowed: raise ValueError("unknown node key")
        text(node["name"])
        if node["name"] in names: raise ValueError("duplicate node")
        if node.get("parent", "Artboard") not in names: raise ValueError("parent must exist before child")
        parent = node.get("parent", "Artboard")
        depth[node["name"]] = depth[parent] + 1
        if depth[node["name"]] > 8: raise ValueError("hierarchy depth budget")
        names.add(node["name"])
        for attr in TRANSFORMS:
            if attr in node: number(node[attr], -4 if attr.startswith("scale") else -2048, 4 if attr.startswith("scale") else 2048)
        if kind != "group":
            number(node["width"], 1, 256); number(node["height"], 1, 256)
            uint(node["color"])
            if "radius" in node: number(node["radius"], 0, 128)
    if type(scene["animations"]) is not dict: raise ValueError("animations must be an object")
    if set(scene["animations"]) != {"Idle", "Active", "Reduced"}: raise ValueError("exact proof states required")
    target_sets = []
    for name, animation in scene["animations"].items():
        if type(animation) is not dict: raise ValueError("animation must be an object")
        if set(animation) != {"duration", "loop", "tracks"}: raise ValueError("unknown animation fields")
        duration = animation["duration"]
        if type(duration) is not int or not 1 <= duration <= 600: raise ValueError("duration budget")
        if type(animation["loop"]) is not bool: raise ValueError("loop must be boolean")
        if type(animation["tracks"]) is not list: raise ValueError("tracks must be an array")
        if not 1 <= len(animation["tracks"]) <= MAX_TRACKS: raise ValueError("track budget")
        seen = set()
        for track in animation["tracks"]:
            if type(track) is not dict: raise ValueError("track must be an object")
            if set(track) != {"node", "property", "keys"}: raise ValueError("unknown track fields")
            target = (track["node"], track["property"])
            if target[0] not in names or target[0] == "Artboard": raise ValueError("missing animation target")
            if target[1] not in TRANSFORMS: raise ValueError("unsupported animated property")
            if target in seen: raise ValueError("duplicate track")
            seen.add(target)
            if type(track["keys"]) is not list: raise ValueError("keys must be an array")
            if not 1 <= len(track["keys"]) <= MAX_KEYS: raise ValueError("keyframe budget")
            previous = -1
            for pair in track["keys"]:
                if type(pair) is not list or len(pair) != 2: raise ValueError("keyframe must be [frame, value]")
                frame, value = pair
                if type(frame) is not int or not previous < frame <= duration: raise ValueError("non-monotonic or out-of-range keyframe")
                previous = frame
                limit = 4 if target[1].startswith("scale") else 2048
                number(value, -limit, limit)
            if track["keys"][0][0] != 0: raise ValueError("explicit frame-zero reset required")
        if name == "Reduced" and (animation["loop"] or any(len(t["keys"]) != 1 for t in animation["tracks"])):
            raise ValueError("Reduced must contain only constant tracks")
        target_sets.append(seen)
    if any(targets != target_sets[0] for targets in target_sets[1:]):
        raise ValueError("every state must explicitly reset every animated target")
    return scene

def compile_scene(scene):
    validate(scene)
    w = Binary()
    w.obj(23)  # Backboard
    w.obj(1, **{"4": ("s", "Mascot"), "7": ("f", 256), "8": ("f", 256), "236": ("u", 0)})
    ids = {"Artboard": 0}; index = 1
    for node in scene["nodes"]:
        ids[node["name"]] = index; own_id = index; index += 1
        p = {"4": ("s", node["name"]), "5": ("u", ids[node.get("parent", "Artboard")])}
        for name, key in TRANSFORMS.items():
            if name in node: p[str(key)] = ("f", node[name])
        w.obj(2 if node["kind"] == "group" else 3, **p)
        if node["kind"] != "group":
            p = {"5": ("u", own_id), "20": ("f", node["width"]), "21": ("f", node["height"])}
            if node["kind"] == "rectangle" and node.get("radius"):
                p["31"] = ("f", node["radius"])
            w.obj(4 if node["kind"] == "ellipse" else 7, **p); index += 1
            fill_id = index
            w.obj(20, **{"5": ("u", own_id)}); index += 1
            w.obj(18, **{"5": ("u", fill_id), "37": ("c", node["color"])}); index += 1
    for name in ("Idle", "Active", "Reduced"):
        anim = scene["animations"][name]
        w.obj(31, **{"55": ("s", name), "56": ("u", 60), "57": ("u", anim["duration"]), "59": ("u", int(anim["loop"]))})
        by_node = {}
        for track in anim["tracks"]: by_node.setdefault(track["node"], []).append(track)
        for node, tracks in by_node.items():
            w.obj(25, **{"51": ("u", ids[node])})
            for track in tracks:
                w.obj(26, **{"53": ("u", TRANSFORMS[track["property"]])})
                for frame, value in track["keys"]:
                    w.obj(30, **{"67": ("u", frame), "68": ("u", 1), "70": ("f", value)})
    w.obj(53, **{"55": ("s", "MascotController")})
    w.obj(56, **{"138": ("s", "mode"), "140": ("f", 0)})
    w.obj(59, **{"138": ("s", "reducedMotion"), "141": ("b", False)})
    w.obj(57, **{"138": ("s", "Presence")})
    w.obj(63)  # Entry state index 0
    w.obj(65, **{"151": ("u", 3)})
    w.obj(62)  # Any state index 1
    w.obj(65, **{"151": ("u", 5)})
    w.obj(71, **{"155": ("u", 1), "156": ("u", 0)})
    w.obj(65, **{"151": ("u", 4)})
    w.obj(70, **{"155": ("u", 0), "156": ("u", 5), "157": ("f", 0)})
    w.obj(71, **{"155": ("u", 1), "156": ("u", 1)})
    w.obj(65, **{"151": ("u", 3)})
    w.obj(70, **{"155": ("u", 0), "156": ("u", 2), "157": ("f", 0)})
    w.obj(71, **{"155": ("u", 1), "156": ("u", 1)})
    w.obj(64)  # Exit state index 2
    for animation_id in range(3): w.obj(61, **{"149": ("u", animation_id)})
    return w.finish()

if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("scene", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    if args.scene.stat().st_size > 131072: raise ValueError("source size budget")
    data = compile_scene(json.loads(args.scene.read_text()))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(data)
    print(json.dumps({"output": args.output.name, "bytes": len(data), "compiler": "original-bounded-python-writer", "riveCliUsed": False}))

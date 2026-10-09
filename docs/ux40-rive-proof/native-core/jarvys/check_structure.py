#!/usr/bin/env python3
"""Independent host-only source/structure and negative-mutation checks.

Checks actual Kotlin bot-mascot-v1 output against the authored scene, fixed VM
contract, component IDs, all timeline keys and all 18 controller branches.
Negative tests establish rejection by this checker, not safe runtime rejection
or a security audit. No Android, Gradle, CLI, network or dependency install.

Supply an existing compiled .riv as the positional argument. The bundled
Jarvys source is used by default; --scene selects another source. This checker
never writes or modifies its inputs.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import math
import os
from pathlib import Path
import struct

ROOT = Path(__file__).resolve().parent
MODES = ["Idle", "Thinking", "Working", "Queued", "WaitingProvider",
         "WaitingUser", "Done", "Error", "Interrupted"]
STATES = MODES + [name + "Reduced" for name in MODES]
TRANSFORMS = {"x": 13, "y": 14, "rotation": 15, "scaleX": 16, "scaleY": 17}
KINDS = {
    **dict.fromkeys((5, 51, 53, 56, 57, 59, 67, 68, 149, 151, 236,
                     554, 566, 583, 586, 587, 593, 647, 650), 0),
    **dict.fromkeys((4, 55, 138, 557, 588), 1),
    **dict.fromkeys((7, 8, 13, 14, 15, 16, 17, 20, 21, 31, 70, 575, 652), 2),
    37: 3,
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def uint(value):
    require(type(value) is int and 0 <= value <= 0xffffffff, "invalid uint32")
    result = bytearray()
    while value >= 128:
        result.append((value & 127) | 128)
        value >>= 7
    return bytes(result + bytes([value]))


def f32(value):
    return struct.unpack("<f", struct.pack("<f", value))[0]


def decode(data):
    require(len(data) <= 65536, "file exceeds byte budget")
    cursor = 0

    def take(size):
        nonlocal cursor
        end = cursor + size
        require(0 <= size and end <= len(data), "truncated field")
        result = data[cursor:end]
        cursor = end
        return result

    def read_uint():
        result = 0
        for index in range(5):
            byte = take(1)[0]
            require(index < 4 or byte <= 15, "uint32 overflow")
            result |= (byte & 127) << (index * 7)
            if byte < 128:
                return result
        raise ValueError("unterminated uint32")

    require(take(4) == b"RIVE", "wrong magic")
    require(tuple(read_uint() for _ in range(3)) == (7, 4, 0), "wrong header")
    keys = []
    while (key := read_uint()) != 0:
        keys.append(key)
    require(keys == sorted(set(keys)), "unordered or duplicate ToC keys")
    types, toc_offsets = {}, {}
    for start in range(0, len(keys), 4):
        offset = cursor
        packed = struct.unpack("<I", take(4))[0]
        require(packed < 256, "nonzero ToC padding")
        for index, key in enumerate(keys[start:start + 4]):
            kind = (packed >> (index * 2)) & 3
            require(key in KINDS and kind == KINDS[key], f"wrong ToC kind for {key}")
            types[key] = kind
            toc_offsets[key] = (offset, index * 2)
    records = []
    while cursor < len(data):
        type_id, fields = read_uint(), {}
        require(type_id > 0, "invalid object ID")
        while (key := read_uint()) != 0:
            require(key in types and key not in fields, "unknown or duplicate field")
            kind = types[key]
            if kind == 0:
                value = read_uint()
            elif kind == 1:
                value = take(read_uint())
            else:
                value = struct.unpack("<f" if kind == 2 else "<I", take(4))[0]
                require(kind != 2 or math.isfinite(value), "nonfinite float")
            fields[key] = value
        records.append((type_id, fields))
    return records, types, toc_offsets


def encode(records, types):
    """Mutation helper only; it never produces accepted product fixtures."""
    keys = sorted(types)
    result = bytearray(b"RIVE" + uint(7) + uint(4) + uint(0))
    result += b"".join(uint(key) for key in keys) + b"\0"
    for start in range(0, len(keys), 4):
        packed = sum(types[key] << (2 * index) for index, key in enumerate(keys[start:start + 4]))
        result += struct.pack("<I", packed)
    for type_id, fields in records:
        result += uint(type_id)
        for key, value in fields.items():
            result += uint(key)
            if types[key] == 0:
                result += uint(value)
            elif types[key] == 1:
                result += uint(len(value)) + value
            else:
                result += struct.pack("<f" if types[key] == 2 else "<I", value)
        result += b"\0"
    return bytes(result)


def expected_records(scene):
    require(scene.get("contract") == "bot-mascot-v1", "wrong scene contract")
    require(set(scene["animations"]) == set(STATES), "wrong scene state names")
    result = [
        (23, {}), (435, {557: b"MascotState"}), (431, {557: b"mode"}),
        (448, {557: b"reducedMotion"}), (437, {4: b"Default", 566: 0}),
        (442, {554: 0, 575: 0.0}), (449, {554: 1, 593: 0}),
        (1, {4: b"Mascot", 7: 256.0, 8: 256.0, 236: 0, 583: 0}),
    ]
    ids, next_id = {"Artboard": 0}, 1
    for node in scene["nodes"]:
        own_id = next_id
        ids[node["name"]] = own_id
        next_id += 1
        fields = {4: node["name"].encode("utf-8"), 5: ids[node.get("parent", "Artboard")]}
        fields.update({key: f32(node[prop]) for prop, key in TRANSFORMS.items() if prop in node})
        result.append((2 if node["kind"] == "group" else 3, fields))
        if node["kind"] != "group":
            fields = {5: own_id, 20: f32(node["width"]), 21: f32(node["height"])}
            if node.get("radius", 0) != 0:
                fields[31] = f32(node["radius"])
            result.append((4 if node["kind"] == "ellipse" else 7, fields))
            next_id += 1
            result.append((20, {5: own_id}))
            result.append((18, {5: next_id, 37: node["color"]}))
            next_id += 2
    targets = {(t["node"], t["property"]) for t in scene["animations"]["Idle"]["tracks"]}
    for name in STATES:
        animation = scene["animations"][name]
        require({(t["node"], t["property"]) for t in animation["tracks"]} == targets,
                "incomplete source reset set")
        result.append((31, {55: name.encode(), 56: 60, 57: animation["duration"],
                            59: int(animation["loop"])}))
        grouped = {}
        for track in animation["tracks"]:
            grouped.setdefault(track["node"], []).append(track)
        for node, tracks in grouped.items():
            result.append((25, {51: ids[node]}))
            for track in tracks:
                require(track["keys"][0][0] == 0, "missing source frame-zero reset")
                if name.endswith("Reduced"):
                    require(len(track["keys"]) == 1 and not animation["loop"], "moving reduced source")
                result.append((26, {53: TRANSFORMS[track["property"]]}))
                for frame, value in track["keys"]:
                    result.append((30, {67: frame, 68: 1, 70: f32(value)}))
    result.extend([(53, {55: b"MascotController"}), (57, {138: b"Presence"}),
                   (63, {}), (65, {151: 3}), (62, {})])
    for reduced in (True, False):
        for mode in range(9):
            result.extend([
                (65, {151: 3 + mode + (9 if reduced else 0)}),
                (482, {650: 0}), (473, {}),
                (447, {586: 636, 587: 0, 588: b"\0\0"}),
                (479, {}), (484, {652: float(mode)}),
                (482, {650: 0}), (472, {}),
                (447, {586: 634, 587: 0, 588: b"\0\1"}),
                (479, {}), (481, {647: int(reduced)}),
            ])
    result.append((64, {}))
    result.extend((61, {149: animation}) for animation in range(18))
    return result


def check(data, expected):
    records, types, offsets = decode(data)
    require(records == expected, "records differ from independently derived fixed source/VM contract")
    require(types.get(588) == 1, "raw path not typed CoreBytes")
    require(encode(records, types) == data, "noncanonical encoding or roundtrip mismatch")
    return records, types, offsets


def negative_tests(data, expected):
    records, types, offsets = check(data, expected)
    bind_index = next(index for index, (type_id, _) in enumerate(records) if type_id == 447)
    condition_index = bind_index - 2
    tests = {}

    def changed(label, mutation):
        edited = copy.deepcopy(records)
        mutation(edited)
        tests[label] = encode(edited, types)

    changed("wrong source VM index", lambda r: r[bind_index][1].update({588: b"\1\0"}))
    changed("wrong source property index", lambda r: r[bind_index][1].update({588: b"\0\2"}))
    changed("missing path segment", lambda r: r[bind_index][1].update({588: b"\0"}))
    changed("extra path segment", lambda r: r[bind_index][1].update({588: b"\0\0\0"}))
    changed("wrong bind target", lambda r: r[bind_index][1].update({586: 634}))
    changed("wrong bind direction", lambda r: r[bind_index][1].update({587: 1}))
    changed("wrong VM association", lambda r: r[7][1].update({583: 1}))
    changed("missing VM association", lambda r: r[7][1].pop(583))
    changed("wrong instance VM index", lambda r: r[4][1].update({566: 1}))
    changed("wrong property value index", lambda r: r[6][1].update({554: 0}))
    changed("wrong comparator operator", lambda r: r[condition_index][1].update({650: 5}))
    changed("wrong numeric mode literal", lambda r: r[bind_index + 2][1].update({652: 8.0}))
    changed("wrong transition destination", lambda r: r[condition_index - 1][1].update({151: 3}))

    def reorder_bind(r):
        r[bind_index], r[bind_index + 1] = r[bind_index + 1], r[bind_index]
    changed("comparator before bind", reorder_bind)

    def reorder_prefix(r):
        r[1], r[7] = r[7], r[1]
    changed("artboard before VM prefix", reorder_prefix)
    wrong_toc = bytearray(data)
    offset, shift = offsets[588]
    wrong_toc[offset] &= ~(3 << shift)
    tests["wrong CoreBytes ToC kind"] = bytes(wrong_toc)
    tests["truncated final object"] = data[:-1]
    for label, candidate in tests.items():
        try:
            check(candidate, expected)
        except (ValueError, IndexError, KeyError, struct.error):
            continue
        raise ValueError(f"negative checker test unexpectedly accepted: {label}")
    return list(tests)


def main():
    parser = argparse.ArgumentParser(description="Read-only exact Jarvys source/binary contract check; mutations test this checker, not the Rive runtime.")
    parser.add_argument("riv", type=Path, help="Existing Kotlin-compiled Jarvys asset.riv")
    parser.add_argument("--scene", type=Path, default=ROOT / "jarvys-main.scene.json")
    args = parser.parse_args()
    scene = json.loads(args.scene.read_text())
    require(scene.get("name") == "Jarvys", "This proof targets original Jarvys")
    data = args.riv.read_bytes()
    records, _, _ = check(data, expected_records(scene))
    rejected = negative_tests(data, expected_records(scene))
    require(len(records) > 0 and len(rejected) == 17, "Nonempty proof required")
    print(json.dumps({"scope": "Host-only checker, not runtime fuzzing or Android acceptance",
                      "sourceSha256": hashlib.sha256(args.scene.read_bytes()).hexdigest(),
                      "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest(),
                      "records": len(records), "timelines": 18, "modernConditions": 36,
                      "directedBranches": 18, "fullSourceRecordParity": True,
                      "checkerNegativeTests": rejected, "passed": True}, indent=2))


if __name__ == "__main__":
    main()

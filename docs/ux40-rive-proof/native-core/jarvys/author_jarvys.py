#!/usr/bin/env python3
"""Original Jarvys scene authoring. Offline, standard library, data output only.

This script does not compile Rive, invoke a renderer, edit an app or alter artwork.
Geometry was drawn for Jarvys from visual inspection of its own launcher artwork.
"""
import hashlib
import json
from pathlib import Path

OUT = Path(__file__).resolve().parent
MODES = ('Idle', 'Thinking', 'Working', 'Queued', 'WaitingProvider',
         'WaitingUser', 'Done', 'Error', 'Interrupted')
NODES = []
DEFAULTS = {}
ANIMATIONS = {}


def put(kind, name, parent='Artboard', **attributes):
    item = {'kind': kind, 'name': name, 'parent': parent, **attributes}
    NODES.append(item)
    return name


def group(name, parent='Artboard', **attributes):
    return put('group', name, parent, **attributes)


def shape(kind, name, parent, x, y, width, height, rgb, radius=None, **attributes):
    attributes.update(x=x, y=y, width=width, height=height,
                      color=0xFF000000 | int(rgb, 16))
    if radius is not None:
        attributes['radius'] = radius
    return put(kind, name, parent, **attributes)


def oval(name, parent, x, y, width, height, rgb, **attributes):
    return shape('ellipse', name, parent, x, y, width, height, rgb, **attributes)


def rounded(name, parent, x, y, width, height, rgb, radius, **attributes):
    return shape('rectangle', name, parent, x, y, width, height, rgb,
                 radius=radius, **attributes)


def animate(name, *properties):
    original = next(item for item in NODES if item['name'] == name)
    for prop in properties:
        DEFAULTS[name, prop] = original.get(prop, 1 if prop.startswith('scale') else 0)


def state(name, frames, loop, pose=None, motion=None):
    pose = pose or {}
    motion = motion or {}
    assert pose.keys() <= DEFAULTS.keys() and motion.keys() <= DEFAULTS.keys()
    static = {**DEFAULTS, **pose}
    tracks = []
    still = []
    for (node, prop), value in static.items():
        tracks.append({'node': node, 'property': prop,
                       'keys': motion.get((node, prop), [[0, value]])})
        still.append({'node': node, 'property': prop, 'keys': [[0, value]]})
    ANIMATIONS[name] = {'duration': frames, 'loop': loop, 'tracks': tracks}
    ANIMATIONS[name + 'Reduced'] = {'duration': 1, 'loop': False, 'tracks': still}


# In this reviewed Rive writer, earlier geometry is in front of later geometry.
# Declare parents before children and front details before their opaque backing.
# Static 94% root scale adds breathing room for tiny avatar rasterization.
# Scale is geometry, not a new animated target; all 18 states retain 24 tracks.
group('Jarvys', x=128, y=134, scaleX=.94, scaleY=.94)

# Rounded side pods with a dark inner socket, slim cyan edge and silver shell.
for suffix, side in (('Left', -1), ('Right', 1)):
    hand = group('Hand' + suffix, 'Jarvys', x=91 * side, y=31,
                 rotation=0.34 * side)
    oval('HandShine' + suffix, hand, -2, -5, 33, 32, 'F4F7FB')
    oval('HandSilver' + suffix, hand, 0, -1.5, 43, 44, 'D9E3EF')
    oval('HandCyan' + suffix, hand, 0, 0.5, 46, 47, '21DCEF')
    oval('HandSocket' + suffix, hand, -5 * side, 0, 50, 49, '1A2537')

# The cyan oval eyes remain separate from the visor so every glance and blink
# moves the complete eye including its small cold-white reflection.
group('Gaze', 'Jarvys', x=0, y=-22)
for suffix, x in (('Left', -33), ('Right', 33)):
    eye = group('Eye' + suffix, 'Gaze', x=x, y=0)
    oval('EyeGlint' + suffix, eye, -3.5, -5.5, 6, 6, 'BEFFFF')
    oval('EyeLight' + suffix, eye, -0.5, -0.8, 20, 24, '38F0F8')
    oval('EyeCyan' + suffix, eye, 0, 0, 23, 27, '06DDEF')
    oval('EyeAura' + suffix, eye, 0, 0, 27, 31, '10314B')

# Small cyan chest slot, not a mouth or text/status badge.
group('Chest', 'Jarvys', x=0, y=47)
group('ChestLight', 'Chest')
rounded('ChestGlint', 'ChestLight', 0, -1.4, 21, 1.5, 'B3FCFF', 0.75)
rounded('ChestCore', 'ChestLight', 0, 0, 32, 5.5, '20E6F3', 2.75)
rounded('ChestWell', 'Chest', 0, 0, 35, 7, '06BBD8', 3.5)
rounded('ChestRim', 'Chest', 0, 0, 39, 9.5, 'BDFAFF', 4.75)
rounded('ChestInset', 'Chest', 0, 0.5, 41, 11, 'C8D5E5', 5.5)

# The broad visor is intentionally larger than the eye pair. Its nested solids
# preserve the light cyan perimeter without gradients, masks or imported paths.
rounded('VisorTopReflection', 'Jarvys', 0, -52, 81, 2, '293B51', 1)
rounded('VisorGlass', 'Jarvys', 0, -21, 141, 85, '101827', 39)
rounded('VisorCyanRim', 'Jarvys', 0, -21, 146, 90, '2FE6F5', 41.5)
rounded('VisorHalo', 'Jarvys', 0, -21, 150, 94, 'C0F8FC', 43.5)

# One uninterrupted oval shell, with original restrained offset silver layers.
# No split head/body silhouette and no silhouette borrowed from another mascot.
oval('ShellDaylight', 'Jarvys', -6, -7, 162, 161, 'F1F5FA')
oval('ShellSilver', 'Jarvys', -1, 1, 171, 170, 'DAE4EF')
oval('ShellLowlight', 'Jarvys', 0, 4, 176, 176, 'A9BFD8')
oval('ShellEdge', 'Jarvys', 0, 4, 178, 178, '6585A7')

# Compact silver feet and cyan soles sit behind the uninterrupted shell.
for suffix, x in (('Left', -25), ('Right', 25)):
    foot = group('Foot' + suffix, 'Jarvys', x=x, y=95)
    oval('FootShine' + suffix, foot, -3, -5, 27, 25, 'EDF3F9')
    rounded('FootSilver' + suffix, foot, 0, -3, 35, 29, 'C2D2E5', 13)
    rounded('FootCyan' + suffix, foot, 0, 9, 36, 12, '27E2F4', 6)
    rounded('FootSole' + suffix, foot, 0, 14, 36, 12, '15273D', 6)

# Antennae are behind the shell, with two dark stalks and cyan spherical tips.
for suffix, x, angle in (('Left', -37, -0.70), ('Right', 37, 0.70)):
    antenna = group('Antenna' + suffix, 'Jarvys', x=x, y=-69, rotation=angle)
    tip = group('Tip' + suffix, antenna, x=0, y=-41)
    oval('TipGlint' + suffix, tip, -3, -3.5, 5.5, 5.5, 'C5FFFF')
    oval('TipLight' + suffix, tip, -1, -1, 18.5, 19, '20E7F7')
    oval('TipCyan' + suffix, tip, 0, 0, 23, 23, '00BDDD')
    rounded('AntennaShine' + suffix, antenna, -1.2, -18, 2.3, 34, '34485F', 1.15)
    rounded('AntennaStalk' + suffix, antenna, 0, -18, 7, 40, '1A2639', 3.5)

# One explicit reset universe shared by all 18 timelines.
animate('Jarvys', 'y', 'rotation')
animate('Gaze', 'x', 'y')
for suffix in ('Left', 'Right'):
    animate('Eye' + suffix, 'scaleY', 'rotation')
    animate('Hand' + suffix, 'x', 'y', 'rotation')
    animate('Antenna' + suffix, 'rotation')
    animate('Tip' + suffix, 'scaleX', 'scaleY')
    animate('Foot' + suffix, 'rotation')
animate('ChestLight', 'scaleX', 'scaleY')

# Idle: a slow breath and one quick blink in six seconds. No attention grab.
state('Idle', 360, True, motion={
    ('Jarvys', 'y'): [[0, 134], [180, 133], [360, 134]],
    ('EyeLeft', 'scaleY'): [[0, 1], [142, 1], [149, .10], [156, 1], [360, 1]],
    ('EyeRight', 'scaleY'): [[0, 1], [142, 1], [149, .10], [156, 1], [360, 1]],
    ('AntennaLeft', 'rotation'): [[0, -.70], [180, -.72], [360, -.70]],
    ('AntennaRight', 'rotation'): [[0, .70], [180, .72], [360, .70]],
})

# Thinking: glance up-left, then up-right while the antennae flex very slightly.
state('Thinking', 240, True, {
    ('Jarvys', 'y'): 133, ('Jarvys', 'rotation'): -.022,
    ('Gaze', 'x'): -4, ('Gaze', 'y'): -25,
    ('EyeLeft', 'scaleY'): .94, ('EyeRight', 'scaleY'): .94,
    ('HandLeft', 'x'): -89, ('HandLeft', 'y'): 34,
    ('HandRight', 'x'): 89, ('HandRight', 'y'): 30,
}, {
    ('Gaze', 'x'): [[0, -4], [66, -4], [114, 4], [174, 4], [222, -4], [240, -4]],
    ('AntennaLeft', 'rotation'): [[0, -.76], [120, -.68], [240, -.76]],
    ('AntennaRight', 'rotation'): [[0, .66], [120, .73], [240, .66]],
})

# Working: composed forward focus, tiny alternating pod motion and chest pulse.
state('Working', 150, True, {
    ('Gaze', 'y'): -20, ('EyeLeft', 'scaleY'): .84, ('EyeRight', 'scaleY'): .84,
    ('HandLeft', 'x'): -90, ('HandRight', 'x'): 90,
    ('HandLeft', 'y'): 29, ('HandRight', 'y'): 33,
}, {
    ('HandLeft', 'y'): [[0, 29], [38, 32], [75, 29], [113, 32], [150, 29]],
    ('HandRight', 'y'): [[0, 33], [38, 30], [75, 33], [113, 30], [150, 33]],
    ('ChestLight', 'scaleX'): [[0, .70], [38, 1], [75, .70], [113, 1], [150, .70]],
    ('ChestLight', 'scaleY'): [[0, .90], [38, 1.07], [75, .90], [113, 1.07], [150, .90]],
})

# Queued: patient compact stance, lowered hands and an almost imperceptible shift.
state('Queued', 300, True, {
    ('Jarvys', 'y'): 135, ('Gaze', 'y'): -20,
    ('EyeLeft', 'scaleY'): .84, ('EyeRight', 'scaleY'): .84,
    ('HandLeft', 'y'): 36, ('HandRight', 'y'): 36,
    ('HandLeft', 'x'): -89, ('HandRight', 'x'): 89,
    ('ChestLight', 'scaleX'): .80,
}, {
    ('Jarvys', 'y'): [[0, 135], [150, 136], [300, 135]],
    ('FootLeft', 'rotation'): [[0, -.018], [150, .012], [300, -.018]],
    ('FootRight', 'rotation'): [[0, .018], [150, -.012], [300, .018]],
})

# WaitingProvider: outward listening, right gaze and a breathing right antenna tip.
state('WaitingProvider', 240, True, {
    ('Jarvys', 'rotation'): .035, ('Gaze', 'x'): 5, ('Gaze', 'y'): -23,
    ('HandRight', 'y'): 26, ('HandRight', 'rotation'): .12,
    ('AntennaLeft', 'rotation'): -.76, ('AntennaRight', 'rotation'): .62,
    ('ChestLight', 'scaleX'): .85,
}, {
    ('TipRight', 'scaleX'): [[0, 1], [120, 1.075], [240, 1]],
    ('TipRight', 'scaleY'): [[0, 1], [120, 1.075], [240, 1]],
    ('HandRight', 'y'): [[0, 26], [120, 24], [240, 26]],
})

# WaitingUser: direct eye contact and one open raised pod, no urgent beckoning.
state('WaitingUser', 240, True, {
    ('Jarvys', 'y'): 132, ('Jarvys', 'rotation'): -.018,
    ('Gaze', 'y'): -23, ('HandLeft', 'y'): 20,
    ('HandLeft', 'rotation'): -.56, ('HandRight', 'y'): 34,
}, {
    ('HandLeft', 'y'): [[0, 20], [120, 18], [240, 20]],
    ('AntennaLeft', 'rotation'): [[0, -.70], [120, -.74], [240, -.70]],
})

# Done: one bounded greeting, then settle. It never loops a celebration.
state('Done', 90, False, {
    ('Jarvys', 'y'): 132, ('EyeLeft', 'scaleY'): .88,
    ('EyeRight', 'scaleY'): .88, ('HandRight', 'y'): 12,
    ('HandRight', 'rotation'): .40, ('ChestLight', 'scaleX'): 1.05,
}, {
    ('Jarvys', 'y'): [[0, 134], [22, 130], [50, 132], [90, 132]],
    ('HandRight', 'y'): [[0, 28], [22, 10], [44, 12], [66, 10], [90, 12]],
    ('HandRight', 'rotation'): [[0, .34], [22, .72], [44, .35], [66, .72], [90, .40]],
})

# Error: one visible but gentle concern tilt, hand settle and antenna droop.
# The pose is subdued and never shakes, flashes, cries or loops.
state('Error', 120, False, {
    ('Jarvys', 'y'): 136, ('Jarvys', 'rotation'): -.055,
    ('Gaze', 'x'): -1, ('Gaze', 'y'): -19,
    ('EyeLeft', 'scaleY'): .72, ('EyeRight', 'scaleY'): .82,
    ('EyeLeft', 'rotation'): -.12, ('EyeRight', 'rotation'): .12,
    ('HandLeft', 'x'): -89, ('HandRight', 'x'): 89,
    ('HandLeft', 'y'): 37, ('HandRight', 'y'): 37,
    ('HandLeft', 'rotation'): -.28, ('HandRight', 'rotation'): .28,
    ('AntennaLeft', 'rotation'): -.83, ('AntennaRight', 'rotation'): .81,
    ('ChestLight', 'scaleX'): .60,
}, {
    ('Jarvys', 'y'): [[0, 134], [48, 137], [90, 136], [120, 136]],
    ('Jarvys', 'rotation'): [[0, 0], [36, -.075], [78, -.055], [120, -.055]],
    ('HandLeft', 'y'): [[0, 31], [45, 39], [90, 37], [120, 37]],
    ('HandRight', 'y'): [[0, 31], [65, 38], [100, 37], [120, 37]],
    ('AntennaLeft', 'rotation'): [[0, -.70], [52, -.89], [95, -.83], [120, -.83]],
    ('AntennaRight', 'rotation'): [[0, .70], [65, .86], [105, .81], [120, .81]],
})

# Interrupted: a quiet, straight stop gesture and gentle settling, not an error.
state('Interrupted', 96, False, {
    ('Jarvys', 'y'): 135, ('Gaze', 'y'): -21,
    ('EyeLeft', 'scaleY'): .78, ('EyeRight', 'scaleY'): .78,
    ('HandLeft', 'x'): -88, ('HandLeft', 'rotation'): 0,
    ('HandRight', 'x'): 89, ('HandRight', 'y'): 20, ('HandRight', 'rotation'): 0,
    ('ChestLight', 'scaleX'): .72,
}, {
    ('HandRight', 'y'): [[0, 31], [36, 18], [66, 20], [96, 20]],
    ('Jarvys', 'y'): [[0, 134], [48, 135.5], [96, 135]],
})

scene = {'contract': 'bot-mascot-v1', 'name': 'Jarvys', 'nodes': NODES,
         'animations': {key: ANIMATIONS[key] for key in
                        MODES + tuple(mode + 'Reduced' for mode in MODES)}}

# Independent data checks. Official Kotlin compilation and Rive runtime validation
# are intentionally performed separately by the integration task.
assert len(NODES) <= 96
assert len(DEFAULTS) <= 64
assert len({node['name'] for node in NODES}) == len(NODES)
seen = {'Artboard': 0}
for node in NODES:
    assert node['parent'] in seen
    seen[node['name']] = seen[node['parent']] + 1
    assert seen[node['name']] <= 8
expected = set(DEFAULTS)
for name, animation in scene['animations'].items():
    assert 0 < animation['duration'] <= 600
    assert {(track['node'], track['property']) for track in animation['tracks']} == expected
    different = False
    for track in animation['tracks']:
        keys = track['keys']
        assert 1 <= len(keys) <= 64 and keys[0][0] == 0
        assert all(a[0] < b[0] for a, b in zip(keys, keys[1:]))
        assert keys[-1][0] <= animation['duration']
        different |= any(key[1] != keys[0][1] for key in keys)
        if name.endswith('Reduced'):
            assert len(keys) == 1 and not animation['loop']
    assert different or name.endswith('Reduced')

payload = (json.dumps(scene, ensure_ascii=False, separators=(',', ':')) + '\n').encode()
assert len(payload) <= 131072
source = OUT / 'jarvys-main.scene.json'
source.write_bytes(payload)
report = {'source': source.name, 'contract': scene['contract'],
          'source_bytes': len(payload), 'source_sha256': hashlib.sha256(payload).hexdigest(),
          'nodes': len(NODES), 'shapes': sum(n['kind'] != 'group' for n in NODES),
          'max_hierarchy_depth': max(seen.values()),
          'tracks_per_state': len(DEFAULTS), 'states': len(scene['animations']),
          'keyframes': sum(len(track['keys']) for anim in scene['animations'].values()
                           for track in anim['tracks']),
          'checks': ['Unique nodes and parent-first hierarchy', 'Nine normal timelines have motion',
                     'Nine reduced timelines are static', 'Every timeline resets identical targets',
                     'All JSON-side compiler budgets met'],
          'not_verified_here': ['Exact Kotlin compilation', 'Rive binary size',
                                'Official Rive rendering', 'Android-device playback']}
(OUT / 'author-validation.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2))

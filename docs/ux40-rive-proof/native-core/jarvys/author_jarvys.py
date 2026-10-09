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
# Static 90% root scale reserves space for broad gestures at actual avatar sizes.
# Identity geometry remains unchanged; motion is authored afresh below.
group('Jarvys', x=128, y=132, scaleX=.90, scaleY=.90)

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

# All 31 targets reset explicitly in all 18 timelines. Foot/root position tracks
# enable actual weight shifts instead of trying to communicate through micro-bobs.
animate('Jarvys', 'x', 'y', 'rotation')
animate('Gaze', 'x', 'y')
for suffix in ('Left', 'Right'):
    animate('Eye' + suffix, 'scaleY', 'rotation')
    animate('Hand' + suffix, 'x', 'y', 'rotation')
    animate('Antenna' + suffix, 'rotation')
    animate('Tip' + suffix, 'scaleX', 'scaleY')
    animate('Foot' + suffix, 'x', 'y', 'rotation')
animate('ChestLight', 'scaleX', 'scaleY')
animate('HandLeft', 'scaleX', 'scaleY')

# Five-second relaxed sway; idle deliberately does not mime an active task.
state('Idle', 300, True, motion={
    ('Jarvys','x'): [[0,128],[75,125],[150,128],[225,131],[300,128]],
    ('Jarvys','y'): [[0,132],[75,128],[150,132],[225,128],[300,132]],
    ('Jarvys','rotation'): [[0,0],[75,-.045],[150,0],[225,.045],[300,0]],
    ('HandLeft','y'): [[0,31],[75,36],[150,31],[225,27],[300,31]],
    ('HandRight','y'): [[0,31],[75,27],[150,31],[225,36],[300,31]],
    ('EyeLeft','scaleY'): [[0,1],[137,1],[145,.08],[154,1],[300,1]],
    ('EyeRight','scaleY'): [[0,1],[137,1],[145,.08],[154,1],[300,1]],
})

# 4.8 seconds: left hand rises to the temple, head considers both directions,
# then the hand returns. Hand travel is about 60 source pixels, not a 2 px twitch.
state('Thinking', 288, True, {
    ('Jarvys','rotation'):-.10, ('Gaze','x'):-5, ('Gaze','y'):-27,
    ('HandLeft','x'):-65, ('HandLeft','y'):-30, ('HandLeft','rotation'):-.65,
    ('HandRight','x'):84, ('HandRight','y'):42,
}, {
    ('Jarvys','rotation'):[[0,0],[72,-.09],[132,-.14],[192,-.07],[252,-.025],[288,0]],
    ('Gaze','x'):[[0,0],[72,-5],[126,4],[180,-5],[240,0],[288,0]],
    ('Gaze','y'):[[0,-22],[72,-27],[192,-27],[252,-22],[288,-22]],
    ('HandLeft','x'):[[0,-91],[72,-65],[132,-61],[192,-65],[252,-85],[288,-91]],
    ('HandLeft','y'):[[0,31],[72,-30],[132,-24],[192,-30],[252,18],[288,31]],
    ('HandLeft','rotation'):[[0,-.34],[72,-.65],[192,-.65],[288,-.34]],
    ('HandRight','y'):[[0,31],[72,42],[192,42],[288,31]],
    ('AntennaLeft','rotation'):[[0,-.70],[96,-.84],[168,-.67],[240,-.75],[288,-.70]],
})

# Four seconds of continuous alternating work strokes. Each pod travels a broad
# inward/downward path, one working while the other returns for its next stroke.
state('Working', 240, True, {
    ('Gaze','y'):-18, ('EyeLeft','scaleY'):.72, ('EyeRight','scaleY'):.72,
    ('HandLeft','x'):-43, ('HandLeft','y'):56,
    ('HandRight','x'):82, ('HandRight','y'):22,
}, {
    ('Jarvys','rotation'):[[0,-.045],[60,.045],[120,-.045],[180,.045],[240,-.045]],
    ('HandLeft','x'):[[0,-82],[30,-58],[60,-43],[90,-59],[120,-82],[150,-58],[180,-43],[210,-59],[240,-82]],
    ('HandLeft','y'):[[0,22],[30,47],[60,56],[90,44],[120,22],[150,47],[180,56],[210,44],[240,22]],
    ('HandRight','x'):[[0,43],[30,59],[60,82],[90,58],[120,43],[150,59],[180,82],[210,58],[240,43]],
    ('HandRight','y'):[[0,56],[30,44],[60,22],[90,47],[120,56],[150,44],[180,22],[210,47],[240,56]],
    ('HandLeft','rotation'):[[0,-.34],[60,-.75],[120,-.34],[180,-.75],[240,-.34]],
    ('HandRight','rotation'):[[0,.75],[60,.34],[120,.75],[180,.34],[240,.75]],
    ('ChestLight','scaleX'):[[0,.65],[60,1.08],[120,.65],[180,1.08],[240,.65]],
})

# Four seconds: hands gathered in front and patient alternating toe taps.
# The folded silhouette distinguishes this from idle even without motion.
state('Queued', 240, True, {
    ('HandLeft','x'):-37, ('HandLeft','y'):53, ('HandLeft','rotation'):-.65,
    ('HandRight','x'):37, ('HandRight','y'):53, ('HandRight','rotation'):.65,
    ('EyeLeft','scaleY'):.82, ('EyeRight','scaleY'):.82,
    ('Gaze','y'):-20,
}, {
    ('Jarvys','x'):[[0,119],[60,119],[120,137],[180,137],[240,119]],
    ('Jarvys','rotation'):[[0,-.09],[60,-.09],[120,.09],[180,.09],[240,-.09]],
    ('FootLeft','y'):[[0,95],[30,95],[60,86],[90,95],[240,95]],
    ('FootRight','y'):[[0,95],[150,95],[180,86],[210,95],[240,95]],
    ('FootLeft','rotation'):[[0,0],[60,-.16],[90,0],[240,0]],
    ('FootRight','rotation'):[[0,0],[150,0],[180,.16],[210,0],[240,0]],
})

# Four seconds: right hand listens high beside the head, gaze is outward,
# antenna responds. A broad listening sweep repeats throughout the loop.
state('WaitingProvider', 240, True, {
    ('Jarvys','rotation'):.10, ('Gaze','x'):7, ('Gaze','y'):-25,
    ('HandRight','x'):68, ('HandRight','y'):-48, ('HandRight','rotation'):.65,
    ('HandLeft','x'):-85, ('HandLeft','y'):43,
    ('AntennaRight','rotation'):.90, ('AntennaLeft','rotation'):-.62,
    ('TipRight','scaleX'):1.25, ('TipRight','scaleY'):1.25,
}, {
    ('Jarvys','rotation'):[[0,.03],[60,.13],[126,.05],[192,.13],[240,.03]],
    ('HandRight','x'):[[0,84],[54,68],[126,100],[192,68],[240,84]],
    ('HandRight','y'):[[0,-8],[54,-48],[126,-12],[192,-48],[240,-8]],
    ('Gaze','x'):[[0,7],[54,4],[126,9],[192,4],[240,7]],
    ('AntennaRight','rotation'):[[0,.70],[60,.94],[126,.75],[192,.94],[240,.70]],
    ('TipRight','scaleX'):[[0,1],[60,1.4],[126,1],[192,1.4],[240,1]],
    ('TipRight','scaleY'):[[0,1],[60,1.4],[126,1],[192,1.4],[240,1]],
})

# Four seconds: one open pod reaches forward/outward then beckons back. Its
# enlarged perspective and asymmetry are unlike neutral breathing or work strokes.
# All of the invitation remains below the eye/visor area; the other pod stays low.
state('WaitingUser', 240, True, {
    ('HandLeft','x'):-77, ('HandLeft','y'):26, ('HandLeft','rotation'):-.08,
    ('HandLeft','scaleX'):1.35, ('HandLeft','scaleY'):1.35,
    ('HandRight','x'):73, ('HandRight','y'):44, ('HandRight','rotation'):.40,
    ('EyeLeft','scaleY'):1.10, ('EyeRight','scaleY'):.90,
    ('Jarvys','rotation'):-.035,
}, {
    ('HandLeft','x'):[[0,-37],[60,-92],[120,-40],[180,-92],[240,-37]],
    ('HandLeft','y'):[[0,43],[60,27],[120,43],[180,27],[240,43]],
    ('HandLeft','scaleX'):[[0,1.05],[60,1.35],[120,1.05],[180,1.35],[240,1.05]],
    ('HandLeft','scaleY'):[[0,1.05],[60,1.35],[120,1.05],[180,1.35],[240,1.05]],
    ('Jarvys','rotation'):[[0,.025],[60,-.045],[120,.025],[180,-.045],[240,.025]],
    ('Gaze','x'):[[0,0],[60,-2],[120,0],[180,-2],[240,0]],
})

# 3.5-second one-shot: both hands rise, two visible celebration arcs and body
# lifts follow, then a happy open rest is reached at 3.3 s and held.
state('Done', 210, False, {
    ('Jarvys','y'):128, ('HandLeft','x'):-87, ('HandLeft','y'):-33,
    ('HandRight','x'):87, ('HandRight','y'):-33,
    ('EyeLeft','scaleY'):.45, ('EyeRight','scaleY'):.45,
    ('ChestLight','scaleX'):1.08,
}, {
    ('Jarvys','y'):[[0,132],[48,123],[78,119],[108,128],[138,118],[168,127],[198,132],[210,132]],
    ('HandLeft','x'):[[0,-91],[48,-87],[84,-96],[120,-72],[150,-96],[174,-86],[198,-82],[210,-82]],
    ('HandRight','x'):[[0,91],[48,87],[84,96],[120,72],[150,96],[174,86],[198,82],[210,82]],
    ('HandLeft','y'):[[0,31],[48,-33],[84,-12],[120,-43],[150,-12],[174,-24],[198,9],[210,9]],
    ('HandRight','y'):[[0,31],[48,-33],[84,-12],[120,-43],[150,-12],[174,-24],[198,9],[210,9]],
    ('EyeLeft','scaleY'):[[0,1],[60,.45],[126,.70],[180,.45],[210,.45]],
    ('EyeRight','scaleY'):[[0,1],[60,.45],[126,.70],[180,.45],[210,.45]],
    ('AntennaLeft','rotation'):[[0,-.70],[78,-.85],[108,-.68],[138,-.85],[198,-.70],[210,-.70]],
    ('AntennaRight','rotation'):[[0,.70],[78,.85],[108,.68],[138,.85],[198,.70],[210,.70]],
})

# 3.5-second one-shot: visible recoil, inspect with both hands, then recover to a
# quiet concerned stance. Meaningful arm/body travel continues for over 3 s.
state('Error', 210, False, {
    ('Jarvys','y'):134, ('Jarvys','rotation'):-.10,
    ('Gaze','y'):-18, ('EyeLeft','scaleY'):.55, ('EyeRight','scaleY'):.65,
    ('HandLeft','x'):-80, ('HandLeft','y'):50,
    ('HandRight','x'):80, ('HandRight','y'):50,
    ('AntennaLeft','rotation'):-.96, ('AntennaRight','rotation'):.96,
    ('ChestLight','scaleX'):.6,
}, {
    ('Jarvys','x'):[[0,128],[48,123],[84,124],[126,130],[174,128],[210,128]],
    ('Jarvys','y'):[[0,132],[48,134],[96,132],[144,124],[192,131],[210,131]],
    ('Jarvys','rotation'):[[0,0],[42,-.15],[78,-.11],[132,.08],[186,-.055],[210,-.055]],
    ('HandLeft','x'):[[0,-91],[42,-99],[96,-45],[150,-78],[192,-80],[210,-80]],
    ('HandLeft','y'):[[0,31],[42,17],[96,52],[150,30],[192,45],[210,45]],
    ('HandRight','x'):[[0,91],[42,99],[96,45],[150,78],[192,80],[210,80]],
    ('HandRight','y'):[[0,31],[42,17],[96,52],[150,30],[192,45],[210,45]],
    ('AntennaLeft','rotation'):[[0,-.70],[48,-1.02],[96,-.96],[150,-.76],[192,-.85],[210,-.85]],
    ('AntennaRight','rotation'):[[0,.70],[48,1.02],[96,.96],[150,.76],[192,.85],[210,.85]],
    ('EyeLeft','scaleY'):[[0,1],[48,.48],[108,.65],[168,.80],[210,.75]],
    ('EyeRight','scaleY'):[[0,1],[48,.58],[108,.75],[168,.90],[210,.85]],
})

# Three-second one-shot: working stroke decelerates, one hand rises to a straight
# stop and the body stabilizes. Pose reached after 2.6 s, not after 0.6 s.
state('Interrupted', 180, False, {
    ('HandLeft','x'):-85, ('HandLeft','y'):33, ('HandLeft','rotation'):0,
    ('HandRight','x'):94, ('HandRight','y'):-19, ('HandRight','rotation'):0,
    ('EyeLeft','scaleY'):.90, ('EyeRight','scaleY'):.90,
}, {
    ('Jarvys','x'):[[0,125],[60,132],[90,133],[156,128],[180,128]],
    ('Jarvys','y'):[[0,132],[60,128],[156,132],[180,132]],
    ('Jarvys','rotation'):[[0,-.07],[66,.06],[144,0],[180,0]],
    ('HandLeft','x'):[[0,-45],[66,-71],[126,-85],[180,-85]],
    ('HandLeft','y'):[[0,54],[66,40],[126,33],[180,33]],
    ('HandRight','x'):[[0,78],[48,88],[96,94],[180,94]],
    ('HandRight','y'):[[0,25],[48,-4],[96,-17],[144,-19],[180,-19]],
})

scene = {'contract': 'bot-mascot-v1', 'name': 'Jarvys', 'nodes': NODES,
         'animations': {key: ANIMATIONS[key] for key in
                        MODES + tuple(mode + 'Reduced' for mode in MODES)}}
assert 61 <= len(NODES) <= 96 and len(DEFAULTS) <= 64
assert len({n['name'] for n in NODES}) == len(NODES)
seen = {'Artboard': 0}
for n in NODES:
    assert n['parent'] in seen
    seen[n['name']] = seen[n['parent']] + 1
    assert seen[n['name']] <= 8
expected = set(DEFAULTS)
timings = {}
for name, a in scene['animations'].items():
    assert 0 < a['duration'] <= 600
    assert {(t['node'], t['property']) for t in a['tracks']} == expected
    last_activity = 0
    for t in a['tracks']:
        keys = t['keys']
        assert 1 <= len(keys) <= 64 and keys[0][0] == 0
        assert all(x[0] < y[0] for x, y in zip(keys, keys[1:]))
        assert keys[-1][0] <= a['duration']
        for first, last in zip(keys, keys[1:]):
            if first[1] != last[1]: last_activity = max(last_activity, last[0])
        if name.endswith('Reduced'):
            assert len(keys) == 1 and not a['loop']
        elif a['loop']:
            assert keys[0][1] == keys[-1][1], (name, t['node'], t['property'])
    assert last_activity or name.endswith('Reduced')
    timings[name] = {'duration_seconds':a['duration']/60,
                     'last_authored_change_seconds':last_activity/60, 'loop':a['loop']}
payload = (json.dumps(scene, ensure_ascii=False, separators=(',', ':')) + '\n').encode()
assert len(payload) <= 131072
source = OUT / 'jarvys-main.scene.json'
source.write_bytes(payload)
report = {'source':source.name, 'source_bytes':len(payload),
          'source_sha256':hashlib.sha256(payload).hexdigest(),
          'nodes':len(NODES), 'shapes':sum(n['kind'] != 'group' for n in NODES),
          'max_hierarchy_depth':max(seen.values()), 'tracks_per_state':len(DEFAULTS),
          'states':len(scene['animations']),
          'keyframes':sum(len(t['keys']) for a in scene['animations'].values() for t in a['tracks']),
          'timings':timings,
          'caution':'Authored activity is not proof of perceptible motion. Official-runtime individual renders and actual-size pixel checks are separate.'}
(OUT / 'author-validation.json').write_text(json.dumps(report, indent=2)+'\n')
print(json.dumps(report, indent=2))

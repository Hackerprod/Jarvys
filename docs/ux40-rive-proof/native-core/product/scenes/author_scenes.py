#!/usr/bin/env python3
"""Author two original UX40 scenes; validate JSON structure, never render/compile Rive.

Standard-library-only, deterministic and offline. Output is beside this file.
The scene format contains data, not this script or executable instructions.
"""
from pathlib import Path
import hashlib
import json
import math

STATES = ('Idle', 'Thinking', 'Working', 'Queued', 'WaitingProvider',
          'WaitingUser', 'Done', 'Error', 'Interrupted')
PROPERTIES = ('x', 'y', 'rotation', 'scaleX', 'scaleY')
ANIMATIONS = STATES + tuple(s + 'Reduced' for s in STATES)


def argb(rgb):
    return 0xFF000000 | int(rgb.removeprefix('#'), 16)


class Scene:
    def __init__(self, name):
        self.name, self.nodes, self.targets, self.normal, self.reduced = name, [], {}, {}, {}

    def node(self, kind, name, parent='Artboard', **fields):
        n = dict(kind=kind, name=name, parent=parent, **fields)
        self.nodes.append(n)
        return n

    def group(self, name, parent='Artboard', **fields):
        return self.node('group', name, parent, **fields)

    def ellipse(self, name, parent, x, y, width, height, color, **fields):
        return self.node('ellipse', name, parent, x=x, y=y, width=width,
                         height=height, color=argb(color), **fields)

    def rect(self, name, parent, x, y, width, height, color, radius=2, **fields):
        return self.node('rectangle', name, parent, x=x, y=y, width=width,
                         height=height, color=argb(color), radius=radius, **fields)

    def target(self, name, *properties):
        node = next(n for n in self.nodes if n['name'] == name)
        for p in properties:
            self.targets[(name, p)] = node.get(p, 1 if p.startswith('scale') else 0)

    def state(self, state, duration, loop, pose, moves=None):
        """Each state starts from the full target universe, then applies its own pose.

        Movement values are full frame/value keys and intentionally linear.
        Reduced poses use the authored pose, rather than freezing a random moment.
        """
        moves = moves or {}
        assert set(pose).issubset(self.targets) and set(moves).issubset(self.targets)
        values = self.targets | pose
        self.normal[state] = dict(duration=duration, loop=loop, tracks=[
            dict(node=n, property=p, keys=moves.get((n, p), [[0, v]]))
            for (n, p), v in values.items()
        ])
        self.reduced[state + 'Reduced'] = dict(duration=1, loop=False, tracks=[
            dict(node=n, property=p, keys=[[0, v]]) for (n, p), v in values.items()
        ])

    def export(self):
        assert set(self.normal) == set(STATES)
        return dict(contract='bot-mascot-v1', name=self.name, nodes=self.nodes,
                    animations=self.normal | self.reduced)


def indicator(scene, parent, ink):
    """Small shared semantic alphabet; character silhouette/motion is authored separately.

    Source order follows the reference writer: foreground geometry before backing.
    No symbol depends on flashing, text, fonts or color alone.
    """
    for state in STATES:
        group = 'Signal' + state
        scene.group(group, parent, scaleX=1 if state == 'Idle' else 0)
        scene.target(group, 'scaleX')
        if state == 'Idle':
            scene.ellipse('SignalRest', group, 0, 0, 5, 5, ink)
        elif state == 'Thinking':
            for i, y in enumerate((3, 0, -3)):
                scene.ellipse('Thought' + str(i), group, (i-1)*7, y, 4, 4, ink)
        elif state == 'Working':
            for i, h in enumerate((7, 15, 11)):
                scene.rect('WorkBar' + str(i), group, (i-1)*6, 7-h/2, 3, h, ink, 1.5)
        elif state == 'Queued':
            for i, width in enumerate((10, 15, 20)):
                scene.rect('QueueRow' + str(i), group, 0, (i-1)*6, width, 3, ink, 1.5)
        elif state == 'WaitingProvider':
            scene.rect('LinkBridge', group, 0, 0, 12, 3, ink, 1.5)
            for suffix, x in (('L', -8), ('R', 8)):
                scene.rect('LinkNode' + suffix, group, x, 0, 6, 10, ink, 2)
        elif state == 'WaitingUser':
            scene.ellipse('PromptDot', group, 1, -1, 4, 4, 'fffaf0')
            scene.rect('PromptTail', group, -5, 7, 5, 8, ink, 1.5, rotation=.4)
            scene.rect('PromptBubble', group, 0, -1, 20, 14, ink, 5)
        elif state == 'Done':
            scene.rect('CheckShort', group, -5, 3, 10, 4, ink, 2, rotation=.72)
            scene.rect('CheckLong', group, 3, 0, 17, 4, ink, 2, rotation=-.8)
        elif state == 'Error':
            scene.ellipse('ErrorDot', group, 0, 8, 4, 4, ink)
            scene.rect('ErrorStem', group, 0, -3, 4, 13, ink, 2)
        elif state == 'Interrupted':
            for suffix, x in (('L', -5), ('R', 5)):
                scene.rect('Pause' + suffix, group, x, 0, 4, 16, ink, 2)


def signal_pose(state):
    return {('Signal' + s, 'scaleX'): int(s == state) for s in STATES}


def nimbo():
    s = Scene('Nimbo')
    ink, shell, rim, dew = '233c63', 'f4f9ff', 'a9c4e5', '729ecc'
    s.group('Cloud', x=126, y=119)
    s.group('Gaze', 'Cloud', x=0, y=3)
    s.ellipse('EyeL', 'Gaze', -16, 0, 7, 12, ink)
    s.ellipse('EyeR', 'Gaze', 16, 0, 7, 12, ink)
    s.rect('Mouth', 'Cloud', 0, 22, 15, 4, ink, 2)
    s.ellipse('BlushL', 'Cloud', -31, 16, 10, 5, 'd8a0a1')
    s.ellipse('BlushR', 'Cloud', 31, 16, 10, 5, 'd8a0a1')
    # A deliberately asymmetrical four-lobe cloud; no body/feet template.
    s.ellipse('CloudBelly', 'Cloud', 0, 15, 114, 58, shell)
    s.ellipse('CloudLobeLeft', 'Cloud', -32, -7, 54, 55, shell)
    s.ellipse('CloudLobeCrown', 'Cloud', 0, -21, 63, 65, shell)
    s.ellipse('CloudLobeRight', 'Cloud', 38, -1, 47, 44, shell)
    s.ellipse('CloudRimBelly', 'Cloud', 0, 16, 121, 64, rim)
    s.ellipse('CloudRimLeft', 'Cloud', -32, -6, 61, 61, rim)
    s.ellipse('CloudRimCrown', 'Cloud', 0, -20, 70, 71, rim)
    s.ellipse('CloudRimRight', 'Cloud', 38, 0, 54, 51, rim)
    for suffix, x, y, angle in (('L', 85, 188, -.22), ('C', 126, 195, 0), ('R', 167, 188, .22)):
        name = 'Dew' + suffix
        s.group(name, x=x, y=y, rotation=angle)
        s.ellipse(name + 'Gleam', name, -2, -3, 3, 6, 'e5f2ff')
        s.ellipse(name + 'Body', name, 0, 0, 12, 20 if suffix == 'C' else 17, dew)
    s.group('Badge', x=190, y=65)
    indicator(s, 'Badge', ink)
    s.ellipse('BadgeFace', 'Badge', 0, 0, 31, 31, 'fffaf0')
    s.ellipse('BadgeRim', 'Badge', 0, 1, 35, 35, rim)
    s.ellipse('Shadow', 'Artboard', 127, 222, 101, 9, 'dce6f0')
    s.target('Cloud', 'x', 'y', 'rotation', 'scaleX', 'scaleY')
    s.target('Gaze', 'x', 'y')
    s.target('EyeL', 'scaleY')
    s.target('EyeR', 'scaleY')
    s.target('Mouth', 'scaleX', 'scaleY', 'rotation')
    for name in ('DewL', 'DewC', 'DewR'):
        s.target(name, 'x', 'y', 'rotation')
    s.target('Badge', 'rotation')
    s.target('Shadow', 'scaleX')
    # Quiet buoyancy; the three drops remain spread below the cloud.
    s.state('Idle', 180, True, signal_pose('Idle'), {
        ('Cloud','y'): [[0,119],[90,117],[180,119]],
        ('Cloud','scaleY'): [[0,1],[90,1.02],[180,1]],
        ('DewL','y'): [[0,188],[90,190],[180,188]],
        ('DewR','y'): [[0,188],[90,186],[180,188]],
    })
    # An upward-left gaze and a little ascending constellation imply reflection.
    s.state('Thinking', 160, True, signal_pose('Thinking') | {
        ('Cloud','rotation'):-.055, ('Gaze','x'):-4, ('Gaze','y'):-2,
        ('Mouth','scaleX'):.48, ('DewL','x'):82, ('DewL','y'):176,
        ('DewC','x'):113, ('DewC','y'):185, ('DewR','x'):146, ('DewR','y'):192,
    }, {
        ('Cloud','rotation'):[[0,-.055],[80,-.025],[160,-.055]],
        ('DewL','y'):[[0,176],[80,172],[160,176]],
        ('DewC','y'):[[0,185],[80,182],[160,185]],
    })
    # A left-to-right relay is different from the whole-character motion of Idle.
    s.state('Working', 108, True, signal_pose('Working') | {
        ('Cloud','y'):117, ('Cloud','scaleX'):1.035, ('Cloud','scaleY'):.97,
        ('Gaze','y'):3, ('Mouth','scaleX'):.75,
        ('DewL','y'):187, ('DewC','y'):187, ('DewR','y'):187,
    }, {
        ('DewL','y'):[[0,187],[18,179],[36,187],[108,187]],
        ('DewC','y'):[[0,187],[36,187],[54,179],[72,187],[108,187]],
        ('DewR','y'):[[0,187],[72,187],[90,179],[108,187]],
        ('Gaze','x'):[[0,-3],[54,3],[108,-3]],
    })
    # A compact horizontal line of drops waits together instead of moving in relay.
    s.state('Queued', 210, True, signal_pose('Queued') | {
        ('Cloud','y'):124, ('Cloud','scaleY'):.95, ('EyeL','scaleY'):.7,
        ('EyeR','scaleY'):.7, ('Mouth','scaleX'):.65,
        ('DewL','x'):105, ('DewL','y'):190, ('DewL','rotation'):0,
        ('DewC','y'):190, ('DewR','x'):147, ('DewR','y'):190, ('DewR','rotation'):0,
    }, {('Cloud','y'):[[0,124],[105,125.5],[210,124]]})
    # Listening outward: two dew drops lean toward the provider on the right.
    s.state('WaitingProvider', 192, True, signal_pose('WaitingProvider') | {
        ('Cloud','rotation'):.06, ('Gaze','x'):5, ('Mouth','scaleX'):.55,
        ('DewL','x'):101, ('DewL','y'):192, ('DewC','x'):148, ('DewC','y'):185,
        ('DewR','x'):179, ('DewR','y'):172, ('DewR','rotation'):.4,
    }, {
        ('DewR','rotation'):[[0,.4],[96,.25],[192,.4]],
        ('DewC','y'):[[0,185],[96,182],[192,185]],
    })
    # Open, upright stance and one raised drop invite a user response.
    s.state('WaitingUser', 180, True, signal_pose('WaitingUser') | {
        ('Cloud','y'):116, ('Cloud','scaleY'):1.035, ('Mouth','scaleX'):1.25,
        ('DewL','x'):77, ('DewL','y'):164, ('DewL','rotation'):-.65,
        ('DewC','y'):191, ('DewR','y'):187,
    }, {('DewL','rotation'):[[0,-.65],[90,-.5],[180,-.65]]})
    # A single gentle rise settles in an open fan; no indefinitely looping celebration.
    s.state('Done', 72, False, signal_pose('Done') | {
        ('Cloud','y'):114, ('Cloud','scaleX'):1.04, ('EyeL','scaleY'):.42,
        ('EyeR','scaleY'):.42, ('Mouth','scaleX'):1.25,
        ('DewL','x'):79, ('DewL','y'):178, ('DewL','rotation'):-.55,
        ('DewC','y'):187, ('DewR','x'):173, ('DewR','y'):178, ('DewR','rotation'):.55,
    }, {
        ('Cloud','y'):[[0,114],[24,109],[48,113],[72,114]],
        ('DewL','x'):[[0,79],[36,75],[72,79]],
        ('DewR','x'):[[0,173],[36,177],[72,173]],
    })
    # An asymmetric droop signals a recoverable problem without shaking or flashing.
    s.state('Error', 90, False, signal_pose('Error') | {
        ('Cloud','y'):127, ('Cloud','rotation'):-.07, ('Cloud','scaleY'):.94,
        ('Gaze','y'):3, ('Mouth','scaleX'):.55, ('Mouth','rotation'):.22,
        ('DewL','x'):101, ('DewL','y'):195, ('DewL','rotation'):-.1,
        ('DewC','x'):127, ('DewC','y'):201, ('DewR','x'):153, ('DewR','y'):195,
    }, {('Cloud','y'):[[0,123],[45,128],[90,127]]})
    # A tidy stationary pair below the pause badge is intentionally unlike error.
    s.state('Interrupted', 48, False, signal_pose('Interrupted') | {
        ('Cloud','y'):124, ('Cloud','scaleX'):.97, ('Cloud','scaleY'):.96,
        ('EyeL','scaleY'):.55, ('EyeR','scaleY'):.55, ('Mouth','scaleX'):.8,
        ('DewL','x'):104, ('DewL','y'):188, ('DewL','rotation'):0,
        ('DewC','x'):126, ('DewC','y'):198, ('DewR','x'):148, ('DewR','y'):188,
        ('DewR','rotation'):0,
    }, {('Cloud','y'):[[0,121],[24,125],[48,124]]})
    return s.export()


def folio():
    s = Scene('Folio')
    ink, cover, spine, paper = '453550', '986482', '6d4368', 'fff0cd'
    s.group('Book', x=120, y=145, rotation=-.035)
    s.group('Gaze', 'Book', x=0, y=-8)
    s.ellipse('EyeL', 'Gaze', -12, 0, 6, 10, ink)
    s.ellipse('EyeR', 'Gaze', 14, 0, 6, 10, ink)
    s.rect('Mouth', 'Book', 2, 9, 13, 4, ink, 2)
    s.rect('CoverRuleTop', 'Book', 2, -31, 39, 3, 'ddbf98', 1.5)
    s.rect('CoverRuleBottom', 'Book', 2, 28, 32, 3, 'ddbf98', 1.5)
    s.rect('CoverInset', 'Book', 2, -2, 66, 83, paper, 11)
    s.rect('SpineStripe', 'Book', -39, 0, 12, 107, spine, 5)
    s.rect('FrontCover', 'Book', 0, 0, 90, 111, cover, 12)
    s.rect('PageEdgeRuleA', 'Book', 48, 5, 3, 88, 'd7bfa0', 1.5)
    s.rect('PageEdgeRuleB', 'Book', 53, 5, 3, 85, 'd7bfa0', 1.5)
    s.rect('PageEdge', 'Book', 49, 4, 15, 101, 'f8e3bd', 5)
    s.rect('BackCover', 'Book', 7, 5, 101, 114, spine, 12)
    # Hinged page wing, with its own pivot and distinct page-specific movement.
    s.group('PageWing', 'Book', x=48, y=-16, rotation=.12)
    s.rect('WingRuleA', 'PageWing', 18, -9, 18, 2, 'c3a97d', 1)
    s.rect('WingRuleB', 'PageWing', 18, -2, 16, 2, 'c3a97d', 1)
    s.rect('WingRuleC', 'PageWing', 16, 5, 12, 2, 'c3a97d', 1)
    s.rect('WingPageFront', 'PageWing', 17, 0, 36, 45, paper, 7)
    s.rect('WingPageBack', 'PageWing', 19, 2, 38, 48, 'ddbf98', 7, rotation=.12)
    s.group('Bookmark', 'Book', x=-9, y=-72, rotation=-.08)
    indicator(s, 'Bookmark', ink)
    s.rect('BookmarkFace', 'Bookmark', 0, 0, 31, 30, 'fffaf0', 7)
    s.rect('BookmarkBacking', 'Bookmark', 0, 0, 36, 35, 'db907a', 9)
    s.rect('BookmarkRibbon', 'Bookmark', 0, 18, 12, 24, 'db907a', 3)
    s.group('FootL', 'Artboard', x=102, y=207, rotation=-.12)
    s.rect('ShoeL', 'FootL', 0, 0, 26, 12, spine, 5)
    s.group('FootR', 'Artboard', x=147, y=207, rotation=.12)
    s.rect('ShoeR', 'FootR', 0, 0, 26, 12, spine, 5)
    s.ellipse('Shadow', 'Artboard', 128, 222, 111, 9, 'e8dde5')
    s.target('Book', 'x', 'y', 'rotation', 'scaleY')
    s.target('PageWing', 'rotation', 'scaleX', 'y')
    s.target('Bookmark', 'rotation', 'y')
    s.target('Gaze', 'x', 'y')
    s.target('EyeL', 'scaleY')
    s.target('EyeR', 'scaleY')
    s.target('Mouth', 'scaleX', 'rotation')
    for foot in ('FootL', 'FootR'):
        s.target(foot, 'x', 'y', 'rotation')
    s.target('Shadow', 'scaleX')
    s.state('Idle', 192, True, signal_pose('Idle'), {
        ('Bookmark','rotation'):[[0,-.08],[96,.015],[192,-.08]],
        ('PageWing','rotation'):[[0,.12],[96,.16],[192,.12]],
    })
    # A tucked page corner and canted ribbon suggest checking an internal note.
    s.state('Thinking', 168, True, signal_pose('Thinking') | {
        ('Book','rotation'):-.09, ('Gaze','x'):-3, ('Gaze','y'):-2,
        ('PageWing','rotation'):-.38, ('PageWing','scaleX'):.7,
        ('Bookmark','rotation'):-.26, ('Mouth','scaleX'):.5,
    }, {('Bookmark','rotation'):[[0,-.26],[84,-.18],[168,-.26]]})
    # Page turning is the work action, rather than a floating/bouncing body.
    s.state('Working', 120, True, signal_pose('Working') | {
        ('Book','rotation'):.015, ('Gaze','x'):3, ('Gaze','y'):1,
        ('PageWing','rotation'):-.12, ('Bookmark','rotation'):.12,
        ('FootL','rotation'):-.18, ('FootR','rotation'):.18,
    }, {
        ('PageWing','scaleX'):[[0,1],[30,.38],[60,1],[90,.38],[120,1]],
        ('PageWing','rotation'):[[0,-.12],[30,-.28],[60,-.12],[90,-.28],[120,-.12]],
        ('Bookmark','y'):[[0,-72],[60,-75],[120,-72]],
    })
    # A closed page wing and feet together are a compact patient queue pose.
    s.state('Queued', 216, True, signal_pose('Queued') | {
        ('Book','rotation'):0, ('Book','y'):148, ('Book','scaleY'):.97,
        ('PageWing','scaleX'):.24, ('PageWing','rotation'):.02,
        ('Bookmark','rotation'):0, ('Bookmark','y'):-68,
        ('FootL','x'):109, ('FootR','x'):138, ('FootL','rotation'):0, ('FootR','rotation'):0,
        ('EyeL','scaleY'):.75, ('EyeR','scaleY'):.75,
    }, {('Bookmark','y'):[[0,-68],[108,-69.5],[216,-68]]})
    # A rightward open page listens for a provider while the book stays grounded.
    s.state('WaitingProvider', 180, True, signal_pose('WaitingProvider') | {
        ('Book','rotation'):.045, ('Gaze','x'):4,
        ('PageWing','rotation'):-.5, ('PageWing','scaleX'):1,
        ('Bookmark','rotation'):.18, ('Mouth','scaleX'):.65,
    }, {('PageWing','rotation'):[[0,-.5],[90,-.4],[180,-.5]]})
    # The ribbon rises and the wing opens toward the reader as an invitation.
    s.state('WaitingUser', 180, True, signal_pose('WaitingUser') | {
        ('Book','rotation'):-.015, ('Book','y'):143,
        ('PageWing','rotation'):-.7, ('Bookmark','y'):-78,
        ('Bookmark','rotation'):0, ('Mouth','scaleX'):1.2,
    }, {('Bookmark','rotation'):[[0,0],[90,.08],[180,0]]})
    # A single bow then an upright open page: done is calm and finite.
    s.state('Done', 84, False, signal_pose('Done') | {
        ('Book','rotation'):0, ('Book','y'):142, ('PageWing','rotation'):-.8,
        ('Bookmark','rotation'):.08, ('Bookmark','y'):-75,
        ('EyeL','scaleY'):.4, ('EyeR','scaleY'):.4, ('Mouth','scaleX'):1.25,
        ('FootL','x'):99, ('FootR','x'):150,
    }, {
        ('Book','rotation'):[[0,0],[28,.075],[56,-.02],[84,0]],
        ('PageWing','rotation'):[[0,-.8],[28,-.96],[84,-.8]],
    })
    # The paper folds inward and the bookmark droops; one settling motion only.
    s.state('Error', 90, False, signal_pose('Error') | {
        ('Book','rotation'):-.09, ('Book','y'):149, ('Gaze','y'):3,
        ('PageWing','rotation'):.48, ('PageWing','scaleX'):.45,
        ('Bookmark','rotation'):-.35, ('Bookmark','y'):-65,
        ('Mouth','scaleX'):.55, ('Mouth','rotation'):.18,
        ('FootL','rotation'):-.03, ('FootR','rotation'):.03,
    }, {('Bookmark','rotation'):[[0,-.22],[45,-.38],[90,-.35]]})
    # Page held halfway, parallel feet and pause tab distinguish interruption.
    s.state('Interrupted', 48, False, signal_pose('Interrupted') | {
        ('Book','rotation'):0, ('Book','y'):147, ('PageWing','rotation'):-.12,
        ('PageWing','scaleX'):.55, ('Bookmark','rotation'):0,
        ('EyeL','scaleY'):.65, ('EyeR','scaleY'):.65,
        ('FootL','rotation'):0, ('FootR','rotation'):0, ('Mouth','scaleX'):.8,
    }, {('PageWing','scaleX'):[[0,.7],[24,.53],[48,.55]]})
    return s.export()


def validate(scene):
    """Strict structural checks for the requested 18-animation contract only.

    This is not a substitute for the Kotlin compiler, a Rive runtime or Android QA.
    """
    def number(value, lo, hi):
        assert type(value) in (int, float) and math.isfinite(value) and lo <= value <= hi

    def name(value):
        assert type(value) is str and 1 <= len(value.encode('utf-8')) <= 80
        assert all(ord(c) >= 32 and ord(c) != 127 for c in value)

    def transform(value, prop):
        limit = 4 if prop.startswith('scale') else 2048
        number(value, -limit, limit)

    assert set(scene) == {'contract', 'name', 'nodes', 'animations'}
    assert scene['contract'] == 'bot-mascot-v1'
    name(scene['name'])
    assert 1 <= len(scene['nodes']) <= 96
    depths = {'Artboard': 0}
    shapes = 0
    for node in scene['nodes']:
        name(node['name'])
        assert node['name'] not in depths
        assert node['kind'] in ('group', 'ellipse', 'rectangle')
        assert node['parent'] in depths
        depths[node['name']] = depths[node['parent']] + 1
        assert depths[node['name']] <= 8
        allowed = {'kind', 'name', 'parent'} | set(PROPERTIES)
        if node['kind'] != 'group':
            shapes += 1
            allowed |= {'width', 'height', 'color'}
            number(node['width'], 1, 256)
            number(node['height'], 1, 256)
            assert type(node['color']) is int and 0 <= node['color'] <= 0xFFFFFFFF
            assert node['color'] >> 24 == 255  # Opaque solid colors, no flashing alpha.
        if node['kind'] == 'rectangle':
            allowed.add('radius')
            number(node['radius'], 0, 128)
        assert set(node) <= allowed
        for prop in PROPERTIES:
            if prop in node:
                transform(node[prop], prop)
    assert set(scene['animations']) == set(ANIMATIONS)
    targets, total_tracks, total_keys, poses, motions = None, 0, 0, set(), set()
    for state, anim in scene['animations'].items():
        assert set(anim) == {'duration', 'loop', 'tracks'}
        assert type(anim['duration']) is int and 1 <= anim['duration'] <= 600
        assert type(anim['loop']) is bool and 1 <= len(anim['tracks']) <= 64
        local = set()
        for track in anim['tracks']:
            assert set(track) == {'node', 'property', 'keys'}
            assert track['node'] in depths and track['node'] != 'Artboard'
            assert track['property'] in PROPERTIES
            target = (track['node'], track['property'])
            assert target not in local
            local.add(target)
            assert 1 <= len(track['keys']) <= 64 and track['keys'][0][0] == 0
            previous = -1
            for key in track['keys']:
                assert type(key) is list and len(key) == 2
                frame, value = key
                assert type(frame) is int and previous < frame <= anim['duration']
                previous = frame
                transform(value, track['property'])
            if state.endswith('Reduced'):
                assert not anim['loop'] and len(track['keys']) == 1
            total_keys += len(track['keys'])
        total_tracks += len(anim['tracks'])
        if targets is None:
            targets = local
        assert local == targets
        visible_signals = [t for t in anim['tracks'] if t['node'].startswith('Signal')
                           and t['keys'][0][1] == 1]
        assert len(visible_signals) == 1
        assert visible_signals[0]['node'] == 'Signal' + state.removesuffix('Reduced')
        if state.endswith('Reduced'):
            # Prove distinct constant character poses even if the badge is excluded.
            pose = [(t['node'], t['property'], t['keys'][0][1]) for t in anim['tracks']
                    if not t['node'].startswith('Signal')]
            poses.add(json.dumps(pose, separators=(',', ':')))
        else:
            moving = [t for t in anim['tracks'] if len({k[1] for k in t['keys']}) > 1]
            assert moving
            motions.add(json.dumps(moving, separators=(',', ':')))
            if anim['loop']:
                assert all(t['keys'][0][1] == t['keys'][-1][1] for t in anim['tracks'])
    assert len(poses) == 9 and len(motions) == 9
    assert total_tracks <= 18 * 64 and total_keys <= 18 * 64 * 64
    encoded = (json.dumps(scene, indent=2, ensure_ascii=False, allow_nan=False) + '\n').encode()
    assert len(encoded) <= 128 * 1024
    return encoded, dict(nodes=len(scene['nodes']), shapes=shapes, depth=max(depths.values()),
                         tracks_per_state=len(targets), total_tracks=total_tracks,
                         total_keys=total_keys, source_bytes=len(encoded),
                         sha256=hashlib.sha256(encoded).hexdigest())


def main():
    output = Path(__file__).resolve().parent
    scenes = (nimbo(), folio())
    # Geometry genuinely differs; these cannot be recolors of one common node tree.
    geometry = lambda x: [(n['kind'], n['name'], n['parent'], n.get('width'), n.get('height'))
                          for n in x['nodes'] if not n['name'].startswith('Signal')]
    assert geometry(scenes[0]) != geometry(scenes[1])
    for scene in scenes:
        encoded, report = validate(scene)
        path = output / (scene['name'].lower() + '.json')
        path.write_bytes(encoded)
        assert path.read_bytes() == encoded
        print(scene['name'] + ': ' + json.dumps(report, sort_keys=True))
    print('PASS: structural authoring assertions only; no binary/runtime/Android claim.')


if __name__ == '__main__':
    main()

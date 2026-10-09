#!/usr/bin/env python3
"""Offline authoring regression checks, not a Rive renderer or Android test."""
import hashlib
import json
import math
import os
from pathlib import Path
import unittest

SOURCE = Path(os.environ.get('JARVYS_SCENE_SOURCE', str(Path(__file__).with_name('jarvys-main.scene.json'))))
MODES = ('Idle', 'Thinking', 'Working', 'Queued', 'WaitingProvider',
         'WaitingUser', 'Done', 'Error', 'Interrupted')
SOURCE_SHA256 = '983546e91329e6ab22e1191fbfb0f3c240384c8eabbacc62a1d96951e39e236b'
PROPERTIES = {'x', 'y', 'rotation', 'scaleX', 'scaleY'}


class JarvysSceneTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.raw = SOURCE.read_bytes()
        cls.scene = json.loads(cls.raw)
        cls.animations = cls.scene['animations']

    def tracks(self, state):
        return {(t['node'], t['property']): t['keys'] for t in self.animations[state]['tracks']}

    def test_frozen_source_identity(self):
        self.assertEqual(hashlib.sha256(self.raw).hexdigest(), SOURCE_SHA256)
        self.assertEqual(set(self.scene), {'contract', 'name', 'nodes', 'animations'})
        self.assertEqual(self.scene['contract'], 'bot-mascot-v1')
        self.assertEqual(self.scene['name'], 'Jarvys')
        self.assertLessEqual(len(self.raw), 131072)

    def test_original_bounded_geometry(self):
        nodes = self.scene['nodes']
        self.assertEqual(len(nodes), 61)
        depths = {'Artboard': 0}
        for node in nodes:
            self.assertNotIn(node['name'], depths)
            self.assertIn(node['parent'], depths)
            self.assertIn(node['kind'], ('group', 'ellipse', 'rectangle'))
            depths[node['name']] = depths[node['parent']] + 1
            self.assertLessEqual(depths[node['name']], 8)
            for key in PROPERTIES.intersection(node):
                self.assertTrue(math.isfinite(node[key]))
                self.assertLessEqual(abs(node[key]), 4 if key.startswith('scale') else 2048)
            if node['kind'] != 'group':
                self.assertTrue(1 <= node['width'] <= 256)
                self.assertTrue(1 <= node['height'] <= 256)
                self.assertIs(type(node['color']), int)
                self.assertTrue(0 <= node['color'] <= 0xFFFFFFFF)
        for actor in ('Jarvys', 'Gaze', 'EyeLeft', 'EyeRight', 'HandLeft', 'HandRight',
                      'AntennaLeft', 'AntennaRight', 'FootLeft', 'FootRight', 'Chest'):
            self.assertIn(actor, depths)

    def test_exact_product_contract_and_full_resets(self):
        self.assertEqual(tuple(self.animations), MODES + tuple(m + 'Reduced' for m in MODES))
        targets = set(self.tracks('Idle'))
        self.assertEqual(len(targets), 31)
        nodes = {n['name'] for n in self.scene['nodes']}
        for state, animation in self.animations.items():
            self.assertTrue(1 <= animation['duration'] <= 600)
            self.assertIs(type(animation['loop']), bool)
            self.assertEqual(len(animation['tracks']), len(targets))
            self.assertEqual(set(self.tracks(state)), targets)
            for track in animation['tracks']:
                self.assertIn(track['node'], nodes)
                self.assertIn(track['property'], PROPERTIES)
                keys = track['keys']
                self.assertTrue(1 <= len(keys) <= 64)
                self.assertEqual(keys[0][0], 0)
                self.assertTrue(all(a[0] < b[0] for a, b in zip(keys, keys[1:])))
                self.assertLessEqual(keys[-1][0], animation['duration'])
                for frame, value in keys:
                    self.assertIs(type(frame), int)
                    self.assertTrue(math.isfinite(value))
                    self.assertLessEqual(abs(value), 4 if track['property'].startswith('scale') else 2048)

    def test_real_timing_and_closed_loops(self):
        expected = (300, 288, 240, 240, 240, 240, 210, 210, 180)
        for state, frames in zip(MODES, expected):
            animation = self.animations[state]
            self.assertEqual(animation['duration'], frames)
            self.assertEqual(animation['loop'], state not in ('Done', 'Error', 'Interrupted'))
            moving = 0
            last_main_action = 0
            for (node, prop), keys in self.tracks(state).items():
                changed = [(a, b) for a, b in zip(keys, keys[1:]) if a[1] != b[1]]
                moving += bool(changed)
                if node in ('Jarvys', 'HandLeft', 'HandRight') and prop in ('x', 'y', 'rotation'):
                    last_main_action = max(last_main_action, max((b[0] for _, b in changed), default=0))
                if animation['loop']:
                    self.assertEqual(keys[0][1], keys[-1][1], (state, node, prop))
            self.assertGreater(moving, 0)
            self.assertGreaterEqual(last_main_action, .8 * frames, state)

    def test_distinct_action_vocabulary(self):
        # These authored-motion checks catch regression to the former 1-3 px bob.
        # Actual-size runtime pixel review remains required.
        def travel(state, node, prop):
            values = [v for _, v in self.tracks(state)[node, prop]]
            return max(values) - min(values)
        for state, node, prop, minimum in (
                ('Thinking', 'HandLeft', 'y', 50),
                ('Working', 'HandLeft', 'x', 35),
                ('Working', 'HandRight', 'x', 35),
                ('WaitingProvider', 'HandRight', 'y', 35),
                ('WaitingUser', 'HandLeft', 'x', 50),
                ('Done', 'HandLeft', 'y', 60),
                ('Done', 'HandRight', 'y', 60),
                ('Error', 'HandLeft', 'x', 50),
                ('Error', 'HandRight', 'x', 50),
                ('Interrupted', 'HandRight', 'y', 40)):
            self.assertGreaterEqual(travel(state, node, prop), minimum, (state, node))
        self.assertGreaterEqual(travel('Queued', 'Jarvys', 'x'), 16)
        self.assertLessEqual(abs(self.tracks('Queued')['HandLeft', 'x'][0][1]), 40)
        self.assertGreaterEqual(max(v for _, v in self.tracks('WaitingUser')['HandLeft', 'scaleX']), 1.3)
        # Error inspection stays low: it no longer repeats Thinking's temple motif.
        self.assertGreaterEqual(min(v for _, v in self.tracks('Error')['HandLeft', 'y']), 0)

    def test_static_reduced_and_distinct_terminal_poses(self):
        reduced = []
        for mode in MODES:
            name = mode + 'Reduced'
            self.assertFalse(self.animations[name]['loop'])
            pose = []
            for keys in self.tracks(name).values():
                self.assertEqual(len(keys), 1)
                pose.append(keys[0][1])
            reduced.append(tuple(pose))
        self.assertEqual(len(set(reduced)), 9)
        terminal = [tuple(keys[-1][1] for keys in self.tracks(state).values())
                    for state in ('Done', 'Error', 'Interrupted')]
        self.assertEqual(len(set(terminal + [reduced[0]])), 4)


if __name__ == '__main__':
    unittest.main()

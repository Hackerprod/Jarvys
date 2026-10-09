#!/usr/bin/env python3
"""Conservative every-quarter-frame transform bounds, not Rive pixel evidence."""
import json
import math
import argparse
from pathlib import Path

ROOT = Path(__file__).resolve().parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--scene', type=Path, default=ROOT / 'jarvys-main.scene.json')
scene = json.loads(parser.parse_args().scene.read_text())
assert scene.get('name') == 'Jarvys' and len(scene['animations']) == 18
sample_count = 0


def compose(a, b):
    return (a[0]*b[0]+a[2]*b[1], a[1]*b[0]+a[3]*b[1],
            a[0]*b[2]+a[2]*b[3], a[1]*b[2]+a[3]*b[3],
            a[0]*b[4]+a[2]*b[5]+a[4], a[1]*b[4]+a[3]*b[5]+a[5])


def sample(keys, frame):
    for first, last in zip(keys, keys[1:]):
        if first[0] <= frame <= last[0]:
            weight = (frame-first[0]) / (last[0]-first[0])
            return first[1] + weight*(last[1]-first[1])
    return keys[-1][1]


results = {}
for name, animation in scene['animations'].items():
    bounds = [float('inf'), float('inf'), -float('inf'), -float('inf')]
    for quarter in range(animation['duration'] * 4 + 1):
        frame = quarter / 4
        sample_count += 1
        values = {(t['node'], t['property']): sample(t['keys'], frame)
                  for t in animation['tracks']}
        matrices = {'Artboard': (1, 0, 0, 1, 0, 0)}
        for node in scene['nodes']:
            v = {key: values.get((node['name'], key), node.get(key, 1 if key.startswith('scale') else 0))
                 for key in ('x', 'y', 'rotation', 'scaleX', 'scaleY')}
            c, s = math.cos(v['rotation']), math.sin(v['rotation'])
            local = (c*v['scaleX'], s*v['scaleX'], -s*v['scaleY'], c*v['scaleY'], v['x'], v['y'])
            m = compose(matrices[node['parent']], local)
            matrices[node['name']] = m
            if node['kind'] == 'group':
                continue
            w, h = node['width']/2, node['height']/2
            if node['kind'] == 'ellipse':
                dx, dy = math.hypot(m[0]*w, m[2]*h), math.hypot(m[1]*w, m[3]*h)
            else:
                dx, dy = abs(m[0]*w)+abs(m[2]*h), abs(m[1]*w)+abs(m[3]*h)
            bounds = [min(bounds[0], m[4]-dx), min(bounds[1], m[5]-dy),
                      max(bounds[2], m[4]+dx), max(bounds[3], m[5]+dy)]
    assert min(bounds) > 0 and max(bounds) < 256, (name, bounds)
    results[name] = bounds
report = {'method': 'Analytic node-transform extents, every quarter frame; rectangles conservatively include square corners. Not Rive pixel evidence.',
          'all_states_inside_artboard': True,
          'quarter_frame_samples': sample_count,
          'minimum_conservative_margin': round(min(min(b[0], b[1], 256-b[2], 256-b[3]) for b in results.values()), 3),
          'state_extents': results}
print(json.dumps(report, indent=2))

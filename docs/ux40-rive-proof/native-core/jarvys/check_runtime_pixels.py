"""Fail-closed checks of a complete frozen Jarvys v3 runtime-render fixture.

Measures actual opaque pod-highlight and antenna-tip pixels, not scene transforms.
These are motion-visibility checks, not a promise of unlabeled status recognition.
"""
from pathlib import Path
import hashlib
import json
import os
import numpy as np
from PIL import Image

MODES = ('Idle', 'Thinking', 'Working', 'Queued', 'WaitingProvider',
         'WaitingUser', 'Done', 'Error', 'Interrupted')
SIZES = (22, 30, 34, 256)
FPS = 20
SOURCE_SHA256 = '983546e91329e6ab22e1191fbfb0f3c240384c8eabbacc62a1d96951e39e236b'
RIV_SHA256 = '514c6cfb16008eb6ecfd13c136ab6032c2ebd615c11f2325491fa762fc73981a'


def require(condition, message):
    if not condition:
        raise ValueError(message)


def load_inputs(root, scene_path):
    """Validate all metadata and file inventory before reading any image."""
    require(scene_path.is_file(), 'Missing frozen scene source')
    source = scene_path.read_bytes()
    require(hashlib.sha256(source).hexdigest() == SOURCE_SHA256, 'Wrong frozen scene source')
    scene = json.loads(source)
    require(scene.get('contract') == 'bot-mascot-v1' and scene.get('name') == 'Jarvys', 'Wrong scene contract')
    require(tuple(scene['animations']) == MODES + tuple(n+'Reduced' for n in MODES), 'Incomplete source states')
    report_path = root / 'runtime-render-report.json'
    require(report_path.is_file(), 'Missing runtime render report')
    render = json.loads(report_path.read_text())
    require(render.get('sourceSha256') == SOURCE_SHA256, 'Missing or wrong frozen source hash in render report')
    require(render.get('rivSha256') == RIV_SHA256, 'Missing or wrong frozen binary hash in render report')
    states = render.get('states')
    require(isinstance(states, list) and len(states) == 9, 'Expected exactly nine rendered states')
    sizes = render.get('sizes')
    require(isinstance(sizes, list) and len(sizes) == 4 and
            all(type(n) is int for n in sizes) and sorted(sizes) == list(SIZES), 'Expected exactly four render sizes')
    require(all(isinstance(s, dict) for s in states), 'Invalid state report entry')
    require([s.get('name') for s in states] == list(MODES), 'Expected exact unique state names in mode order')
    require(all(type(s.get('mode')) is int for s in states) and
            [s.get('mode') for s in states] == list(range(9)), 'Expected exact unique mode indices')
    expected_captures = 0
    for state in states:
        animation = scene['animations'][state['name']]
        duration = animation['duration']
        require(type(duration) is int and duration > 0 and duration % 3 == 0, 'Invalid frozen duration')
        expected_frames = duration // 3
        require(type(state.get('renderFrames')) is int and state['renderFrames'] == expected_frames and
                expected_frames > 0, 'Wrong positive frame count: '+state['name'])
        require(type(state.get('fps')) is int and state['fps'] == FPS, 'Wrong fps: '+state['name'])
        require(type(state.get('durationSeconds')) in (int, float) and
                state['durationSeconds'] == duration / 60, 'Wrong duration: '+state['name'])
        require(type(state.get('loop')) is bool and state['loop'] == animation['loop'], 'Wrong loop flag: '+state['name'])
        expected_captures += expected_frames * len(SIZES) + 13
    require(type(render.get('captures')) is int and render['captures'] == expected_captures,
            'Wrong total capture count')
    frames_root = root / 'runtime-frames'
    require(frames_root.is_dir(), 'Missing runtime frame directory')
    require({p.name for p in frames_root.iterdir()} == set(MODES), 'Missing or extra state directories')
    for state in states:
        folder = frames_root / state['name']
        require(folder.is_dir(), 'State frame path is not a directory: '+state['name'])
        expected = {f'{size}-{f:03}.png' for size in SIZES for f in range(state['renderFrames'])}
        expected |= {f'{size}-{suffix}.png' for size in SIZES for suffix in ('final', 'after-3s', 'reduced')}
        expected.add('256-reduced-after-4s.png')
        present = {p.name for p in folder.iterdir()}
        require(present == expected, 'Missing or extra frame files for '+state['name']+
                ': missing='+str(sorted(expected-present))+', extra='+str(sorted(present-expected)))
        require(all((folder / name).is_file() for name in expected), 'Frame entry is not a regular file')
    return scene, render


def pixels(path, size):
    with Image.open(path) as image:
        require(image.format == 'PNG' and image.mode == 'RGBA', 'Expected RGBA PNG: '+path.name)
        require(image.size == (size, size), 'Wrong image dimensions: '+path.name)
        array = np.array(image)
    require(array.shape == (size, size, 4), 'Wrong pixel array shape: '+path.name)
    require(bool(np.any(array[:, :, 3] > 0)), 'Empty transparent image: '+path.name)
    return array


def feature(a, side):
    n = a.shape[0]
    mask = (a[:,:,0] >= 243) & (a[:,:,1] >= 246) & (a[:,:,1] <= 249) & (a[:,:,2] >= 250) & (a[:,:,3] >= 200)
    if side == 'left': mask[:,n//2:] = False
    else: mask[:,:n//2] = False
    yy, xx = np.where(mask)
    return None if len(xx) == 0 else [float(xx.mean()), float(yy.mean())]


def antenna_feature(a, side):
    n = a.shape[0]
    mask = (a[:,:,0] < 150) & (a[:,:,1] > 160) & (a[:,:,2] > 180) & (a[:,:,3] > 100)
    mask[int(n*.28):,:] = False
    if side == 'left': mask[:,int(n*.36):] = False
    else: mask[:,:int(n*.64)] = False
    yy, xx = np.where(mask)
    return None if len(xx) == 0 else [float(xx.mean()), float(yy.mean())]


def span(points):
    valid = np.array([p for p in points if p is not None])
    if len(valid) < 2: return None
    return round(float(np.sqrt(((valid[:,None,:]-valid[None,:,:])**2).sum(axis=2)).max()), 3)


def validate_fixture(root, scene_path):
    root, scene_path = Path(root), Path(scene_path)
    _, render = load_inputs(root, scene_path)
    report = {}
    for state in render['states']:
        name = state['name']; folder = root / 'runtime-frames' / name; result = {}
        terminal_stability = []
        for size in SIZES:
            # Exact positive indexed filenames, never a potentially empty glob.
            arrays = [pixels(folder / f'{size}-{f:03}.png', size) for f in range(state['renderFrames'])]
            require(len(arrays) == state['renderFrames'] and len(arrays) > 0, 'Incomplete image array')
            require(any(not np.array_equal(arrays[0], a) for a in arrays[1:]), 'Normal timeline has no rendered motion: '+name)
            clips = []
            markers = {side: [feature(a, side) for a in arrays] for side in ('left', 'right')}
            last_motion = {}
            for side, positions in markers.items():
                last = 0
                for f in range(5, len(positions)):
                    if positions[f] is not None and positions[f-5] is not None:
                        distance = float(np.linalg.norm(np.array(positions[f])-positions[f-5]))
                        if distance >= .5*(size/22): last = f/FPS
                last_motion[side] = last
            for i, a in enumerate(arrays):
                if np.any(a[0,:,3]) or np.any(a[-1,:,3]) or np.any(a[:,0,3]) or np.any(a[:,-1,3]): clips.append(i)
            require(not clips, 'Rendered border touches: '+name+' '+str(size))
            final = pixels(folder / f'{size}-final.png', size)
            after = pixels(folder / f'{size}-after-3s.png', size)
            pixels(folder / f'{size}-reduced.png', size)
            if not state['loop']:
                terminal_stability.append(bool(np.array_equal(final, after)))
                require(terminal_stability[-1], 'Terminal pose changed after settled endpoint: '+name+' '+str(size))
            result[str(size)] = {'frames': len(arrays), 'border_touch_frames': clips,
                'pod_highlight_span_px': {side: span(points) for side, points in markers.items()},
                'marker_missing_frames': {side: sum(p is None for p in points) for side, points in markers.items()},
                'antenna_tip_span_px': {side: span([antenna_feature(a, side) for a in arrays]) for side in ('left', 'right')},
                'last_detected_pod_motion_seconds': last_motion}
        reduced = pixels(folder / '256-reduced.png', 256)
        later = pixels(folder / '256-reduced-after-4s.png', 256)
        result['reduced_static_after_4s'] = bool(np.array_equal(reduced, later))
        require(result['reduced_static_after_4s'], 'Reduced pose changed: '+name)
        if not state['loop']:
            require(len(terminal_stability) == 4, 'Incomplete terminal stability samples')
            result['one_shot_holds_final_after_3s'] = all(terminal_stability)
        report[name] = result
    require(tuple(report) == MODES, 'Incomplete final result set')
    return {'method': __doc__, 'runtime': 'Official Canvas2D WASM actual-size images; not Android.',
            'source_sha256': SOURCE_SHA256, 'riv_sha256': RIV_SHA256, 'passed': True, 'results': report}


def main():
    root = Path(os.environ['JARVYS_PROOF_OUTPUT']).resolve()
    scene_path = Path(os.environ.get('JARVYS_SCENE_SOURCE', str(Path(__file__).with_name('jarvys-main.scene.json'))))
    report = validate_fixture(root, scene_path)
    (root / 'runtime-pixel-checks.json').write_text(json.dumps(report, indent=2)+'\n')
    for name, result in report['results'].items():
        print(name, '22px pod spans', result['22']['pod_highlight_span_px'],
              'antenna spans', result['22']['antenna_tip_span_px'], 'complete fixture passed')


if __name__ == '__main__':
    main()

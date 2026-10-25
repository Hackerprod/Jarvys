"""Render a contact sheet of animation poses to check the drawing stays coherent when parts move.
Usage: python poses.py  -> out/poses_sheet.png (4x3 grid, each cell labelled in poses_index.txt)"""
import os
from PIL import Image
from cmp import render, OUT, HERE

POSES = [
    ('default', {}),
    ('blink', {'open': 0.0}),
    ('half_blink', {'open': 0.45}),
    ('happy', {'happy': 1, 'mouth': 1, 'mopen': 0.5, 'blush': 0.95, 'earL': 0.4, 'earR': 0.4, 'swirl': 1.2}),
    ('look_left_up', {'lx': -1, 'ly': -1}),
    ('look_right_down', {'lx': 1, 'ly': 1}),
    ('ears_up', {'earL': 0.55, 'earR': 0.55, 'browL': 0.5, 'browR': 0.5}),
    ('ears_down_sleep', {'open': 0, 'earL': -0.35, 'earR': -0.35, 'tilt': 0.06, 'star': 0.6}),
    ('error', {'worry': 1, 'red': 1, 'mouth': -0.6, 'mopen': 0.15, 'earL': -0.2, 'earR': -0.2, 'open': 1.12, 'blush': 0, 'browL': 0.7, 'browR': 0.7}),
    ('typing_hands', {'handL': 22, 'handR': -22, 'ly': 0.85, 'star': 1.3, 'spin': 0.8}),
    ('squash', {'sx': 1.14, 'sy': 0.86}),
    ('stretch_tilt_swirl', {'sx': 0.92, 'sy': 1.12, 'tilt': -0.09, 'swirl': 1.0}),
]
if __name__ == '__main__':
    os.makedirs(OUT, exist_ok=True)
    cells = []
    for i, (name, pose) in enumerate(POSES):
        im = render(pose, OUT + '/pose_%02d.png' % i)
        cells.append(im.resize((420, 420), Image.LANCZOS))
    sheet = Image.new('RGB', (420 * 4, 420 * 3))
    for i, c in enumerate(cells):
        sheet.paste(c, ((i % 4) * 420, (i // 4) * 420))
    sheet.save(OUT + '/poses_sheet.png')
    open(OUT + '/poses_index.txt', 'w').write('\n'.join('%d: %s' % (i, n) for i, (n, _) in enumerate(POSES)))
    print('wrote out/poses_sheet.png; order row-major:', ', '.join(n for n, _ in POSES))

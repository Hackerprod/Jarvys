"""Compose individual exact-speed previews from official-runtime pixels only.

Robot pixels are never rescaled. Labels give elapsed time and actual pixel size,
not state names, so the initial visual review can test gesture differentiation.
"""
from pathlib import Path
import os
import json
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(os.environ['JARVYS_PROOF_OUTPUT']).resolve()
OUT = ROOT / 'individual-previews'
OUT.mkdir(exist_ok=True)
REPORT = json.loads((ROOT / 'runtime-render-report.json').read_text())
STATES = {entry['name']: entry for entry in REPORT['states']}
ORDER = ['Working','Error','WaitingUser','Idle','Interrupted','Thinking','Done','Queued','WaitingProvider']
FONT_PATH = os.environ.get('JARVYS_PROOF_FONT', 'DejaVuSans.ttf')
FONT = ImageFont.truetype(FONT_PATH, 15)
SMALL = ImageFont.truetype(FONT_PATH, 12)
BG = '#111827'

def paste(image, path, xy):
    source = Image.open(path).convert('RGBA')
    image.paste(source, xy, source)

def panel(state, frame, elapsed, caption=True):
    folder = ROOT / 'runtime-frames' / state
    image = Image.new('RGB',(380,410),BG)
    draw = ImageDraw.Draw(image)
    paste(image, folder / f'256-{frame}.png', (62,12))
    for size, x in [(22,96),(30,175),(34,254)]:
        paste(image, folder / f'{size}-{frame}.png', (x,300-size//2))
        draw.text((x-5,330),f'{size} px',font=SMALL,fill='#c2cbd8')
    draw.text((18,369),f'{elapsed:.2f} s',font=FONT,fill='white')
    if caption:
        draw.text((92,372),'Official Rive runtime · actual avatar pixels',font=SMALL,fill='#c2cbd8')
    return image

manifest = []
for index, state in enumerate(ORDER,1):
    info = STATES[state]
    code = f'clip-{index:02}'
    frames = [panel(state,f'{f:03}',f/20) for f in range(info['renderFrames'])]
    options = dict(save_all=True,append_images=frames[1:],duration=50,optimize=False,disposal=2)
    if info['loop']: options['loop'] = 0
    frames[0].save(OUT / f'{code}.gif',**options)
    # Endpoint proof is separate; no padded hold inflates the GIF duration.
    panel(state,'final',info['durationSeconds']).save(OUT / f'{code}-final.png')
    panel(state,'reduced',0).save(OUT / f'{code}-reduced.png')
    # Six ordered phases of this ONE gesture, with a true 256 px runtime capture
    # and the unscaled avatar sizes below each. No multi-state montage.
    indices = [round((info['renderFrames']-1)*f/5) for f in range(6)]
    sheet = Image.new('RGB',(380*3,410*2),BG)
    for col, f in enumerate(indices):
        sheet.paste(panel(state,f'{f:03}',f/20),(col%3*380,col//3*410))
    sheet.save(OUT / f'{code}-phases.png')
    check = Image.open(OUT/f'{code}.gif')
    milliseconds=0
    for f in range(check.n_frames):
        check.seek(f);milliseconds += check.info['duration']
    assert abs(milliseconds/1000-info['durationSeconds'])<.001
    assert info['loop'] or 'loop' not in check.info
    manifest.append({'code':code,'state':state,'gif_seconds':milliseconds/1000,
                     'runtime_frames':info['renderFrames'],'fps':20,'loop':info['loop'],
                     'gif':f'{code}.gif','phases':f'{code}-phases.png',
                     'final':f'{code}-final.png','reduced':f'{code}-reduced.png'})
(ROOT/'preview-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
print('Nine individual previews and 27 supporting images written. True duration verified.')

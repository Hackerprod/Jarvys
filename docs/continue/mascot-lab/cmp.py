"""Render jdraw.js (default pose) with headless Edge and compare against reference.png.
Usage: python cmp.py [--grid] [--crop x0,y0,x1,y1] [--rows y0,y1,step]
Outputs in out/: mine.png, sheet.png (ref | mine / 50% blend | silhouette diff), crop_cmp.png (with --crop)
Prints IoU of silhouettes and mean abs RGB diff over the full image composited on #0c1033."""
import subprocess, sys, os, json, argparse
from urllib.parse import quote
from PIL import Image
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, 'out')
EDGE = r'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'
BG = (12, 16, 51)


def render(pose, path, size=1254):
    if os.path.exists(path):
        os.remove(path)
    url = 'file:///' + HERE.replace('\\', '/') + '/render.html'
    if pose:
        url += '?pose=' + quote(json.dumps(pose))
    subprocess.run([EDGE, '--headless=new', '--disable-gpu', '--hide-scrollbars', '--force-device-scale-factor=1',
                    '--window-size=%d,%d' % (size, size), '--allow-file-access-from-files', '--virtual-time-budget=2000',
                    '--screenshot=' + path.replace('/', '\\'), url], capture_output=True, timeout=90)
    return Image.open(path).convert('RGB').crop((0, 0, size, size))


def segs(row):
    idx = np.where(row)[0]
    if len(idx) == 0:
        return []
    out = []
    s = p = idx[0]
    for i in idx[1:]:
        if i != p + 1:
            out.append((int(s), int(p))); s = i
        p = i
    out.append((int(s), int(p)))
    return out


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('--grid', action='store_true')
    ap.add_argument('--crop')
    ap.add_argument('--rows')
    a = ap.parse_args()
    os.makedirs(OUT, exist_ok=True)
    bg = Image.new('RGBA', (1254, 1254), BG + (255,))
    ref = Image.alpha_composite(bg, Image.open(os.path.join(HERE, 'reference.png')).convert('RGBA')).convert('RGB')
    mine = render(None, OUT + '/mine.png')
    R, M = np.array(ref).astype(int), np.array(mine).astype(int)
    bgc = np.array(BG)
    fr = np.abs(R - bgc).sum(2) > 60
    fm = np.abs(M - bgc).sum(2) > 60
    iou = (fr & fm).sum() / max(1, (fr | fm).sum())
    print('IoU %.3f  meanabsdiff %.2f' % (iou, np.abs(R - M).mean()))
    sil = np.zeros((1254, 1254, 3), np.uint8)
    sil[fr & ~fm] = (255, 60, 60); sil[fm & ~fr] = (60, 255, 90); sil[fr & fm] = (70, 70, 90)
    sheet = Image.new('RGB', (2508, 2508))
    sheet.paste(ref, (0, 0)); sheet.paste(mine, (1254, 0)); sheet.paste(Image.blend(ref, mine, 0.5), (0, 1254)); sheet.paste(Image.fromarray(sil), (1254, 1254))
    sheet.resize((1400, 1400), Image.LANCZOS).save(OUT + '/sheet.png')
    if a.grid:
        both = fr & fm; n = 12; s = 1254 // n
        for gy in range(n):
            line = []
            for gx in range(n):
                sl = (slice(gy * s, (gy + 1) * s), slice(gx * s, (gx + 1) * s))
                if both[sl].sum() < 200:
                    line.append('  . '); continue
                line.append('%+4d' % int((R[sl] - M[sl])[both[sl]].mean()))
            print(' '.join(line))
        print('(mean(ref - mine) brightness; + means ref lighter; cells are %dpx)' % s)
    if a.crop:
        x0, y0, x1, y1 = [int(v) for v in a.crop.split(',')]
        A, B = ref.crop((x0, y0, x1, y1)), mine.crop((x0, y0, x1, y1)); w, h = A.size; k = min(2.0, 1400 / (2 * w))
        sh = Image.new('RGB', (w * 2, h)); sh.paste(A, (0, 0)); sh.paste(B, (w, 0))
        sh.resize((int(w * 2 * k), int(h * k)), Image.LANCZOS).save(OUT + '/crop_cmp.png')
    if a.rows:
        y0, y1, st = [int(v) for v in a.rows.split(',')]
        for y in range(y0, y1, st):
            print(y, 'ref', segs(fr[y]), 'mine', segs(fm[y]))

"""Validate real rendered captures and assemble a labeled proof contact sheet."""
import json
from pathlib import Path
from PIL import Image, ImageChops, ImageDraw, ImageFont
BASE=Path(__file__).parent
captures=BASE/'artifacts/captures'
results=[]
for mascot in ('miga','tallo'):
    for mode,a,b in [('Idle',1,31),('Active',1,22),('Reduced',1,181)]:
        x=Image.open(captures/f'{mascot}-{mode}-{a}.png').convert('RGBA')
        y=Image.open(captures/f'{mascot}-{mode}-{b}.png').convert('RGBA')
        visible=sum(pixel[3]>0 for pixel in x.getdata())
        assert visible>1000, 'render must contain visible geometry'
        changed=sum(p!=q for p,q in zip(x.getdata(),y.getdata()))
        assert (changed==0) if mode=='Reduced' else (changed>0)
        results.append(dict(mascot=mascot,mode=mode,frames=[a,b],visiblePixels=visible,changedPixels=changed,passed=True))
sheet=Image.new('RGB',(960,785),'#EDF1F6');d=ImageDraw.Draw(sheet)
font='/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf'
heading=ImageFont.truetype(font,27);body=ImageFont.truetype(font,17);small=ImageFont.truetype(font,14)
d.text((30,25),'Mascotas creadas con un escritor .riv propio',font=heading,fill='#24374A')
d.text((30,66),'Prototipos técnicos. Compilación local sin Rive CLI; prueba en host.',font=body,fill='#526072')
for row,mascot in enumerate(('miga','tallo')):
    for col,(mode,frame,label) in enumerate([('Idle',1,'Reposo'),('Active',22,'Actividad'),('Reduced',1,'Movimiento reducido')]):
        x=30+col*310;y=110+row*300
        d.rounded_rectangle((x,y,x+285,y+280),radius=18,fill='white')
        d.text((x+18,y+12),f'{mascot.title()} · {label}',font=body,fill='#24374A')
        im=Image.open(captures/f'{mascot}-{mode}-{frame}.png').convert('RGBA')
        sheet.paste(im,(x+15,y+28),im)
d.text((30,725),'Imágenes del runtime oficial Rive WASM/Canvas2D con adaptador Canvas de host.',font=small,fill='#526072')
d.text((30,749),'Tres estados de prueba. Todavía sin ejecución Android ni integración en Jarvys.',font=small,fill='#526072')
sheet.save(BASE/'artifacts/Prototipos Miga y Tallo - escritor local Rive.png')
(BASE/'artifacts/pixel-validation.json').write_text(json.dumps(dict(comparisons=results,passed=True),indent=2)+'\n')
print(json.dumps(results))

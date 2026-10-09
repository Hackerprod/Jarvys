"""Pixel evidence and contact sheet for genuine runtime captures, not a design editor."""
import os,json,hashlib
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
root=Path(__file__).parent;out=Path(os.environ.get('RIVE_PRODUCT_OUTPUT',root/'artifacts'))
names=['Idle','Thinking','Working','Queued','WaitingProvider','WaitingUser','Done','Error','Interrupted'];results=[]
scene_dir=Path(os.environ.get('RIVE_PRODUCT_SCENES',root/'scenes'))
ids=sorted(p.stem for p in scene_dir.glob('*.json'))
assert ids==['folio','nimbo'],('Both original product scenes are required',ids)
for id in ids:
 reduced_hashes=set()
 for state in names:
  for reduced in [False,True]:
   label=state+('Reduced' if reduced else '');a=Image.open(out/'captures'/f'{id}-{label}-1.png').convert('RGBA');b=Image.open(out/'captures'/f'{id}-{label}-{181 if reduced else 31}.png').convert('RGBA')
   pixels=list(a.getdata());visible=sum(p[3]>0 for p in pixels);changed=sum(x!=y for x,y in zip(pixels,b.getdata()));assert visible>100,(id,label,visible)
   assert changed==0 if reduced else changed>0,(id,label,changed)
   results.append(dict(id=id,state=label,visiblePixels=visible,changedPixels=changed,passed=True))
   if reduced: reduced_hashes.add(hashlib.sha256(a.tobytes()).hexdigest())
 assert len(reduced_hashes)==9,(id,'reduced poses must render distinctly',len(reduced_hashes))
assert len(results)==36,('Expected36pixelcomparisons',len(results))
(out/'product-pixel-validation.json').write_text(json.dumps(results,indent=2)+'\n')
w=180;h=225;sheet=Image.new('RGB',(w*9,h*4+65),'#f2f4f8');draw=ImageDraw.Draw(sheet)
draw.text((15,12),'Nimbo y Folio: prototipos originales del escritor local',fill='#182030')
draw.text((15,32),'Runtime Rive oficial WASM. Prueba de host; Android pendiente.',fill='#38445b')
for row,(id,reduced) in enumerate((i,r) for i in ids for r in [False,True]):
 for col,name in enumerate(names):
  state=name+('Reduced' if reduced else '');im=Image.open(out/'captures'/f'{id}-{state}-{181 if reduced else 31}.png').convert('RGBA');im.thumbnail((170,170));x=col*w+(w-im.width)//2;y=65+row*h;sheet.paste(im,(x,y),im);draw.text((col*w+8,y+173),id.title()+(' · reducida' if reduced else ' · animada'),fill='#182030');draw.text((col*w+8,y+191),name,fill='#38445b')
sheet.save(out/'Prototipos Nimbo y Folio - nueve estados.png');print(json.dumps({'passed':True,'pixelComparisons':len(results)}))

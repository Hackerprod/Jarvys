from pathlib import Path
import json,hashlib,collections
from PIL import Image,ImageChops,ImageDraw
base=Path(__file__).resolve().parents[1]
r=base/'artifacts/brisa'
p=base/'sources/brisa'
d=json.loads((r/'inspect.json').read_text()); assert d['problems']==[]
objs=[]
def walk(o):
 if isinstance(o,dict):
  if 'type' in o:objs.append(o)
  for v in o.values():walk(v)
 elif isinstance(o,list):
  for v in o:walk(v)
walk(d)
types=collections.Counter(o['type'] for o in objs)
assert types['AnimationState']==18 and types['LinearAnimation']==18 and types['DataBindContext']==36
assert not any('Asset' in t or 'Script' in t for t in types)
assert len([o for o in objs if o['type']=='Artboard' and o['name']=='Mascot'])==1
assert len([o for o in objs if o['type']=='ViewModel' and o['name']=='MascotState'])==1
results=[]
names=['idle','thinking','working','waitingUser','done','error','interrupted','queued','waitingProvider']
canvas=Image.new('RGB',(1200,1290),'#1d1d1d');draw=ImageDraw.Draw(canvas)
staticcanvas=canvas.copy();sd=ImageDraw.Draw(staticcanvas)
for n in range(9):
 data=json.loads((r/f'mode-{n}.json').read_text()); props={v['name']:v['value'] for v in data['viewModel']['properties']}
 assert props['mode']==n and props['reducedMotion'] is False
 for frame in [1,180]:
  data=json.loads((r/f'static-{n}-frame{frame}.json').read_text()); props={v['name']:v['value'] for v in data['viewModel']['properties']}
  assert props['mode']==n and props['reducedMotion'] is True
 a=Image.open(r/f'motion-{n}-frame1.png').convert('RGB'); b=Image.open(r/f'mode-{n}.png').convert('RGB')
 moving=ImageChops.difference(a,b); moving_count=sum(1 for px in moving.get_flattened_data() if px!=(0,0,0))
 a=Image.open(r/f'static-{n}-frame1.png').convert('RGB');z=Image.open(r/f'static-{n}-frame180.png').convert('RGB')
 static=ImageChops.difference(a,z); static_count=sum(1 for px in static.get_flattened_data() if px!=(0,0,0))
 assert moving_count>0, (n,'normal state did not change between sampled frames')
 assert static_count==0, (n,'reducedMotion changed pixels')
 x,y=(n%3)*400,(n//3)*430;canvas.paste(b,(x,y));draw.text((x+20,y+405),f'{n}  {names[n]}',fill='white');staticcanvas.paste(z,(x,y));sd.text((x+20,y+405),f'{n}  {names[n]} / reduced motion',fill='white')
 results.append({'mode':n,'name':names[n],'modeReadback':True,'reducedMotionReadback':True,'animatedFrame1Vs40ChangedPixels':moving_count,'staticFrame1Vs180ChangedPixels':static_count})
sustained=[]
for mode in [0,1,2,3,7,8]:
 frames=(30,90) if mode==3 else (60,100)
 images=[]
 for frame in frames:
  data=json.loads((r/f'sustained-{mode}-{frame}.json').read_text());props={v['name']:v['value'] for v in data['viewModel']['properties']}
  assert props['mode']==mode and props['reducedMotion'] is False
  images.append(Image.open(r/f'sustained-{mode}-{frame}.png').convert('RGB'))
 count=sum(1 for px in ImageChops.difference(*images).get_flattened_data() if px!=(0,0,0))
 assert count>0,(mode,'no motion after entry blend')
 sustained.append({'mode':mode,'frames':frames,'changedPixels':count,'passed':True})
canvas.save(r/'modes-contact-sheet.png');staticcanvas.save(r/'static-contact-sheet.png')
riv=(p/'build/brisa.riv').read_bytes();assert riv[:4]==b'RIVE'
report={'cliVersion':'1.5.1','rivFile':str(p/'build/brisa.riv'),'rivBytes':len(riv),'rivSha256':hashlib.sha256(riv).hexdigest(),'rivHeaderHex':riv[:16].hex(),'sceneSha256':hashlib.sha256((p/'scene.rml').read_bytes()).hexdigest(),'inspectProblems':d['problems'],'objectCounts':dict(types),'modes':results,'sustainedLoopComparisons':sustained,'visualReview':'Not performed by this script. Historical manual review is recorded in README.md; inspect new renders yourself.','limitations':['Headless CLI proof only; actual target-runtime integration remains untested.','No performance, battery, device or frame-rate claim.','Normal state differences compare initial transition frame 1 with frame 40, not steady-state loop performance.','This capture script verifies independent mode entry; use validate_live_runtime.mjs for live switching.']}
(r/'validation.json').write_text(json.dumps(report,indent=2));print(json.dumps(report,indent=2))

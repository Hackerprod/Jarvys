from pathlib import Path
import subprocess,json,concurrent.futures,hashlib,datetime,os
from PIL import Image,ImageChops
base=Path(__file__).resolve().parents[1];project=base/'sources/lumen';out=base/'artifacts/lumen';out.mkdir(parents=True,exist_ok=True)
cli=Path(os.environ.get('RIVE_CLI','rive'));modes=['idle','thinking','working','waitingUser','done','error','interrupted','queued','waitingProvider']
def capture(job):
    mode,reduced,frame=job; stem=f'{mode:02d}_{modes[mode]}_{"reduced" if reduced else "motion"}_{frame:03d}'
    args=[str(cli),str(project),f'--screenshot={out/stem}.png',f'--data=mode={mode}',f'--data=reducedMotion={str(reduced).lower()}',f'--advance={frame}',f'--data-dump={out/stem}.json']
    p=subprocess.run(args,capture_output=True,text=True,timeout=40)
    (out/(stem+'.log')).write_text(p.stdout+p.stderr)
    assert p.returncode==0,(stem,p.returncode,p.stderr)
    data=json.loads((out/(stem+'.json')).read_text());props={v['name']:v for v in data['viewModel']['properties']}
    assert data['artboard']=='Mascot' and data['stateMachine']=='MascotMotion'
    assert data['viewModel']['viewModel']=='MascotState'
    assert props['mode']['type']=='number' and props['mode']['value']==mode,(stem,props)
    assert props['reducedMotion']['type']=='boolean' and props['reducedMotion']['value']==reduced
    im=Image.open(out/(stem+'.png')).convert('RGBA'); assert im.size==(256,256)
    return {'mode':mode,'state':modes[mode],'reducedMotion':reduced,'frame':frame,'file':stem+'.png','pixel_sha256':hashlib.sha256(im.tobytes()).hexdigest(),'png_bytes':(out/(stem+'.png')).stat().st_size}
jobs=[(m,r,f) for m in range(9) for r in (False,True) for f in ((7,27) if not r else (30,90))]
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool: results=list(pool.map(capture,jobs))
comparisons=[]
for m in range(9):
    for r in (False,True):
        pair=[x for x in results if x['mode']==m and x['reducedMotion']==r]
        same=pair[0]['pixel_sha256']==pair[1]['pixel_sha256']
        should_move=(not r and m in (0,1,2,4,5))
        assert same!=should_move,(m,r,'unexpected frame equality',same)
        comparisons.append({'mode':m,'reducedMotion':r,'expected_motion':should_move,'different_pixels':not same,'passed':True})
# Every state must visibly differ from every other in reduced-motion mode.
poses=[x for x in results if x['reducedMotion'] and x['frame']==30]
assert len(set(x['pixel_sha256'] for x in poses))==9,'duplicate reduced-motion state poses'
report={'utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'mascot':'Lumen','captures':results,'comparisons':comparisons,'readback_assertions':len(results),'distinct_static_poses':len(poses),'passed':True,'scope':'native CLI headless renderer; not Android device performance'}
(base/'artifacts/lumen-validation.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps({'passed':True,'captures':len(results),'comparisons':len(comparisons),'distinct_static_poses':len(poses)}))

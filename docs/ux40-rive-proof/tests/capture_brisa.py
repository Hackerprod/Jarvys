"""Recreate Brisa's CLI capture inputs for validate_brisa.py; no network or account use."""
from pathlib import Path
import os,subprocess,concurrent.futures
base=Path(__file__).resolve().parents[1]
project=base/'sources/brisa';out=base/'artifacts/brisa';out.mkdir(parents=True,exist_ok=True)
cli=os.environ.get('RIVE_CLI','rive')
def capture(job):
    mode,reduced,frame,stem=job
    args=[cli,str(project),f'--screenshot={out/stem}.png',f'--data=mode={mode}',f'--data=reducedMotion={str(reduced).lower()}',f'--advance={frame}',f'--data-dump={out/stem}.json']
    result=subprocess.run(args,capture_output=True,text=True,timeout=40)
    (out/(stem+'.log')).write_text(result.stdout+result.stderr)
    if result.returncode:raise RuntimeError(f'Capture failed: {stem}; see its log')
inspect=subprocess.run([cli,'inspect',str(project),'--json'],capture_output=True,text=True,timeout=40,check=True)
(out/'inspect.json').write_text(inspect.stdout)
jobs=[]
for mode in range(9):
    jobs.extend([(mode,False,1,f'motion-{mode}-frame1'),(mode,False,40,f'mode-{mode}'),(mode,True,1,f'static-{mode}-frame1'),(mode,True,180,f'static-{mode}-frame180')])
for mode in [0,1,2,3,7,8]:
    frames=(30,90) if mode==3 else (60,100)
    jobs.extend((mode,False,frame,f'sustained-{mode}-{frame}') for frame in frames)
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:list(pool.map(capture,jobs))
print(f'Captured {len(jobs)} Brisa frames; run validate_brisa.py next.')

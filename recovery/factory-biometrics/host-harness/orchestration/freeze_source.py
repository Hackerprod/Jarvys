#!/usr/bin/env python3
import pathlib,subprocess,hashlib,json,sys,time
V=pathlib.Path(__file__).resolve().parent; P=V.parent/'integration'
files=subprocess.check_output(['git','ls-files','app'],cwd=P,text=True).splitlines()
assert not subprocess.check_output(['git','ls-files','--others','--exclude-standard','--','app'],cwd=P,text=True).strip()
r={'head':subprocess.check_output(['git','rev-parse','HEAD'],cwd=P,text=True).strip(),'baseline':'5c177d32cfc1b13209a54131753f98cec3582d42','epoch':time.time(),'files':{f:hashlib.sha256((P/f).read_bytes()).hexdigest() for f in files}}
target=V/(sys.argv[1] if len(sys.argv)>1 else 'source-freeze.json')
if (V/'source-freeze.json').exists():assert json.loads((V/'source-freeze.json').read_text())['files']==r['files'],'Frozen source changed'
target.write_text(json.dumps(r,indent=2)+'\n');print(len(files),target)

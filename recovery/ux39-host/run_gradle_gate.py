#!/usr/bin/env python3
"""Run one recoverable host build gate, saving actual exit, timestamps and log."""
import datetime, json, os, pathlib, subprocess, sys, time, uuid
base=pathlib.Path('/workspace/shared/Jarvys-recovery/UX39-recovered-validation')
label=sys.argv[1]+'-'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S')+'-'+uuid.uuid4().hex[:8]
run=base/'runs'/label
run.mkdir(parents=True,exist_ok=False)
args=sys.argv[2:]
assert args,'No Gradle task or argument'
if any(':test' in arg for arg in args):
    assert '--info' in args, 'Guarded Test runs require --info worker launch evidence'
env=dict(os.environ,JARVYS_RUN_LABEL=label)
log=run/'gradle.log'
meta={'label':label,'cwd':os.getcwd(),'args':args,'start_epoch':time.time(),'log':str(log),'test_guard_scope':'per Test worker, not Gradle launcher/daemon'}
(run/'run.json').write_text(json.dumps(meta,indent=2)+'\n')
print('RUN_EVIDENCE='+str(run),flush=True)
with log.open('w') as out:
    process=subprocess.Popen([str(base/'gradle-run.sh'),*args],stdout=out,stderr=subprocess.STDOUT,env=env)
    meta['launcher_pid']=process.pid
    meta['exit_code']=process.wait()
meta['finish_epoch']=time.time()
(run/'run.json').write_text(json.dumps(meta,indent=2)+'\n')
print(json.dumps(meta,indent=2),flush=True)
print(log.read_text()[-7000:],flush=True)
sys.exit(meta['exit_code'])

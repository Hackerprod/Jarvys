#!/usr/bin/env python3
"""Fresh, serialized host gates. Does not install/run any APK or access a device."""
from pathlib import Path
import sys,subprocess,json,shutil,hashlib
V=Path(__file__).resolve().parent; R=V.parent; H=R/'UX39-recovered-validation'; P=R/'integration'; kind=sys.argv[1]
assert kind in ('baseline-prepare','baseline','baseline-build','lint','aggregate','build')
base=kind.startswith('baseline'); project=R/'v79-baseline' if base else P
out=R/'FACTORY-SENSORS-v79-validation' if base else V
if kind=='baseline-prepare':out=out/'metadata-preparation'
out.mkdir(parents=True,exist_ok=True)
def baseline_freeze():
 assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=project,text=True).strip()=='5c177d32cfc1b13209a54131753f98cec3582d42'
 assert subprocess.check_output(['git','rev-parse','HEAD:app'],cwd=project,text=True).strip()=='805c0cc19c5aa9b8bb50649c0e2aef2a238cff43'
 assert not subprocess.check_output(['git','status','--porcelain','--','app'],cwd=project,text=True).strip()
 files=subprocess.check_output(['git','ls-files','app'],cwd=project,text=True).splitlines()
 return {f:hashlib.sha256((project/f).read_bytes()).hexdigest() for f in files}
if base:
 before=baseline_freeze();(out/'baseline-source-freeze.json').write_text(json.dumps({'reconstructed':True,'head':'5c177d32cfc1b13209a54131753f98cec3582d42','appTree':'805c0cc19c5aa9b8bb50649c0e2aef2a238cff43','files':before},indent=2)+'\n')
if not base:subprocess.run([sys.executable,str(V/'freeze_source.py'),kind+'-source-freeze.json'],check=True)
args=['--info']+([] if kind in ('baseline-prepare','baseline-build') else ['--offline'])
if kind in ('baseline-build','build'):args+=['-I',str(V/'fresh-apks.init.gradle')]
if kind in ('baseline-prepare','baseline','lint'):
 args+=['-I',str(H/'fresh-lint.init.gradle'),'-I',str(V/'library-lint.init.gradle'),':app:lintReportFullDebug',':app:lintReportPlayDebug',':apk-runtime:lintReportRelease',':factory-runtime-core:lintReportRelease','-x',':apk-runtime:lintVitalRelease']
elif kind=='aggregate':
 args+=['--continue','-I',str(H/'fresh-tests.init.gradle'),'-I',str(V/'factory-evidence.init.gradle'),':app:testFullDebugUnitTest',':app:testPlayDebugUnitTest',':apk-runtime:testDebugUnitTest',':apk-runtime:testReleaseUnitTest',':factory-runtime-core:testDebugUnitTest',':factory-runtime-core:testReleaseUnitTest']
else:args+=[':app:assembleFullDebug',':app:assemblePlayDebug',':app:assembleFullRelease']
prior=set((H/'runs').glob('*'));rc=subprocess.run([sys.executable,str(H/'run_gradle_gate.py'),'v80-restored-'+kind,*args],cwd=project/'app').returncode
created=set((H/'runs').glob('*'))-prior;assert len(created)==1;run=created.pop();(out/(kind+'-run.txt')).write_text(str(run)+'\n')
if rc:sys.exit(rc)
r=json.loads((run/'run.json').read_text());assert r['exit_code']==0
if kind in ('baseline-prepare','baseline','lint'):
 for module,name in [('app','fullDebug'),('app','playDebug'),('apk-runtime','release'),('factory-runtime-core','release')]:
  src=project/'app'/module/'build/reports'/('lint-results-'+name+'.xml');assert r['start_epoch']<=src.stat().st_mtime<=r['finish_epoch']+1;shutil.copy2(src,out/(module+'-'+src.name))
if kind=='aggregate':
 for module,task in [('app','testFullDebugUnitTest'),('app','testPlayDebugUnitTest'),('apk-runtime','testDebugUnitTest'),('apk-runtime','testReleaseUnitTest'),('factory-runtime-core','testDebugUnitTest'),('factory-runtime-core','testReleaseUnitTest')]:
  src=project/'app'/module/'build/test-results'/task;dst=out/'aggregate-junit'/(module+'-'+task);dst.mkdir(parents=True,exist_ok=False)
  files=list(src.glob('TEST-*.xml'));assert files
  for f in files:assert r['start_epoch']<=f.stat().st_mtime<=r['finish_epoch']+1;shutil.copy2(f,dst/f.name)
 with (out/'sdk-tests.log').open('w') as log:subprocess.run(['node','--test','--test-reporter=tap','apk-runtime/tests/sdk.test.cjs'],cwd=project/'app',stdout=log,stderr=subprocess.STDOUT,check=True)
if kind in ('baseline-build','build'):
 artifacts=[];dest=out/'final-artifacts';dest.mkdir(exist_ok=False)
 for variant in ('full-debug','play-debug','full-release'):
  flavor,build=variant.split('-');src=project/'app/app/build/outputs/apk'/flavor/build/('app-'+variant+'-unsigned.apk');assert src.exists();assert r['start_epoch']<=src.stat().st_mtime<=r['finish_epoch']+1
  target=dest/src.name;shutil.copy2(src,target);artifacts.append({'name':target.name,'sha256':hashlib.sha256(target.read_bytes()).hexdigest(),'bytes':target.stat().st_size})
 (out/'built-artifacts.json').write_text(json.dumps(artifacts,indent=2)+'\n')
if kind=='build':subprocess.run([sys.executable,str(V/'freeze_source.py'),'after-build-source-freeze.json'],check=True)
if base:assert baseline_freeze()==before
if kind=='lint':subprocess.run([sys.executable,str(V/'compare_lint.py')],check=True)
print('ARCHIVED_SUCCESS',kind,str(run))

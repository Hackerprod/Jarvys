#!/usr/bin/env python3
"""Independent review of reconstructed offline guard. Never changes harness pins."""
from pathlib import Path
import datetime,hashlib,json,os,re,subprocess,tempfile,zipfile
O=Path(__file__).resolve().parent;R=O.parent.parent;P=R/'integration';S=P/'recovery/ux39-host';H=R/'UX39-recovered-validation';G=H/'offline-guard';J=R/'toolchain/jdk-21.0.12.1+1'
def require(ok,msg):
 if not ok:raise AssertionError(msg)
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def cmd(*a):return subprocess.check_output([str(x) for x in a],text=True,stderr=subprocess.STDOUT)
def zipentries(p):
 with zipfile.ZipFile(p) as z:
  require(z.testzip() is None and len(z.namelist())==len(set(z.namelist())),'Bad ZIP')
  return {n:z.read(n) for n in z.namelist() if not n.endswith('/')}
manifest=json.loads((S/'source-checksums.json').read_text());checks=[]
for n,digest in manifest.items():
 require(sha(S/n)==digest,'Archived source differs '+n)
 actual=(H/n).read_bytes();old=(S/n).read_bytes()
 if n.endswith(('.java',)) or '/smoke/' in n:require(actual==old,'Guard/smoke source differs '+n)
 checks.append({'path':n,'archivedSha256':digest,'restoredSha256':sha(H/n)})
original=json.loads((S/'offline-guard/provenance.json').read_text())
require(sha(J/'lib/src.zip')==original['jdk_src_zip_sha256'],'JDK source ZIP differs');require(sha(R/'toolchain/downloads/OpenJDK21U-jdk_x64_linux_hotspot_21.0.12.1_1.tar.gz')==original['jdk_tar_sha256'],'JDK archive differs')
version=cmd(J/'bin/java','-version');require('Temurin-21.0.12.1+1' in version,'Wrong JDK runtime')
# Independent compiler reproduction of all source classes, apart from nondeterministic JAR timestamps.
tmp=Path(tempfile.mkdtemp(prefix='guard-reproduction-',dir=O));boot=tmp/'bootstrap';agent=tmp/'agent';boot.mkdir();agent.mkdir()
compilelog=cmd(J/'bin/javac','-d',boot,S/'offline-guard/src/guard/bootstrap/OfflineGuard.java')
compilelog+=cmd(J/'bin/javac','--add-exports','java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED','-cp',boot,'-d',agent,*sorted((S/'offline-guard/src/guard/agent').glob('*.java')))
(tmp/'compile.log').write_text(compilelog)
artifacts=[]
for name,classes in [('guard-bootstrap.jar',boot),('jarvys-offline-guard.jar',agent)]:
 entries=zipentries(G/name);expected={str(p.relative_to(classes)):p.read_bytes() for p in classes.rglob('*.class')}
 require({n:v for n,v in entries.items() if n.endswith('.class')}==expected,'Compiled source/JAR class mismatch '+name)
 require(set(entries)==set(expected)|{'META-INF/MANIFEST.MF'},'Unexpected JAR payload '+name)
 mf=entries['META-INF/MANIFEST.MF'].decode().replace('\r\n','\n')
 if name=='jarvys-offline-guard.jar':
  for line in ['Premain-Class: guard.agent.Agent','Can-Retransform-Classes: true','Boot-Class-Path: guard-bootstrap.jar']:require(line in mf.splitlines(),'Agent manifest differs')
  require('Agent-Class:' not in mf,'Unexpected runtime attach')
 artifacts.append({'filename':name,'sha256':sha(G/name),'classPayloadIndependentlyRecompiled':True,'classSha256':{n:hashlib.sha256(v).hexdigest() for n,v in expected.items()}})
smoke=(G/'reports/smoke.txt').read_text();require('SMOKE_OK checks=52 blockedAttempts=100 nonLocalDnsCalls=0' in smoke,'Smoke totals differ')
require('[jarvys-offline-guard] installed hooks=7 localhost=verified-loopback' in smoke,'Installation not proven')
fail=json.loads((G/'reports/fail-closed-results.json').read_text());require({x['case'] for x in fail}=={'missing-bootstrap','unsupported-arguments','missing-report-directory','relative-report-directory','unwritable-report-directory'} and len(fail)==5,'Fail-closed cases incomplete')
for row in fail:
 require(row['pass'] is True and row['mainReached'] is False and row['exitCode']!=0,'Fail-closed did not fail')
 raw=(G/'reports'/('fail-closed-'+row['case']+'.txt')).read_text();require('APPLICATION_MAIN_REACHED' not in raw and '[jarvys-offline-guard] installed' not in raw,'Startup reached main')
attribution=json.loads((G/'reports/attribution-results.json').read_text());require(len(attribution)==2 and sorted(x['expectedBlockedAttempts'] for x in attribution)==[0,100],'Concurrent attribution incomplete')
for row in attribution:
 one=json.loads((G/row['installFile']).read_text());two=json.loads((G/row['shutdownFile']).read_text());keys={'pid','jvmStartedAtEpochMillis','installedAtEpochMillis'}
 require(set(one)==keys and set(two)==keys|{'shutdownAtEpochMillis','blockedAttempts'},'Attribution schema');require(all(type(v) is int for v in two.values()),'Noninteger evidence');require(all(one[k]==two[k] for k in keys),'Attribution identity');require(one['pid']==row['pid'] and one['jvmStartedAtEpochMillis']<=one['installedAtEpochMillis']<=two['shutdownAtEpochMillis'],'Attribution times');require(two['blockedAttempts']==row['expectedBlockedAttempts']==row['actualBlockedAttempts'] and row['pass'] is True,'Attribution count')
 raw=(G/row['installFile']).parent.joinpath('process-'+str(row['pid'])+'.log').read_text();require('[jarvys-offline-guard] installed hooks=7' in raw and '[jarvys-offline-guard] blockedAttempts='+str(two['blockedAttempts']) in raw,'Attribution log absent')
# Current reviewed pin enforcement must not be replaced with bypasses during reconstruction.
fresh=(H/'fresh-tests.init.gradle').read_text()
for a in original['artifacts']:require(a['sha256'] in fresh,'Original pin was altered before independent review')
require("if (digest != expected) throw new GradleException('Reviewed host guard artifact hash mismatch')" in fresh,'Pin enforcement weakened')
result={'approvedReconstructedGuard':True,'scope':'Same pinned Temurin source/runtime, exact archived Java/smoke sources, independent class recompilation, all smoke/fail-closed/concurrent evidence. Approval is only for exact reconstructed hashes below; original bytes have not been recovered. No whole-OS/arbitrary-transport isolation claim.','sourceCommit':cmd('git','-C',P,'rev-parse','HEAD').strip(),'sourceChecks':checks,'jdkVersion':version,'jdkSourceZipSha256':sha(J/'lib/src.zip'),'jdkArchiveSha256':sha(R/'toolchain/downloads/OpenJDK21U-jdk_x64_linux_hotspot_21.0.12.1_1.tar.gz'),'originalPins':original['artifacts'],'reconstructedArtifacts':artifacts,'smokeSha256':sha(G/'reports/smoke.txt'),'failClosed':fail,'attribution':attribution,'freshHarnessSha256BeforeRepin':sha(H/'fresh-tests.init.gradle'),'independentBuild':str(tmp),'reviewedUtc':datetime.datetime.now(datetime.timezone.utc).isoformat()}
(O/'reviewer-restored-guard.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps({'approvedReconstructedGuard':True,'artifacts':artifacts},indent=2))

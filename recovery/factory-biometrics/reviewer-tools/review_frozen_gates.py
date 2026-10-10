#!/usr/bin/env python3
"""Independent frozen source/fresh JUnit, lint, SDK, worker and artifact evidence audit. No Gradle."""
from pathlib import Path
import argparse,collections,datetime,hashlib,json,re,subprocess,xml.etree.ElementTree as ET
OUT=Path(__file__).resolve().parent;V=OUT.parent;ROOT=V.parent;P=ROOT/'integration'
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--baseline-validation',type=Path,default=ROOT/'FACTORY-SENSORS-v79-validation');args=p.parse_args();BASE=args.baseline_validation

def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def require(ok,message):
 if not ok:raise AssertionError(message)
def run(kind):
 path=Path((V/(kind+'-run.txt')).read_text().strip());path=path/'run.json' if path.is_dir() else path;r=json.loads(path.read_text());log=Path(r['log'])
 require(r.get('exit_code')==0 and r.get('finish_epoch',0)>=r['start_epoch'],kind+': incomplete/failed gate');require('BUILD SUCCESSFUL' in log.read_text(),kind+': no successful Gradle result');return r,log,path
def fresh(path,r):require(r['start_epoch']<=Path(path).stat().st_mtime<=r['finish_epoch']+1,'Stale evidence: '+str(path))
def location(file):
 # Detached baseline and candidate project roots differ; retain the complete module-relative path.
 require('/app/' in file or file.startswith('app/'),'Unrecognized lint path: '+file)
 return 'app/'+file.split('/app/',1)[1] if '/app/' in file else file
def issues(path):return collections.Counter((x.get('id'),x.get('severity'),x.get('message'),tuple(sorted(location(l.get('file','')) for l in x.findall('location')))) for x in ET.parse(path).getroot().findall('issue'))

freeze=json.loads((V/'source-freeze.json').read_text());current=set(subprocess.check_output(['git','ls-files','app'],cwd=P,text=True).splitlines()+subprocess.check_output(['git','ls-files','--others','--exclude-standard','--','app'],cwd=P,text=True).splitlines())
require(current==set(freeze['files']),'Source inventory changed');require(all(sha(P/n)==h for n,h in freeze['files'].items()),'Source bytes changed')
for kind in ('aggregate','lint','build','after-build'):
 f=json.loads((V/(kind+'-source-freeze.json')).read_text());require(f['files']==freeze['files'],kind+': inputs differ')
aggregate,alog,apath=run('aggregate');require('--tests' not in aggregate['args'] and '--offline' in aggregate['args'] and '--info' in aggregate['args'],'Aggregate narrowed or lacks evidence flags');require(any(x.endswith('/fresh-tests.init.gradle') for x in aggregate['args']),'Fresh Test harness absent')
tasks=[('app','testFullDebugUnitTest'),('app','testPlayDebugUnitTest'),('apk-runtime','testDebugUnitTest'),('apk-runtime','testReleaseUnitTest'),('factory-runtime-core','testDebugUnitTest'),('factory-runtime-core','testReleaseUnitTest')];text=alog.read_text();suites=[];guards=[];blocked=0
for module,task in tasks:
 full=':'+module+':'+task;require(full in aggregate['args'] and ('FRESH_GUARDED_TEST_TASK '+full+' ') in text,'Fresh task unproven '+full)
 xmls=sorted((V/'aggregate-junit'/(module+'-'+task)).glob('TEST-*.xml'));require(xmls,'JUnit missing '+full);sums={k:0 for k in ('tests','failures','errors','skipped')};names=[];hashes={}
 for xml in xmls:
  fresh(xml,aggregate);root=ET.parse(xml).getroot();names.append(root.get('name'));tests=root.findall('testcase');require(len(tests)==int(root.get('tests')),'Testcase count mismatch '+str(xml))
  for k in sums:sums[k]+=int(root.get(k,0))
  for plural,singular in [('failures','failure'),('errors','error'),('skipped','skipped')]:require(sum(len(t.findall(singular)) for t in tests)==int(root.get(plural,0)),'JUnit status mismatch')
  hashes[xml.name]=sha(xml)
 require(len(names)==len(set(names)),'Duplicate JUnit suite');require(sums['tests']>0 and sums['failures']==sums['errors']==sums['skipped']==0,'Nonpassing/skipped tests '+full)
 if module=='factory-runtime-core':
  for name in ('BiometricAuthenticationTest','BiometricPlatformTest','FactoryRuntimeBiometricsTest'):require(any(n.endswith('.'+name) for n in names),'Biometric suite absent '+name)
 if module=='app':require(any(n.endswith('.TemplateApkTest') for n in names) and any(n.endswith('.FactoryApkSignerTest') for n in names),'Profile/signer suite absent')
 suites.append(dict(module=module,task=task,suiteCount=len(xmls),xmlSha256=hashes,**sums))
 gd=apath.parent/(module+'-'+task)/'guard';installed=sorted(gd.glob('*.installed.json'));ended=sorted(gd.glob('*.shutdown.json'))
 require(installed and {x.name.replace('.installed.json','') for x in installed}=={x.name.replace('.shutdown.json','') for x in ended},'Unpaired guard workers '+full)
 for path in installed:
  end=path.with_name(path.name.replace('.installed.json','.shutdown.json'));one,two=json.loads(path.read_text()),json.loads(end.read_text());keys={'pid','jvmStartedAtEpochMillis','installedAtEpochMillis'}
  require(set(one)==keys and set(two)==keys|{'shutdownAtEpochMillis','blockedAttempts'} and all(type(v) is int for v in two.values()),'Guard schema mismatch')
  require(all(one[k]==two[k] for k in keys),'Guard identity mismatch');require(one['jvmStartedAtEpochMillis']<=one['installedAtEpochMillis'],'JVM start order');require(aggregate['start_epoch']*1000<=one['installedAtEpochMillis']<=two['shutdownAtEpochMillis']<=(aggregate['finish_epoch']+1)*1000,'Guard times outside gate')
  require(two['blockedAttempts']>=0,'Invalid guard count');blocked+=two['blockedAttempts'];guards.append(dict(task=full,installSha256=sha(path),shutdownSha256=sha(end),**two))
launches=[line for line in text.splitlines() if line.startswith("Starting process 'Gradle Test Executor")]
require(len(launches)==len(guards) and all('-javaagent:' in x and 'jarvys-offline-guard.jar' in x for x in launches),'Worker launch/guard count mismatch')
require('shutdown-report-write-failed' not in text,'Incomplete guard shutdown evidence')
lint,llog,lpath=run('lint');lintrows=[]
for name,count in [('app-lint-results-fullDebug.xml',313),('app-lint-results-playDebug.xml',300),('apk-runtime-lint-results-release.xml',3),('factory-runtime-core-lint-results-release.xml',2)]:
 candidate=V/name;baseline=BASE/name;fresh(candidate,lint);old,new=issues(baseline),issues(candidate);require(old==new and sum(new.values())==count,'Lint multiset changed '+name);lintrows.append(dict(name=name,count=count,sha256=sha(candidate),baselineSha256=sha(baseline),identicalToV79=True))
build,blog,bpath=run('build');artifacts=[]
for variant in ('full-debug','play-debug','full-release'):
 path=V/'final-artifacts'/('app-'+variant+'-unsigned.apk');fresh(path,build);artifacts.append(dict(name=path.name,sha256=sha(path),bytes=path.stat().st_size))
require(artifacts==json.loads((V/'built-artifacts.json').read_text()),'Copied artifact manifest mismatch')
sdk=V/'sdk-tests.log';sdktext=sdk.read_text();counts={name:int(re.search(r'^# '+name+r' (\d+)$',sdktext,re.M)[1]) for name in ('tests','pass','fail','cancelled','skipped','todo')}
require(counts['tests']==counts['pass'] and counts['tests']>35 and sum(counts[x] for x in ('fail','cancelled','skipped','todo'))==0,'SDK incomplete');require(aggregate['finish_epoch']<=sdk.stat().st_mtime<=build['start_epoch']+1,'SDK freshness mismatch')
result={'passed':True,'scope':'Fresh complete six-task JUnit, every paired Test worker, exact lint multisets, SDK/source/artifact freshness. Binary and signature review separate.','baseline':freeze['baseline'],'frozenHead':freeze['head'],'sourceInputs':len(freeze['files']),'sourceFreezeSha256':sha(V/'source-freeze.json'),'aggregate':{'run':str(apath),'logSha256':sha(alog),'suites':suites,'tests':sum(x['tests'] for x in suites),'pairedGuardWorkers':len(guards),'blockedAttempts':blocked,'guards':guards},'lint':{'run':str(lpath),'logSha256':sha(llog),'reports':lintrows},'sdk':dict(counts,logSha256=sha(sdk)),'build':{'run':str(bpath),'logSha256':sha(blog),'artifacts':artifacts},'reviewedUtc':datetime.datetime.now(datetime.timezone.utc).isoformat()}
(OUT/'reviewer-frozen-gates.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps({'passed':True,'tests':result['aggregate']['tests'],'guardWorkers':len(guards),'sdkTests':counts['tests'],'lintCounts':[x['count'] for x in lintrows]}))

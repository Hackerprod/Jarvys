#!/usr/bin/env python3
"""Host-only evidence checker. Never treats missing/old reports as passing."""
import argparse, collections, datetime, hashlib, json, pathlib, re, subprocess, xml.etree.ElementTree as ET
ROOT = pathlib.Path('/workspace/shared/Jarvys-recovery')
VALID = ROOT / 'UX39-recovered-validation'
REPO = ROOT / 'integration'

def output(name, value):
    (VALID / name).write_text(json.dumps(value, indent=2, ensure_ascii=False) + '\n')
    print(json.dumps(value, indent=2, ensure_ascii=False))

def source_check():
    frozen = json.loads((VALID / 'recovered-source-snapshot.json').read_text())
    now = {p: hashlib.sha256((REPO / p).read_bytes()).hexdigest() for p in frozen['files']}
    changes = [p for p in now if now[p] != frozen['files'][p]]
    tracked = subprocess.check_output(['git','ls-files','app'],cwd=REPO,text=True).splitlines()
    untracked = subprocess.check_output(['git','ls-files','--others','--exclude-standard','--','app'],cwd=REPO,text=True).splitlines()
    added = sorted(set(tracked)-set(now))
    deleted = sorted(set(now)-set(tracked))
    result = {'source_app_tree':subprocess.check_output(['git','rev-parse','HEAD:app'],cwd=REPO,text=True).strip(), 'files':len(now),'changed':changes,'added':added,'deleted':deleted,'untracked_app_inputs':untracked,'passed':not(changes or added or deleted or untracked)}
    output('source-equivalence.json',result)
    assert result['passed'], 'Frozen app sources changed'

def task_evidence(run_file, task, guarded=False):
    run_path=pathlib.Path(run_file)
    run=json.loads(run_path.read_text())
    assert run['exit_code']==0, 'Gradle process did not exit successfully'
    assert run['finish_epoch']>=run['start_epoch'], 'Invalid run times'
    assert task in run['args'], 'Task not requested by recorded run'
    log=pathlib.Path(run['log']).read_text()
    assert 'BUILD SUCCESSFUL' in log and 'BUILD FAILED' not in log, 'Missing successful Gradle completion'
    executed=re.findall(r'^> Task '+re.escape(task)+r'([^\n]*)$',log,re.M)
    assert executed==[''], 'Task did not execute freshly: '+repr(executed)
    evidence={'run':str(run_path),'task':task,'exit_code':0,'actual_task_executed':True}
    if guarded:
        launches=re.findall(r"^Starting process 'Gradle Test Executor \d+'\.[^\n]*Command: ([^\n]+)$",log,re.M)
        assert launches, 'No actual test worker launch evidence'
        guard_path=str(VALID/'offline-guard/jarvys-offline-guard.jar')
        project, name=task.strip(':').split(':')
        directory=run_path.parent/(project+'-'+name)/'guard'
        for launch in launches:
            agents=re.findall(r'-javaagent:([^\s]+)',launch)
            assert agents and agents[0]==guard_path, 'Test worker did not start guard first'
            report_dirs=re.findall(r'-Djarvys.offlineGuard.reportDir=([^\s]+)',launch)
            assert report_dirs==[str(directory)], 'Test worker report directory mismatch'
        project, name=task.strip(':').split(':')
        directory=run_path.parent/(project+'-'+name)/'guard'
        installed=sorted(directory.glob('*.installed.json'))
        shutdown=sorted(directory.glob('*.shutdown.json'))
        assert len(installed)==len(shutdown)==len(launches), 'Unmatched test worker/guard sidecars'
        forks=[]
        for first in installed:
            last=first.with_name(first.name.replace('.installed.json','.shutdown.json'))
            initial=json.loads(first.read_text()); final=json.loads(last.read_text())
            for key in ['pid','jvmStartedAtEpochMillis','installedAtEpochMillis']:
                assert final[key]==initial[key], 'Sidecar identity mismatch'
            assert run['start_epoch']*1000 <= initial['jvmStartedAtEpochMillis'] <= initial['installedAtEpochMillis'] <= final['shutdownAtEpochMillis'] <= run['finish_epoch']*1000+1000, 'Stale or out-of-order guard evidence'
            assert isinstance(final['blockedAttempts'],int) and final['blockedAttempts']>=0
            assert first.stat().st_mtime>=run['start_epoch'] and last.stat().st_mtime>=run['start_epoch']
            forks.append(final)
        evidence['forks']=forks
        evidence['blocked_attempts']=sum(f['blockedAttempts'] for f in forks)
    return run,evidence

def tests(directory, run_file, task, expected, policy):
    run,evidence=task_evidence(run_file,task,True)
    start=run['start_epoch']
    files=sorted(pathlib.Path(directory).glob('TEST-*.xml'))
    assert files, 'No JUnit XML'
    totals=collections.Counter(tests=0, failures=0, errors=0, skipped=0)
    stale=[]; suite_names=[]; policy_count=0
    for path in files:
        if path.stat().st_mtime < start: stale.append(str(path))
        suite=ET.parse(path).getroot()
        assert suite.tag == 'testsuite', f'Unexpected JUnit root: {path}'
        for key in totals: totals[key]+=int(suite.get(key,0))
        suite_names.append(suite.get('name'))
        if suite.get('name','').endswith('.PreviewResponsePolicyTest'):
            policy_count+=int(suite.get('tests',0))
    passed=not stale and totals['tests']==expected and not(totals['failures'] or totals['errors'] or totals['skipped']) and (not policy or policy_count==policy)
    result={'task_evidence':evidence,'directory':directory,'start_epoch':start,'suite_files':len(files),'totals':dict(totals),'policy_cases':policy_count,'stale':stale,'passed':passed}
    output(pathlib.Path(directory).name+'-evidence.json',result)
    assert passed,'Fresh test gate failed'

def lint_issues(path):
    data=ET.parse(path).getroot(); result=[]; counts=collections.Counter()
    def norm(value):
        for prefix in [str(ROOT/'integration'),str(ROOT/'ux39-baseline')]: value=value.replace(prefix,'<repo>')
        return value
    for issue in data.findall('issue'):
        severity=issue.get('severity'); counts[severity]+=1
        key=(issue.get('id'),severity,norm(issue.get('message','')),tuple(sorted(norm(l.get('file','')) for l in issue.findall('location'))))
        result.append(key)
    return collections.Counter(result),dict(counts)

def lint(baseline, current, baseline_run, current_run, task):
    br,be=task_evidence(baseline_run,task); cr,ce=task_evidence(current_run,task)
    for report,run in [(baseline,br),(current,cr)]:
        assert run['start_epoch']<=pathlib.Path(report).stat().st_mtime<=run['finish_epoch']+1, 'Lint report is not fresh'
    old, old_count=lint_issues(baseline); new,new_count=lint_issues(current)
    added=new-old; removed=old-new
    result={'baseline_task_evidence':be,'current_task_evidence':ce,'baseline':baseline,'current':current,'baseline_counts':old_count,'current_counts':new_count,'added':[{'issue':k,'count':n} for k,n in added.items()],'removed':[{'issue':k,'count':n} for k,n in removed.items()],'passed':not added}
    output(pathlib.Path(current).stem+'-comparison.json',result)
    assert not added,'New lint diagnostics'

parser=argparse.ArgumentParser(); sub=parser.add_subparsers(dest='command',required=True)
sub.add_parser('source')
p=sub.add_parser('tests'); p.add_argument('directory');p.add_argument('run');p.add_argument('task');p.add_argument('expected',type=int);p.add_argument('--policy',type=int,default=0)
p=sub.add_parser('lint');p.add_argument('baseline');p.add_argument('current');p.add_argument('baseline_run');p.add_argument('current_run');p.add_argument('task')
a=parser.parse_args()
if a.command=='source':source_check()
elif a.command=='tests':tests(a.directory,a.run,a.task,a.expected,a.policy)
elif a.command=='lint':lint(a.baseline,a.current,a.baseline_run,a.current_run,a.task)

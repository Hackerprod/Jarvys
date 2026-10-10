#!/usr/bin/env python3
"""Freeze, seed and verify only public lint metadata and the exact lint cache version marker.
No Gradle, downloads, analysis-result copying, source edits or recursive deletion.
Every CLI operation takes the same nonblocking flock used by the authorized Gradle runner.
"""
from pathlib import Path
import argparse, contextlib, datetime, fcntl, hashlib, json, os, re, shutil, stat, subprocess

SCHEMA=1
MARKER=b'Cache for Android Lint31.13.2\n'
DOMAINS=('maven.google','maven-versions','sdk_index')
MODULES=('app','apk-runtime','factory-runtime-core')
TASK=re.compile(r'(?:lint(?:Vital)?Analyze(?:FullDebug|PlayDebug|FullRelease|Debug|Release)(?:UnitTest|AndroidTest)?|lintReport(?:FullDebug|PlayDebug|Release))\Z')

def task_allowed(module,name):
    if not TASK.fullmatch(name):return False
    if name.startswith('lintReport'):
        return name in (('lintReportFullDebug','lintReportPlayDebug') if module=='app' else ('lintReportRelease',))
    return True
HOSTS={'repo1.maven.org','repo.gradle.org','plugins.gradle.org','jitpack.io','io.fabric'}
BASELINE='5c177d32cfc1b13209a54131753f98cec3582d42'
BASE_TREE='805c0cc19c5aa9b8bb50649c0e2aef2a238cff43'
REPORTS={':app:lintReportFullDebug',':app:lintReportPlayDebug',':apk-runtime:lintReportRelease',':factory-runtime-core:lintReportRelease'}

def require(value,message):
    if not value: raise ValueError(message)

def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()

def safe(path):
    path=Path(path)
    require(path.is_absolute() and '..' not in path.parts,'Absolute path without traversal required')
    for parent in [*reversed(path.parents),path]:
        require(not parent.is_symlink(),'Symlink forbidden: '+str(parent))
    return path

def external(path):
    safe(path); parent=path if path.exists() else path.parent
    while not parent.exists(): parent=parent.parent
    result=subprocess.run(['git','-C',str(parent),'rev-parse','--is-inside-work-tree'],capture_output=True,text=True)
    require(result.returncode!=0 or result.stdout.strip()!='true','Snapshot must be outside a Git worktree')

def allowed(relative):
    p=Path(relative); parts=p.parts
    require(not p.is_absolute() and '..' not in parts,'Invalid metadata relative path')
    require(len(parts)>=7 and parts[0]=='app' and parts[1] in MODULES and parts[2:5]==('build','intermediates','lint-cache') and task_allowed(parts[1],parts[5]),'Outside reviewed task cache: '+relative)
    tail=parts[6:]
    if tail==('lint-cache-version.txt',): return 'marker'
    require(len(tail)>=2 and tail[0] in DOMAINS,'Unapproved cache domain: '+relative)
    require(all(re.fullmatch(r'[A-Za-z0-9_.-]+',s) for s in tail),'Invalid metadata name')
    if tail[0]=='maven.google':
        require(tail==('maven.google','master-index.xml') or (len(tail)>=3 and tail[-1]=='group-index.xml'),'Unexpected Google Maven metadata')
    elif tail[0]=='maven-versions':
        require(len(tail)>=3 and tail[1] in HOSTS and tail[-1]=='maven-metadata.xml','Unexpected Maven metadata')
    else: require(tail==('sdk_index','snapshot.gz'),'Unexpected SDK index metadata')
    return tail[0]

def file_record(path):
    safe(path); info=path.stat()
    require(stat.S_ISREG(info.st_mode) and info.st_nlink==1,'Only singly linked regular input files permitted')
    return {'sha256':sha(path),'bytes':info.st_size,'mtimeNs':info.st_mtime_ns}

def collect(project,allow_missing_markers=False):
    project=safe(project); require(project.is_dir(),'Project missing')
    files={}; domains=[]; tasks=[]
    for module in MODULES:
        root=safe(project/'app'/module/'build/intermediates/lint-cache')
        if not root.exists(): continue
        for task in sorted(root.iterdir()):
            safe(task); require(task.is_dir() and task_allowed(module,task.name),'Unexpected lint task cache');tasks.append(str(task.relative_to(project)))
            marker=safe(task/'lint-cache-version.txt')
            if marker.exists():
                require(marker.is_file() and marker.read_bytes()==MARKER,'Mismatched lint version marker: '+str(marker))
                relative=str(marker.relative_to(project)); allowed(relative);files[relative]=file_record(marker)
            else: require(allow_missing_markers,'Missing lint version marker: '+str(marker))
            for domain in DOMAINS:
                folder=safe(task/domain)
                if not folder.exists():continue
                require(folder.is_dir(),'Metadata domain is not a directory')
                domains.append(str(folder.relative_to(project)))
                for child in sorted(folder.rglob('*')):
                    safe(child)
                    if child.is_dir():continue
                    relative=str(child.relative_to(project));allowed(relative);files[relative]=file_record(child)
    return {'files':files,'directories':sorted(domains),'tasks':sorted(tasks)}

def same_bytes(a,b):
    return {k:(v['sha256'],v['bytes']) for k,v in a.items()}=={k:(v['sha256'],v['bytes']) for k,v in b.items()}

def validate_manifest(manifest):
    require(set(manifest)=={'schema','kind','baselineCommit','baselineAppTree','project','preparationRunSha256','preparedAtUtc','files','directories','tasks'},'Snapshot schema mismatch')
    require(manifest['schema']==SCHEMA and manifest['kind']=='public-lint-metadata-only','Snapshot type mismatch')
    require(manifest['baselineCommit']==BASELINE and manifest['baselineAppTree']==BASE_TREE,'Snapshot source identity mismatch')
    require(manifest['files'],'Empty snapshot')
    for relative,row in manifest['files'].items():
        allowed(relative)
        require(set(row)=={'sha256','bytes','mtimeNs'} and re.fullmatch('[0-9a-f]{64}',row['sha256']) and type(row['bytes']) is int and row['bytes']>=0 and type(row['mtimeNs']) is int and row['mtimeNs']>0,'Invalid metadata record')
    for directory in manifest['directories']:
        allowed(directory+'/snapshot.gz' if directory.endswith('/sdk_index') else directory+('/master-index.xml' if directory.endswith('/maven.google') else '/repo.gradle.org/maven-metadata.xml'))
    require(len(manifest['directories'])==len(set(manifest['directories'])),'Duplicate metadata directory')
    require(len(manifest['tasks'])==len(set(manifest['tasks'])),'Duplicate task cache')
    for task in manifest['tasks']:allowed(task+'/lint-cache-version.txt')
    require(set(manifest['tasks'])=={str(Path(f).parent) for f in manifest['files'] if allowed(f)=='marker'},'Task/marker inventory mismatch')

def load_snapshot(snapshot,expected_sha256):
    snapshot=safe(snapshot); external(snapshot)
    marker=safe(snapshot/'manifest.json');require(marker.is_file(),'Snapshot manifest missing')
    file_record(marker)
    require(re.fullmatch('[0-9a-f]{64}',expected_sha256 or '') and sha(marker)==expected_sha256,'Externally pinned snapshot digest mismatch')
    manifest=json.loads(marker.read_text());validate_manifest(manifest)
    present=set()
    for child in snapshot.rglob('*'):
        safe(child)
        if child.is_dir():continue
        require(child.is_file(),'Snapshot has a special file')
        present.add(str(child.relative_to(snapshot)))
    require(present=={'manifest.json'}|{'payload/'+x for x in manifest['files']},'Snapshot extra/missing file')
    for relative,expected in manifest['files'].items():
        path=snapshot/'payload'/relative
        require(file_record(path)==expected,'Snapshot payload changed: '+relative)
        if allowed(relative)=='marker':require(path.read_bytes()==MARKER,'Wrong lint marker')
    return manifest

def freeze(project,run_path,snapshot):
    project=safe(project);run_path=safe(run_path);snapshot=safe(snapshot);external(snapshot)
    require(not snapshot.exists(),'Snapshot already exists')
    run=json.loads(run_path.read_text());require(run.get('exit_code')==0 and run.get('finish_epoch',0)>=run['start_epoch'],'Preparation incomplete/failed')
    require(Path(run['cwd'])==project/'app' and '--offline' not in run['args'] and REPORTS<=set(run['args']),'Preparation mode/targets mismatch')
    require('BUILD SUCCESSFUL' in safe(Path(run['log'])).read_text(),'Preparation success absent')
    git=lambda *args:subprocess.check_output(['git','-C',str(project),*args],text=True).strip()
    require(git('rev-parse','HEAD')==BASELINE and git('rev-parse','HEAD:app')==BASE_TREE and not git('status','--porcelain','--','app'),'Baseline source not exact/clean')
    inventory=collect(project);require(any(allowed(f)!='marker' for f in inventory['files']),'No remote metadata collected')
    manifest={'schema':SCHEMA,'kind':'public-lint-metadata-only','baselineCommit':BASELINE,'baselineAppTree':BASE_TREE,'project':str(project),'preparationRunSha256':sha(run_path),'preparedAtUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),**inventory}
    validate_manifest(manifest); snapshot.mkdir()
    for relative in inventory['files']:
        target=snapshot/'payload'/relative;safe(target);target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(project/relative,target)
    require(collect(project)==inventory,'Metadata mutated during snapshot')
    (snapshot/'manifest.json').write_text(json.dumps(manifest,indent=2,sort_keys=True)+'\n')
    load_snapshot(snapshot,sha(snapshot/'manifest.json'));return {'frozenFiles':len(inventory['files']),'manifestSha256':sha(snapshot/'manifest.json')}

def seed(snapshot,project,expected_sha256):
    manifest=load_snapshot(snapshot,expected_sha256);project=safe(project);require(project.is_dir(),'Target project missing')
    # Preflight the complete target before writing anything. Existing analysis output is untouched.
    existing=collect(project,allow_missing_markers=True)
    require(set(existing['tasks'])<=set(manifest['tasks']) and set(existing['files'])<=set(manifest['files']) and set(existing['directories'])<=set(manifest['directories']),'Target has extra task caches or metadata inputs')
    for relative,row in existing['files'].items():require(same_bytes({relative:row},{relative:manifest['files'][relative]}),'Target metadata conflict: '+relative)
    for relative in manifest['files']:
        target=safe(project/relative)
        for parent in target.parents:
            if parent==project.parent:break
            require(not parent.exists() or parent.is_dir(),'Target parent is not a directory')
        if target.exists():file_record(target)
    for relative in manifest['directories']:
        target=safe(project/relative);require(not target.exists() or target.is_dir(),'Target domain is not a directory')
    for relative in manifest['directories']:(project/relative).mkdir(parents=True,exist_ok=True)
    for relative,expected in manifest['files'].items():
        target=project/relative;target.parent.mkdir(parents=True,exist_ok=True)
        if target.exists():os.utime(target,ns=(target.stat().st_atime_ns,expected['mtimeNs']))
        else:shutil.copy2(snapshot/'payload'/relative,target)
    return verify(snapshot,project,expected_sha256)

def verify(snapshot,project,expected_sha256):
    manifest=load_snapshot(snapshot,expected_sha256);actual=collect(safe(project))
    require(same_bytes(actual['files'],manifest['files']) and actual['directories']==manifest['directories'] and actual['tasks']==manifest['tasks'],'Metadata bytes/inventory differ')
    return {'verifiedFiles':len(actual['files']),'manifestSha256':sha(snapshot/'manifest.json'),'project':str(project)}

@contextlib.contextmanager
def lock(path):
    path=safe(path);require(path.is_file(),'Existing runner lock required')
    with path.open('r') as stream:
        try:fcntl.flock(stream,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError:raise ValueError('Gradle runner lock is busy; no cache action permitted')
        try:yield
        finally:fcntl.flock(stream,fcntl.LOCK_UN)

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('action',choices=('freeze','seed','verify'));parser.add_argument('--snapshot',type=Path,required=True);parser.add_argument('--project',type=Path,required=True);parser.add_argument('--run-json',type=Path);parser.add_argument('--lock',type=Path,required=True);parser.add_argument('--manifest-sha256');args=parser.parse_args()
    recovery=Path(__file__).absolute().parents[2]
    require(args.project in (recovery/'integration',recovery/'v79-baseline'),'Unapproved project root')
    require(args.lock==recovery/'toolchain/gradle.lock','Must use the verified Gradle runner lock')
    if args.action=='freeze':require(args.project==recovery/'v79-baseline','Only exact detached baseline can prepare metadata')
    else:require(args.manifest_sha256 is not None,'Externally recorded manifest SHA-256 required')
    with lock(args.lock):
        if args.action=='freeze':require(args.run_json is not None,'Preparation run JSON required');result=freeze(args.project,args.run_json,args.snapshot)
        elif args.action=='seed':result=seed(args.snapshot,args.project,args.manifest_sha256)
        else:result=verify(args.snapshot,args.project,args.manifest_sha256)
    print(json.dumps(result,sort_keys=True))
if __name__=='__main__':main()

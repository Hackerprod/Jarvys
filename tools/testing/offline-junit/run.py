#!/usr/bin/env python3
"""Explicit, local-only class runner. Does not run Gradle or resolve/download dependencies."""
import argparse,json,os,pathlib,re,subprocess,sys
p=argparse.ArgumentParser()
p.add_argument('--export',required=True)
p.add_argument('--classes',required=True,help='Reviewed JSON array of exact class names; no discovery or wildcards')
p.add_argument('--java',required=True)
p.add_argument('--results',required=True)
p.add_argument('--robolectric-dependency-dir',required=True,help='Existing offline instrumented Android jar directory')
p.add_argument('--timeout',type=int,default=300)
p.add_argument('--allow-missing-classpath',help='Reviewed JSON array of exact optional output directory paths')
a=p.parse_args()
base=pathlib.Path(__file__).resolve().parent
meta=json.loads(pathlib.Path(a.export).read_text())
classes=json.loads(pathlib.Path(a.classes).read_text())
if not isinstance(classes,list) or not classes or len(set(classes))!=len(classes) or any(not isinstance(x,str) or not re.fullmatch(r'com\.jarvys\.agent\.[A-Za-z0-9_$.]+',x) for x in classes):
    p.error('Expected a nonempty duplicate-free exact Jarvys class list')
reviewed_args={'-Djava.security.manager=allow','--add-opens=java.base/java.io=ALL-UNNAMED'}
jvm_args=meta.get('jvmArgs',[])
if not isinstance(jvm_args,list) or any(x not in reviewed_args for x in jvm_args):
    p.error('Export has unreviewed JVM arguments')
cp=meta['classpath']
allowed=json.loads(pathlib.Path(a.allow_missing_classpath).read_text()) if a.allow_missing_classpath else []
if not isinstance(allowed,list) or any(not isinstance(x,str) or x not in cp or pathlib.Path(x).suffix.lower() in ('.jar','.zip') for x in allowed):
    p.error('Missing classpath exceptions must be exact reviewed output directories, never archives')
missing=[x for x in cp if not pathlib.Path(x).exists()]
if not cp or set(missing)!=set(allowed):p.error('Missing classpath differs from reviewed optional directories; no downloads or inferred exceptions')
env=os.environ.copy()
for key in list(env):
    if key.lower().endswith('_proxy') or key in ('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','GRADLE_OPTS','JAVA_OPTS','CLASSPATH'):
        env.pop(key,None)
out=pathlib.Path(a.results).resolve();out.mkdir(parents=True,exist_ok=True)
compiled=out/'launcher-classes';compiled.mkdir(exist_ok=True)
# Explicit caller invocation authorizes compilation; no compilation occurs on import.
subprocess.run([a.java,'com.sun.tools.javac.Main','-source','8','-target','8','-cp',os.pathsep.join(cp),'-d',str(compiled),str(base/'OfflineJUnit.java')],env=env,check=True)
props=dict(meta['systemProperties'])
# Never inherit configured outbound proxies or an alternate JVM security policy.
for key in list(props):
    if 'proxy' in key.lower() or key.startswith('java.security.'):
        del props[key]
props.update({'robolectric.offline':'true','robolectric.dependency.dir':str(pathlib.Path(a.robolectric_dependency_dir).resolve()),'java.security.manager':'allow'})
if not isinstance(meta.get('enableAssertions'),bool):p.error('Missing exported assertion mode')
args=[a.java]+jvm_args+['-Xmx1536m','-ea' if meta['enableAssertions'] else '-da','-Dfile.encoding=UTF-8']+['-D'+k+'='+str(v) for k,v in sorted(props.items())]+['-cp',os.pathsep.join([str(compiled)]+cp),'OfflineJUnit']
failed=[]
for cls in classes:
    xml=out/('TEST-'+cls+'.xml')
    if xml.exists():p.error('Refusing to overwrite existing evidence: '+str(xml))
    with (out/(cls+'.log')).open('wb') as log:
        try:
            result=subprocess.run(args+[cls,str(xml)],cwd=meta['workingDir'],env=env,stdout=log,stderr=subprocess.STDOUT,timeout=a.timeout)
            code=result.returncode
        except subprocess.TimeoutExpired:
            code=124
    if code!=0 or not xml.exists():failed.append({'class':cls,'exit':code,'xml':xml.exists()})
    print(cls+': '+('PASS' if code==0 and xml.exists() else 'FAIL'),flush=True)
(out/'launcher-summary.json').write_text(json.dumps({'classes':classes,'failures':failed,'export':str(pathlib.Path(a.export).resolve()),'jvmArgs':jvm_args,'enableAssertions':meta['enableAssertions'],'reviewedOptionalClasspath':allowed,'networkBoundary':'JVM sockets/DNS/listen/accept + child-process execution; not native-network sandbox'},indent=2))
sys.exit(1 if failed else 0)

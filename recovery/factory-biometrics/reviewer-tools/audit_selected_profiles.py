#!/usr/bin/env python3
"""Independent closed AXML/selective permission/fixture signature audit. No installation."""
import argparse,hashlib,importlib.util,json,os,pathlib,re,subprocess,sys,zipfile
sys.dont_write_bytecode=True
OUT=pathlib.Path(__file__).resolve().parent;ROOT=OUT.parent.parent
HELPER=ROOT/'integration/recovery/ux42-f0a3/verify_compiled_bindings.py'
spec=importlib.util.spec_from_file_location('f0a3_readonly',HELPER);F=importlib.util.module_from_spec(spec);spec.loader.exec_module(F)
SDK=ROOT/'toolchain/android-sdk/build-tools/35.0.0'
ANDROID='http://schemas.android.com/apk/res/android';PERMISSION='android.permission.USE_BIOMETRIC'
BROKERS=['com.jarvys.agent','com.jarvys.agent.recoverytest'];ACTIVITY='com.jarvys.factory.runtime.FactoryActivity';NIL=0xffffffff

def require(ok,message):
 if not ok:raise AssertionError(message)
def sha(data):return hashlib.sha256(data).hexdigest()
def command(*args):return subprocess.check_output([str(x) for x in args],text=True,stderr=subprocess.STDOUT)
def entries(path):
 with zipfile.ZipFile(path) as z:
  require(z.testzip() is None,'ZIP CRC failure');require(len(z.namelist())==len(set(z.namelist())),'Duplicate ZIP entries')
  return {n:z.read(n) for n in z.namelist()}
def dex(files):
 d={n:v for n,v in files.items() if n.lower().endswith('.dex')}
 require(d and set(d)=={'classes.dex'}|{'classes%d.dex'%i for i in range(2,len(d)+1)},'Noncanonical or missing DEX');return d

def axml(data):
 b=F.Binary(data);require(b.u16(0)==3 and b.u16(2)==8 and b.u32(4)==len(data),'AXML envelope')
 strings=None;resources=[];nodes=[];stack=[];namespaces=[];closed=False
 def text(i):
  require(strings is not None and i<len(strings),'AXML string index');return strings[i]
 for off,kind,header,size in b.chunks(8,len(data)):
  if kind==1:
   require(off==8 and strings is None,'Unexpected AXML pool');strings=b.pool(off)
  elif kind==0x180:
   require(not nodes and not resources and header==8,'Unexpected resource map');resources=[b.u32(i) for i in range(off+8,off+size,4)]
  elif kind in (0x100,0x101):
   require(header==16 and size==24,'Namespace layout');pair=(text(b.u32(off+16)),text(b.u32(off+20)))
   if kind==0x100:
    require(pair==('android',ANDROID) and not nodes and not namespaces,'Unexpected namespace');namespaces.append(pair)
   else:
    require(namespaces==[pair] and closed and not stack,'Namespace closure');namespaces.pop()
  elif kind==0x102:
   require(header==16 and b.u32(off+12)==NIL and b.u32(off+16)==NIL and not closed,'Start element')
   name=text(b.u32(off+20));attrs={}
   require(b.u16(off+24)==b.u16(off+26)==20 and size==36+20*b.u16(off+28),'Attribute layout')
   require(b.u16(off+30)==b.u16(off+32)==b.u16(off+34)==0,'Special attribute index')
   for pos in range(off+36,off+size,20):
    nsidx,nidx,raw=b.u32(pos),b.u32(pos+4),b.u32(pos+8);ns='' if nsidx==NIL else text(nsidx);key=ns+'|'+text(nidx)
    typ,value=b.u8(pos+15),b.u32(pos+16)
    require(ns in ('',ANDROID) and key not in attrs,'Namespace/duplicate attribute')
    require(b.u16(pos+12)==8 and b.u8(pos+14)==0 and typ in (1,3,16,17,18),'Typed value')
    decoded=text(value) if typ==3 else value
    require((raw==NIL or text(raw)==decoded) if typ==3 else raw==NIL,'Raw/typed disagreement')
    if typ==18:require(value in (0,NIL),'Boolean encoding')
    attrs[key]={'id':resources[nidx] if nidx<len(resources) else 0,'type':typ,'value':decoded}
   nodes.append({'path':'/'.join([x[0] for x in stack]+[name]),'parent':stack[-1][1] if stack else -1,'attrs':attrs});stack.append((name,len(nodes)-1))
  elif kind==0x103:
   require(header==16 and size==24 and b.u32(off+16)==NIL and stack,'End element');require(text(b.u32(off+20))==stack[-1][0],'Element mismatch');stack.pop();closed=not stack
  else:raise AssertionError('Unapproved AXML chunk %x'%kind)
 require(closed and not stack and not namespaces and nodes,'Incomplete AXML');return nodes

def attr(name,rid,typ,value):return ((ANDROID if rid else '')+'|'+name,{'id':rid,'type':typ,'value':value})
def manifest_expected(appid,label,version,version_name,icon,backup,biometric=False,queries=False):
 nodes=[]
 def node(path,parent,*attrs):nodes.append({'path':path,'parent':parent,'attrs':dict(attrs)})
 node('manifest',-1,attr('package',0,3,appid),attr('versionCode',0x0101021b,16,version),attr('versionName',0x0101021c,3,version_name),attr('compileSdkVersion',0x01010572,16,36),attr('compileSdkVersionCodename',0x01010573,3,'16'),attr('platformBuildVersionCode',0,16,36),attr('platformBuildVersionName',0,16,16))
 node('manifest/uses-sdk',0,attr('minSdkVersion',0x0101020c,16,24),attr('targetSdkVersion',0x01010270,16,35))
 node('manifest/application',0,attr('theme',0x01010000,1,0x01030241),attr('label',0x01010001,3,label),attr('icon',0x01010002,1,icon),attr('allowBackup',0x01010280,18,0),attr('supportsRtl',0x010103af,18,NIL),attr('extractNativeLibs',0x010104ea,18,0),attr('usesCleartextTraffic',0x010104ec,18,0),attr('appComponentFactory',0x0101057a,3,'androidx.core.app.CoreComponentFactory'),attr('dataExtractionRules',0x0101063e,1,backup))
 node('manifest/application/activity',2,attr('name',0x01010003,3,ACTIVITY),attr('exported',0x01010010,18,NIL),attr('windowSoftInputMode',0x0101022b,17,16))
 node('manifest/application/activity/intent-filter',3)
 node('manifest/application/activity/intent-filter/action',4,attr('name',0x01010003,3,'android.intent.action.MAIN'))
 node('manifest/application/activity/intent-filter/category',4,attr('name',0x01010003,3,'android.intent.category.LAUNCHER'))
 if biometric:node('manifest/uses-permission',0,attr('name',0x01010003,3,PERMISSION))
 if queries:
  parent=len(nodes);node('manifest/queries',0)
  for package in BROKERS:node('manifest/queries/package',parent,attr('name',0x01010003,3,package))
 return nodes

def backup_expected():
 nodes=[{'path':'data-extraction-rules','parent':-1,'attrs':{}}]
 for group in ('cloud-backup','device-transfer'):
  parent=len(nodes);path='data-extraction-rules/'+group;nodes.append({'path':path,'parent':0,'attrs':{}})
  for domain in ('root','file','database','sharedpref','external','device_root','device_file','device_database','device_sharedpref'):
   nodes.append({'path':path+'/exclude','parent':parent,'attrs':dict((attr('domain',0,3,domain),attr('path',0,3,'.')))})
 return nodes

def audit_apk(path,template_dex,identity,biometric=False,queries=False):
 files=entries(path);actual_dex=dex(files)
 if template_dex is not None:require(actual_dex==template_dex,'Fixture DEX differs from template')
 resources=F.arsc(files['resources.arsc']);ids={r['name']:int(r['id'],16) for r in resources}
 for row in resources:require(row['path'] in files,'Missing bound resource payload')
 backup=next(r['path'] for r in resources if r['name']=='factory_backup_rules');require(axml(files[backup])==backup_expected(),'Backup exclusions differ')
 classes=[dict(r,dex=n) for n,data in actual_dex.items() for r in F.dex_classes(data)];require(sorted(r['descriptor'] for r in classes)==sorted(F.CLASSES),'Entry components missing/duplicate')
 wanted=manifest_expected(*identity,ids['factory_icon'],ids['factory_backup_rules'],biometric,queries);actual=axml(files['AndroidManifest.xml']);require(actual==wanted,'Closed manifest mismatch: '+str(path))
 pdump=command(SDK/'aapt2','dump','permissions',path);observed=re.findall(r"^uses-permission: name='([^']+)'",pdump,re.M)
 require(observed==([PERMISSION] if biometric else []) and len(re.findall(r'^uses-permission',pdump,re.M))==len(observed),'Official permissions differ')
 xml=command(SDK/'aapt2','dump','xmltree',path,'--file','AndroidManifest.xml');paths=[];stack=[]
 for line in xml.splitlines():
  m=re.match(r'^(\s*)E: ([^ ]+) \(line=',line)
  if m:
   level=len(m[1])
   while stack and stack[-1][0]>=level:stack.pop()
   stack.append((level,m[2]));paths.append('/'.join(x[1] for x in stack))
 require(paths==[n['path'] for n in wanted],'Official tree differs');command(SDK/'zipalign','-c','-P','16','4',path)
 return {'apk':str(path.resolve()),'sha256':sha(path.read_bytes()),'size':path.stat().st_size,'permissions':observed,'queries':BROKERS if queries else [],'nodeCount':len(actual),'manifest':actual,'manifestSha256':sha(files['AndroidManifest.xml']),'aapt2ManifestSha256':sha(xml.encode()),'aapt2PermissionsSha256':sha(pdump.encode()),'resources':resources,'components':classes,'dexSha256':{n:sha(d) for n,d in actual_dex.items()},'zipalign':True,'dexIdenticalToTemplate':template_dex is not None},files

def signed_pair(directory,name,tdex,identity,bio,queries):
 metadata=directory/(name+'.json');record=json.loads(metadata.read_text());require(record.get('testKeyOnly') is True and record.get('privateKeyPersisted') is False,'Ephemeral identity evidence absent')
 before,uf=audit_apk(directory/(name+'-unsigned.apk'),tdex,identity,bio,queries);after,sf=audit_apk(directory/(name+'-signed.apk'),tdex,identity,bio,queries)
 require(uf==sf,'Signed ZIP payload changed');require(after['sha256']==record['signedSha256'],'Signed hash mismatch')
 if 'unsignedSha256' in record:require(before['sha256']==record['unsignedSha256'],'Unsigned hash mismatch')
 if bio:
  require(record['permissions']==[PERMISSION] and record['queries']==(BROKERS if queries else []),'Selected metadata mismatch');require(record['capabilities']==(['biometrics','documents'] if queries else ['biometrics']),'Capability metadata mismatch')
  cfg=json.loads(uf['assets/factory-app.json']);require(cfg['capabilities']==record['capabilities'] and cfg['appId']==identity[0],'Fixture configuration mismatch')
 verify=command(SDK/'apksigner','verify','--verbose','--print-certs',directory/(name+'-signed.apk'))
 for scheme in ('v2','v3'):require('Verified using %s scheme (APK Signature Scheme %s): true'%(scheme,scheme) in verify,'Missing signature '+scheme)
 certs=re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)$',verify,re.M);require(certs==[record['certificateSha256']],'Certificate mismatch')
 return {'name':name,'unsigned':before,'signed':after,'payloadUnchanged':True,'certificateSha256':certs[0],'v2Verified':True,'v3Verified':True,'apksignerOutputSha256':sha(verify.encode()),'metadataSha256':sha(metadata.read_bytes())}

def main():
 p=argparse.ArgumentParser(description=__doc__)
 for k in ('template','fixtures','run','output'):p.add_argument('--'+k,required=True,type=pathlib.Path)
 args=p.parse_args();run=json.loads(args.run.read_text());require(run.get('exit_code')==0 and run.get('finish_epoch',0)>=run['start_epoch'],'Incomplete aggregate');require(args.fixtures.name==run['label'] and '--tests' not in run['args'],'Not full aggregate fixtures')
 template,files=audit_apk(args.template,None,('com.jarvys.factory.template','FACTORY_APP_LABEL',1,'FACTORY_VERSION'));tdex=dex(files);groups=[];metadata=sorted(args.fixtures.rglob('manifest-profiles.json'))
 require({str(f.relative_to(args.fixtures)) for f in metadata}=={'app-testFullDebugUnitTest/manifest-profiles.json','app-testPlayDebugUnitTest/manifest-profiles.json'},'Missing/extra Full/Play groups')
 for meta in metadata:
  directory=meta.parent;record=json.loads(meta.read_text());names=['manifest-profiles.json']+['profile-'+n+'-unsigned.apk' for n in ('zero','queries','biometrics','biometrics-queries')]
  for n in ('biometrics','biometrics-queries','0','1','2'):names += [n+'.json',n+'-unsigned.apk',n+'-signed.apk']
  for n in names:require(run['start_epoch']<=(directory/n).stat().st_mtime<=run['finish_epoch']+1,'Fixture freshness: '+n)
  require(record['testFixtureOnly'] is True and record['exhaustiveSelections']==1048576 and record['schemaVersion']==1,'Exhaustive receipt mismatch');require([r['name'] for r in record['profiles']]==['zero','queries','biometrics','biometrics-queries'],'Profile names');profiles=[]
  for r in record['profiles']:
   bio=r['name'].startswith('biometrics');queries='queries' in r['name'];require(r['file']=='profile-'+r['name']+'-unsigned.apk','Profile file')
   row,_=audit_apk(directory/r['file'],tdex,('org.example.factory.notes','Factory notes',1,'1.0'),bio,queries)
   require(row['sha256']==r['sha256'] and row['permissions']==r['permissions'] and row['queries']==r['queries'] and row['nodeCount']==r['nodeCount'] and r['features']==[],'Profile metadata')
   require(r['appId']=='org.example.factory.notes' and r['versionCode']==1 and r['capabilities']==(['biometrics'] if bio else [])+(['documents'] if queries else []),'Profile identity/capabilities');profiles.append(row)
  signed=[signed_pair(directory,n,tdex,(appid,'Biometric test',1,'1.0'),True,n.endswith('queries')) for n,appid in [('biometrics','org.example.biometrics'),('biometrics-queries','org.example.biometricqueries')]]
  legacy=[signed_pair(directory,str(i),tdex,(appid,'Test '+str(i),v,str(v)+'.0'),False,False) for i,(appid,v) in enumerate([('org.example.first',1),('org.example.second',1),('org.example.first',2)])]
  require(legacy[0]['certificateSha256']==legacy[2]['certificateSha256']!=legacy[1]['certificateSha256'],'Legacy update identity relation')
  groups.append({'directory':str(directory.resolve()),'profiles':profiles,'signedBiometricFixtures':signed,'retainedZeroPermissionFixtures':legacy})
 result={'passed':True,'hostOnly':True,'fixtureGroups':len(groups),'template':template,'groups':groups,'aggregateRun':str(args.run.resolve()),'aggregateRunSha256':sha(args.run.read_bytes()),'freshFullPlayEvidence':True,'scriptSha256':sha(pathlib.Path(__file__).read_bytes()),'f0aHelperSha256':sha(HELPER.read_bytes()),'limits':'Synthetic profile and ephemeral signing evidence only. No device biometrics, installation, OS dialog rendering, key custody or remote identity claim.'}
 args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(result,indent=2)+'\n');print(json.dumps({'passed':True,'fixtureGroups':len(groups),'output':str(args.output)}))
if __name__=='__main__':main()

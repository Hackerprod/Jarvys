#!/usr/bin/env python3
"""Independent official-aapt2 comparison of non-installable construction fixtures.
Expected nodes, parents, attributes and IDs below are authored independently of the Java encoder.
"""
import argparse, hashlib, json, pathlib, re, subprocess, zipfile
p=argparse.ArgumentParser();p.add_argument('--fixtures',required=True,type=pathlib.Path);p.add_argument('--output',required=True,type=pathlib.Path);a=p.parse_args()
NS='http://schemas.android.com/apk/res/android:'
def attr(name,id): return NS+name+'(0x'+id+')'
def string(value): return json.dumps(value,ensure_ascii=False)+' (Raw: '+json.dumps(value,ensure_ascii=False)+')'
def attributes(**values):
 ids={'name':'01010003','exported':'01010010','authorities':'01010018','grantUriPermissions':'0101001b','maxSdkVersion':'01010271','required':'0101028e','value':'01010024','resource':'01010025','mimeType':'01010026','scheme':'01010027'}
 return {attr(k,ids[k]):v for k,v in values.items()}
def expected(extended):
 nodes=[]
 def add(name,parent,attrs=None):
  path=name if parent==-1 else nodes[parent]['path']+'/'+name
  nodes.append({'path':path,'parent':parent,'attrs':attrs or {}});return len(nodes)-1
 root=add('manifest',-1,{'package':string('org.example.constructed'),attr('versionCode','0101021b'):'7',attr('versionName','0101021c'):string('versionName'),attr('compileSdkVersion','01010572'):'36',attr('compileSdkVersionCodename','01010573'):string('16'),'platformBuildVersionCode':'36','platformBuildVersionName':'16'})
 add('uses-sdk',root,{attr('minSdkVersion','0101020c'):'24',attr('targetSdkVersion','01010270'):'35'})
 if extended:
  add('uses-permission',root,attributes(name=string('android.permission.INTERNET')))
  add('uses-permission-sdk-23',root,attributes(name=string('android.permission.CAMERA'),maxSdkVersion='35'))
  add('uses-permission',root,attributes(name=string('android.permission.RECORD_AUDIO'),maxSdkVersion='36'))
  add('uses-feature',root,attributes(name=string('android.hardware.camera'),required='false'))
  add('uses-feature',root,attributes(name=string('android.hardware.sensor.accelerometer'),required='true'))
  queries=add('queries',root)
  for action,data in [('SEND',attributes(mimeType=string('text/plain'))),('VIEW',attributes(scheme=string('https')))]:
   intent=add('intent',queries);add('action',intent,attributes(name=string('android.intent.action.'+action)));add('data',intent,data)
  for package in ['org.example.handler','org.example.second']:add('package',queries,attributes(name=string(package)))
 app=add('application',root,{attr('theme','01010000'):'@0x01030241',attr('label','01010001'):string('name 🎉 日本語'),attr('icon','01010002'):'@0x7f010000',attr('allowBackup','01010280'):'false',attr('supportsRtl','010103af'):'true',attr('extractNativeLibs','010104ea'):'false',attr('usesCleartextTraffic','010104ec'):'false',attr('appComponentFactory','0101057a'):string('androidx.core.app.CoreComponentFactory'),attr('dataExtractionRules','0101063e'):'@0x7f020000'})
 launcher=add('activity',app,attributes(name=string('com.jarvys.factory.runtime.FactoryActivity'),exported='true')|{attr('windowSoftInputMode','0101022b'):'0x00000010'})
 f=add('intent-filter',launcher);add('action',f,attributes(name=string('android.intent.action.MAIN')));add('category',f,attributes(name=string('android.intent.category.LAUNCHER')))
 if extended:
  def private(kind,name):return add(kind,app,attributes(name=string('org.example.'+name),exported='false'))
  def filter(parent,actions,categories,mime):
   f=add('intent-filter',parent)
   for action in actions:add('action',f,attributes(name=string('android.intent.action.'+action)))
   for category in categories:add('category',f,attributes(name=string('android.intent.category.'+category)))
   add('data',f,attributes(mimeType=string(mime)))
  activity=private('activity','PrivateActivity')
  filter(activity,['SEND','SEND_MULTIPLE'],['BROWSABLE','DEFAULT'],'text/plain')
  filter(activity,['VIEW'],['DEFAULT'],'image/png')
  add('meta-data',activity,attributes(name=string('org.example.flag'),value='false'))
  add('meta-data',activity,attributes(name=string('org.example.label'),value=string('name')))
  service=private('service','PrivateService')
  add('meta-data',service,attributes(name=string('org.example.count'),value='17'))
  add('meta-data',service,attributes(name=string('org.example.resource'),resource='@0x7f020000'))
  receiver=private('receiver','PrivateReceiver');filter(receiver,['SEND'],['DEFAULT'],'application/json')
  provider=add('provider',app,attributes(name=string('org.example.PrivateProvider'),exported='false',authorities=string('org.example.constructed.files'),grantUriPermissions='true'))
  add('meta-data',provider,attributes(name=string('android.support.FILE_PROVIDER_PATHS'),resource='@0x7f020000'))
 return nodes

def decoded(path):
 dump=subprocess.check_output(['aapt2','dump','xmltree',str(path),'--file','AndroidManifest.xml'],text=True,stderr=subprocess.STDOUT)
 nodes=[];stack=[]
 for line in dump.splitlines():
  m=re.match(r'^(\s*)E: ([^ ]+) \(line=',line)
  if m:
   indent=len(m[1]);name=m[2]
   while stack and stack[-1][0]>=indent:stack.pop()
   parent=stack[-1][1] if stack else -1
   path=name if parent==-1 else nodes[parent]['path']+'/'+name
   nodes.append({'path':path,'parent':parent,'attrs':{}});stack.append((indent,len(nodes)-1));continue
  m=re.match(r'^\s*A: (.+?)=(.*)$',line)
  if m:
   assert nodes and m[1] not in nodes[-1]['attrs'];nodes[-1]['attrs'][m[1]]=m[2]
 return dump,nodes
rows=[]
for source in sorted(a.fixtures.glob('*/typed-manifest/*.xml')):
 extended=source.name=='AndroidManifest.xml'
 archive=source.with_suffix('.host-fixture.zip')
 with zipfile.ZipFile(archive,'w',compression=zipfile.ZIP_STORED) as z:z.writestr('AndroidManifest.xml',source.read_bytes())
 dump,nodes=decoded(archive);wanted=expected(extended)
 assert nodes==wanted,(str(source),nodes,wanted)
 source.with_suffix('.aapt2.txt').write_text(dump)
 rows.append({'source':str(source.relative_to(a.fixtures)),'manifestSha256':hashlib.sha256(source.read_bytes()).hexdigest(),'aapt2DumpSha256':hashlib.sha256(dump.encode()).hexdigest(),'nodes':len(nodes),'attributes':sum(len(n['attrs']) for n in nodes),'explicitParentsVerified':True,'extendedConstructionOnly':extended})
assert len(rows)>=2 and len(rows)%2==0,rows
result={'passed':True,'hostOnly':True,'rows':rows,'limits':'Non-installable manifest-only archives. Fixture component classes/resource references are not functional handlers or verified compiled bindings. No Android installation, grant or action.'}
a.output.write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))

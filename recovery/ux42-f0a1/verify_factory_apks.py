#!/usr/bin/env python3
"""Host-only cross-check using official aapt2/apksigner/zipalign; no installation or key access."""
import argparse,hashlib,json,pathlib,re,subprocess,zipfile
p=argparse.ArgumentParser();p.add_argument('--template',required=True,type=pathlib.Path);p.add_argument('--fixtures',required=True,type=pathlib.Path);p.add_argument('--output',required=True,type=pathlib.Path);a=p.parse_args()
def command(*args):return subprocess.check_output(args,text=True,stderr=subprocess.STDOUT)
def entries(path):
 with zipfile.ZipFile(path) as z:
  assert z.testzip() is None
  assert len(z.namelist())==len(set(z.namelist()))
  return {n:z.read(n) for n in z.namelist()}
def dex(files):return {n:hashlib.sha256(b).hexdigest() for n,b in files.items() if n.endswith('.dex')}
def decode(path):
 output=command('aapt2','dump','xmltree',str(path),'--file','AndroidManifest.xml');nodes=[];stack=[]
 for line in output.splitlines():
  m=re.match(r'^(\s*)E: ([^ ]+) \(line=',line)
  if m:
   indent=len(m[1]);name=m[2]
   while stack and stack[-1][0]>=indent:stack.pop()
   stack.append((indent,name));nodes.append(('/'.join(x[1] for x in stack),{}));continue
  m=re.match(r'^\s*A: (.+?)=(.*)$',line)
  if m:
   assert nodes and m[1] not in nodes[-1][1]
   nodes[-1][1][m[1]]=m[2]
 return output,nodes
def check(path,index,record):
 output,nodes=decode(path);base='http://schemas.android.com/apk/res/android:'
 def attr(name,id):return base+name+'(0x'+id+')'
 def string(value):return json.dumps(value,ensure_ascii=False)+' (Raw: '+json.dumps(value,ensure_ascii=False)+')'
 resources=command('aapt2','dump','resources',str(path))
 icon=re.search(r'resource (0x[0-9a-f]+) drawable/factory_icon\n',resources)[1]
 backup=re.search(r'resource (0x[0-9a-f]+) xml/factory_backup_rules\n',resources)[1]
 expect=[('manifest',{'package':string(record['appId']),attr('versionCode','0101021b'):str(record['versionCode']),attr('versionName','0101021c'):string(str(record['versionCode'])+'.0'),attr('compileSdkVersion','01010572'):'36',attr('compileSdkVersionCodename','01010573'):string('16'),'platformBuildVersionCode':'36','platformBuildVersionName':'16'}),
 ('manifest/uses-sdk',{attr('minSdkVersion','0101020c'):'24',attr('targetSdkVersion','01010270'):'35'}),
 ('manifest/application',{attr('theme','01010000'):'@0x01030241',attr('label','01010001'):string('Test '+str(index)),attr('icon','01010002'):'@'+icon,attr('allowBackup','01010280'):'false',attr('supportsRtl','010103af'):'true',attr('extractNativeLibs','010104ea'):'false',attr('usesCleartextTraffic','010104ec'):'false',attr('appComponentFactory','0101057a'):string('androidx.core.app.CoreComponentFactory'),attr('dataExtractionRules','0101063e'):'@'+backup}),
 ('manifest/application/activity',{attr('name','01010003'):string('com.jarvys.factory.runtime.FactoryActivity'),attr('exported','01010010'):'true',attr('windowSoftInputMode','0101022b'):'0x00000010'}),
 ('manifest/application/activity/intent-filter',{}),('manifest/application/activity/intent-filter/action',{attr('name','01010003'):string('android.intent.action.MAIN')}),('manifest/application/activity/intent-filter/category',{attr('name','01010003'):string('android.intent.category.LAUNCHER')})]
 assert nodes==expect,(str(path),nodes,expect)
 permissions=command('aapt2','dump','permissions',str(path));assert 'uses-permission:' not in permissions
 command('zipalign','-c','-P','16','4',str(path))
 return hashlib.sha256(output.encode()).hexdigest()
original=dex(entries(a.template));assert original
rows=[]
for metadata in sorted(a.fixtures.glob('*/[012].json')):
 record=json.loads(metadata.read_text());index=int(metadata.stem)
 assert record['testKeyOnly'] and not record['privateKeyPersisted']
 unsigned=metadata.with_name(str(index)+'-unsigned.apk');signed=metadata.with_name(str(index)+'-signed.apk')
 before=entries(unsigned);after=entries(signed);assert before==after
 assert dex(before)==original and dex(after)==original
 assert hashlib.sha256(signed.read_bytes()).hexdigest()==record['signedSha256']
 manifest=check(unsigned,index,record);assert manifest==check(signed,index,record)
 verify=command('apksigner','verify','--verbose','--print-certs',str(signed))
 assert 'Verified using v2 scheme (APK Signature Scheme v2): true' in verify
 assert 'Verified using v3 scheme (APK Signature Scheme v3): true' in verify
 actual_certificate=re.findall(r'^Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]+)$',verify,re.M)
 assert actual_certificate==[record['certificateSha256']],(actual_certificate,record['certificateSha256'])
 rows.append({'fixture':metadata.parent.name+'/'+metadata.stem,'appId':record['appId'],'versionCode':record['versionCode'],'certificateSha256':record['certificateSha256'],'unsignedSha256':hashlib.sha256(unsigned.read_bytes()).hexdigest(),'signedSha256':record['signedSha256'],'manifestDumpSha256':manifest,'dexSha256':original})
assert len(rows)>=3 and len(rows)%3==0,len(rows)
for offset in range(0,len(rows),3):
 one,two,update=rows[offset:offset+3];assert one['appId']!=two['appId'] and one['appId']==update['appId'];assert one['certificateSha256']==update['certificateSha256']!=two['certificateSha256'];assert update['versionCode']>one['versionCode']
result={'hostOnly':True,'fixtureCount':len(rows),'apkCount':len(rows)*2,'passed':True,'rows':rows,'limits':'Ephemeral signing fixtures; no Android installation, key-provider or physical WebView/SAF acceptance.'}
a.output.write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))

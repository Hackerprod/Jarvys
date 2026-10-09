#!/usr/bin/env python3
"""Prepare separate compact/aligned unsigned copies; leave Gradle outputs unchanged."""
from pathlib import Path
import copy, hashlib, json, re, struct, subprocess, zipfile
ROOT=Path('/workspace/shared/Jarvys-recovery')
REPO=ROOT/'integration/app'
OUT=ROOT/'artifacts/UX39-v56'
TOOLS=ROOT/'toolchain/android-sdk/build-tools/35.0.0'
OUT.mkdir(parents=True,exist_ok=True)
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def entries(p):
 with zipfile.ZipFile(p) as z:
  assert not z.testzip(), 'ZIP CRC failure'
  names=z.namelist(); assert len(names)==len(set(names)), 'Duplicate ZIP entries'
  return {i.filename:hashlib.sha256(z.read(i)).hexdigest() for i in z.infolist()}
def check_contiguous(p):
 b=p.read_bytes()
 with zipfile.ZipFile(p) as z:
  cursor=0
  for i in sorted(z.infolist(),key=lambda i:i.header_offset):
   assert i.header_offset==cursor, 'ZIP gap before '+i.filename
   sig,version,flags,method,tm,dt,crc,compressed,raw,name_len,extra_len=struct.unpack_from('<IHHHHHIIIHH',b,cursor)
   assert sig==0x04034b50 and not(flags&8), 'Unexpected local ZIP header/descriptor'
   cursor+=30+name_len+extra_len+i.compress_size
  assert cursor==z.start_dir,'Gap before central directory'
def dex_classes(data):
 assert data[:4]==b'dex\n'
 nstrings,stringsoff,ntypes,typesoff=struct.unpack_from('<IIII',data,0x38)
 nclasses,classesoff=struct.unpack_from('<II',data,0x60)
 def string(idx):
  p=struct.unpack_from('<I',data,stringsoff+4*idx)[0]
  while data[p]&0x80:p+=1
  p+=1
  return data[p:data.index(0,p)].decode('utf-8','replace')
 types=[string(struct.unpack_from('<I',data,typesoff+4*i)[0]) for i in range(ntypes)]
 return {types[struct.unpack_from('<I',data,classesoff+32*i)[0]] for i in range(nclasses)}
results=[]
for flavor in ['full','play']:
 raw=REPO/f'app/build/outputs/apk/{flavor}/debug/app-{flavor}-debug-unsigned.apk'
 assert raw.is_file(),str(raw)
 compact=OUT/f'Jarvys-UX39-{flavor}-v56-compact.tmp.apk'
 final=OUT/f'Jarvys-UX39-{flavor}-v56-unsigned.apk'
 assert not compact.exists() and not final.exists(), 'Refuse replacing prior handoff copies'
 rawhash=sha(raw); expected=entries(raw)
 with zipfile.ZipFile(raw) as src,zipfile.ZipFile(compact,'w') as dst:
  for info in src.infolist():
   entry=copy.copy(info); entry.extra=b''
   dst.writestr(entry,src.read(info))
 subprocess.run([str(TOOLS/'zipalign'),'-P','16','4',str(compact),str(final)],check=True)
 aligned=subprocess.run([str(TOOLS/'zipalign'),'-c','-P','16','4',str(final)],capture_output=True,text=True)
 assert aligned.returncode==0,aligned.stderr
 actual=entries(final); assert actual==expected,'Entry contents changed'
 assert sha(raw)==rawhash,'Gradle output was modified'
 check_contiguous(final)
 badging=subprocess.check_output([str(TOOLS/'aapt'),'dump','badging',str(final)],text=True)
 assert "package: name='com.jarvys.agent' versionCode='56' versionName='1.2.49-UX39'" in badging
 assert "application-label:'Jarvys'" in badging and 'application-debuggable' in badging
 permissions=subprocess.check_output([str(TOOLS/'aapt'),'dump','permissions',str(final)],text=True)
 signature=subprocess.run([str(TOOLS/'apksigner'),'verify','--verbose',str(final)],capture_output=True,text=True)
 assert signature.returncode!=0 and 'Missing META-INF/MANIFEST.MF' in signature.stderr+signature.stdout, 'Unexpected signature-verification failure'
 with zipfile.ZipFile(final) as z:
  assert not any(re.match(r'META-INF/.*\.(SF|RSA|DSA|EC)$',n,re.I) for n in z.namelist())
  classes=set()
  for n in z.namelist():
   if re.fullmatch(r'classes\d*\.dex',n):classes.update(dex_classes(z.read(n)))
  forbidden=[x for x in classes if x.startswith(('Lorg/robolectric/','Lorg/junit/','Lguard/'))]
  assert not forbidden,forbidden[:5]
  if flavor=='play':
   assert not any(x.startswith(('Lcom/google/android/gms/','Lcom/jarvys/agent/linux/')) for x in classes)
   assert not any('proot' in n for n in z.namelist())
  template=z.read('assets/apk_factory/template.apk')
  template_hash=z.read('assets/apk_factory/template.sha256').decode().strip()
  assert hashlib.sha256(template).hexdigest()==template_hash
 record={'flavor':flavor,'raw_path':str(raw),'raw_sha256':rawhash,'path':str(final),'sha256':sha(final),'bytes':final.stat().st_size,'entry_count':len(actual),'entry_contents_identical_to_gradle':True,'crc_verified':True,'aligned_16k':True,'zip_gaps':False,'unsigned':True,'package':'com.jarvys.agent','version_code':56,'version_name':'1.2.49-UX39','label':'Jarvys','debuggable':True,'class_definitions':len(classes),'host_guard_or_test_classes':False,'factory_template_sha256':template_hash,'permissions':permissions.splitlines(),'entries':actual}
 (OUT/f'{flavor}-aapt-badging.txt').write_text(badging)
 (OUT/f'{flavor}-aapt-permissions.txt').write_text(permissions)
 (OUT/f'{flavor}-apksigner-unsigned.txt').write_text(signature.stdout+signature.stderr)
 (OUT/f'{flavor}-entry-hashes.json').write_text(json.dumps(record.pop('entries'),indent=2)+'\n')
 results.append(record)
 compact.unlink() # Disposable intermediate only; verified final copy and raw output remain.
 print(json.dumps(record),flush=True)
(OUT/'unsigned-artifacts.json').write_text(json.dumps(results,indent=2)+'\n')

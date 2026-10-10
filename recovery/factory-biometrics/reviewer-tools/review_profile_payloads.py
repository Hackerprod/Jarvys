#!/usr/bin/env python3
"""Independent synthetic controls and exact v79 profile comparison; no parser edits."""
import hashlib,json,pathlib,struct,tempfile,zipfile,zlib
import profile_payloads as parser
from test_profile_payloads import ProfileControls
ROOT=pathlib.Path(__file__).resolve().parent
ProfileControls.setUpClass();t=ProfileControls();d=t.dex;results=[]

def accepted(name,condition):
 if not condition:raise AssertionError(name)
 results.append({'case':name,'passed':True})
def rejected(name,prof,meta,dex=None):
 try:parser.decode(prof,meta,{'classes.dex':t.data} if dex is None else dex)
 except (ValueError,zlib.error,UnicodeDecodeError) as error:results.append({'case':name,'rejected':True,'reason':str(error)})
 else:raise AssertionError('Accepted negative: '+name)

def changes(data,offset,value,meta=False):
 body=bytearray(zlib.decompress(data[18 if meta else 17:]));body[offset:offset+len(value)]=value
 return t.wrap(bytes(body),meta)

classdefs=[d.types[d.u32(p)] for p in d.table(96,32)]
accepted('fixture distinguishes index spaces',d.types[0]!=classdefs[0])
prof,meta=t.inputs(classids=(0,),metaids=(0,));decoded=parser.decode(prof,meta,{'classes.dex':t.data})['classes.dex']
accepted('P010 zero means type ID zero',decoded['classes']==[d.types[0]])
accepted('metadata002 zero means class-def zero',decoded['metadataClasses']==[classdefs[0]])
prof,meta=t.inputs(classids=(d.types.index(classdefs[1]),),metaids=(1,));decoded=parser.decode(prof,meta,{'classes.dex':t.data})['classes.dex']
accepted('matching nonempty semantic class sets through different index spaces',decoded['classes']==decoded['metadataClasses']==[classdefs[1]])
for flag in range(1,8):
 prof,meta=t.inputs(flags={0:flag});decoded=parser.decode(prof,meta,{'classes.dex':t.data})['classes.dex']
 accepted('preserve exact H/S/P flags '+str(flag),decoded['methods']=={d.method_symbol(0):flag})
for name,kwargs in [('P010 duplicate class delta',{'classids':(0,0)}),('P010 class out of range',{'classids':(len(d.types),)}),
                    ('metadata duplicate class delta',{'metaids':(0,0)}),('metadata class-def out of range',{'metaids':(len(classdefs),)})]:
 prof,meta=t.inputs(**kwargs);rejected(name,prof,meta)
prof,meta=t.inputs();rejected('metadata unsupported version',prof,meta[:4]+b'999\0'+meta[8:])
rejected('metadata declared DEX count mismatch',prof,meta[:8]+struct.pack('<H',2)+meta[10:])
rejected('metadata trailing body data',prof,t.wrap(zlib.decompress(meta[18:])+b'x',True))
rejected('metadata trailing file data',prof,meta+b'x')
for hot in (1,3,5):rejected('misaligned hot region '+str(hot),changes(prof,4,struct.pack('<I',hot)),meta)
n=len(d.methods);bits=2*n
if bits%8:
 body=bytearray(zlib.decompress(prof[17:]));body[-1]|=0x80
 rejected('nonzero bitmap padding',t.wrap(bytes(body)),meta)
rejected('claimed inflated size too large',prof[:9]+struct.pack('<I',struct.unpack_from('<I',prof,9)[0]+1)+prof[13:],meta)
rejected('truncated zlib stream',prof[:13]+struct.pack('<I',len(prof)-18)+prof[17:-1],meta)
extra=zlib.compress(b'other stream');rejected('concatenated compressed stream',prof[:13]+struct.pack('<I',len(prof)-17+len(extra))+prof[17:]+extra,meta)
# A valid two-DEX layout with different flags proves headers are read before bodies.
p1,m1=t.inputs(flags={0:1});p2,m2=t.inputs(flags={1:6},key=b'classes2.dex')
b1=zlib.decompress(p1[17:]);b2=zlib.decompress(p2[17:]);h1=16+len(b'classes.dex');h2=16+len(b'classes2.dex')
m1b=zlib.decompress(m1[18:]);m2b=bytearray(zlib.decompress(m2[18:]));m2b[:2]=struct.pack('<H',1)
p=t.wrap(b1[:h1]+b2[:h2]+b1[h1:]+b2[h2:],count=2);m=t.wrap(m1b+bytes(m2b),True,count=2)
multi=parser.decode(p,m,{'classes.dex':t.data,'classes2.dex':t.data})
accepted('two DEX headers/bodies retain per-DEX flags',multi['classes.dex']['methods']=={d.method_symbol(0):1} and multi['classes2.dex']['methods']=={d.method_symbol(1):6})
rejected('duplicate DEX profile key',t.wrap(b1[:h1]+b1[:h1]+b1[h1:]+b1[h1:],count=2),m,{'classes.dex':t.data,'classes2.dex':t.data})
rejected('metadata record order/index mismatch',p,t.wrap(bytes(m2b)+m1b,True,count=2),{'classes.dex':t.data,'classes2.dex':t.data})
with tempfile.TemporaryDirectory(prefix='profile-review-',dir=ROOT) as folder:
 for entries in (['classes.dex','classes3.dex'],['classes2.dex'],['classes.dex','classes2.DEX']):
  apk=pathlib.Path(folder)/'negative.apk'
  with zipfile.ZipFile(apk,'w') as z:
   for name in entries:z.writestr(name,t.data)
   z.writestr('assets/dexopt/baseline.prof',prof);z.writestr('assets/dexopt/baseline.profm',meta)
  try:parser.inventory(apk)
  except ValueError as error:results.append({'case':'APK noncanonical/gapped '+str(entries),'rejected':True,'reason':str(error)})
  else:raise AssertionError('Invalid DEX inventory accepted')
old_inv=json.loads((ROOT/'delivered-v79-template.symbolic.json').read_text());new_inv=json.loads((ROOT/'reconstructed-v79-template.symbolic.json').read_text())
old=parser.inventory(pathlib.Path(old_inv['source']));new=parser.inventory(pathlib.Path(new_inv['source']))
accepted('actual v79 profile symbols and flags identical',old['semanticProfile']==new['semanticProfile'])
accepted('actual v79 APK hashes bind symbolic inventories',old['apkSha256']==old_inv['sourceSha256'] and new['apkSha256']==new_inv['sourceSha256'])
accepted('actual v79 exactly 45 method symbols',sum(len(x['methods']) for x in old['profiles'].values())==45)
accepted('actual v79 both class sets empty',all(not x['classes'] and not x['metadataClasses'] for inv in (old,new) for x in inv['profiles'].values()))
report={'parserSha256':hashlib.sha256((ROOT/'profile_payloads.py').read_bytes()).hexdigest(),'passed':True,'controlCount':len(results),'controls':results,
        'indexSpaceDiscriminator':{'typeId0':d.types[0],'classDef0':classdefs[0]},'actualDeliveredProfile':old,'actualReconstructedProfile':new,
        'scope':'Read-only profile verification and synthetic controls; no Gradle, signing, generic-comparator changes or final gate approval.'}
(ROOT/'profile-independent-review.json').write_text(json.dumps(report,indent=2,sort_keys=True)+'\n')
print(json.dumps({'passed':True,'controls':len(results),'profileMethods':45,'parserSha256':report['parserSha256']}))

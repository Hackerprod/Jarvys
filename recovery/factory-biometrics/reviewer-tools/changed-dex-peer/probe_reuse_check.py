#!/usr/bin/env python3
"""Tiny real-D8 probe equivalence check; never an application audit."""
from pathlib import Path
import hashlib,json,struct,tempfile,zipfile,zlib
import reuse_unchanged_dex as reuse
O=Path(__file__).resolve().parent.parent
helper=reuse.load_approved_helper(O/'symbolic_dex.py')
tool=O.parents[1]/'toolchain/android-sdk/build-tools/35.0.0/dexdump'
assert reuse.hash_file(tool)==reuse.DEXDUMP_SHA
raw=(O/'probe-dex/classes.dex').read_bytes();assert len(raw)==2116 and raw.count(b'marker\0')==1
changed=bytearray(raw.replace(b'marker\0',b'markes\0'));changed[12:32]=hashlib.sha1(changed[32:]).digest();struct.pack_into('<I',changed,8,zlib.adler32(changed[12:])&0xffffffff)
rows=[]
with tempfile.TemporaryDirectory(prefix='real-probe-',dir=Path(__file__).resolve().parent) as temp:
 temp=Path(temp);apks=[]
 for name,data in [('before',raw),('same',raw),('changed',changed)]:
  path=temp/(name+'.apk')
  with zipfile.ZipFile(path,'w') as z:z.writestr('classes.dex',data)
  apks.append(path)
 old=helper.extract(apks[0],tool);pins={'apkSha256':old['sourceSha256'],'classes':3,'dexFiles':1}
 for apk,label in zip(apks[1:],['same','changed']):
  fresh=helper.extract(apk,tool)
  with reuse.Apk(apks[0],pins['apkSha256']) as before,reuse.Apk(apk) as after:
   result,provenance=reuse.reconstruct(old,before,after,pins,helper,lambda name,data:reuse.parse_changed_payload(helper,tool,name,data,temp))
   before.verify_unchanged();after.verify_unchanged()
  assert result['classes']==fresh['classes'] and len(result['classes'])==3
  assert provenance['reusedDexEntries']==(['classes.dex'] if label=='same' else [])
  assert provenance['freshlyParsedDexEntries']==(['classes.dex'] if label=='changed' else [])
  assert helper.compare(old,result,False)['equivalent']==(label=='same')
  rows.append({'case':label,'completeClassContentMatchesOriginalExtractor':True,'classes':3,'reused':provenance['reusedDexEntries'],'fullyParsed':provenance['freshlyParsedDexEntries']})
report={'passed':True,'scope':'Tiny static 2,116-byte D8 probe, no application approval','rows':rows,'wrapperSha256':reuse.hash_file(Path(reuse.__file__)),'helperSha256':reuse.HELPER_SHA,'dexdumpSha256':reuse.DEXDUMP_SHA}
Path(__file__).with_suffix('.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report))

#!/usr/bin/env python3
"""Read-only semantic binding of AGP P010/metadata002 profiles to their APK DEX.
Only static profiles without inline caches are supported; unknown formats fail closed.
Format reference: AndroidX ProfileTranscoder.java in the official androidx/androidx repository.
"""
from pathlib import Path
import argparse,hashlib,json,re,struct,zipfile,zlib
from symbolic_dex import Dex

def require(x,why):
 if not x:raise ValueError(why)
class Reader:
 def __init__(self,data):self.data=data;self.pos=0
 def take(self,n):
  require(n>=0 and self.pos+n<=len(self.data),'Truncated profile');v=self.data[self.pos:self.pos+n];self.pos+=n;return v
 def num(self,n):return int.from_bytes(self.take(n),'little')
 def done(self):require(self.pos==len(self.data),'Trailing profile data')
def inflate(data,magic,count_width):
 r=Reader(data);require(r.take(8)==magic,'Unsupported profile format');count=r.num(count_width);size=r.num(4);compressed=r.num(4);require(count>0 and size<=64*1024*1024,'Invalid profile bounds');b=r.take(compressed);r.done();d=zlib.decompressobj();out=d.decompress(b,size+1);require(len(out)==size and d.eof and not d.unused_data and not d.unconsumed_tail,'Invalid profile compression/size');return count,Reader(out)
def decode(prof,meta,dex_data):
 count,r=inflate(prof,b'pro\x00010\x00',1);headers=[];dex={k:Dex(v) for k,v in dex_data.items()};seen=set()
 for _ in range(count):
  keysize=r.num(2);classes=r.num(2);hot=r.num(4);crc=r.num(4);methods=r.num(4);key=r.take(keysize).decode('utf-8');require(re.fullmatch(r'classes(?:(?:[2-9]|[1-9][0-9]+))?\.dex',key) and key in dex and key not in seen,'Unknown/duplicate profile DEX key');seen.add(key);d=dex[key];require(crc==zlib.crc32(dex_data[key])&0xffffffff,'Profile CRC does not bind DEX');require(methods==len(d.methods),'Profile method count does not bind DEX');headers.append((key,classes,hot,crc,methods))
 out={}
 for key,nclass,hot,crc,nmethods in headers:
  d=dex[key];stop=r.pos+hot;methods={};index=0
  while r.pos<stop:
   index+=r.num(2);inline=r.num(2);require(inline==0,'Inline-cache profiles not supported');require(index<nmethods and index not in methods,'Invalid/duplicate hot method');methods[index]=1
  require(r.pos==stop,'Hot method region length mismatch');classes=[];index=0
  for _ in range(nclass):index+=r.num(2);require(index<len(d.types) and index not in classes,'Invalid/duplicate profile class');classes.append(index)
  bitmap=r.take((nmethods*2+7)//8);bits=int.from_bytes(bitmap,'little');require(bits>>(2*nmethods)==0,'Nonzero profile bitmap padding')
  for index in range(nmethods):
   flags=((bits>>index)&1)*2+((bits>>(nmethods+index))&1)*4
   if flags:methods[index]=methods.get(index,0)|flags
  out[key]={'dexCrc32':crc,'methodIds':nmethods,'typeIds':len(d.types),'classes':sorted(d.types[i] for i in classes),'methods':{d.method_symbol(i):v for i,v in sorted(methods.items())}}
 r.done();mcount,m=inflate(meta,b'prm\x00002\x00',2);require(mcount==count,'Metadata/profile DEX count mismatch');mseen=set()
 for i in range(mcount):
  profile_index=m.num(2);key=m.take(m.num(2)).decode('utf-8');types=m.num(4);nclass=m.num(2);require(profile_index==i and key==headers[i][0] and key not in mseen,'Metadata profile order/key mismatch');mseen.add(key);d=dex[key];require(types==len(d.types),'Metadata type count does not bind DEX');defs=[d.types[d.u32(p)] for p in d.table(96,32)];indices=[];index=0
  for _ in range(nclass):index+=m.num(2);require(index<len(defs) and index not in indices,'Invalid metadata class index');indices.append(index)
  out[key]['metadataClasses']=sorted(defs[i] for i in indices)
 m.done();return out

def inventory(apk):
 with zipfile.ZipFile(apk) as z:
  names=z.namelist();require(len(names)==len(set(names)),'Duplicate ZIP entries');require(z.testzip() is None,'ZIP CRC failure');require(all(re.fullmatch(r'classes(?:(?:[2-9]|[1-9][0-9]+))?\.dex',n) for n in names if n.lower().endswith('.dex')),'Noncanonical DEX path');entries={n:z.read(n) for n in names if re.fullmatch(r'classes(?:(?:[2-9]|[1-9][0-9]+))?\.dex',n)};require(set(entries)=={'classes'+('' if i==1 else str(i))+'.dex' for i in range(1,len(entries)+1)},'Missing/noncontiguous canonical DEX');require(entries,'No canonical DEX');prof=z.read('assets/dexopt/baseline.prof');meta=z.read('assets/dexopt/baseline.profm');profiles=decode(prof,meta,entries)
 return {'apk':str(apk),'apkSha256':hashlib.sha256(Path(apk).read_bytes()).hexdigest(),'profileSha256':hashlib.sha256(prof).hexdigest(),'metadataSha256':hashlib.sha256(meta).hexdigest(),'profiles':profiles,'semanticProfile':{k:{f:v for f,v in row.items() if f not in ('dexCrc32','methodIds','typeIds')} for k,row in profiles.items()}}

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('apk',type=Path);p.add_argument('--output',type=Path,required=True);a=p.parse_args();r=inventory(a.apk);a.output.write_text(json.dumps(r,indent=2,sort_keys=True)+'\n');print(json.dumps({'dexProfiles':len(r['profiles']),'profileMethods':sum(len(v['methods']) for v in r['profiles'].values()),'apkSha256':r['apkSha256']}))
if __name__=='__main__':main()

import unittest,struct,zlib,os,json,tempfile,zipfile
from pathlib import Path
from profile_payloads import decode,inventory
from symbolic_dex import Dex
F=Path(os.environ.get('JARVYS_AUDIT_FIXTURES',Path(__file__).resolve().parent))
class ProfileControls(unittest.TestCase):
 @classmethod
 def setUpClass(cls):
  p=F/'probe-dex/classes.dex'
  if not p.exists():p=F/'classes.dex'
  cls.data=p.read_bytes();cls.dex=Dex(cls.data)
 def wrap(self,body,meta=False,count=1):return (b'prm\x00002\x00' if meta else b'pro\x00010\x00')+count.to_bytes(2 if meta else 1,'little')+struct.pack('<II',len(body),len(zlib.compress(body)))+zlib.compress(body)
 def inputs(self,crc=None,method_count=None,type_count=None,flags=None,inline=0,key=b'classes.dex',classids=(),metaids=()):
  n=len(self.dex.methods) if method_count is None else method_count;flags={0:7,1:6} if flags is None else flags;hot=b'';prior=0
  for i,f in sorted(flags.items()):
   if f&1:hot+=struct.pack('<HH',i-prior,inline);prior=i
  bits=0
  for i,f in flags.items():
   if f&2:bits|=1<<i
   if f&4:bits|=1<<(n+i)
  def deltas(ids):
   out=b'';prior=0
   for i in ids:out+=struct.pack('<H',i-prior);prior=i
   return out
  header=struct.pack('<HHIII',len(key),len(classids),len(hot),zlib.crc32(self.data) if crc is None else crc,n)+key
  prof=self.wrap(header+hot+deltas(classids)+bits.to_bytes((2*n+7)//8,'little'))
  body=struct.pack('<HH',0,len(key))+key+struct.pack('<IH',len(self.dex.types) if type_count is None else type_count,len(metaids))+deltas(metaids)
  return prof,self.wrap(body,True)
 def test_complete_binding_and_flags(self):
  p,m=self.inputs();r=decode(p,m,{'classes.dex':self.data})['classes.dex'];self.assertEqual(r['methods'][self.dex.method_symbol(0)],7);self.assertEqual(r['methods'][self.dex.method_symbol(1)],6)
 def test_empty_profile(self):
  p,m=self.inputs(flags={});self.assertEqual(decode(p,m,{'classes.dex':self.data})['classes.dex']['methods'],{})
 def test_class_symbol_binding(self):
  p,m=self.inputs(classids=(0,),metaids=(0,));r=decode(p,m,{'classes.dex':self.data})['classes.dex'];self.assertEqual(r['classes'],[self.dex.types[0]]);self.assertEqual(len(r['metadataClasses']),1)
 def test_crc_mismatch(self):
  p,m=self.inputs(crc=0)
  with self.assertRaises(ValueError):decode(p,m,{'classes.dex':self.data})
 def test_method_count_mismatch(self):
  p,m=self.inputs(method_count=len(self.dex.methods)+1)
  with self.assertRaises(ValueError):decode(p,m,{'classes.dex':self.data})
 def test_type_count_mismatch(self):
  p,m=self.inputs(type_count=len(self.dex.types)+1)
  with self.assertRaises(ValueError):decode(p,m,{'classes.dex':self.data})
 def test_inline_cache_rejected(self):
  p,m=self.inputs(inline=1)
  with self.assertRaises(ValueError):decode(p,m,{'classes.dex':self.data})
 def test_missing_dex_key(self):
  p,m=self.inputs(key=b'classes2.dex')
  with self.assertRaises(ValueError):decode(p,m,{'classes.dex':self.data})
 def test_unknown_version(self):
  p,m=self.inputs();p=p[:4]+b'999\0'+p[8:]
  with self.assertRaises(ValueError):decode(p,m,{'classes.dex':self.data})
 def test_trailing_file_data(self):
  p,m=self.inputs()
  with self.assertRaises(ValueError):decode(p+b'x',m,{'classes.dex':self.data})
 def test_trailing_compressed_data(self):
  p,m=self.inputs();b=p[17:]+b'x';p=p[:13]+struct.pack('<I',len(b))+b
  with self.assertRaises(ValueError):decode(p,m,{'classes.dex':self.data})
 def test_trailing_body_data(self):
  p,m=self.inputs();p=self.wrap(zlib.decompress(p[17:])+b'x')
  with self.assertRaises(ValueError):decode(p,m,{'classes.dex':self.data})
 def test_metadata_key_mismatch(self):
  p,m=self.inputs();b=zlib.decompress(m[18:]).replace(b'classes.dex',b'classee.dex');m=self.wrap(b,True)
  with self.assertRaises(ValueError):decode(p,m,{'classes.dex':self.data})
 def test_metadata_index_mismatch(self):
  p,m=self.inputs();b=bytearray(zlib.decompress(m[18:]));b[0]=1;m=self.wrap(bytes(b),True)
  with self.assertRaises(ValueError):decode(p,m,{'classes.dex':self.data})
 def test_hot_method_out_of_range(self):
  p,m=self.inputs(flags={len(self.dex.methods):1})
  with self.assertRaises(ValueError):decode(p,m,{'classes.dex':self.data})
 def test_duplicate_hot_method(self):
  p,m=self.inputs();b=bytearray(zlib.decompress(p[17:]));old=struct.unpack_from('<I',b,4)[0];struct.pack_into('<I',b,4,old+4);offset=16+len(b'classes.dex');b[offset:offset]=b'\0'*4;p=self.wrap(bytes(b))
  with self.assertRaises(ValueError):decode(p,m,{'classes.dex':self.data})
 def test_bad_dex_checksum(self):
  p,m=self.inputs();d=self.data[:-1]+bytes([self.data[-1]^1])
  with self.assertRaises((ValueError,AssertionError)):decode(p,m,{'classes.dex':d})
 def test_changed_method_flags_are_retained(self):
  p,m=self.inputs();a=decode(p,m,{'classes.dex':self.data});p,m=self.inputs(flags={0:3,1:6});b=decode(p,m,{'classes.dex':self.data});self.assertNotEqual(a['classes.dex']['methods'],b['classes.dex']['methods'])
 def test_apk_binding_and_extra_dex_rejection(self):
  p,m=self.inputs()
  with tempfile.TemporaryDirectory() as folder:
   for extra in (False,True):
    apk=Path(folder)/('bad.apk' if extra else 'good.apk')
    with zipfile.ZipFile(apk,'w') as z:
     z.writestr('classes.dex',self.data);z.writestr('assets/dexopt/baseline.prof',p);z.writestr('assets/dexopt/baseline.profm',m)
     if extra:z.writestr('hidden/extra.dex',self.data)
    if extra:
     with self.assertRaises(ValueError):inventory(apk)
    else:self.assertEqual(len(inventory(apk)['profiles']),1)
if __name__=='__main__':unittest.main()

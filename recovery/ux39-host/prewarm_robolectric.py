#!/usr/bin/env python3
"""Fetch only the pinned Robolectric 4.16 test SDK artifacts from Maven Central."""
import concurrent.futures, hashlib, json, pathlib, subprocess
root=pathlib.Path('/workspace/shared/Jarvys-recovery/UX39-recovered-validation/robolectric-sdks')
versions={24:'7.0.0_r1-robolectric-r1-i7',25:'7.1.0_r7-robolectric-r1-i7',26:'8.0.0_r4-robolectric-r1-i7',28:'9-robolectric-4913185-2-i7',29:'10-robolectric-5803371-i7',32:'12.1-robolectric-8229987-i7',34:'14-robolectric-10818077-i7',35:'15-robolectric-13954326-i7',36:'16-robolectric-13921718-i7'}
root.mkdir(parents=True,exist_ok=True)
def fetch(item):
 sdk,version=item
 name='android-all-instrumented-'+version+'.jar'
 url='https://repo.maven.apache.org/maven2/org/robolectric/android-all-instrumented/'+version+'/'+name
 artifact=root/name; checksum=root/(name+'.sha512')
 for dest,remote in [(artifact,url),(checksum,url+'.sha512')]:
  subprocess.run(['curl','--fail','--location','--silent','--show-error','--output',str(dest),remote],check=True)
 data=artifact.read_bytes(); expected=checksum.read_text().strip().split()[0]
 assert hashlib.sha512(data).hexdigest()==expected, 'Maven published checksum mismatch for SDK '+str(sdk)
 result={'sdk':sdk,'url':url,'name':name,'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest(),'sha512_verified':expected}
 print('VERIFIED_ROBOLECTRIC_SDK',sdk,len(data),flush=True)
 return result
with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool: records=list(pool.map(fetch,versions.items()))
(root/'artifact-manifest.json').write_text(json.dumps(records,indent=2)+'\n')
print('ALL_TEST_SDKS_VERIFIED',len(records),flush=True)

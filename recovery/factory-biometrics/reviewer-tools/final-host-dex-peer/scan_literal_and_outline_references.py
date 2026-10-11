#!/usr/bin/env python3
"""Bounded record reads with a complete descriptor index, reflection/outline audit."""
import argparse,pathlib,json,hashlib,mmap,re,collections,types
D=pathlib.Path(__file__).resolve().parent;O=D.parent
p=argparse.ArgumentParser();p.add_argument('variant');args=p.parse_args();v=args.variant
h=(O/'symbolic_dex.py').read_bytes();assert hashlib.sha256(h).hexdigest()=='d2f331fc834f999aebf199b15dcb4408cc4ea36590d0c266394994f2083520db';sd=types.ModuleType('approved_symbolic_dex');sd.__file__=str(O/'symbolic_dex.py');exec(compile(h,sd.__file__,'exec'),sd.__dict__)
s=json.loads((D/('raw-'+v+'-extraction-summary.json')).read_text());e=json.loads((D/(v+'-bounded-correspondence.json')).read_text());terms=set()
for a,b in e['classMap'].items():
 for x in [a,b]:terms.update([x,x[1:-1],x[1:-1].replace('/','.'),x.split('/')[-1].rstrip(';')])
for row in e['methodMap']:
 for x in [row['before'],row['after']]:terms.add(x.split('(')[0])
pattern=re.compile('|'.join(re.escape(x) for x in sorted(terms,key=len,reverse=True)));raw_pattern=re.compile(b'|'.join(re.escape(x.encode()) for x in sorted(terms,key=len,reverse=True)))
outline='Lcom/jarvys/agent/linux/RootfsTarExtractor$$ExternalSyntheticApiModelOutline0;';report={'variant':v,'method':'Complete class-descriptor index matched to full raw comparison digest indices; all candidate-containing records decoded and their exact hashes checked. Scan real string literals and typed string values without changing them.','sides':{}}
for label in ['before','after']:
 index=json.loads((D/('raw-'+v+'-'+label+'-all-class-sha256.json')).read_text());path=pathlib.Path(s['inputInventories'][label]['path']);literals=[];calls=[];checked=0
 with path.open('rb') as f:
  with mmap.mmap(f.fileno(),0,access=mmap.ACCESS_READ) as raw:
   matches=[m for m in re.finditer(rb'"(L[^"\\;]+;)"\s*:\s*\{',raw) if m[1].decode() in index];names=[m[1].decode() for m in matches];assert len(names)==len(set(names)) and set(names)==set(index),(len(names),len(index))
   for n,m in enumerate(matches):
    owner=names[n];start=m.end()-1;end=matches[n+1].start() if n+1<len(matches) else len(raw);part=raw[start:end]
    if not raw_pattern.search(part) and outline.encode() not in part:continue
    c,_=json.JSONDecoder().raw_decode(part.decode());assert sd.digest(c)==index[owner];checked+=1
    for key,method in c['methods'].items():
     for ordinal,ins in enumerate((method.get('body') or {}).get('instructions',[])):
      if not isinstance(ins,str):continue
      if ins.startswith(('const-string ','const-string/jumbo ')) and pattern.search(ins):literals.append({'class':owner,'method':key,'ordinal':ordinal,'literalInstruction':ins})
      if outline in ins and not ins.startswith(('const-string ','const-string/jumbo ')):calls.append({'class':owner,'method':key,'ordinal':ordinal,'instruction':ins})
    def walk(obj,loc):
     if isinstance(obj,dict):
      if obj.get('type')==23 and isinstance(obj.get('value'),str) and pattern.search(obj['value']):literals.append({'class':owner,'metadataPath':loc,'typedString':obj['value']})
      for k,x in obj.items():walk(x,loc+[k])
     elif isinstance(obj,list):
      for k,x in enumerate(obj):walk(x,loc+[k])
    walk(c,[])
 report['sides'][label]={'completeClassIndexCount':len(names),'exactRecordsChecked':checked,'movedIdentityLiteralHits':literals,'allApiOutlineExecutableReferences':calls}
assert all(not x['movedIdentityLiteralHits'] for x in report['sides'].values()),'Reflection-visible moved identity literal found; retain and review explicitly'
report['noMovedIdentityStringLiterals']=True
(D/(v+'-literal-outline-reference-audit.json')).write_text(json.dumps(report,indent=2)+'\n');print(json.dumps({'variant':v,'noMovedIdentityStringLiterals':True,'counts':{k:{q:z for q,z in x.items() if q in ['completeClassIndexCount','exactRecordsChecked']} for k,x in report['sides'].items()}},indent=2))

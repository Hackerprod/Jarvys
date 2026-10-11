#!/usr/bin/env python3
"""Run the unchanged full comparator once; retain all actual deltas and bindings.
Subset selection is evidence extraction only, never a comparison exemption.
"""
import argparse,hashlib,json,pathlib,sys,time,types
D=pathlib.Path(__file__).resolve().parent; O=D.parent
p=argparse.ArgumentParser();p.add_argument('variant');p.add_argument('before',type=pathlib.Path);p.add_argument('after',type=pathlib.Path);p.add_argument('--no-ordinal-discovery',action='store_true');args=p.parse_args()
h=O/'symbolic_dex.py';raw=h.read_bytes();assert hashlib.sha256(raw).hexdigest()=='d2f331fc834f999aebf199b15dcb4408cc4ea36590d0c266394994f2083520db'
sd=types.ModuleType('approved_symbolic_dex');sd.__file__=str(h);exec(compile(raw,str(h),'exec'),sd.__dict__)
def log(x):print(json.dumps(x),flush=True)
def load(path):
 raw=path.read_bytes();digest=hashlib.sha256(raw).hexdigest();obj=json.loads(raw);del raw;return obj,digest
def write(name,value):
 path=D/(args.variant+'-'+name+'.json')
 with path.open('w') as f:json.dump(value,f,indent=2,sort_keys=True);f.write('\n')
 return {'path':str(path),'sha256':hashlib.sha256(path.read_bytes()).hexdigest()}
t=time.time();a,ash=load(args.before);log({'loaded':'before','classes':len(a['classes']),'seconds':time.time()-t})
b,bsh=load(args.after);log({'loaded':'after','classes':len(b['classes']),'seconds':time.time()-t})
assert a['completeBinaryMetadata'] and b['completeBinaryMetadata']
log({'phase':'unchanged_generic_compare','seconds':time.time()-t})
generic=sd.compare(a,b,not args.no_ordinal_discovery);genericfile=write('generic-semantic',generic);log({'genericSummary':generic['summary'],'seconds':time.time()-t})
ac,bc=a['classes'],b['classes'];rawchanged={k for k in ac.keys()&bc.keys() if ac[k]!=bc[k]};rawadded=set(bc)-set(ac);rawremoved=set(ac)-set(bc);rawdelta=rawchanged|rawadded|rawremoved
selected={};indices={};refs={}
for label,obj in [('before',a),('after',b)]:
 select={};index={};incoming=[];mention_owners=[]
 for owner,c in obj['classes'].items():
  content=sd.stable(c);index[owner]=hashlib.sha256(content.encode()).hexdigest()
  # This exact substring covers every possible FactoryRuntime class/method
  # substitution used in the previously reviewed source-derived proposal.
  mentions_runtime='Lcom/jarvys/factory/runtime/FactoryRuntime' in content
  if owner in rawdelta or owner.startswith('Lcom/jarvys/factory/') or mentions_runtime:
   select[owner]=c
  if mentions_runtime:mention_owners.append(owner)
  for key,m in c['methods'].items():
   for ordinal,ins in enumerate((m.get('body') or {}).get('instructions',[])):
    if isinstance(ins,str) and 'Lcom/jarvys/factory/runtime/FactoryRuntime' in ins and not ins.startswith(('const-string ','const-string/jumbo ')):
     incoming.append({'owner':owner,'method':key,'ordinal':ordinal,'instruction':ins})
 selected[label]=write(label+'-evidence-classes',dict(format=obj['format'],completeBinaryMetadata=True,source=obj.get('source'),sourceSha256=obj.get('sourceSha256'),dexFiles=obj['dexFiles'],classes=select))
 indices[label]=write(label+'-all-class-sha256',index)
 refs[label]=write(label+'-incoming-executable-references',incoming)
 log({'evidenceSide':label,'selectedClasses':len(select),'runtimeMentionOwners':len(mention_owners),'incomingReferences':len(incoming),'seconds':time.time()-t})
 # Proof that the subset has not removed any raw delta or symbolic substitution
 # target/source/reference; unselected exact classes cannot be affected by map.
 assert rawdelta&obj['classes'].keys()<=select.keys()
 assert set(mention_owners)<=select.keys()
summary={'variant':args.variant,'ordinalDiscoveryEnabled':not args.no_ordinal_discovery,'scope':'Complete unchanged generic comparator plus evidence extraction; no acceptance or package exemption','helperSha256':hashlib.sha256(raw).hexdigest(),'inputInventories':{'before':{'path':str(args.before),'sha256':ash},'after':{'path':str(args.after),'sha256':bsh}},'sourceSha256':{'before':a['sourceSha256'],'after':b['sourceSha256']},'completeClassCounts':{'before':len(ac),'after':len(bc)},'completeBinaryMetadata':True,'rawChangedClasses':sorted(rawchanged),'rawAddedClasses':sorted(rawadded),'rawRemovedClasses':sorted(rawremoved),'genericSummary':generic['summary'],'genericReport':genericfile,'evidenceSubsets':selected,'allClassDigests':indices,'incomingReferences':refs,'selectionRule':'All raw changed/added/removed identities; all contract/runtime classes; every whole class whose complete canonical JSON mentions the exact FactoryRuntime descriptor prefix. Full class maps/digests retained for injectivity checks. Selection changes no comparator behavior.'}
write('extraction-summary',summary);log({'done':True,'seconds':time.time()-t,'variant':args.variant})

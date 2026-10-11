#!/usr/bin/env python3
"""Rebind full raw comparisons; prove finite maps, all incoming calls, delta coverage."""
import argparse,pathlib,json,hashlib,types,collections,re,zipfile
D=pathlib.Path(__file__).resolve().parent;O=D.parent
p=argparse.ArgumentParser();p.add_argument('variant');args=p.parse_args();v=args.variant
h=(O/'symbolic_dex.py').read_bytes();assert hashlib.sha256(h).hexdigest()=='d2f331fc834f999aebf199b15dcb4408cc4ea36590d0c266394994f2083520db'
sd=types.ModuleType('approved_symbolic_dex');sd.__file__=str(O/'symbolic_dex.py');exec(compile(h,sd.__file__,'exec'),sd.__dict__)
def load(name):return json.loads((D/name).read_text())
def sha(path):
 d=hashlib.sha256()
 with path.open('rb') as f:
  for b in iter(lambda:f.read(1<<20),b''):d.update(b)
 return d.hexdigest()
s=load('raw-'+v+'-extraction-summary.json');a=load('raw-'+v+'-before-evidence-classes.json');b=load('raw-'+v+'-after-evidence-classes.json');ai=load('raw-'+v+'-before-all-class-sha256.json');bi=load('raw-'+v+'-after-all-class-sha256.json');e=load(v+'-bounded-correspondence.json')
assert s['ordinalDiscoveryEnabled'] is False
cm=e['classMap'];mm={(m['owner'],m['before']):m['after'] for m in e['methodMap']}
assert len({cm.get(k,k) for k in ai})==len(ai)
assert len(ai)==s['completeClassCounts']['before'] and len(bi)==s['completeClassCounts']['after']
assert set(ai)==set(a['classes'])|{k for k in ai if k not in a['classes']}
rawdelta=set(s['rawChangedClasses'])|set(s['rawAddedClasses'])|set(s['rawRemovedClasses'])
assert set(s['rawChangedClasses'])=={k for k in ai.keys()&bi.keys() if ai[k]!=bi[k]}
assert set(s['rawAddedClasses'])==bi.keys()-ai.keys() and set(s['rawRemovedClasses'])==ai.keys()-bi.keys()
for label,obj,index in [('before',a,ai),('after',b,bi)]:
 assert all(index[k]==sd.digest(c) for k,c in obj['classes'].items())
 assert rawdelta&index.keys()<=obj['classes'].keys()
 assert all(ai[k]==bi[k] for k in ai.keys()-a['classes'].keys())
 assert obj['sourceSha256']==s['sourceSha256'][label]
 assert sha(pathlib.Path(obj['source']))==obj['sourceSha256']
 with zipfile.ZipFile(obj['source']) as z:
  actual={n:hashlib.sha256(z.read(n)).hexdigest() for n in z.namelist() if n.lower().endswith('.dex')}
 assert actual=={k:x['sha256'] for k,x in obj['dexFiles'].items()}
original_refs={label:load('raw-'+v+'-'+label+'-incoming-executable-references.json') for label in ['before','after']}
def index_refs(rows,transform):
 out=collections.defaultdict(collections.Counter);methods=collections.defaultdict(collections.Counter)
 for row in rows:
  owner,key,ins=row['owner'],row['method'],row['instruction']
  if transform:owner,key,ins=cm.get(owner,owner),sd.translate(mm.get((owner,key),key),cm,{}),sd.translate(ins,cm,mm)
  item=(owner,key,ins)
  for name in set(re.findall(r'L[^;\s,{}]+;',ins)):out[name][item]+=1
  # Full symbolic method signature retains overload and target class.
  for name in re.findall(r'L[^;\s,{}]+;\.[^: ()]+:\([^ ]+',ins):methods[name][item]+=1
 return out,methods
pre,pre_m=index_refs(original_refs['before'],True);post,post_m=index_refs(original_refs['after'],False);classrows=[];methodrows=[]
for pr in e['exactCorrespondenceProofs']:
 if pr['kind']=='class':
  target=pr['after'];x,y=pre[target],post[target];row={'before':pr['before'],'after':target,'sameIncomingExecutableReferences':x==y,'beforeCount':sum(x.values()),'afterCount':sum(y.values()),'missing':[list(k)+[n] for k,n in (x-y).items()],'added':[list(k)+[n] for k,n in (y-x).items()]};classrows.append(row)
 else:
  name,proto=pr['after'].split('(',1);target=cm.get(pr['owner'],pr['owner'])+'.'+name+':('+proto;x,y=pre_m[target],post_m[target];row={'owner':pr['owner'],'before':pr['before'],'after':pr['after'],'sameIncomingExecutableReferences':x==y,'beforeCount':sum(x.values()),'afterCount':sum(y.values()),'missing':[list(k)+[n] for k,n in (x-y).items()],'added':[list(k)+[n] for k,n in (y-x).items()]};methodrows.append(row)
assert all(x['sameIncomingExecutableReferences'] for x in classrows+methodrows),[x for x in classrows+methodrows if not x['sameIncomingExecutableReferences']]
# Preserve a complete per-old-method coverage ledger, including methods that move.
mapped=sd.transformed_classes(a['classes'],cm,mm);changed={c['class']:c for c in e['changedClasses']};ledger=[]
for old in s['rawChangedClasses']:
 target=cm.get(old,old);row={'beforeClass':old,'afterClass':target,'beforeClassSha256':ai[old],'candidateClassSha256':bi[target],'completeClassExactAfterMap':mapped[target]==b['classes'][target],'methods':[]}
 for key,m in a['classes'][old]['methods'].items():
  final=sd.translate(mm.get((old,key),key),cm,{});after=b['classes'][target]['methods'].get(final);expected=sd.translate(m,cm,mm);exact=expected==after
  row['methods'].append({'before':key,'after':final,'exactAfterMap':exact,'beforeSha256':sd.digest(m),'afterSha256':sd.digest(after) if after is not None else None})
  if not exact:assert target in changed and final in {x['method'] for x in changed[target]['changedMethods']},(old,key,final)
 ledger.append(row)
proof={'variant':v,'status':'Passed complete raw class coverage, exact finite correspondence, global injectivity and all incoming executable-reference equality','completeCounts':s['completeClassCounts'],'sourceSha256':s['sourceSha256'],'allPackagedDexPayloadHashesVerified':True,'classMapGlobalInjective':True,'unselectedClassesExactAndUnaffectedBySubstitution':True,'rawSummary':s['genericSummary'],'afterCorrespondenceSummary':e['summary'],'exactClassMoves':len(classrows),'exactMethodMoves':len(methodrows),'classIncomingReferenceProofs':classrows,'methodIncomingReferenceProofs':methodrows,'retainedNonExactAlignments':e['nonExactIdentityAlignments'],'rawChangedClassCoverage':ledger,'completeMethodCoverageInRawChangedClasses':sum(len(r['methods']) for r in ledger),'trueAddedClasses':list(e['addedClasses']),'limits':['A named symbol substitution is bounded static application-path correspondence, not universal reflection/stacktrace/instrumentation equivalence.','Nonexact FactoryRuntime switch alignment remains visible, and every residual method/class/metadata delta still requires source classification.','No Android execution, authentication/OEM behavior, signing or release approval is established by this script.']}
(D/(v+'-complete-coverage.json')).write_text(json.dumps(proof,indent=2)+'\n');print(json.dumps({k:proof[k] for k in ['variant','status','completeCounts','afterCorrespondenceSummary','exactClassMoves','exactMethodMoves','completeMethodCoverageInRawChangedClasses']},indent=2))

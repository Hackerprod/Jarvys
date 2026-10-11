#!/usr/bin/env python3
"""Bind only exact complete content to template review; preserve all nonmatches."""
import argparse,pathlib,json,hashlib,types
D=pathlib.Path(__file__).resolve().parent;O=D.parent
p=argparse.ArgumentParser();p.add_argument('variant');args=p.parse_args();v=args.variant
h=(O/'symbolic_dex.py').read_bytes();assert hashlib.sha256(h).hexdigest()=='d2f331fc834f999aebf199b15dcb4408cc4ea36590d0c266394994f2083520db';sd=types.ModuleType('approved_symbolic_dex');sd.__file__=str(O/'symbolic_dex.py');exec(compile(h,sd.__file__,'exec'),sd.__dict__)
t=json.loads((O/'template-delta-peer/bounded-correspondence.json').read_text());e=json.loads((D/(v+'-bounded-correspondence.json')).read_text())
cm={}
for old in t['addedClasses']:
 if old in e['addedClasses']:continue
 candidates=[k for k in e['addedClasses'] if old.rsplit('Lambda',1)[0]==k.rsplit('Lambda',1)[0]]
 for target in candidates:
  if sd.translate(t['addedClasses'][old],{old:target},{})==e['addedClasses'][target]:cm[old]=target;break
# The executor adapter has one explicit direct-inlining difference; its source identity
# is proposed by captured Handler field and Executor interface, never auto-approved.
for n,target in [(0,12),(1,13)]:
 old=f'Lcom/jarvys/factory/runtime/BiometricAuthentication$Api30Driver$$ExternalSyntheticLambda{n};';new=f'Lcom/jarvys/factory/runtime/BiometricAuthentication$Api30Driver$$ExternalSyntheticLambda{target};'
 if old not in e['addedClasses'] and new in e['addedClasses']:cm.setdefault(old,new)
assert len(set(cm.values()))==len(cm)
added=[];remaining=[];metadata=[];missing=[]
for old,c in t['addedClasses'].items():
 target=cm.get(old,old);assert target in e['addedClasses'];x=sd.translate(c,cm,{});y=e['addedClasses'][target]
 row={'templateClass':old,'hostClass':target,'completeClassExactAfterExplicitSymbols':x==y,'templateClassSha256':sd.digest(c),'hostClassSha256':sd.digest(y),'methods':[]}
 for key in set(x)|set(y):
  if key!='methods' and x.get(key)!=y.get(key):metadata.append({'class':target,'key':key,'template':x.get(key),'host':y.get(key)})
 for key,m in y['methods'].items():
  before=x['methods'].get(key);exact=before==m;mr={'method':key,'exactTemplateContent':exact,'templateSha256':sd.digest(before) if before else None,'hostSha256':sd.digest(m)};row['methods'].append(mr)
  if not exact:remaining.append({'class':target,'method':key,'template':before,'host':m})
 for key in x['methods'].keys()-y['methods'].keys():missing.append({'class':target,'method':key,'template':x['methods'][key]})
 added.append(row)
true=[]
for c in e['changedClasses']:
 tc=next((x for x in t['changedClasses'] if x['class']==c['class']),None)
 if not tc:continue
 for kind in ['changedMethods','addedMethods']:
  for m in c[kind]:
   tm=next((x for x in tc[kind] if x['method']==m['method']),None)
   if not tm:continue
   exact=all(sd.translate(tm[k],cm,{})==m[k] for k in ['before','after'] if k in m)
   true.append({'class':c['class'],'method':m['method'],'kind':kind,'completeBeforeAfterExact':exact,'hostBeforeSha256':sd.digest(m['before']) if 'before'in m else None,'hostAfterSha256':sd.digest(m['after'])})
report={'variant':v,'scope':'Exact method/class bindings only; every unmatched body retained and requires separate actual-host review. Cross-compilation changes are never an exemption.','explicitTemplateToHostClassSymbols':cm,'addedClassBindings':added,'unmatchedAddedClassMethods':remaining,'templateOnlySyntheticForwarders':missing,'addedMetadataDifferences':metadata,'changedMethodBindings':true,'unmatchedTrueChanges':[x for x in true if not x['completeBeforeAfterExact']]}
(D/(v+'-template-content-bindings.json')).write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps({'variant':v,'exactAddedClasses':sum(x['completeClassExactAfterExplicitSymbols'] for x in added),'exactAddedMethods':sum(m['exactTemplateContent'] for x in added for m in x['methods']),'unmatchedAddedMethods':[(x['class'],x['method']) for x in remaining],'templateOnlyForwarders':[(x['class'],x['method']) for x in missing],'metadataDifferences':len(metadata),'unmatchedTrueChanges':[(x['class'],x['method']) for x in report['unmatchedTrueChanges']]},indent=2))

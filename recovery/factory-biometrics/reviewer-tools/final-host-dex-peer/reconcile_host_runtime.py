#!/usr/bin/env python3
"""Source-informed proposals with exact complete host proof, no package exemption."""
import argparse,json,pathlib,sys,re,hashlib,types,collections
D=pathlib.Path(__file__).resolve().parent;O=D.parent
p=argparse.ArgumentParser();p.add_argument('variant');p.add_argument('before',type=pathlib.Path);p.add_argument('after',type=pathlib.Path);p.add_argument('--early',action='store_true');args=p.parse_args()
h=(O/'symbolic_dex.py').read_bytes();assert hashlib.sha256(h).hexdigest()=='d2f331fc834f999aebf199b15dcb4408cc4ea36590d0c266394994f2083520db'
sd=types.ModuleType('approved_symbolic_dex');sd.__file__=str(O/'symbolic_dex.py');exec(compile(h,sd.__file__,'exec'),sd.__dict__)
x=json.loads(args.before.read_text());y=json.loads(args.after.read_text());a=x if args.early else x['classes'];b=y if args.early else y['classes'];R='Lcom/jarvys/factory/runtime/FactoryRuntime;'
cm={};mm={};proof=[]
for k,m in a[R]['methods'].items():
 n=k
 match=re.match(r'^(lambda\$[^$]+\$)(\d+)(.*)$',k)
 if match and int(match[2])>=17:n=match[1]+str(int(match[2])+1)+match[3]
 match=re.match(r'^(access\$)(\d+)(.*)$',k)
 if match and int(match[2])>=1300:n=match[1]+str(int(match[2])+100)+match[3]
 if n!=k:
  assert m['access']&0x1000 and n in b[R]['methods'],(k,n)
  mm[R,k]=n
# Identity-only correspondence, deliberately retained as a genuine nonexact delta.
switch='Lcom/jarvys/factory/runtime/FactoryRuntime$13;';cm[switch]='Lcom/jarvys/factory/runtime/FactoryRuntime$14;'
while True:
 changed=False
 for owner,c in a.items():
  if owner in cm or not re.fullmatch(r'Lcom/jarvys/factory/runtime/FactoryRuntime(?:\$\$ExternalSyntheticLambda|\$)\d+;',owner):continue
  mapped=sd.transformed_classes({owner:c},{},mm)[owner];sig=sd.digest(sd.translate(mapped,{owner:'@SELF;'},{}));family=re.sub(r'\d+;$','',owner)
  candidates=[target for target,d in b.items() if target not in cm.values() and re.sub(r'\d+;$','',target)==family and sd.digest(sd.translate(d,{target:'@SELF;'},{}))==sig]
  if len(candidates)==1:cm[owner]=candidates[0];changed=True
 if not changed:break
cm={k:v for k,v in cm.items() if k!=v}
mapped=sd.transformed_classes(a,cm,mm);assert len(mapped)==len(a)
for owner,c in a.items():assert len(mapped[cm.get(owner,owner)]['methods'])==len(c['methods'])
for (owner,key),target in mm.items():
 final=sd.translate(target,cm,{});exact=mapped[cm.get(owner,owner)]['methods'][final]==b[cm.get(owner,owner)]['methods'][final]
 proof.append({'kind':'method','owner':owner,'before':key,'after':target,'exactFinalBodyAccessPrototypeCatches':exact,'afterSha256':sd.digest(b[cm.get(owner,owner)]['methods'][final])})
for owner,target in cm.items():
 exact=mapped[target]==b[target];proof.append({'kind':'class','before':owner,'after':target,'exactFinalClassIncludingCapturesAndMetadata':exact,'afterSha256':sd.digest(b[target]),'note':None if exact else 'Retained genuine enum switch-map delta; not exempted'})
assert all(p.get('exactFinalBodyAccessPrototypeCatches',True) for p in proof)
assert all(p.get('exactFinalClassIncludingCapturesAndMetadata',True) for p in proof if p.get('before')!=switch)
review=sd.compare({'format':sd.FORMAT,'classes':mapped,'completeBinaryMetadata':True},{'format':sd.FORMAT,'classes':b,'completeBinaryMetadata':True},False)
review.update({'scope':'EARLY runtime-subset evidence only; full host generic comparison and reference coverage pending' if args.early else 'Complete generic delta and all possible FactoryRuntime substitution-reference subset; full comparison companion required','variant':args.variant,'classMap':cm,'methodMap':[{'owner':o,'before':k,'after':v} for (o,k),v in mm.items()],'correspondenceChecks':proof,'exactCorrespondenceProofs':[p for p in proof if p.get('exactFinalClassIncludingCapturesAndMetadata',p.get('exactFinalBodyAccessPrototypeCatches'))],'nonExactIdentityAlignments':[p for p in proof if not p.get('exactFinalClassIncludingCapturesAndMetadata',p.get('exactFinalBodyAccessPrototypeCatches'))],'inputSha256':{str(q):hashlib.sha256(q.read_bytes()).hexdigest() for q in [args.before,args.after]},'helperSha256':hashlib.sha256(h).hexdigest()})
name=('early-' if args.early else '')+args.variant+'-bounded-correspondence.json';(D/name).write_text(json.dumps(review,indent=2)+'\n');print(json.dumps({'output':name,'summary':review['summary'],'proofCounts':dict(collections.Counter(p['kind'] for p in review['exactCorrespondenceProofs'])),'nonExact':review['nonExactIdentityAlignments']},indent=2))
print('\n'.join(c['class']+' '+str([m['method'] for m in c['changedMethods']]) for c in review['changedClasses']))

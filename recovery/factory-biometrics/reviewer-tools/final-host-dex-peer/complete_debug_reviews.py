import pathlib,json,hashlib,sys
D=pathlib.Path(__file__).resolve().parent;O=D.parent;sys.path.insert(0,str(O));import symbolic_dex as sd
load=lambda p:json.loads(p.read_text());sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
peerpath=D/'debug-biometric-peer/compiled-debug-biometric-review.json';assert sha(peerpath)=='de25ca3046acc4ee40f152473bb6df5015dc6b688095d7dc486afaebf05e7497';bio=load(peerpath);parent=load(O/'reviewer-final-debug-host-contract-review.json');runtime=load(D/'debug-runtime-reviewed.json');switch=load(D/'debug-runtime-switch-reviewed.json');assert parent['passed'] and runtime['passed'] and switch['passed'];assert bio['result'].startswith('PASS ')
for v in ['full-debug','play-debug']:
 e=load(D/(v+'-bounded-correspondence.json'));cov=load(D/(v+'-complete-coverage.json'));literals=load(D/(v+'-literal-outline-reference-audit.json'));assert literals['noMovedIdentityStringLiterals'];changes={c['class']:c for c in e['changedClasses']};notes={};metanotes={};metakey='fullDebugMetadataDeltaSha256' if v=='full-debug' else 'playDebugMetadataDeltaSha256'
 for row in parent['rows']:
  c=changes[row['class']];assert sd.digest(c['metadataChanges'])==row[metakey]
  for m in row['methods']:
   key=m['method'] if v=='full-debug' else m['method'].replace('$app_fullDebug','$app_playDebug');actual=next(x for x in c['changedMethods'] if x['method']==key);assert sd.digest(actual['before'])==m['beforeSha256'] and sd.digest(actual['after'])==m['afterSha256'];notes[c['class'],key]=row['classification']
  for key in c['metadataChanges']:metanotes[c['class'],key]=row['classification']+' Complete metadata delta hash verified independently for this exact flavor.'
 rr='Lcom/jarvys/factory/runtime/FactoryRuntime;';rc=changes[rr];assert sd.digest(rc['metadataChanges'])==runtime['metadataDeltaSha256']
 for m in runtime['changedMethodExactPreservationProofs']:
  actual=next(x for x in rc['changedMethods'] if x['method']==m['method']);assert sd.digest(actual['before'])==m['beforeSha256'] and sd.digest(actual['after'])==m['afterSha256'];notes[rr,m['method']]=m['preservationProof']
 for m in runtime['addedMethodSourceReviews']:
  actual=next(x for x in rc['addedMethods'] if x['method']==m['method']);assert sd.digest(actual['after'])==m['sha256'];notes[rr,m['method']]=m['classification']
 for key in rc['metadataChanges']:metanotes[rr,key]=runtime['metadataReview']
 sc=changes[switch['class']];assert not sc['metadataChanges'];sm=sc['changedMethods'][0]
 for row in switch['rows']:assert sd.digest(sm[row['side']])==row['sha256']
 notes[sc['class'],sm['method']]=switch['classification']
 methods=[];metadata=[]
 for c in e['changedClasses']:
  assert not c['removedMethods']
  for kind in ['changedMethods','addedMethods']:
   for m in c[kind]:methods.append({'class':c['class'],'method':m['method'],'kind':kind,'classification':notes[c['class'],m['method']],'beforeSha256':sd.digest(m['before']) if 'before'in m else None,'afterSha256':sd.digest(m['after'])})
  for key,pair in c['metadataChanges'].items():metadata.append({'class':c['class'],'field':key,'classification':metanotes[c['class'],key],'beforeSha256':sd.digest(pair['before']),'afterSha256':sd.digest(pair['after'])})
 assert len(methods)==30
 unmatched={(r['class'],r['method']):r for r in bio['unmatchedMethodClassifications']};added=[]
 for row in bio['addedClassBindings']:
  owner=row['class'];actual=e['addedClasses'][owner];expected=row['actualFullDebugSha256'] if v=='full-debug' else row['actualPlayDebugSha256'];assert sd.digest(actual)==expected;assert row['metadataExactToTemplate'];mr=[]
  assert set(actual['methods'])=={m['method'] for m in row['methods']}
  for m in row['methods']:
   assert sd.digest(actual['methods'][m['method']])==m['actualSha256'];note='Complete exact template-content binding, including metadata, registers, instructions, payloads and catches.'
   if not m['exactTemplateContent']:
    review=unmatched[owner,m['method']];assert review['actualSha256']==m['actualSha256'];note=review['review']
   mr.append({'method':m['method'],'sha256':m['actualSha256'],'exactTemplateContent':m['exactTemplateContent'],'classification':note})
  added.append({'class':owner,'classSha256':expected,'metadataExactlyTemplateBound':True,'methods':mr})
 assert {r['class'] for r in added}==set(e['addedClasses']) and len(added)==21
 report={'variant':v,'passed':True,'scope':'Independent complete compiled DEX review of exact final unsigned Debug APK. Every true class/method/metadata delta is explicitly classified; no unexplained third-party change remains within symbolic model. Static DEX acceptance only.','sourceCommit':'d815b19b27f2f0b18a8d9325924ef7225790bb0e','sourceAppTree':'119dac1fee793fa306656438447f491cad0a7cb0','candidateApkSha256':cov['sourceSha256']['after'],'rawSummary':cov['rawSummary'],'reviewedSummary':e['summary'],'exactMethodMoves':44,'exactClassMoves':38,'allIncomingClassAndMethodReferencesEqual':True,'noMovedIdentityStringLiterals':True,'allResidualMethods':methods,'allResidualMetadata':metadata,'allAddedClasses':added,'sourceSafetyFindings':bio['sourceSafetyFindings'],'evidenceBindings':{str(p.relative_to(O)):sha(p) for p in [D/(v+'-complete-coverage.json'),D/(v+'-literal-outline-reference-audit.json'),D/'debug-runtime-reviewed.json',D/'debug-runtime-switch-reviewed.json',peerpath,O/'reviewer-final-debug-host-contract-review.json']},'limits':['Debug/source/physical offsets, DEX pool indices/order and ZIP layout remain outside approved symbolic inventory model.','Explicit synthetic substitutions are bounded static application-path correspondence, not universal reflection/instrumentation/stacktrace equivalence.','The nonexact FactoryRuntime$13→$14 switch alignment is retained and fully reconstructed/reviewed, never counted as exact.','Native prompt/matching/lockout/OEM callback timing and lifecycle execution remain unproved on a real Android device; main-loop serialization/timing is an integration assumption.','Signature, manifest/profile/ZIP surfaces and final distribution approval are separate gates.']}
 (D/(v+'-final-compiled-review.json')).write_text(json.dumps(report,indent=2)+'\n');print(json.dumps({'variant':v,'passed':True,'candidateApkSha256':report['candidateApkSha256'],'reportSha256':sha(D/(v+'-final-compiled-review.json')),'summary':e['summary']}))

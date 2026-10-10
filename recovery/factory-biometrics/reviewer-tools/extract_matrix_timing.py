#!/usr/bin/env python3
"""Factual v80 matrix timing from fresh final JUnit. No test execution/factoring."""
from pathlib import Path
from decimal import Decimal
import collections,datetime,hashlib,json,xml.etree.ElementTree as ET
O=Path(__file__).resolve().parent;V=O.parent
p=Path((V/'aggregate-run.txt').read_text().strip());p=p/'run.json' if p.is_dir() else p;r=json.loads(p.read_text());assert r['exit_code']==0 and r['finish_epoch']>=r['start_epoch']
names={'all1048576SelectionsHaveOnlyTheExactSelectivePermissionAndQueryProfiles','all1048576SelectionsEncodeOnlyTheExactSelectivePermissionAndQueryProfiles','all1048576CapabilityPlansSelectOnlyBiometricPermissionAndDeduplicateHostVisibility','allCapabilitySelectionsMatchCatalogAndTypedValidators'}
rows=[];matrix=[];bytask=collections.defaultdict(lambda:{'testcaseSeconds':Decimal(0),'matrixSeconds':Decimal(0),'cases':0,'matrixCases':0});sources={}
for xml in sorted((V/'aggregate-junit').glob('*/TEST-*.xml')):
 assert r['start_epoch']<=xml.stat().st_mtime<=r['finish_epoch']+1
 root=ET.parse(xml).getroot();assert all(int(root.get(k,0))==0 for k in ('failures','errors','skipped'));task=xml.parent.name;sources[str(xml.relative_to(V))]=hashlib.sha256(xml.read_bytes()).hexdigest()
 for case in root.findall('testcase'):
  seconds=Decimal(case.get('time','0'));assert seconds>=0
  name=case.get('name');base=name.split('[',1)[0];is_matrix=base in names;row={'task':task,'suite':root.get('name'),'case':name,'seconds':str(seconds),'matrix':is_matrix};rows.append(row);bytask[task]['testcaseSeconds']+=seconds;bytask[task]['cases']+=1
  if is_matrix:matrix.append(row);bytask[task]['matrixSeconds']+=seconds;bytask[task]['matrixCases']+=1
assert set(x['case'].split('[',1)[0] for x in matrix)==names
for values in bytask.values():
 values['matrixFractionOfSummedTestcaseTime']=str(values['matrixSeconds']/values['testcaseSeconds']) if values['testcaseSeconds'] else None
 values['testcaseSeconds']=str(values['testcaseSeconds']);values['matrixSeconds']=str(values['matrixSeconds'])
result={'scope':'Fresh v80 final JUnit-reported test-case timings, unchanged exhaustive matrices and 42-method contract. No factoring or historical speedup comparison.','aggregateRun':str(p),'aggregateRunSha256':hashlib.sha256(p.read_bytes()).hexdigest(),'aggregateWallSeconds':str(Decimal(str(r['finish_epoch']))-Decimal(str(r['start_epoch']))),'matrixCases':matrix,'matrixSummedSeconds':str(sum((Decimal(x['seconds']) for x in matrix),Decimal(0))),'summedTestcaseSeconds':str(sum((Decimal(x['seconds']) for x in rows),Decimal(0))),'perTask':dict(bytask),'slowestCases':sorted(rows,key=lambda x:Decimal(x['seconds']),reverse=True)[:50],'junitSha256':sources,'limitations':['JUnit case duration is reported elapsed time, not CPU time, allocation count, or benchmark repeatability.','Summed case durations and aggregate wall duration have different scopes; setup, teardown, workers, compilation and scheduling can differ.','Prior v79 ephemeral timing XML was lost in reset; no reconstructed v79 timing is presented as historical timing.','Any future factoring requires a separate independent compositional proof; this report changes no coverage.'],'reviewedUtc':datetime.datetime.now(datetime.timezone.utc).isoformat()}
(O/'v80-matrix-timing.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps({'matrixCases':len(matrix),'matrixSummedSeconds':result['matrixSummedSeconds'],'aggregateWallSeconds':result['aggregateWallSeconds']}))

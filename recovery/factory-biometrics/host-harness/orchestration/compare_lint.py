#!/usr/bin/env python3
from pathlib import Path
import collections,xml.etree.ElementTree as ET,json
V=Path(__file__).resolve().parent;B=V.parent/'FACTORY-SENSORS-v79-validation'
def path(s):
 assert '/app/' in s or s.startswith('app/')
 return 'app/'+s.split('/app/',1)[1] if '/app/' in s else s
def issues(f):
 return collections.Counter((x.get('id'),x.get('severity'),x.get('message'),tuple(sorted(path(l.get('file','')) for l in x.findall('location')))) for x in ET.parse(f).getroot().findall('issue'))
rows=[]
for n,c in [('app-lint-results-fullDebug.xml',313),('app-lint-results-playDebug.xml',300),('apk-runtime-lint-results-release.xml',3),('factory-runtime-core-lint-results-release.xml',2)]:
 old,new=issues(B/n),issues(V/n);assert old==new,(n,old-new,new-old);assert sum(new.values())==c;rows.append({'report':n,'identical':True,'count':c})
(V/'lint-comparison.json').write_text(json.dumps(rows,indent=2)+'\n');print('EXACT_LINT_MULTISET_PASS',rows)

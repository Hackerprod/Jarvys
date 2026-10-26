#!/usr/bin/env python3
"""Restore a reviewed exact source snapshot locally; no network, build or commit."""
from pathlib import Path
import hashlib,json,subprocess
root=Path(__file__).resolve().parents[3]
def git(*args): return subprocess.check_output(['git',*args],cwd=root,text=True).strip()
def require(condition,message):
    if not condition: raise SystemExit(message)
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
m=json.loads((root/'docs/continue/backup/manifest.json').read_text())
require(git('status','--porcelain')=='','Refusing dirty checkout. Use a fresh clean clone/worktree.')
patch=root/'docs/continue/backup/restore-s6-fixture.patch'; readme=root/'README.md'
require(sha(patch)==m['patch_sha256'],'Patch integrity mismatch.')
require(sha(readme)==m['readme_backup_sha256'],'README integrity mismatch.')
seen=set()
for fixture in m['fixtures']:
    relative=fixture['path']; path=root/relative
    require(relative.startswith('app/app/src/test/') and '..' not in Path(relative).parts and relative not in seen,'Invalid or duplicate fixture path.')
    seen.add(relative)
    require(sha(path)==fixture['remote_sha256'],'Fixture baseline mismatch: '+relative)
footer=m['readme_footer'].encode(); original=readme.read_bytes()
require(original.endswith(footer),'Backup footer mismatch.')
subprocess.run(['git','apply','--check','--unidiff-zero',str(patch)],cwd=root,check=True)
subprocess.run(['git','apply','--unidiff-zero',str(patch)],cwd=root,check=True)
for fixture in m['fixtures']:
    require(sha(root/fixture['path'])==fixture['restored_sha256'],'Restored fixture mismatch; no commit created: '+fixture['path'])
readme.write_bytes(original[:-len(footer)])
require(sha(readme)==m['readme_source_sha256'],'Restored README mismatch.')
for relative in m['backup_only_paths']:
    require(relative in ['docs/continue/backup/manifest.json','docs/continue/backup/restore-s6-fixture.patch','docs/continue/backup/restore-s6.py'],'Unexpected auxiliary path.')
    (root/relative).unlink()
subprocess.run(['git','add','-A','--'],cwd=root,check=True)
tree=git('write-tree');require(tree==m['source_tree'],'Restored tree differs; inspect staged changes, no commit created.')
print('Restored exact source tree '+tree+' from snapshot '+m['source_commit'])
print('Staged only. No commit, network, build or service action. Do not publish restricted fixtures without authorization.')

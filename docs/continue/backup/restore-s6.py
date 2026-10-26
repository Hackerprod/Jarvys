#!/usr/bin/env python3
"""Restore the exact validated local source tree in a CLEAN backup checkout; no network/build."""
from pathlib import Path
import hashlib,json,subprocess
root=Path(subprocess.check_output(['git','-C',str(Path(__file__).resolve().parent),'rev-parse','--show-toplevel'],text=True).strip())
manifest_path=root/'docs/continue/backup/manifest.json'
m=json.loads(manifest_path.read_text())
def git(*args): return subprocess.check_output(['git',*args],cwd=root,text=True).strip()
def require(condition,message):
    if not condition: raise SystemExit(message)
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
require(git('status','--porcelain')=='','Refusing a dirty checkout. Use a fresh clean clone/worktree.')
fixture=root/m['fixture_path']; patch=root/'docs/continue/backup/restore-s6-fixture.patch'; readme=root/'README.md'
require(sha(fixture)==m['fixture_remote_sha256'],'Fixture does not match remote baseline.')
require(sha(patch)==m['patch_sha256'],'Patch integrity mismatch.')
require(sha(readme)==m['readme_backup_sha256'],'README integrity mismatch.')
footer=m['readme_footer'].encode(); original_readme=readme.read_bytes()
require(original_readme.endswith(footer),'Backup notice mismatch.')
subprocess.run(['git','apply','--check','--unidiff-zero',str(patch)],cwd=root,check=True)
subprocess.run(['git','apply','--unidiff-zero',str(patch)],cwd=root,check=True)
readme.write_bytes(original_readme[:-len(footer)])
require(sha(fixture)==m['fixture_restored_sha256'],'Restored fixture mismatch; inspect worktree, no commit was created.')
require(sha(readme)==m['readme_source_sha256'],'Restored README mismatch.')
for relative in m['backup_only_paths']:
    require(relative in ['docs/continue/backup/manifest.json','docs/continue/backup/restore-s6-fixture.patch','docs/continue/backup/restore-s6.py'],'Unexpected auxiliary path.')
    (root/relative).unlink()
subprocess.run(['git','add','-A','--'],cwd=root,check=True)
tree=git('write-tree')
require(tree==m['source_tree'],'Restored tree differs; inspect staged changes, no commit was created.')
print('Restored exact source tree '+tree+' from local snapshot '+m['source_commit'])
print('Changes are staged only. No commit, network request, build or service action was performed.')

import contextlib,fcntl,hashlib,json,os,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
import lint_metadata_snapshot as m

class MetadataControls(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name);self.source=self.root/'source';self.target=self.root/'target';self.source.mkdir();self.target.mkdir();self.snapshot=self.root/'snapshot'
  self.cache='app/app/build/intermediates/lint-cache/lintAnalyzeFullDebug/'
  self.marker=self.cache+'lint-cache-version.txt';self.google=self.cache+'maven.google/androidx/core/group-index.xml';self.maven=self.cache+'maven-versions/repo1.maven.org/junit/junit/maven-metadata.xml';self.sdk=self.cache+'sdk_index/snapshot.gz'
  self.write(self.source,self.marker,m.MARKER);self.write(self.source,self.google,b'<versions/>');self.write(self.source,self.maven,b'');self.write(self.source,self.sdk,b'example fixture only')
  self.write(self.source,self.cache+'api-versions.bin',b'ANALYSIS MUST NOT COPY')
  self.log=self.root/'log';self.log.write_text('BUILD SUCCESSFUL')
  self.run=self.root/'run.json';self.run.write_text(json.dumps({'exit_code':0,'start_epoch':1,'finish_epoch':2,'cwd':str(self.source/'app'),'args':sorted(m.REPORTS),'log':str(self.log)}))
 def tearDown(self):self.temp.cleanup()
 def write(self,root,relative,data):
  p=root/relative;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(data);return p
 def freeze(self):
  def git(args,**kwargs):
   if args[-2:]==['rev-parse','HEAD']:return m.BASELINE+'\n'
   if args[-2:]==['rev-parse','HEAD:app']:return m.BASE_TREE+'\n'
   if 'status' in args:return ''
   raise AssertionError(args)
  with patch.object(m.subprocess,'check_output',git):result=m.freeze(self.source,self.run,self.snapshot)
  self.digest=result['manifestSha256'];return result
 def test_freeze_copies_only_metadata_and_marker(self):
  self.freeze();r=m.load_snapshot(self.snapshot,self.digest);self.assertEqual(len(r['files']),4);self.assertNotIn(self.cache+'api-versions.bin',r['files']);self.assertFalse((self.snapshot/'payload'/ (self.cache+'api-versions.bin')).exists())
 def test_seed_exact_inputs_and_version_marker(self):
  self.freeze();r=m.seed(self.snapshot,self.target,self.digest);self.assertEqual(r['verifiedFiles'],4);self.assertEqual((self.target/self.marker).read_bytes(),m.MARKER)
 def test_seed_preserves_mtime(self):
  self.freeze();m.seed(self.snapshot,self.target,self.digest)
  for p,row in m.load_snapshot(self.snapshot,self.digest)['files'].items():self.assertEqual((self.target/p).stat().st_mtime_ns,row['mtimeNs'])
 def test_repeat_seed_idempotent(self):self.freeze();m.seed(self.snapshot,self.target,self.digest);self.assertEqual(m.seed(self.snapshot,self.target,self.digest)['verifiedFiles'],4)
 def test_existing_analysis_file_untouched(self):
  self.freeze();p=self.write(self.target,self.cache+'analysis.bin',b'local analysis');self.write(self.target,self.marker,m.MARKER);m.seed(self.snapshot,self.target,self.digest);self.assertEqual(p.read_bytes(),b'local analysis')
 def test_snapshot_tamper_rejected(self):
  self.freeze();(self.snapshot/'payload'/self.google).write_bytes(b'changed')
  with self.assertRaises(ValueError):m.seed(self.snapshot,self.target,self.digest)
  self.assertFalse((self.target/'app').exists())
 def test_extra_snapshot_file_rejected(self):
  self.freeze();(self.snapshot/'extra').write_text('x')
  with self.assertRaises(ValueError):m.load_snapshot(self.snapshot,self.digest)
 def test_source_symlink_rejected(self):
  (self.source/self.google).unlink();(self.source/self.google).symlink_to(self.log)
  with self.assertRaises(ValueError):self.freeze()
  self.assertFalse(self.snapshot.exists())
 def test_source_domain_symlink_rejected(self):
  p=self.source/self.cache/'maven.google';import shutil;shutil.rmtree(p);p.symlink_to(self.root,target_is_directory=True)
  with self.assertRaises(ValueError):self.freeze()
 def test_target_ancestor_symlink_rejected_before_write(self):
  self.freeze();outside=self.root/'outside';outside.mkdir();(self.target/'app').symlink_to(outside,target_is_directory=True)
  with self.assertRaises(ValueError):m.seed(self.snapshot,self.target,self.digest)
  self.assertEqual(list(outside.iterdir()),[])
 def test_snapshot_ancestor_symlink_rejected(self):
  p=self.root/'link';p.symlink_to(self.root,target_is_directory=True);self.snapshot=p/'snapshot'
  with self.assertRaises(ValueError):self.freeze()
 def test_snapshot_symlink_payload_rejected(self):
  self.freeze();p=self.snapshot/'payload'/self.google;p.unlink();p.symlink_to(self.source/self.google)
  with self.assertRaises(ValueError):m.load_snapshot(self.snapshot,self.digest)
 def test_version_marker_mismatch_rejected(self):
  (self.source/self.marker).write_bytes(b'wrong\n')
  with self.assertRaises(ValueError):self.freeze()
 def test_missing_marker_rejected(self):
  (self.source/self.marker).unlink()
  with self.assertRaises(ValueError):self.freeze()
 def test_target_conflict_is_not_overwritten(self):
  self.freeze();self.write(self.target,self.marker,m.MARKER);p=self.write(self.target,self.google,b'old distinct');
  with self.assertRaises(ValueError):m.seed(self.snapshot,self.target,self.digest)
  self.assertEqual(p.read_bytes(),b'old distinct');self.assertFalse((self.target/self.maven).exists())
 def test_target_extra_metadata_rejected(self):
  self.freeze();self.write(self.target,self.marker,m.MARKER);self.write(self.target,self.cache+'maven.google/extra/group-index.xml',b'extra')
  with self.assertRaises(ValueError):m.seed(self.snapshot,self.target,self.digest)
  self.assertFalse((self.target/self.google).exists())
 def test_analysis_path_in_manifest_rejected(self):
  self.freeze();p=self.snapshot/'manifest.json';r=json.loads(p.read_text());r['files'][self.cache+'analysis.bin']=next(iter(r['files'].values()));p.write_text(json.dumps(r))
  with self.assertRaises(ValueError):m.load_snapshot(self.snapshot,self.digest)
 def test_traversal_rejected(self):
  with self.assertRaises(ValueError):m.allowed('app/app/build/intermediates/lint-cache/lintAnalyzeFullDebug/maven.google/../group-index.xml')
 def test_unknown_metadata_file_rejected(self):
  self.write(self.source,self.cache+'maven.google/analysis.bin',b'not metadata')
  with self.assertRaises(ValueError):self.freeze()
 def test_unknown_module_rejected(self):
  with self.assertRaises(ValueError):m.allowed(self.google.replace('app/app/','app/other/'))
 def test_unknown_task_rejected(self):
  with self.assertRaises(ValueError):m.allowed(self.google.replace('lintAnalyzeFullDebug','executeAnything'))
 def test_unknown_metadata_host_rejected(self):
  with self.assertRaises(ValueError):m.allowed(self.maven.replace('repo1.maven.org','example.invalid'))
 def test_snapshot_overwrite_refused(self):
  self.freeze()
  with self.assertRaises(ValueError):self.freeze()
 def test_incomplete_run_rejected(self):
  r=json.loads(self.run.read_text());r.pop('exit_code');self.run.write_text(json.dumps(r))
  with self.assertRaises(ValueError):self.freeze()
 def test_offline_preparation_rejected(self):
  r=json.loads(self.run.read_text());r['args'].append('--offline');self.run.write_text(json.dumps(r))
  with self.assertRaises(ValueError):self.freeze()
 def test_narrowed_preparation_rejected(self):
  r=json.loads(self.run.read_text());r['args'].pop();self.run.write_text(json.dumps(r))
  with self.assertRaises(ValueError):self.freeze()
 def test_busy_gradle_lock_rejected(self):
  p=self.root/'gradle.lock';p.touch()
  with p.open('r') as f:
   fcntl.flock(f,fcntl.LOCK_EX|fcntl.LOCK_NB)
   with self.assertRaises(ValueError):
    with m.lock(p):pass
 def test_free_lock_available(self):
  p=self.root/'gradle.lock';p.touch()
  with m.lock(p):pass
 def test_snapshot_inside_worktree_rejected(self):
  import subprocess
  repo=self.root/'repo';repo.mkdir();subprocess.run(['git','init','-q',str(repo)],check=True);self.snapshot=repo/'snapshot'
  with self.assertRaises(ValueError):self.freeze()
 def test_verify_detects_removed_input(self):
  self.freeze();m.seed(self.snapshot,self.target,self.digest);(self.target/self.google).unlink()
  with self.assertRaises(ValueError):m.verify(self.snapshot,self.target,self.digest)
 def test_missing_target_marker_is_seeded(self):
  self.freeze();(self.target/self.cache).mkdir(parents=True);m.seed(self.snapshot,self.target,self.digest);self.assertEqual((self.target/self.marker).read_bytes(),m.MARKER)
 def test_target_file_directory_collision_rejected_before_write(self):
  self.freeze();self.write(self.target,self.marker,m.MARKER);before=(self.target/self.marker).stat().st_mtime_ns;(self.target/self.google).mkdir(parents=True)
  with self.assertRaises(ValueError):m.seed(self.snapshot,self.target,self.digest)
  self.assertEqual((self.target/self.marker).stat().st_mtime_ns,before);self.assertFalse((self.target/self.sdk).exists())
 def test_target_hardlink_rejected_before_write(self):
  self.freeze();self.write(self.target,self.marker,m.MARKER);outside=self.root/'outside';outside.write_bytes(b'<versions/>');p=self.target/self.google;p.parent.mkdir(parents=True);os.link(outside,p);before=outside.stat().st_mtime_ns
  with self.assertRaises(ValueError):m.seed(self.snapshot,self.target,self.digest)
  self.assertEqual(outside.stat().st_mtime_ns,before);self.assertFalse((self.target/self.sdk).exists())
 def test_resealed_snapshot_rejected_by_external_digest(self):
  self.freeze();p=self.snapshot/'payload'/self.google;p.write_bytes(b'tampered');r=json.loads((self.snapshot/'manifest.json').read_text());r['files'][self.google]=m.file_record(p);(self.snapshot/'manifest.json').write_text(json.dumps(r))
  with self.assertRaises(ValueError):m.seed(self.snapshot,self.target,self.digest)
  self.assertFalse((self.target/'app').exists())
 def test_target_parent_file_collision_rejected_before_write(self):
  self.freeze();self.write(self.target,'app/app/build',b'not a directory')
  with self.assertRaises((ValueError,NotADirectoryError)):m.seed(self.snapshot,self.target,self.digest)
  self.assertEqual((self.target/'app/app/build').read_bytes(),b'not a directory')
 def test_extra_markerless_task_rejected_before_write(self):
  self.freeze();extra=self.cache.replace('lintAnalyzeFullDebug','lintAnalyzeFullRelease');self.write(self.target,extra+'analysis.bin',b'local analysis')
  with self.assertRaises(ValueError):m.seed(self.snapshot,self.target,self.digest)
  self.assertFalse((self.target/self.marker).exists());self.assertFalse((self.target/self.sdk).exists())
 def test_actual_report_caches_can_be_frozen_and_seeded(self):
  for module,task in [('app','lintReportFullDebug'),('app','lintReportPlayDebug'),('apk-runtime','lintReportRelease'),('factory-runtime-core','lintReportRelease')]:
   self.write(self.source,f'app/{module}/build/intermediates/lint-cache/{task}/lint-cache-version.txt',m.MARKER)
  self.freeze();self.assertEqual(m.seed(self.snapshot,self.target,self.digest)['verifiedFiles'],8)
 def test_report_variant_cross_module_rejected(self):
  with self.assertRaises(ValueError):m.allowed('app/apk-runtime/build/intermediates/lint-cache/lintReportFullDebug/lint-cache-version.txt')
  with self.assertRaises(ValueError):m.allowed('app/app/build/intermediates/lint-cache/lintReportRelease/lint-cache-version.txt')
 def test_unreviewed_report_task_rejected(self):
  for name in ('lintReportDebug','lintReportFullRelease','lintReportPlayDebugUnitTest','lintVitalReportRelease'):
   with self.assertRaises(ValueError):m.allowed(self.marker.replace('lintAnalyzeFullDebug',name))
if __name__=='__main__':unittest.main()

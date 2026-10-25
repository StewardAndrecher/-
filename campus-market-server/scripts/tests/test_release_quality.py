from pathlib import Path
import importlib.util,sys,tempfile,unittest,json
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import release_quality as gate
class QualityGateTests(unittest.TestCase):
 def test_zero_tests_or_skips_are_not_success(self):
  for junit in [{'tests':0,'failures':0,'errors':0,'skipped':0},{'tests':1,'failures':0,'errors':0,'skipped':1}]:
   with self.assertRaises(ValueError):gate.passed({'status':'passed','temporaryRuntimeRemoved':True,'junit':junit},'test')
 def test_uncertain_cleanup_blocks_release(self):
  with self.assertRaises(ValueError):gate.passed({'status':'passed','junit':{'tests':1,'failures':0,'errors':0,'skipped':0}},'test')
 def test_source_digest_changes_for_rename_or_mutation(self):
  a={'a.txt':'0'*64};self.assertNotEqual(gate.digest(a),gate.digest({'b.txt':'0'*64}));self.assertNotEqual(gate.digest(a),gate.digest({'a.txt':'1'*64}))
 def test_secrets_artifacts_and_symlinks(self):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);(root/'App.vue').write_text('source');(root/'.env.local').write_text('private');(root/'unpackage').mkdir();(root/'unpackage/built').write_text('old')
   self.assertEqual(['App.vue'],list(gate.inventory(root,gate.CLIENT_IGNORED,True)))
   (root/'leak').symlink_to(root/'.env.local')
   with self.assertRaises(ValueError):gate.inventory(root,gate.CLIENT_IGNORED,True)
 def test_missing_evidence_never_becomes_passed(self):
  with tempfile.TemporaryDirectory() as d:
   with self.assertRaises(FileNotFoundError):gate.verify(d,d,d)

 def test_digest_uses_the_same_path_order_as_source_snapshots(self):
  import hashlib
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);(root/'pages').mkdir();(root/'pages/home.vue').write_text('page');(root/'pages.json').write_text('config')
   expected=hashlib.sha256()
   for p in sorted(root.rglob('*')):
    if p.is_file():expected.update(p.relative_to(root).as_posix().encode());expected.update(b'\0');expected.update(hashlib.sha256(p.read_bytes()).digest())
   self.assertEqual(expected.hexdigest(),gate.digest(gate.inventory(root,set())))

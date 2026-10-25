from pathlib import Path
import contextlib,io,json,os,signal,subprocess,sys,tempfile,unittest
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import quick_test_support as support
import test_quick

class QuickToolsTests(unittest.TestCase):
    def setUp(self):
        self.temporary=tempfile.TemporaryDirectory(prefix='campus-quick-tools-');self.root=Path(self.temporary.name);self.addCleanup(self.temporary.cleanup)
    def report(self,text='<testcase name="one"/>'):
        path=self.root/'report.xml';path.write_text('<testsuites>'+text+'</testsuites>');return path
    def project(self,name='campus-market-client'):
        root=self.root/name;(root/'tools').mkdir(parents=True);(root/'tools/one.test.mjs').write_text('fixture');return root
    def test_environment_drops_node_options_auth_and_real_compiler(self):
        env=support.isolated_environment({'PATH':'/bin','HOME':'/home/user','NODE_OPTIONS':'--test-name-pattern=skip','NODE_PATH':'private','DB_PASSWORD':'private','MONITORING_TOKEN':'private','UNI_COMPILER_HOME':'/real/compiler','TMPDIR':'/shared'},self.root,'/node/bin/node')
        self.assertEqual({'PATH','HOME','TMPDIR','TMP','TEMP'},set(env));self.assertEqual(str(self.root),env['TMPDIR']);self.assertTrue(env['PATH'].startswith('/node/bin'))
    def test_node_version_requires_22_12_and_drops_injection(self):
        node=self.root/'node';node.write_text('fixture');node.chmod(0o700)
        for version,valid in [('v22.11.0',False),('v22.12.0',True),('v22.22.2',True),('v26.8.1',True),('nonsense',False)]:
            with self.subTest(version=version),patch.object(support.subprocess,'run',return_value=subprocess.CompletedProcess([],0,version+'\n','')) as run:
                if valid:self.assertEqual(version,support.locate_node({'NODE_BIN':str(node),'NODE_OPTIONS':'injected'})[1])
                else:
                    with self.assertRaisesRegex(RuntimeError,'22.12'):support.locate_node({'NODE_BIN':str(node)})
                self.assertNotIn('NODE_OPTIONS',run.call_args.kwargs['env'])
    def test_discovery_requires_selected_project_and_supports_standalone_client(self):
        client=self.project();self.assertEqual(['tools/one.test.mjs'],support.discover(client,'client')['client'][1])
        with self.assertRaisesRegex(RuntimeError,'No desktop'):support.discover(client,'all')
        desktop=self.root/'campus-market-desktop';(desktop/'test').mkdir(parents=True);(desktop/'test/two.test.mjs').write_text('fixture')
        self.assertEqual({'client','desktop'},set(support.discover(client,'all')))
        with self.assertRaises(ValueError):support.discover(client,'unknown')
    def test_discovery_rejects_test_symlinks(self):
        client=self.project();(client/'tools/link.test.mjs').symlink_to(client/'tools/one.test.mjs')
        with self.assertRaisesRegex(RuntimeError,'symlink'):support.discover(client,'client')
    def test_snapshot_excludes_secrets_artifacts_and_bytecode(self):
        client=self.project()
        for folder in ['data','unpackage','node_modules','tools/__pycache__']:
            p=client/folder;p.mkdir(parents=True);(p/'private').write_text('excluded')
        (client/'.env.local').write_text('private');destination=self.root/'copy with spaces';digest=support.snapshot(client,destination)
        self.assertEqual(64,len(digest));self.assertEqual(['tools/one.test.mjs'],[p.relative_to(destination).as_posix() for p in destination.rglob('*') if p.is_file()])
        (client/'external').symlink_to(self.root/'elsewhere')
        with self.assertRaisesRegex(RuntimeError,'symlink'):support.snapshot(client,self.root/'bad-copy')
    def test_junit_counts_fail_error_skip_and_rejects_empty_or_invalid_xml(self):
        counts=support.parse_report(self.report('<testcase name="ok"/><testcase name="bad"><failure/></testcase><testcase name="error"><error/></testcase><testcase name="skip"><skipped/></testcase>'))
        self.assertEqual(dict(tests=4,failures=1,errors=1,skipped=1),counts)
        with self.assertRaisesRegex(RuntimeError,'no test'):support.parse_report(self.report(''))
        path=self.report();path.write_text('<garbage/>')
        with self.assertRaisesRegex(RuntimeError,'root'):support.parse_report(path)
    def test_reports_require_exact_file_coverage_and_nonzero_exit_never_passes(self):
        folder=self.root/'junit';folder.mkdir();(folder/'client-00.xml').write_bytes(self.report().read_bytes())
        item={'suite':'client','file':'tools/one.test.mjs','report':'client-00.xml','exitCode':0}
        self.assertTrue(support.collect_results(self.root,[item])['passed'])
        self.assertFalse(support.collect_results(self.root,[dict(item,exitCode=1)])['passed'])
        (folder/'extra.xml').write_bytes(self.report().read_bytes())
        with self.assertRaisesRegex(RuntimeError,'coverage'):support.collect_results(self.root,[item])
    def test_skips_and_empty_reports_cannot_pass(self):
        folder=self.root/'junit';folder.mkdir();p=folder/'one.xml';item={'report':'one.xml','exitCode':0}
        p.write_bytes(self.report('<testcase name="skip"><skipped/></testcase>').read_bytes());self.assertFalse(support.collect_results(self.root,[item])['passed'])
        p.write_bytes(self.report('').read_bytes());self.assertFalse(support.collect_results(self.root,[item])['passed'])
    def test_empty_node_file_placeholder_cannot_pass(self):
        p=self.report('<testcase name="/tmp/source/tools/empty.test.mjs"/>')
        with self.assertRaisesRegex(RuntimeError,'placeholder'):support.parse_report(p,'tools/empty.test.mjs')
    def test_timeout_stops_owned_group_and_real_child_exits(self):
        with self.assertRaises(subprocess.TimeoutExpired):support.run_node([sys.executable,'-c','import time;time.sleep(60)'],os.environ,self.root,self.root/'log',timeout=.1)
        process=subprocess.Popen([sys.executable,'-c','import time;time.sleep(60)'],start_new_session=True)
        self.addCleanup(lambda:support.stop_owned_group(process));support.stop_owned_group(process);self.assertIsNotNone(process.poll())
    def test_stop_failure_is_distinct_and_not_silently_successful(self):
        with patch.object(support.os,'killpg',side_effect=PermissionError('fixture')):
            with self.assertRaises(support.CleanupUnconfirmed):support.stop_owned_group(type('P',(),{'pid':12345})())
    def test_check_creates_no_results_or_temporary_runtime(self):
        client=self.project()
        with patch.object(test_quick,'__file__',str(client/'tools/test_quick.py')),patch.object(sys,'argv',['test_quick.py','--check','--suite','client']),patch.object(test_quick,'locate_node',return_value=('/node','v22.22.2')),patch.object(test_quick.tempfile,'mkdtemp') as mkdtemp,contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0,test_quick.main());mkdtemp.assert_not_called()
        self.assertFalse((client/'unpackage').exists())
    def test_unconfirmed_process_cleanup_retains_runtime_and_fails(self):
        client=self.project();runtime=self.root/'owned-runtime';runtime.mkdir()
        with patch.object(test_quick,'__file__',str(client/'tools/test_quick.py')),patch.object(sys,'argv',['test_quick.py','--suite','client']),patch.object(test_quick,'locate_node',return_value=('/node','v22.22.2')),patch.object(test_quick.tempfile,'mkdtemp',return_value=str(runtime)),patch.object(test_quick,'run_node',side_effect=support.CleanupUnconfirmed('fixture cleanup unconfirmed')),contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(1,test_quick.main())
        summary=json.loads((client/'unpackage/quick-tests/latest.json').read_text());self.assertEqual('failed',summary['status']);self.assertFalse(summary['temporaryRuntimeRemoved']);self.assertTrue(runtime.exists())
    def test_json_summary_is_private(self):
        path=self.root/'summary.json';support.write_json(path,{'status':'passed'});self.assertEqual(0o600,path.stat().st_mode&0o777);self.assertEqual('passed',json.loads(path.read_text())['status'])

if __name__=='__main__':unittest.main()

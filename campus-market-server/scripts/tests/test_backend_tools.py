# coding: utf-8
"""Verification infrastructure tests: prevent skipped/partial runs from looking successful."""
from pathlib import Path
import contextlib,io,json,os,subprocess,sys,tempfile,unittest
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from backend_test_support import *
import test_backend

class BackendToolsTests(unittest.TestCase):
    def setUp(self):
        self.temporary=tempfile.TemporaryDirectory(prefix='campus-tools-');self.root=Path(self.temporary.name)
        self.addCleanup(self.temporary.cleanup)
    def source(self,name='OneTest',package='com.campus.market.example'):
        p=self.root/'src/test/java'/package.replace('.','/')/(name+'.java');p.parent.mkdir(parents=True,exist_ok=True);p.write_text('package '+package+';\nclass '+name+' {}');return p
    def report(self,name='com.campus.market.OneTest',tests=1,failures=0,errors=0,skipped=0,filename=None):
        p=self.root/(filename or 'TEST-'+name+'.xml');p.write_text(f'<testsuite name="{name}" tests="{tests}" failures="{failures}" errors="{errors}" skipped="{skipped}">'+('<testcase name="case"/>'*max(tests,0))+'</testsuite>');return p
    def test_environment_drops_live_service_and_jvm_settings(self):
        dangerous=['DB_PASSWORD','MYSQL_PWD','DB_MIGRATION_ENABLED','SPRING_FLYWAY_ENABLED','LESSON74_DB_URL','SPRING_APPLICATION_JSON','SPRING_CONFIG_LOCATION','SPRING_DATASOURCE_URL','API_DOCS_ENABLED','MONITORING_TOKEN','RABBITMQ_PASSWORD','MAVEN_OPTS','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','HTTP_PROXY']
        source={k:'do-not-inherit' for k in dangerous};source.update(PATH='/bin',HOME='/home/student',JAVA_HOME='/jdk17',LANG='C.UTF-8')
        self.assertEqual({'PATH':'/bin','HOME':'/home/student','JAVA_HOME':'/jdk17','LANG':'C.UTF-8'},isolated_environment(source))
    def test_discovers_tests_and_ignores_non_test_helpers(self):
        self.source();self.source('TwoTests');self.source('ProductTestRequests')
        self.assertEqual({'OneTest','TwoTests'},set(discover_tests(self.root)))
    def test_docs_suite_is_complete_and_specific(self):
        for name in DOCS_CLASSES:self.source(name)
        self.source('OtherTest');self.assertEqual(DOCS_CLASSES,set(discover_tests(self.root,'docs')))
    def test_rejects_missing_docs_test(self):
        self.source('ApiContractIntegrationTest')
        with self.assertRaisesRegex(RuntimeError,'incomplete'):discover_tests(self.root,'docs')
    def test_rejects_duplicate_names_and_wrong_packages(self):
        self.source();self.source(package='com.campus.market.second')
        with self.assertRaisesRegex(RuntimeError,'Duplicate'):discover_tests(self.root)
        self.temporary.cleanup();self.root.mkdir();self.source(package='foreign.example')
        with self.assertRaisesRegex(RuntimeError,'package'):discover_tests(self.root)
    def test_rejects_no_discovered_tests(self):
        with self.assertRaisesRegex(RuntimeError,'No test'):discover_tests(self.root)
    def test_collects_all_reported_cases(self):
        self.report(tests=3);self.report(name='com.campus.market.TwoTest',tests=2)
        self.assertEqual({'tests':5,'failures':0,'errors':0,'skipped':0,'suites':2},collect_results(self.root,{'OneTest':'com.campus.market.OneTest','TwoTest':'com.campus.market.TwoTest'}))
    def test_rejects_missing_report(self):
        self.report()
        with self.assertRaisesRegex(RuntimeError,'coverage mismatch'):collect_results(self.root,{'Missing':'com.campus.market.MissingTest'})
    def test_rejects_skips_failures_and_errors(self):
        for outcome in ['skipped','failures','errors']:
            with self.subTest(outcome=outcome):
                self.report(**{outcome:1})
                with self.assertRaisesRegex(RuntimeError,'failure/error/skip'):collect_results(self.root,{'OneTest':'com.campus.market.OneTest'})
    def test_rejects_invalid_counts_and_empty_suite(self):
        p=self.report();p.write_text(p.read_text().replace('tests="1"','tests="2"'))
        with self.assertRaisesRegex(RuntimeError,'counts'):collect_results(self.root,{'OneTest':'com.campus.market.OneTest'})
        self.report(tests=0)
        with self.assertRaisesRegex(RuntimeError,'Empty'):collect_results(self.root,{'OneTest':'com.campus.market.OneTest'})
    def test_rejects_duplicate_report(self):
        self.report();self.report(filename='TEST-copy.xml')
        with self.assertRaisesRegex(RuntimeError,'duplicate'):collect_results(self.root,{'OneTest':'com.campus.market.OneTest'})
    def test_snapshot_excludes_private_data_and_does_not_change_source(self):
        project=self.root/'source with spaces';project.mkdir();(project/'pom.xml').write_text('<project/>')
        for name in ['data','target','.idea']:
            (project/name).mkdir();(project/name/'private.txt').write_text('not-for-build')
        first=self.root/'build one';second=self.root/'build two';a=snapshot_source(project,first);b=snapshot_source(project,second)
        self.assertEqual(a,b);self.assertEqual(['pom.xml'],[p.name for p in first.iterdir()]);self.assertTrue((project/'target/private.txt').is_file())
    def test_snapshot_rejects_source_symlink(self):
        project=self.root/'source';project.mkdir();(project/'external').symlink_to(self.root/'elsewhere')
        with self.assertRaisesRegex(RuntimeError,'symlink'):snapshot_source(project,self.root/'build')
    def test_missing_tool_fails_without_starting_service(self):
        with patch('backend_test_support.subprocess.run') as run:
            with self.assertRaisesRegex(RuntimeError,'Missing executable'):locate_tools({'PATH':'/a-missing-path','JAVA_HOME':'/missing-jdk'})
            run.assert_not_called()
    def test_non_17_java_is_rejected(self):
        java=self.root/'bin/java';java.parent.mkdir();java.write_text('#!/bin/sh\n');java.chmod(0o700)
        with patch('backend_test_support.subprocess.run',return_value=subprocess.CompletedProcess([],0,'','openjdk version "26"')):
            with self.assertRaisesRegex(RuntimeError,'JDK 17'):locate_tools({'JAVA_HOME':str(self.root),'PATH':''})
    def test_maven_timeout_still_cleans_owned_group(self):
        with patch('backend_test_support.subprocess.Popen') as popen,patch('backend_test_support.stop_owned_group') as stop:
            process=popen.return_value;process.wait.side_effect=subprocess.TimeoutExpired('mvn',1200)
            with self.assertRaises(subprocess.TimeoutExpired):run_maven(['mvn','test'],{},self.root/'maven.log')
            self.assertTrue(popen.call_args.kwargs['start_new_session']);stop.assert_called_once_with(process)
    def test_owned_group_can_be_stopped_without_shared_service_commands(self):
        process=subprocess.Popen([sys.executable,'-c','import time;time.sleep(60)'],start_new_session=True)
        self.addCleanup(lambda:stop_owned_group(process));stop_owned_group(process);self.assertIsNotNone(process.poll())
    def test_json_results_are_private_and_valid(self):
        target=self.root/'summary.json';write_json(target,{'status':'passed','tests':843})
        self.assertEqual('passed',json.loads(target.read_text())['status']);self.assertEqual(0o600,target.stat().st_mode&0o777);self.assertFalse(target.with_name('summary.json.tmp').exists())
    def test_check_mode_never_enters_runtime_or_creates_results(self):
        project=Path(test_backend.__file__).resolve().parents[1];before=(project/'data/test-results').exists()
        with patch.object(sys,'argv',['test_backend.py','--check']),patch.object(test_backend,'locate_tools',return_value={'java':'fixture'}),contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(0,test_backend.main())
        self.assertFalse(json.loads(output.getvalue())['servicesStarted']);self.assertEqual(before,(project/'data/test-results').exists())

    def test_unconfirmed_runtime_cleanup_retains_diagnostics_and_fails(self):
        project=self.root/'project';(project/'scripts').mkdir(parents=True);(project/'sql').mkdir()
        for name in ['lesson15-users.sql','lesson19-login-sessions.sql']:(project/'sql'/name).write_text('fixture')
        runtime=self.root/'runtime';runtime.mkdir();(runtime/'diagnostic.txt').write_text('keep')
        with patch.object(test_backend,'__file__',str(project/'scripts/test_backend.py')),patch.object(sys,'argv',['test_backend.py']),patch.object(test_backend,'locate_tools',return_value={}),patch.object(test_backend,'discover_tests',return_value={'OneTest':'com.campus.market.OneTest'}),patch.object(test_backend.tempfile,'mkdtemp',return_value=str(runtime)),patch('backend_test_runtime.execute',side_effect=RuntimeError('cleanup was not confirmed')),contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(1,test_backend.main())
        self.assertTrue((runtime/'diagnostic.txt').is_file());record=json.loads((project/'data/test-results/latest.json').read_text());self.assertEqual('failed',record['status']);self.assertFalse(record['temporaryRuntimeRemoved'])

    def test_quick_suite_has_only_its_eight_explicit_classes(self):
        for name in QUICK_CLASSES:self.source(name)
        self.source('DatabaseIntegrationTest')
        self.assertEqual(QUICK_CLASSES,set(discover_tests(self.root,'quick')))
    def test_quick_suite_missing_a_class_is_rejected(self):
        self.source('PasswordConfigTest')
        with self.assertRaisesRegex(RuntimeError,'quick suite is incomplete'):discover_tests(self.root,'quick')
    def test_quick_suite_rejects_full_boot_context(self):
        for name in QUICK_CLASSES:self.source(name)
        p=self.source('PasswordConfigTest');p.write_text(p.read_text()+'\n@SpringBootTest class Extra {}')
        with self.assertRaisesRegex(RuntimeError,'full Spring Boot'):discover_tests(self.root,'quick')
    def test_unknown_suite_is_rejected(self):
        with self.assertRaises(ValueError):discover_tests(self.root,'typo')
        with self.assertRaises(ValueError):locate_tools({},'typo')
    def test_quick_preflight_never_searches_for_middleware(self):
        java=self.root/'jdk/bin/java';java.parent.mkdir(parents=True);java.write_text('fixture');java.chmod(0o700)
        maven=self.root/'bin/mvn';maven.parent.mkdir();maven.write_text('fixture');maven.chmod(0o700)
        with patch('backend_test_support.subprocess.run',return_value=subprocess.CompletedProcess([],0,'','openjdk version "17.0.20"')),patch('backend_test_support.shutil.which',return_value=str(maven)) as which:
            tools=locate_tools({'JAVA_HOME':str(self.root/'jdk'),'PATH':str(maven.parent)},'quick')
            self.assertEqual({'java','mvn'},set(tools));self.assertEqual(['mvn'],[call.args[0] for call in which.call_args_list])
    def test_all_preflight_still_requires_database_tools(self):
        java=self.root/'jdk/bin/java';java.parent.mkdir(parents=True);java.write_text('fixture');java.chmod(0o700)
        maven=self.root/'mvn';maven.write_text('fixture');maven.chmod(0o700)
        def available(name,path):return str(maven) if name=='mvn' else None
        with patch('backend_test_support.subprocess.run',return_value=subprocess.CompletedProcess([],0,'','openjdk version "17.0.20"')),patch('backend_test_support.shutil.which',side_effect=available),patch('backend_test_support.Path.is_file',autospec=True,side_effect=lambda p: p==java or p==maven):
            with self.assertRaisesRegex(RuntimeError,'Missing executable: mysql'):locate_tools({'JAVA_HOME':str(self.root/'jdk'),'PATH':''},'all')
    def test_quick_executor_does_not_dispatch_to_service_runtime(self):
        project=self.root/'project';(project/'scripts').mkdir(parents=True)
        runtime=self.root/'runtime';runtime.mkdir()
        def quick(*args):
            shutil.rmtree(args[3]);return {'tests':89,'failures':0,'errors':0,'skipped':0,'suites':10,'sourceSha256':'fixture'}
        with patch.object(test_backend,'__file__',str(project/'scripts/test_backend.py')),patch.object(sys,'argv',['test_backend.py','--suite','quick']),patch.object(test_backend,'locate_tools',return_value={}),patch.object(test_backend,'discover_tests',return_value={'OneTest':'com.campus.market.OneTest'}),patch.object(test_backend.tempfile,'mkdtemp',return_value=str(runtime)),patch('backend_quick_runtime.execute',side_effect=quick) as pure,patch('backend_test_runtime.execute') as services,contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0,test_backend.main())
        pure.assert_called_once();services.assert_not_called();record=json.loads((project/'data/test-results/latest.json').read_text());self.assertFalse(record['requiresMiddleware'])
    def test_quick_check_needs_no_historical_sql_files(self):
        project=self.root/'project';(project/'scripts').mkdir(parents=True)
        with patch.object(test_backend,'__file__',str(project/'scripts/test_backend.py')),patch.object(sys,'argv',['test_backend.py','--suite','quick','--check']),patch.object(test_backend,'locate_tools',return_value={'java':'fixture','mvn':'fixture'}),patch.object(test_backend,'discover_tests',return_value={'OneTest':'com.campus.market.OneTest'}),contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(0,test_backend.main())
        self.assertFalse(json.loads(output.getvalue())['servicesStarted']);self.assertFalse((project/'data').exists())

    def test_maven_stop_callback_requires_confirmed_cleanup_even_on_timeout(self):
        for cleanup_ok in [True,False]:
            with self.subTest(cleanup_ok=cleanup_ok),patch('backend_test_support.subprocess.Popen') as popen,patch('backend_test_support.stop_owned_group') as stop:
                popen.return_value.wait.side_effect=subprocess.TimeoutExpired('mvn',1200)
                if not cleanup_ok:stop.side_effect=RuntimeError('unconfirmed')
                called=[]
                with self.assertRaises((subprocess.TimeoutExpired,RuntimeError)):run_maven(['mvn','test'],{},self.root/'maven.log',on_stopped=lambda:called.append(True))
                self.assertEqual([True] if cleanup_ok else [],called)
    def test_quick_interrupt_removes_runtime_after_confirmed_maven_stop(self):
        import backend_quick_runtime as quick
        project=self.root/'source';project.mkdir();(project/'pom.xml').write_text('<project/>');runtime=self.root/'runtime';runtime.mkdir();results=self.root/'results';results.mkdir()
        def interrupted(*args,**kwargs):kwargs['on_stopped']();raise KeyboardInterrupt()
        from types import SimpleNamespace
        with patch.object(quick,'run_maven',side_effect=interrupted):
            with self.assertRaises(KeyboardInterrupt):quick.execute(SimpleNamespace(offline=True),project,results,runtime,{'java':'/fixture/jdk/bin/java','mvn':'/fixture/bin/mvn'},{'OneTest':'com.campus.market.OneTest'})
        self.assertFalse(runtime.exists())
    def test_quick_unconfirmed_maven_stop_preserves_runtime(self):
        import backend_quick_runtime as quick
        project=self.root/'source';project.mkdir();(project/'pom.xml').write_text('<project/>');runtime=self.root/'runtime';runtime.mkdir();results=self.root/'results';results.mkdir()
        from types import SimpleNamespace
        with patch.object(quick,'run_maven',side_effect=RuntimeError('stop unconfirmed')):
            with self.assertRaisesRegex(RuntimeError,'unconfirmed'):quick.execute(SimpleNamespace(offline=True),project,results,runtime,{'java':'/fixture/jdk/bin/java','mvn':'/fixture/bin/mvn'},{'OneTest':'com.campus.market.OneTest'})
        self.assertTrue(runtime.exists())

    def test_runtime_diagnostics_keep_only_bounded_logs_and_no_credentials(self):
        runtime=self.root/'runtime';(runtime/'rabbitmq/logs').mkdir(parents=True)
        (runtime/'mysql.log').write_bytes(b'x'*140000)
        (runtime/'rabbitmq/startup.log').write_text('startup error')
        (runtime/'rabbitmq/logs/node.log').write_text('node diagnostic')
        (runtime/'rabbitmq/rabbitmq.conf').write_text('private password')
        (runtime/'rabbitmq/erlang.cookie').write_text('private cookie')
        results=self.root/'results';results.mkdir()
        self.assertEqual(3,preserve_runtime_logs(runtime,results));files=list((results/'runtime-logs').iterdir())
        self.assertEqual(131072,(results/'runtime-logs/mysql.log').stat().st_size)
        self.assertTrue(all(p.stat().st_mode&0o777==0o600 for p in files))
        self.assertFalse(any(b'private' in p.read_bytes() for p in files))

if __name__=='__main__':unittest.main()

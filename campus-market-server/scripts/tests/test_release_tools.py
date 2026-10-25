from pathlib import Path
import contextlib,io,json,shutil,subprocess,sys,tempfile,unittest,zipfile
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import build_release
import release_support as support

class ReleaseToolsTests(unittest.TestCase):
    def setUp(self):
        self.temporary=tempfile.TemporaryDirectory(prefix='campus-release-tools-');self.root=Path(self.temporary.name);self.addCleanup(self.temporary.cleanup)
    def jar(self,name='app.jar',extra=None,missing=None,manifest=None):
        files={'META-INF/MANIFEST.MF':manifest or 'Main-Class: org.springframework.boot.loader.launch.JarLauncher\r\nStart-Class: '+support.START_CLASS+'\r\n',
               'org/springframework/boot/loader/launch/JarLauncher.class':'fixture','BOOT-INF/classes/'+support.START_CLASS.replace('.','/')+'.class':'fixture','BOOT-INF/classes/application.properties':'fixture','BOOT-INF/lib/runtime.jar':'fixture','BOOT-INF/classes/application-production.properties':'fixture','META-INF/spring.factories':'org.springframework.boot.EnvironmentPostProcessor=com.campus.market.config.ProductionEnvironmentGuard','BOOT-INF/classes/com/campus/market/config/ProductionEnvironmentGuard.class':'fixture','BOOT-INF/classes/com/campus/market/order/TradeCapabilitiesController.class':'fixture'}
        files.update(extra or {})
        if missing:files.pop(missing)
        path=self.root/name
        with zipfile.ZipFile(path,'w') as z:
            for key,value in files.items():z.writestr(key,value)
        return path
    def bundle(self):
        folder=self.root/'bundle';folder.mkdir();(folder/'database').mkdir();(folder/'config').mkdir();shutil.copy2(self.jar(),folder/'app.jar')
        for name in support.PAYLOAD-{'app.jar'}:
            (folder/name).parent.mkdir(parents=True,exist_ok=True);(folder/name).write_text('fixture')
        manifest={'schemaVersion':1,'files':{name:support.sha256(folder/name) for name in support.PAYLOAD}}
        (folder/'manifest.json').write_text(json.dumps(manifest))
        (folder/'SHA256SUMS').write_text(''.join(support.sha256(folder/name)+'  '+name+'\n' for name in sorted(support.PAYLOAD|{'manifest.json'})))
        return folder
    def test_schema_is_explicit_order_and_missing_input_fails(self):
        project=self.root/'source';(project/'sql').mkdir(parents=True)
        for i,name in enumerate(support.SCHEMA_FILES):(project/'sql'/name).write_text('SELECT '+str(i)+';')
        text=support.assemble_schema(project);self.assertEqual(42,len(support.SCHEMA_FILES));self.assertLess(text.index('SELECT 0;'),text.index('SELECT 32;'))
        (project/'sql'/support.SCHEMA_FILES[0]).unlink()
        with self.assertRaisesRegex(RuntimeError,'schema'):support.assemble_schema(project)
    def test_executable_jar_manifest_and_dependencies_are_verified(self):
        result=support.inspect_jar(self.jar());self.assertEqual(support.START_CLASS,result['startClass']);self.assertEqual(1,result['runtimeLibraries'])
    def test_plain_jar_or_missing_launcher_is_rejected(self):
        with self.assertRaisesRegex(RuntimeError,'expected executable'):support.inspect_jar(self.jar(manifest='Main-Class: example.Main\n'))
        with self.assertRaisesRegex(RuntimeError,'missing launcher'):support.inspect_jar(self.jar(name='missing.jar',missing='org/springframework/boot/loader/launch/JarLauncher.class'))
    def test_test_libraries_and_test_classes_are_rejected(self):
        for name in ['BOOT-INF/lib/junit-jupiter.jar','BOOT-INF/lib/mockito-core.jar','BOOT-INF/lib/spring-test.jar','BOOT-INF/classes/com/campus/market/UnexpectedTest.class']:
            with self.subTest(name=name):
                with self.assertRaisesRegex(RuntimeError,'Test'):support.inspect_jar(self.jar(extra={name:'fixture'}))
    def test_manifest_continuation_lines_are_supported(self):
        manifest='Main-Class: org.springframework.boot.loader.launch.\r\n JarLauncher\r\nStart-Class: '+support.START_CLASS+'\r\n'
        self.assertEqual(support.START_CLASS,support.inspect_jar(self.jar(manifest=manifest))['startClass'])
    def test_valid_bundle_includes_deployment_and_verified_jar(self):
        result=support.verify_bundle(self.bundle());self.assertEqual('verified',result['status']);self.assertEqual(18,result['files'])
    def test_modified_payload_is_rejected(self):
        folder=self.bundle();(folder/'RUN.md').write_text('unexpected change')
        with self.assertRaisesRegex(RuntimeError,'checksum'):support.verify_bundle(folder)
    def test_missing_or_extra_files_are_rejected(self):
        folder=self.bundle();(folder/'unexpected.env').write_text('fixture')
        with self.assertRaisesRegex(RuntimeError,'file set'):support.verify_bundle(folder)
        (folder/'unexpected.env').unlink();(folder/'RUN.md').unlink()
        with self.assertRaisesRegex(RuntimeError,'file set'):support.verify_bundle(folder)
    def test_symlink_payload_is_rejected(self):
        folder=self.bundle();(folder/'RUN.md').unlink();(self.root/'external.md').write_text('fixture');(folder/'RUN.md').symlink_to(self.root/'external.md')
        with self.assertRaisesRegex(RuntimeError,'symlink'):support.verify_bundle(folder)
    def test_manifest_file_set_cannot_escape_release_root(self):
        folder=self.bundle();p=folder/'manifest.json';p.write_text(json.dumps({'schemaVersion':1,'files':{'../external':'fake'}}))
        with self.assertRaisesRegex(RuntimeError,'manifest'):support.verify_bundle(folder)
    def test_checksum_coverage_and_duplicate_lines_are_rejected(self):
        folder=self.bundle();p=folder/'SHA256SUMS';original=p.read_text();p.write_text(original+original.splitlines()[0]+'\n')
        with self.assertRaisesRegex(RuntimeError,'SHA256SUMS'):support.verify_bundle(folder)
        p.write_text('\n'.join(original.splitlines()[:-1]))
        with self.assertRaisesRegex(RuntimeError,'SHA256SUMS'):support.verify_bundle(folder)
    def test_preflight_never_creates_runtime_or_builds(self):
        project=self.root/'source';(project/'scripts').mkdir(parents=True);(project/'release').mkdir()
        for name in ['RUN.md','application.properties.example']:(project/'release'/name).write_text('fixture')
        (project/'release/deployment').mkdir()
        for name in support.DEPLOYMENT_FILES:(project/'release/deployment'/name).write_text('fixture')
        (project/'release/database').mkdir()
        for name in support.DATABASE_FILES:(project/'release/database'/name).write_text('fixture')
        for name in support.OPERATION_FILES:(project/'scripts'/name).write_text('fixture')
        with patch.object(build_release,'__file__',str(project/'scripts/build_release.py')),patch.object(sys,'argv',['build_release.py','--check']),patch.object(build_release,'locate_tools',return_value={}),patch.object(build_release,'discover_tests',return_value={'OneTest':'example.OneTest'}),patch.object(build_release,'assemble_schema',return_value='fixture'),patch.object(build_release.tempfile,'mkdtemp') as mkdtemp,contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0,build_release.main());mkdtemp.assert_not_called()
        self.assertFalse((project/'data').exists())
    def test_maven_cleanup_unconfirmed_retains_private_diagnostics(self):
        project=self.root/'source';(project/'scripts').mkdir(parents=True);(project/'release').mkdir()
        for name in ['RUN.md','application.properties.example']:(project/'release'/name).write_text('fixture')
        (project/'release/deployment').mkdir()
        for name in support.DEPLOYMENT_FILES:(project/'release/deployment'/name).write_text('fixture')
        (project/'release/database').mkdir()
        for name in support.DATABASE_FILES:(project/'release/database'/name).write_text('fixture')
        for name in support.OPERATION_FILES:(project/'scripts'/name).write_text('fixture')
        runtime=self.root/'runtime';runtime.mkdir()
        with patch.object(build_release,'__file__',str(project/'scripts/build_release.py')),patch.object(sys,'argv',['build_release.py']),patch.object(build_release,'locate_tools',return_value={'java':'/jdk/bin/java','mvn':'/bin/mvn'}),patch.object(build_release,'discover_tests',return_value={'OneTest':'example.OneTest'}),patch.object(build_release,'assemble_schema',return_value='fixture'),patch.object(build_release,'snapshot_source',return_value='fixture-sha'),patch.object(build_release.tempfile,'mkdtemp',return_value=str(runtime)),patch.object(build_release,'run_maven',side_effect=RuntimeError('cleanup not confirmed')),contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(1,build_release.main())
        summary=json.loads((project/'data/releases/latest.json').read_text());self.assertEqual('failed',summary['status']);self.assertFalse(summary['temporaryBuildRemoved']);self.assertTrue(runtime.exists());self.assertFalse('archive' in summary)

if __name__=='__main__':unittest.main()

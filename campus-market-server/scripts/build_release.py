#!/usr/bin/env python3
"""Build a checksummed Spring Boot release after the quick Java test suite passes."""
from pathlib import Path
import argparse,datetime,fcntl,json,os,shutil,signal,sys,tempfile,time,uuid,zipfile
from backend_test_support import locate_tools,discover_tests,snapshot_source,isolated_environment,run_maven,collect_results,write_json
from release_support import assemble_schema,inspect_jar,verify_bundle,sha256,PAYLOAD,SCHEMA_FILES,DEPLOYMENT_FILES,DATABASE_FILES,OPERATION_FILES

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    mode=parser.add_mutually_exclusive_group();mode.add_argument('--check',action='store_true');mode.add_argument('--verify',type=Path)
    parser.add_argument('--offline',action='store_true');args=parser.parse_args()
    if args.verify:print(json.dumps(verify_bundle(args.verify),ensure_ascii=False,indent=2));return 0
    project=Path(__file__).resolve().parents[1]
    if sys.version_info<(3,9) or os.name!='posix':raise RuntimeError('Build requires Python3.9+ on macOS/Linux')
    tools=locate_tools(os.environ,'quick');expected=discover_tests(project,'quick');assemble_schema(project)
    for name in ['application.properties.example','RUN.md']:
        if not (project/'release'/name).is_file():raise RuntimeError('Missing release template: '+name)
    for name in DEPLOYMENT_FILES:
        if not (project/'release/deployment'/name).is_file():raise RuntimeError('Missing deployment template: '+name)
    for folder,names in [('release/database',DATABASE_FILES),('scripts',OPERATION_FILES)]:
        for name in names:
            if not (project/folder/name).is_file():raise RuntimeError('Missing database operation payload: '+name)
    if args.check:print(json.dumps({'preflight':'PASS','java':17,'testClasses':len(expected),'schemaFiles':len(SCHEMA_FILES),'middlewareStarted':False},indent=2));return 0
    os.umask(0o077);release_root=project/'data/releases';release_root.mkdir(parents=True,exist_ok=True,mode=0o700)
    with (release_root/'.build.lock').open('a') as lock:
        try:fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError:raise RuntimeError('Another release build is running')
        run_id=time.strftime('%Y%m%d-%H%M%S')+'-'+uuid.uuid4().hex[:8];results=release_root/run_id;results.mkdir(mode=0o700)
        root=Path(tempfile.mkdtemp(prefix='campus-release-'));root.chmod(0o700);record={'runId':run_id,'status':'running','java':17,'testSuite':'quick','runtimeDirectory':str(root),'middlewareStarted':False}
        write_json(results/'summary.json',record);print('Private release results: '+str(results),flush=True)
        started=time.monotonic();build_started=False;build_stopped=False;code=1
        def interrupted(signum,frame):raise KeyboardInterrupt('Termination requested')
        def stopped():
            nonlocal build_stopped
            build_stopped=True
        previous=signal.signal(signal.SIGTERM,interrupted)
        try:
            build=root/'project';record['sourceSha256']=snapshot_source(project,build)
            env=isolated_environment(os.environ);env['JAVA_HOME']=str(Path(tools['java']).parents[1]);env['PATH']=os.pathsep.join([str(Path(tools['java']).parent),str(Path(tools['mvn']).parent),env.get('PATH',os.defpath)])
            command=[tools['mvn'],*(['-o'] if args.offline else []),'-q','-f',str(build/'pom.xml'),'-Dtest='+','.join(sorted(expected)),'clean','package']
            build_started=True;exit_code=run_maven(command,env,results/'maven.log',on_stopped=stopped)
            reports=build/'target/surefire-reports'
            if reports.is_dir():
                (results/'junit').mkdir(mode=0o700)
                for p in reports.glob('TEST-*.xml'):shutil.copy2(p,results/'junit'/p.name)
            if exit_code:raise RuntimeError('Maven package failed; see private maven.log')
            record['junit']=collect_results(results/'junit',expected)
            jars=list((build/'target').glob('*.jar'))
            if len(jars)!=1:raise RuntimeError('Expected exactly one executable JAR')
            record['jar']=inspect_jar(jars[0]);bundle=results/'.bundle.tmp';bundle.mkdir(mode=0o700)
            (bundle/'database').mkdir();(bundle/'config').mkdir();shutil.copy2(jars[0],bundle/'app.jar')
            (bundle/'database/init.sql').write_text(assemble_schema(build));shutil.copy2(build/'release/application.properties.example',bundle/'config/application.properties.example');shutil.copy2(build/'release/RUN.md',bundle/'RUN.md')
            (bundle/'deployment').mkdir()
            for name in DEPLOYMENT_FILES:shutil.copy2(build/'release/deployment'/name,bundle/'deployment'/name)
            (bundle/'scripts').mkdir()
            for name in OPERATION_FILES:shutil.copy2(build/'scripts'/name,bundle/'scripts'/name)
            for name in DATABASE_FILES:shutil.copy2(build/'release/database'/name,bundle/'database'/name)
            manifest={'schemaVersion':1,'application':'campus-market-server','releaseId':run_id,'createdAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'java':17,'sourceSha256':record['sourceSha256'],'tests':record['junit'],'schemaFiles':len(SCHEMA_FILES),'files':{name:sha256(bundle/name) for name in sorted(PAYLOAD)}}
            write_json(bundle/'manifest.json',manifest)
            (bundle/'SHA256SUMS').write_text(''.join(sha256(bundle/name)+'  '+name+'\n' for name in sorted(PAYLOAD|{'manifest.json'})))
            record['verification']=verify_bundle(bundle);bundle.rename(results/'bundle')
            archive=results/'release.zip.tmp'
            with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as z:
                for p in sorted((results/'bundle').rglob('*')):
                    if p.is_file():z.write(p,'campus-market-server/'+p.relative_to(results/'bundle').as_posix())
            archive.rename(results/'release.zip');record.update(status='passed',bundleDirectory=str(results/'bundle'),archive=str(results/'release.zip'),archiveSha256=sha256(results/'release.zip'));code=0
        except (Exception,KeyboardInterrupt) as error:record.update(status='interrupted' if isinstance(error,KeyboardInterrupt) else 'failed',reason=str(error)[:1000] or type(error).__name__)
        finally:
            if not build_started or build_stopped:
                try:shutil.rmtree(root)
                except OSError as error:record.update(status='failed',reason='Temporary build cleanup failed: '+str(error));code=1
            record['temporaryBuildRemoved']=not root.exists();record['durationSeconds']=round(time.monotonic()-started,2)
            if not record['temporaryBuildRemoved']:record['status']='failed';code=1
            write_json(results/'summary.json',record);write_json(release_root/'latest.json',record);signal.signal(signal.SIGTERM,previous)
        print(record['status'].upper()+': '+str(record.get('archive',record.get('reason'))),flush=True);return code

if __name__=='__main__':
    try:sys.exit(main())
    except Exception as error:print('Release preflight failed: '+str(error),file=sys.stderr);sys.exit(2)

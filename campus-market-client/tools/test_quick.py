#!/usr/bin/env python3
"""Run client/desktop Node tests in private temporary source copies; no npm install."""
from pathlib import Path
import argparse,fcntl,os,shutil,signal,sys,tempfile,time,uuid
from quick_test_support import locate_node,discover,snapshot,isolated_environment,run_node,collect_results,write_json,CleanupUnconfirmed

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--suite',choices=['all','client','desktop'],default='all')
    parser.add_argument('--check',action='store_true')
    args=parser.parse_args()
    if sys.version_info<(3,9) or os.name!='posix':raise RuntimeError('Requires Python3.9+ on macOS/Linux')
    client=Path(__file__).resolve().parents[1];node,version=locate_node(os.environ);targets=discover(client,args.suite)
    plan={label:files for label,(project,files) in targets.items()}
    if args.check:
        import json
        print(json.dumps({'preflight':'PASS','node':version,'testFiles':plan,'npmInstallRequired':False,'middlewareStarted':False},ensure_ascii=False,indent=2));return 0
    os.umask(0o077)
    result_root=client/'unpackage/quick-tests';result_root.mkdir(parents=True,exist_ok=True,mode=0o700)
    with (result_root/'.run.lock').open('a') as lock:
        try:fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError:raise RuntimeError('Another client/desktop quick verification is running')
        run_id=time.strftime('%Y%m%d-%H%M%S')+'-'+uuid.uuid4().hex[:8]
        results=result_root/run_id;results.mkdir(mode=0o700);(results/'junit').mkdir(mode=0o700)
        runtime=Path(tempfile.mkdtemp(prefix='campus-node-quick-'));runtime.chmod(0o700)
        record={'runId':run_id,'suite':args.suite,'status':'running','node':version,'testFiles':plan,'runtimeDirectory':str(runtime),'npmInstallRequired':False,'middlewareStarted':False}
        write_json(results/'summary.json',record);print('Private results: '+str(results),flush=True)
        started=time.monotonic();safe_to_remove=True;code=1
        def interrupted(signum,frame):raise KeyboardInterrupt('Termination requested')
        previous=signal.signal(signal.SIGTERM,interrupted)
        try:
            tmp=runtime/'tmp';tmp.mkdir();env=isolated_environment(os.environ,tmp,node);expected=[];hashes={}
            for label,(project,files) in targets.items():
                build=runtime/label;hashes[label]=snapshot(project,build)
                for number,name in enumerate(files):
                    report=f'{label}-{number:02d}.xml'
                    command=[node,'--test','--test-reporter=junit','--test-reporter-destination='+str(results/'junit'/report),str(build/name)]
                    exit_code=run_node(command,env,build,results/f'{label}-{number:02d}.log')
                    expected.append({'suite':label,'file':name,'report':report,'exitCode':exit_code})
            record['sourceSha256']=hashes;checked=collect_results(results,expected);record.update({k:v for k,v in checked.items() if k!='passed'})
            record['status']='passed' if checked['passed'] else 'failed';code=0 if checked['passed'] else 1
        except CleanupUnconfirmed as error:safe_to_remove=False;record.update(status='failed',reason=str(error))
        except (Exception,KeyboardInterrupt) as error:record.update(status='interrupted' if isinstance(error,KeyboardInterrupt) else 'failed',reason=str(error)[:1000] or type(error).__name__)
        finally:
            if safe_to_remove:
                try:shutil.rmtree(runtime)
                except OSError as error:record.update(status='failed',reason='Runtime cleanup failed: '+str(error));code=1
            record['temporaryRuntimeRemoved']=not runtime.exists();record['durationSeconds']=round(time.monotonic()-started,2)
            if not record['temporaryRuntimeRemoved']:record['status']='failed';code=1
            write_json(results/'summary.json',record);write_json(result_root/'latest.json',record);signal.signal(signal.SIGTERM,previous)
        print(record['status'].upper()+': '+str(record.get('junit',record.get('reason'))),flush=True);return code

if __name__=='__main__':
    try:sys.exit(main())
    except Exception as error:print('Preflight failed: '+str(error),file=sys.stderr);sys.exit(2)

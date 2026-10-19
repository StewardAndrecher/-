#!/usr/bin/env python3
# coding: utf-8
"""Run the project's Java tests using disposable MySQL, two Redis instances and RabbitMQ."""
from pathlib import Path
import argparse,fcntl,json,os,shutil,signal,sys,tempfile,time,uuid
from backend_test_support import discover_tests,locate_tools,write_json


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--check',action='store_true',help='Check dependencies and discovered test classes; start no services')
    p.add_argument('--offline',action='store_true',help='Use only locally cached Maven dependencies')
    p.add_argument('--suite',choices=['all','docs','quick'],default='all')
    args=p.parse_args();project=Path(__file__).resolve().parents[1]
    if sys.version_info<(3,9) or os.name!='posix':raise RuntimeError('Requires Python 3.9+ on macOS/Linux')
    tools=locate_tools(os.environ,args.suite);expected=discover_tests(project,args.suite)
    for name in ([] if args.suite=='quick' else ['lesson15-users.sql','lesson19-login-sessions.sql']):
        if not (project/'sql'/name).is_file():raise RuntimeError('Missing repository schema: '+name)
    if args.check:
        print(json.dumps({'preflight':'PASS','suite':args.suite,'testClasses':len(expected),'javaMajor':17,'dependencies':sorted(tools),'servicesStarted':False},ensure_ascii=False,indent=2));return 0
    os.umask(0o077)
    result_root=project/'data/test-results';result_root.mkdir(parents=True,exist_ok=True,mode=0o700);result_root.chmod(0o700)
    with (result_root/'.run.lock').open('a') as lock:
        try:fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError:raise RuntimeError('Another backend verification is running for this project')
        run_id=time.strftime('%Y%m%d-%H%M%S')+'-'+uuid.uuid4().hex[:8]
        result_dir=result_root/run_id;result_dir.mkdir(mode=0o700)
        # Historical upload tests require this exact private-directory identity.
        root=Path(tempfile.mkdtemp(prefix='campus-lesson24-'));root.chmod(0o700)
        record={'runId':run_id,'suite':args.suite,'status':'running','expectedTestClasses':len(expected),'requiresMiddleware':args.suite!='quick','buildUsesTemporarySourceCopy':True,'runtimeDirectory':str(root)}
        write_json(result_dir/'summary.json',record)
        print('Starting isolated backend verification: '+args.suite,flush=True)
        print('Private results: '+str(result_dir),flush=True)
        def interrupted(signum,frame):raise KeyboardInterrupt('Termination requested')
        previous_term=signal.signal(signal.SIGTERM,interrupted)
        started=time.monotonic();code=1;execution_entered=False
        try:
            if args.suite=='quick':
                from backend_quick_runtime import execute
            else:
                from backend_test_runtime import execute
            execution_entered=True
            record['junit']=execute(args,project,result_dir,root,tools,expected)
            record['sourceSha256']=record['junit'].pop('sourceSha256')
            record['status']='passed';code=0
        except (Exception,KeyboardInterrupt) as error:
            record['status']='interrupted' if isinstance(error,KeyboardInterrupt) else 'failed'
            record['reason']=str(error)[:1000] or type(error).__name__
        finally:
            signal.signal(signal.SIGTERM,previous_term)
            # After entering execute, only its verified cleanup may remove the runtime.
            # Never infer process ownership/termination merely from absent PID files.
            if not execution_entered and root.exists():shutil.rmtree(root)
            record['temporaryRuntimeRemoved']=not root.exists();record['durationSeconds']=round(time.monotonic()-started,2)
            if not record['temporaryRuntimeRemoved']:record['status']='failed';code=1
            write_json(result_dir/'summary.json',record)
            write_json(result_root/'latest.json',record)
        print(json.dumps(record,ensure_ascii=False,indent=2),flush=True)
        return code

if __name__=='__main__':
    try:sys.exit(main())
    except Exception as error:print('Preflight failed: '+str(error),file=sys.stderr);sys.exit(2)

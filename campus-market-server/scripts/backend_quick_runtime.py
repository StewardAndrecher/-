# coding: utf-8
"""Compile and verify an explicit pure-test suite without starting middleware."""
from pathlib import Path
import os,shutil
from backend_test_support import isolated_environment,snapshot_source,run_maven,collect_results


def execute(options,project,result_dir,root,tools,expected):
    started=False;stopped=False
    try:
        build=root/'project';source_sha256=snapshot_source(project,build)
        env=isolated_environment(os.environ)
        env['JAVA_HOME']=str(Path(tools['java']).parents[1])
        env['PATH']=os.pathsep.join([str(Path(tools['java']).parent),str(Path(tools['mvn']).parent),env.get('PATH',os.defpath)])
        command=[tools['mvn'],*(['-o'] if options.offline else []),'-q','-f',str(build/'pom.xml'),'-Dtest='+','.join(sorted(expected)),'clean','test']
        started=True
        def confirmed_stopped():
            nonlocal stopped
            stopped=True
        code=run_maven(command,env,result_dir/'maven.log',on_stopped=confirmed_stopped)
        reports=build/'target/surefire-reports'
        if reports.is_dir():
            saved=result_dir/'junit';saved.mkdir(mode=0o700)
            for report in reports.glob('TEST-*.xml'):
                shutil.copy2(report,saved/report.name);(saved/report.name).chmod(0o600)
        if code:raise RuntimeError('Maven failed with exit code '+str(code)+'; see private maven.log')
        summary=collect_results(result_dir/'junit',expected)
        summary['sourceSha256']=source_sha256
        return summary
    finally:
        if not started or stopped:shutil.rmtree(root)

"""Shared helpers for dependency-free client/desktop Node verification."""
from pathlib import Path
import hashlib,json,os,re,shutil,signal,subprocess,xml.etree.ElementTree as ET

IGNORED={'.git','.idea','.vscode','.hbuilderx','node_modules','unpackage','dist','web','.web-history','data','target','__pycache__','.DS_Store'}
ENV_KEYS={'PATH','HOME','USER','LOGNAME','LANG','LC_ALL','LC_CTYPE','SHELL','__CF_USER_TEXT_ENCODING'}

class CleanupUnconfirmed(RuntimeError):pass

def isolated_environment(source,tmp,node):
    env={k:v for k,v in source.items() if k in ENV_KEYS}
    env.update(TMPDIR=str(tmp),TMP=str(tmp),TEMP=str(tmp))
    env['PATH']=str(Path(node).parent)+os.pathsep+env.get('PATH',os.defpath)
    return env

def locate_node(source):
    value=source.get('NODE_BIN') or shutil.which('node',path=source.get('PATH',os.defpath))
    if not value or not Path(value).is_file() or not os.access(value,os.X_OK):raise RuntimeError('Node executable not found; set NODE_BIN')
    value=str(Path(value).resolve())
    version=subprocess.run([value,'--version'],env={k:v for k,v in source.items() if k in ENV_KEYS},capture_output=True,text=True,timeout=10)
    match=re.fullmatch(r'v(\d+)\.(\d+)\.(\d+)\s*',version.stdout)
    if version.returncode or not match or tuple(map(int,match.groups()))<(22,12,0):raise RuntimeError('Node.js 22.12.0 or newer is required')
    return value,version.stdout.strip()

def discover(client,suite):
    if suite not in {'all','client','desktop'}:raise ValueError('Unknown suite: '+suite)
    targets={}
    for label,project,folder in [('client',Path(client),'tools'),('desktop',Path(client).parent/'campus-market-desktop','test')]:
        if suite not in {'all',label}:continue
        if project.is_symlink():raise RuntimeError('Project symlinks are not accepted')
        files=sorted((project/folder).glob('*.test.mjs'))
        if not files:raise RuntimeError('No '+label+' tests found in '+str(project/folder))
        if any(p.is_symlink() for p in files):raise RuntimeError('Test symlinks are not accepted')
        targets[label]=(project,[p.relative_to(project).as_posix() for p in files])
    return targets

def snapshot(project,destination):
    project=Path(project);destination=Path(destination)
    def ignored(name):return name in IGNORED or name.startswith('.web-staging-') or name=='.env' or name.startswith('.env.')
    for directory,dirs,files in os.walk(project,followlinks=False):
        dirs[:]=[name for name in dirs if not ignored(name)]
        for name in dirs+files:
            if not ignored(name) and (Path(directory)/name).is_symlink():raise RuntimeError('Source symlink rejected')
    shutil.copytree(project,destination,ignore=lambda directory,names:[name for name in names if ignored(name)])
    digest=hashlib.sha256()
    for p in sorted(destination.rglob('*')):
        if p.is_file():digest.update(p.relative_to(destination).as_posix().encode());digest.update(b'\0');digest.update(hashlib.sha256(p.read_bytes()).digest())
    return digest.hexdigest()

def stop_owned_group(process):
    try:
        try:os.killpg(process.pid,signal.SIGTERM)
        except ProcessLookupError:process.wait(timeout=2);return
        try:process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            try:os.killpg(process.pid,signal.SIGKILL)
            except ProcessLookupError:pass
            process.wait(timeout=5)
        try:os.killpg(process.pid,signal.SIGKILL)
        except ProcessLookupError:pass
    except Exception as error:raise CleanupUnconfirmed('Unable to confirm owned Node process cleanup') from error

def run_node(command,env,cwd,log_path,timeout=180):
    with Path(log_path).open('wb') as log:
        process=subprocess.Popen(command,cwd=cwd,env=env,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
        try:return process.wait(timeout=timeout)
        finally:stop_owned_group(process)

def parse_report(path,test_file=None):
    root=ET.parse(path).getroot()
    if root.tag not in {'testsuites','testsuite'}:raise RuntimeError('Unexpected JUnit root')
    cases=list(root.iter('testcase'))
    if not cases:raise RuntimeError('JUnit report has no test cases')
    if test_file and any(c.get('name')==test_file or c.get('name','').endswith('/'+test_file) for c in cases):raise RuntimeError('Node reported a file placeholder instead of declared test cases')
    return {'tests':len(cases),'failures':sum(c.find('failure') is not None for c in cases),
            'errors':sum(c.find('error') is not None for c in cases),
            'skipped':sum(c.find('skipped') is not None for c in cases)}

def collect_results(result_dir,expected):
    result_dir=Path(result_dir)
    if set(p.name for p in (result_dir/'junit').glob('*.xml'))!={i['report'] for i in expected}:raise RuntimeError('JUnit file coverage mismatch')
    totals=dict(tests=0,failures=0,errors=0,skipped=0);reports=[];passed=True
    for item in expected:
        entry=dict(item)
        try:
            counts=parse_report(result_dir/'junit'/item['report'],item.get('file'));entry['junit']=counts
            for key in totals:totals[key]+=counts[key]
            if item['exitCode']!=0 or any(counts[k] for k in ['failures','errors','skipped']):passed=False
        except (ET.ParseError,OSError,RuntimeError) as error:entry['invalidReport']=str(error);passed=False
        reports.append(entry)
    if not expected:raise RuntimeError('No expected test files')
    return {'passed':passed,'junit':dict(totals,files=len(reports)),'reports':reports}

def write_json(path,value):
    path=Path(path);temporary=path.with_name(path.name+'.tmp')
    temporary.write_text(json.dumps(value,ensure_ascii=False,indent=2));temporary.chmod(0o600);os.replace(temporary,path)

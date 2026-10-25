# coding: utf-8
"""Helpers for the self-contained backend verification command; standard library only."""
from pathlib import Path
import hashlib,json,os,re,shutil,signal,subprocess,time,xml.etree.ElementTree as ET

DOCS_CLASSES={'ApiDocsAccessFilterTest','ApiContractIntegrationTest','ApiDocsDisabledIntegrationTest'}
QUICK_CLASSES={'ProductionEnvironmentGuardTest','TradeCapabilitiesControllerTest','RegistrationMetricsTest','NotificationMetricsTest','NotificationMonitorTest','PasswordConfigTest','RequestCorrelationFilterTest','ApiDocsAccessFilterTest','OrderMqLogTest','OrderMqMetricsTest','OrderMqMonitorTest','OrderTimeoutSenderTest','MetricsControllerTest'}
IGNORED={'.git','.idea','.vscode','target','data','node_modules','__pycache__','.DS_Store'}
ENV_KEYS={'PATH','HOME','USER','LOGNAME','SHELL','JAVA_HOME','LANG','LC_ALL','LC_CTYPE','TMPDIR','TMP','TEMP','HOMEBREW_PREFIX','__CF_USER_TEXT_ENCODING'}

def isolated_environment(source):
    """Do not inherit Spring, database, proxy, Maven/JVM injection or production settings."""
    return {k:v for k,v in source.items() if k in ENV_KEYS}

def discover_tests(project,suite='all'):
    if suite not in {'all','docs','quick'}:raise ValueError('Unknown suite: '+suite)
    selected=DOCS_CLASSES if suite=='docs' else QUICK_CLASSES if suite=='quick' else None
    found={}
    for p in sorted((Path(project)/'src/test/java').rglob('*.java')):
        if not (p.stem.endswith('Test') or p.stem.endswith('Tests')):continue
        if selected is not None and p.stem not in selected:continue
        if suite=='quick' and re.search(r'@(?:[\w]+[.])*SpringBootTest\b',p.read_text()):raise RuntimeError('Quick tests must not start a full Spring Boot context: '+p.name)
        match=re.search(r'^package\s+([\w.]+)\s*;',p.read_text(),re.M)
        if not match or not match.group(1).startswith('com.campus.market'):raise RuntimeError('Unexpected test package: '+p.name)
        if p.stem in found:raise RuntimeError('Duplicate test class name: '+p.stem)
        found[p.stem]=match.group(1)+'.'+p.stem
    if not found:raise RuntimeError('No test classes discovered')
    if selected is not None and set(found)!=selected:raise RuntimeError('The '+suite+' suite is incomplete')
    return found

def snapshot_source(project,destination):
    """Compile in a disposable copy so an active IDEA backend keeps its target/classes."""
    project=Path(project);destination=Path(destination)
    if destination.exists():raise RuntimeError('Build snapshot already exists')
    for directory,dirs,files in os.walk(project,followlinks=False):
        dirs[:]=[name for name in dirs if name not in IGNORED]
        for name in dirs+files:
            p=Path(directory)/name
            if name not in IGNORED and p.is_symlink():raise RuntimeError('Unexpected source symlink: '+str(p.relative_to(project)))
    shutil.copytree(project,destination,ignore=shutil.ignore_patterns(*IGNORED))
    digest=hashlib.sha256()
    for p in sorted(destination.rglob('*')):
        if p.is_file():digest.update(p.relative_to(destination).as_posix().encode());digest.update(b'\0');digest.update(hashlib.sha256(p.read_bytes()).digest())
    return digest.hexdigest()

def locate_tools(source,suite='all'):
    if suite not in {'all','docs','quick'}:raise ValueError('Unknown suite: '+suite)
    search=source.get('PATH',os.defpath)
    def tool(name,override=None,relative=None):
        value=override or shutil.which(name,path=search)
        if not value and relative:
            value=next((str(Path(base)/relative) for base in ['/opt/homebrew','/usr/local'] if (Path(base)/relative).is_file()),None)
        if not value or not Path(value).is_file() or not os.access(value,os.X_OK):raise RuntimeError('Missing executable: '+name)
        return str(Path(value).resolve())
    java_home=source.get('JAVA_HOME');java=tool('java',str(Path(java_home)/'bin/java') if java_home else None)
    version=subprocess.run([java,'-version'],env=isolated_environment(source),capture_output=True,text=True,timeout=10)
    if version.returncode or not re.search(r'version "17(?:[.\"])',version.stderr+version.stdout):raise RuntimeError('This verified test environment requires JDK 17; set JAVA_HOME')
    if suite=='quick':return {'java':java,'mvn':tool('mvn')}
    mq_dir=source.get('RABBITMQ_SBIN')
    result={'java':java,'mvn':tool('mvn'),
        'mysql':tool('mysql',relative='opt/mysql/bin/mysql'),'mysqld':tool('mysqld',relative='opt/mysql/bin/mysqld'),'mysqldump':tool('mysqldump',relative='opt/mysql/bin/mysqldump'),
        'redis-server':tool('redis-server',source.get('REDIS_SERVER_BIN'),relative='opt/redis/bin/redis-server'),
        'rabbitmq-server':tool('rabbitmq-server',str(Path(mq_dir)/'rabbitmq-server') if mq_dir else None,relative='opt/rabbitmq/sbin/rabbitmq-server'),
        'rabbitmqctl':tool('rabbitmqctl',str(Path(mq_dir)/'rabbitmqctl') if mq_dir else None,relative='opt/rabbitmq/sbin/rabbitmqctl')}
    if Path(result['rabbitmq-server']).parent!=Path(result['rabbitmqctl']).parent:raise RuntimeError('RabbitMQ server and CLI must use the same installation')
    return result

def stop_owned_group(process):
    if process is None:return
    # All callers create the child with start_new_session=True. Never signal a shared service.
    try:os.killpg(process.pid,signal.SIGTERM)
    except ProcessLookupError:
        process.wait(timeout=2);return
    try:process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        try:os.killpg(process.pid,signal.SIGKILL)
        except ProcessLookupError:pass
        process.wait(timeout=5)
    try:os.killpg(process.pid,signal.SIGKILL)
    except ProcessLookupError:pass

def run_maven(command,env,log_path,on_stopped=None):
    with Path(log_path).open('w') as log:
        process=subprocess.Popen(command,env=env,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
        try:return process.wait(timeout=1200)
        finally:
            stop_owned_group(process)
            if on_stopped is not None:on_stopped()

def collect_results(folder,expected):
    reports={};totals=dict(tests=0,failures=0,errors=0,skipped=0)
    for p in sorted(Path(folder).glob('TEST-*.xml')):
        root=ET.parse(p).getroot();name=root.get('name')
        if root.tag!='testsuite' or name in reports:raise RuntimeError('Invalid or duplicate JUnit report')
        counts={key:int(root.get(key,'-1')) for key in totals}
        if any(v<0 for v in counts.values()) or counts['tests']!=len(root.findall('testcase')):raise RuntimeError('Invalid JUnit test counts: '+str(name))
        if sum(counts[k] for k in ['failures','errors','skipped'])>counts['tests']:raise RuntimeError('Invalid JUnit outcomes')
        reports[name]=counts
        for key in totals:totals[key]+=counts[key]
    if set(reports)!=set(expected.values()):raise RuntimeError('JUnit report coverage mismatch; missing='+','.join(sorted(set(expected.values())-set(reports)))+'; unexpected='+','.join(sorted(set(reports)-set(expected.values()))))
    if totals['tests']==0 or any(c['tests']==0 for c in reports.values()):raise RuntimeError('Empty JUnit suite')
    if any(totals[k] for k in ['failures','errors','skipped']):raise RuntimeError('JUnit failure/error/skip: '+json.dumps(totals))
    return dict(totals,suites=len(reports))

def write_json(path,value):
    path=Path(path);temporary=path.with_name(path.name+'.tmp')
    with temporary.open('w',encoding='utf-8') as f:json.dump(value,f,ensure_ascii=False,indent=2)
    temporary.chmod(0o600);os.replace(temporary,path)


def preserve_runtime_logs(root,result_dir):
    """Keep bounded startup diagnostics privately; never copy config, cookies or data stores."""
    root=Path(root);logs=[root/name for name in ['mysql.log','redis.log','login-redis.log','rabbitmq/startup.log']]
    logs.extend((root/'rabbitmq/logs').glob('*.log'))
    destination=Path(result_dir)/'runtime-logs';count=0
    for source in logs:
        if source.is_symlink() or not source.is_file():continue
        destination.mkdir(mode=0o700,exist_ok=True)
        target=destination/source.relative_to(root).as_posix().replace('/','__')
        with source.open('rb') as original:
            original.seek(max(0,source.stat().st_size-131072));tail=original.read()
        target.write_bytes(tail);target.chmod(0o600);count+=1
    return count

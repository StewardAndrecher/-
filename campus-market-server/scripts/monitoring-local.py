#!/usr/bin/env python3
"""本项目独立的本机Prometheus；配置、凭证和时序数据不提交Git。"""
from pathlib import Path
import argparse,json,os,secrets,shutil,signal,socket,subprocess,time,urllib.request,urllib.parse,xml.etree.ElementTree as ET

root=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('action',choices=['init','configure-idea','start','status','query','stop'])
p.add_argument('--port',type=int,default=9090);p.add_argument('--api-port',type=int,default=8080)
p.add_argument('--alertmanager-port',type=int,default=9093)
p.add_argument('--expr',default='up{job="campus-market"}')
args=p.parse_args()
if not all(1024<=v<=65535 for v in [args.port,args.api_port,args.alertmanager_port]):raise SystemExit('端口必须为1024～65535')
runtime=Path(os.environ.get('MONITORING_DATA_DIR',root/'data/monitoring')).resolve();runtime.mkdir(parents=True,exist_ok=True,mode=0o700);runtime.chmod(0o700)
token_file=runtime/'token'
if not token_file.exists():token_file.write_text(secrets.token_hex(32));token_file.chmod(0o600)
token=token_file.read_text().strip()
if len(token)!=64 or any(c not in '0123456789abcdef' for c in token):raise SystemExit('监控凭证格式无效，未修改现有文件')
values={'MONITORING_ENABLED':'true','MONITORING_TOKEN':token}
env_file=runtime/'client-env.json';env_file.write_text(json.dumps(values));env_file.chmod(0o600)
http=urllib.request.build_opener(urllib.request.ProxyHandler({}))
state_file=runtime/'process.json'
def state():return json.loads(state_file.read_text()) if state_file.exists() else None
def owned(record):
 if not record:return False
 result=subprocess.run(['ps','-p',str(record['pid']),'-o','command='],capture_output=True)
 command=result.stdout.decode('utf-8',errors='replace')
 return result.returncode==0 and record['binary'] in command and '--config.file='+str(runtime/'prometheus.yml') in command
def request(path):
 record=state()
 if not owned(record):raise SystemExit('本项目Prometheus未运行')
 with http.open('http://127.0.0.1:'+str(record['port'])+path,timeout=5) as response:return response.read()

if args.action=='init':
 print('已生成独立监控凭证：data/monitoring；未输出Token。')
elif args.action=='configure-idea':
 file=root/'.idea/workspace.xml'
 if not file.exists():raise SystemExit('请先建立IDEA Spring Boot运行配置')
 backup=runtime/('idea-workspace-before-'+time.strftime('%Y%m%d-%H%M%S')+'.xml');shutil.copy2(file,backup);backup.chmod(0o600)
 tree=ET.parse(file);config=next(c for c in tree.getroot().iter('configuration') if c.get('name')=='CampusMarketServerApplication' and c.get('type')=='SpringBootApplicationConfigurationType')
 envs=config.find('envs')
 if envs is None:envs=ET.SubElement(config,'envs')
 for key,value in values.items():
  entry=next((e for e in envs.findall('env') if e.get('name')==key),None)
  if entry is None:entry=ET.SubElement(envs,'env',{'name':key})
  entry.set('value',value)
 tree.write(file,encoding='UTF-8',xml_declaration=True)
 print('已备份并更新本机IDEA监控配置；未输出Token。')
elif args.action=='start':
 record=state()
 if owned(record):print('本项目Prometheus已运行于127.0.0.1:'+str(record['port']));raise SystemExit(0)
 binary=os.environ.get('PROMETHEUS_BIN') or shutil.which('prometheus') or '/opt/homebrew/opt/prometheus/bin/prometheus'
 binary=str(Path(binary).resolve())
 if not Path(binary).is_file():raise SystemExit('请安装Prometheus，或设置PROMETHEUS_BIN')
 with socket.socket() as probe:
  probe.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
  try:probe.bind(('127.0.0.1',args.port))
  except OSError:raise SystemExit('监听端口已被占用，未停止任何程序')
 config=runtime/'prometheus.yml'
 config.write_text('''global:
  scrape_interval: 5s
  scrape_timeout: 3s
  evaluation_interval: 5s
rule_files:
  - '''+json.dumps(str(root/'monitoring/prometheus-alerts.yml'))+'''
alerting:
  alertmanagers:
    - static_configs:
        - targets: ['127.0.0.1:'''+str(args.alertmanager_port)+'''']
scrape_configs:
  - job_name: campus-market
    metrics_path: /api/ops/metrics
    authorization:
      type: Bearer
      credentials_file: '''+json.dumps(str(token_file))+'''
    static_configs:
      - targets: ['127.0.0.1:'''+str(args.api_port)+'''']
''');config.chmod(0o600)
 command=[binary,'--config.file='+str(config),'--storage.tsdb.path='+str(runtime/'tsdb'),'--storage.tsdb.retention.time=7d','--storage.tsdb.retention.size=256MB','--web.listen-address=127.0.0.1:'+str(args.port)]
 with (runtime/'prometheus.log').open('ab') as log:process=subprocess.Popen(command,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
 record={'pid':process.pid,'binary':binary,'port':args.port,'apiPort':args.api_port,'alertmanagerPort':args.alertmanager_port};state_file.write_text(json.dumps(record));state_file.chmod(0o600)
 for _ in range(100):
  if process.poll() is not None:raise SystemExit('启动失败，请检查data/monitoring/prometheus.log')
  try:
   if b'Ready' in request('/-/ready') or b'ready' in request('/-/ready'):break
  except (OSError,urllib.error.URLError):pass
  time.sleep(.1)
 else:raise SystemExit('Prometheus尚未就绪，请检查本机日志')
 print('Prometheus已启动：http://127.0.0.1:'+str(args.port)+'；每5秒采集；未启用开机服务。')
elif args.action=='status':
 data=json.loads(request('/api/v1/targets'))
 targets=[{'job':t['labels'].get('job'),'health':t['health'],'scrapeUrl':t['scrapeUrl']} for t in data['data']['activeTargets']]
 print(json.dumps({'ready':True,'targets':targets},ensure_ascii=False,indent=2))
elif args.action=='query':
 data=json.loads(request('/api/v1/query?'+urllib.parse.urlencode({'query':args.expr})))
 print(json.dumps(data,ensure_ascii=False,indent=2))
else:
 record=state()
 if not owned(record):raise SystemExit('无法核对项目Prometheus归属，未停止任何程序')
 os.kill(record['pid'],signal.SIGTERM)
 for _ in range(100):
  if not owned(record):break
  time.sleep(.1)
 else:raise SystemExit('已请求停止，进程仍在退出，请稍后检查')
 print('本项目Prometheus已停止；本机时序数据保留。')

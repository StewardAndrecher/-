#!/usr/bin/env python3
"""启动本项目独立的本机Grafana；不复用全局数据库或启用匿名访问。"""
from pathlib import Path
import argparse,base64,json,os,secrets,shutil,signal,socket,subprocess,time,urllib.request,urllib.error,urllib.parse

root=Path(__file__).resolve().parents[1];parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('action',choices=['start','stop','status','login-info'])
parser.add_argument('--port',type=int,default=3000);parser.add_argument('--prometheus-port',type=int,default=9090)
args=parser.parse_args()
if not all(1024<=v<=65535 for v in [args.port,args.prometheus_port]):raise SystemExit('端口必须为1024～65535')
runtime=Path(os.environ.get('GRAFANA_DATA_DIR',root/'data/grafana')).resolve();runtime.mkdir(parents=True,exist_ok=True,mode=0o700);runtime.chmod(0o700)
credentials=runtime/'credentials.json'
if not credentials.exists():credentials.write_text(json.dumps({'username':'campus_admin','password':secrets.token_urlsafe(32),'secretKey':secrets.token_hex(32)}));credentials.chmod(0o600)
identity=json.loads(credentials.read_text());state_file=runtime/'process.json'
http=urllib.request.build_opener(urllib.request.ProxyHandler({}))
def state():return json.loads(state_file.read_text()) if state_file.exists() else None
def owned(record):
 if not record:return False
 result=subprocess.run(['ps','-p',str(record['pid']),'-o','command='],capture_output=True)
 command=result.stdout.decode('utf-8',errors='replace')
 return result.returncode==0 and record['binary'] in command and '--config='+str(runtime/'grafana.ini') in command
def api(path,authenticated=True):
 record=state()
 if not owned(record):raise SystemExit('本项目Grafana未运行')
 headers={}
 if authenticated:headers['Authorization']='Basic '+base64.b64encode((identity['username']+':'+identity['password']).encode()).decode()
 with http.open(urllib.request.Request('http://127.0.0.1:'+str(record['port'])+path,headers=headers),timeout=8) as response:return json.load(response)

if args.action=='start':
 if owned(state()):print('本项目Grafana已运行：http://127.0.0.1:'+str(state()['port'])+'/d/campus-mq-overview');raise SystemExit(0)
 binary=os.environ.get('GRAFANA_BIN') or shutil.which('grafana') or '/opt/homebrew/opt/grafana/bin/grafana';binary=str(Path(binary).resolve())
 home=Path(os.environ.get('GRAFANA_HOME','/opt/homebrew/opt/grafana/share/grafana')).resolve()
 if not Path(binary).is_file() or not (home/'conf/defaults.ini').exists():raise SystemExit('请安装Grafana，或设置GRAFANA_BIN和GRAFANA_HOME')
 with socket.socket() as probe:
  probe.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
  try:probe.bind(('127.0.0.1',args.port))
  except OSError:raise SystemExit('端口已有程序，未停止或修改其他实例')
 for name in ['provisioning/datasources','provisioning/dashboards','provisioning/plugins','provisioning/alerting','dashboards','logs','plugins','database']:(runtime/name).mkdir(parents=True,exist_ok=True)
 # Grafana13.2将Prometheus拆为官方独立插件；固定版本，保留默认签名校验。
 plugin_version='13.2.3';plugin_manifest=runtime/'plugins/prometheus/plugin.json'
 if not plugin_manifest.exists():
  install_env={k:v for k,v in os.environ.items() if k not in ['GF_PLUGIN_URL','GF_PLUGIN_REPO','GF_PLUGIN_DIR']}
  with (runtime/'plugin-install.log').open('ab') as log:
   installed=subprocess.run([binary,'cli','--homepath='+str(home),'--pluginsDir='+str(runtime/'plugins'),'--repo=https://grafana.com/api/plugins','plugins','install','prometheus',plugin_version],env=install_env,stdout=log,stderr=subprocess.STDOUT,timeout=180)
  if installed.returncode:raise SystemExit('官方Prometheus插件安装失败，请检查data/grafana/plugin-install.log')
 if json.loads(plugin_manifest.read_text())['info']['version']!=plugin_version:raise SystemExit('已有Prometheus插件版本与本课不符，未覆盖；请核对后再升级')
 source=root/'monitoring'
 shutil.copy2(source/'grafana-datasource.yml',runtime/'provisioning/datasources/campus.yml')
 shutil.copy2(source/'grafana-provider.yml',runtime/'provisioning/dashboards/campus.yml')
 dashboard=json.loads((source/'grafana-mq-dashboard.json').read_text())
 for link in dashboard.get('links',[]):link['url']=link['url'].replace('http://127.0.0.1:9090','http://127.0.0.1:'+str(args.prometheus_port))
 (runtime/'dashboards/campus-mq.json').write_text(json.dumps(dashboard,ensure_ascii=False))
 config=runtime/'grafana.ini'
 config.write_text('''[server]
http_addr = 127.0.0.1
http_port = '''+str(args.port)+'''
domain = 127.0.0.1
root_url = http://127.0.0.1:'''+str(args.port)+'''/
[paths]
data = '''+str(runtime/'database')+'''
logs = '''+str(runtime/'logs')+'''
plugins = '''+str(runtime/'plugins')+'''
provisioning = '''+str(runtime/'provisioning')+'''
[analytics]
reporting_enabled = false
check_for_updates = false
check_for_plugin_updates = false
feedback_links_enabled = false
[users]
allow_sign_up = false
[auth.anonymous]
enabled = false
[auth]
login_maximum_inactive_lifetime_duration = 30m
login_maximum_lifetime_duration = 8h
[security]
disable_gravatar = true
cookie_samesite = strict
[plugins]
preinstall_disabled = true
preinstall_auto_update = false
''');config.chmod(0o600)
 env=os.environ.copy();env.update(GF_SECURITY_ADMIN_USER=identity['username'],GF_SECURITY_ADMIN_PASSWORD=identity['password'],GF_SECURITY_SECRET_KEY=identity['secretKey'],CAMPUS_PROMETHEUS_URL='http://127.0.0.1:'+str(args.prometheus_port),CAMPUS_GRAFANA_DASHBOARDS=str(runtime/'dashboards'))
 with (runtime/'startup.log').open('ab') as log:process=subprocess.Popen([binary,'server','--homepath='+str(home),'--config='+str(config),'--packaging=brew'],env=env,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
 record={'pid':process.pid,'binary':binary,'port':args.port,'prometheusPort':args.prometheus_port};state_file.write_text(json.dumps(record));state_file.chmod(0o600)
 for _ in range(240):
  if process.poll() is not None:raise SystemExit('Grafana启动失败，请检查data/grafana/startup.log')
  try:
   if api('/api/health',False).get('database')=='ok':break
  except (OSError,urllib.error.URLError,ValueError):pass
  time.sleep(.25)
 else:raise SystemExit('Grafana尚未就绪，请检查本机日志')
 print('Grafana已启动：http://127.0.0.1:'+str(args.port)+'/d/campus-mq-overview；登录凭证仅保存于data/grafana/credentials.json。')
elif args.action=='status':
 health=api('/api/health',False);dashboard=api('/api/dashboards/uid/campus-mq-overview');source=api('/api/datasources/uid/campus-prometheus')
 print(json.dumps({'ready':health.get('database')=='ok','version':health.get('version'),'dashboard':dashboard['dashboard']['title'],'panels':len(dashboard['dashboard']['panels']),'provisioned':dashboard['meta'].get('provisioned'),'dataSource':source['name'],'dataSourceUrl':source['url']},ensure_ascii=False,indent=2))
elif args.action=='login-info':
 print('用户名：'+identity['username']+'；密码在本机文件：'+str(credentials)+'。请在本机查看，勿提交或公开。')
else:
 record=state()
 if not owned(record):raise SystemExit('无法核对项目Grafana归属，未停止任何程序')
 os.kill(record['pid'],signal.SIGTERM)
 for _ in range(150):
  if not owned(record):break
  time.sleep(.1)
 else:raise SystemExit('已请求停止，进程仍在退出，请稍后检查')
 print('本项目Grafana已停止，私有配置、数据库和登录凭证保留。')

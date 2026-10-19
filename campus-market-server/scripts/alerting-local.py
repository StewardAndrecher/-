#!/usr/bin/env python3
"""项目专用Alertmanager和只读本机收件箱；不注册开机服务。"""
from pathlib import Path
import argparse,json,os,secrets,signal,socket,subprocess,sys,time,urllib.request
root=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('action',choices=['init','check','start','status','stop'])
p.add_argument('--port',type=int,default=9093);p.add_argument('--receiver-port',type=int,default=9094)
a=p.parse_args()
if not all(1024<=v<=65535 for v in [a.port,a.receiver_port]) or a.port==a.receiver_port:raise SystemExit('请选择两个不同的1024～65535端口')
os.umask(0o077)
runtime=Path(os.environ.get('ALERTING_DATA_DIR',root/'data/alerting')).resolve();runtime.mkdir(parents=True,exist_ok=True,mode=0o700);runtime.chmod(0o700)
http=urllib.request.build_opener(urllib.request.ProxyHandler({}))
def request(port,path):
 with http.open(f'http://127.0.0.1:{port}'+path,timeout=3) as response:return response.read()
def state():
 file=runtime/'process.json'
 return json.loads(file.read_text()) if file.exists() else None
def owned(record,key):
 if not record or key not in record:return False
 item=record[key];r=subprocess.run(['ps','-p',str(item['pid']),'-o','command='],capture_output=True,text=True)
 expected=([str(root/'scripts/alert-receiver.py'),'--runtime '+str(runtime),'--port '+str(record.get('receiverPort'))] if key=='receiver' else [str(Path(os.environ.get('ALERTMANAGER_BIN',runtime/'bin/alertmanager')).resolve()),'--config.file='+str(runtime/'alertmanager.yml')])
 return r.returncode==0 and all(part in r.stdout for part in expected)
def stop_one(record,key):
 if not owned(record,key):return
 os.kill(record[key]['pid'],signal.SIGTERM)
 for _ in range(150):
  if not owned(record,key):return
  time.sleep(.1)
 raise RuntimeError('进程尚未退出，请稍后检查：'+key)
def prepare():
 token=runtime/'webhook-token'
 if not token.exists():token.write_text(secrets.token_hex(32));token.chmod(0o600)
 value=token.read_text().strip()
 if len(value)!=64 or any(c not in '0123456789abcdef' for c in value):raise SystemExit('本机Webhook凭证格式异常，未覆盖')
 config=(root/'monitoring/alertmanager.yml.template').read_text().replace('__RECEIVER_PORT__',str(a.receiver_port)).replace('__TOKEN_PATH_JSON__',json.dumps(str(token)))
 file=runtime/'alertmanager.yml';file.write_text(config);file.chmod(0o600)
 return file
if a.action=='stop':
 record=state()
 if not owned(record,'alertmanager') and not owned(record,'receiver'):raise SystemExit('无法核对本项目进程归属，未停止任何程序')
 for key in ['alertmanager','receiver']:stop_one(record,key)
 print('本项目Alertmanager和收件箱已停止，通知与去重数据保留。');raise SystemExit(0)
if a.action=='status':
 record=state()
 if not all(owned(record,key) for key in ['receiver','alertmanager']):raise SystemExit('本项目告警服务未完整运行')
 request(record['port'],'/-/ready');data=json.loads(request(record['receiverPort'],'/api/notifications'))
 print(json.dumps({'ready':True,'alertmanager':f"http://127.0.0.1:{record['port']}",'inbox':f"http://127.0.0.1:{record['receiverPort']}",'retainedDeliveries':data['total']},ensure_ascii=False,indent=2));raise SystemExit(0)
record=state()
if a.action=='start' and any(owned(record,key) for key in ['receiver','alertmanager']):
 if all(owned(record,key) for key in ['receiver','alertmanager']):print('本项目告警服务已运行；修改端口或配置后请先停止再启动。');raise SystemExit(0)
 raise SystemExit('检测到部分服务仍在运行，请先执行stop；未启动或停止其他程序')
if a.action in ['init','check'] and any(owned(record,key) for key in ['receiver','alertmanager']):raise SystemExit('请先停止本项目告警服务，再重新生成配置')
if a.action=='start':
 for port in [a.port,a.receiver_port]:
  with socket.socket() as probe:
   probe.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
   try:probe.bind(('127.0.0.1',port))
   except OSError:raise SystemExit('端口被占用，未停止任何程序')
config=prepare()
if a.action=='init':print('已生成本机告警配置和独立Webhook凭证，未输出凭证。');raise SystemExit(0)
binary=Path(os.environ.get('ALERTMANAGER_BIN',runtime/'bin/alertmanager')).resolve();amtool=binary.with_name('amtool')
if not binary.is_file() or not amtool.is_file():raise SystemExit('请先运行scripts/install-alertmanager.py，或设置ALERTMANAGER_BIN')
check=subprocess.run([str(amtool),'check-config',str(config)],capture_output=True,text=True)
if check.returncode:raise SystemExit('Alertmanager配置校验失败，请在本机运行amtool check-config检查')
if a.action=='check':print('Alertmanager配置校验通过。');raise SystemExit(0)
record={'port':a.port,'receiverPort':a.receiver_port}
children=[]
def launch(key,command,markers):
 with (runtime/(key+'.log')).open('ab') as log:
  proc=subprocess.Popen(command,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
 children.append(proc);record[key]={'pid':proc.pid,'markers':markers}
 file=runtime/'process.json';file.write_text(json.dumps(record));file.chmod(0o600)
 return proc
def wait(proc,port,path):
 for _ in range(150):
  if proc.poll() is not None:raise RuntimeError('服务启动失败，请检查本机data/alerting日志')
  try:request(port,path);return
  except (OSError,urllib.error.URLError):time.sleep(.1)
 raise RuntimeError('服务启动等待超时，请检查本机日志')
try:
 receiver=str(root/'scripts/alert-receiver.py')
 proc=launch('receiver',[sys.executable,receiver,'--runtime',str(runtime),'--port',str(a.receiver_port)],[receiver,'--runtime '+str(runtime),'--port '+str(a.receiver_port)])
 wait(proc,a.receiver_port,'/health')
 config_arg='--config.file='+str(config)
 proc=launch('alertmanager',[str(binary),config_arg,'--storage.path='+str(runtime/'storage'),'--data.retention=120h','--web.listen-address=127.0.0.1:'+str(a.port),'--cluster.listen-address='],[str(binary),config_arg])
 wait(proc,a.port,'/-/ready')
except Exception:
 for key in ['alertmanager','receiver']:stop_one(record,key)
 raise
print(f'Alertmanager：http://127.0.0.1:{a.port}；告警收件箱：http://127.0.0.1:{a.receiver_port}；仅监听本机。')

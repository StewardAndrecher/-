#!/usr/bin/env python3
"""管理本项目独立的本机RabbitMQ，不启用开机服务，不修改其他节点。"""
from pathlib import Path
import argparse,json,os,secrets,shutil,socket,subprocess,time,sys,xml.etree.ElementTree as ET
root=Path(__file__).resolve().parents[1];runtime=root/'data/rabbitmq';runtime.mkdir(parents=True,exist_ok=True);runtime.chmod(0o700)
p=argparse.ArgumentParser(description=__doc__);p.add_argument('action',choices=['start','stop','status','configure-idea']);args=p.parse_args()
sbin=Path(os.environ.get('RABBITMQ_SBIN','/opt/homebrew/opt/rabbitmq/sbin'));node='campus_market@localhost'
credentials=runtime/'client-env.json'
if not credentials.exists():
 credentials.write_text(json.dumps({'RABBITMQ_HOST':'127.0.0.1','RABBITMQ_PORT':'5672','RABBITMQ_USERNAME':'campus_mq','RABBITMQ_PASSWORD':secrets.token_urlsafe(32),'RABBITMQ_VHOST':'/campus-market','ORDER_MQ_ENABLED':'true'}));credentials.chmod(0o600)
values=json.loads(credentials.read_text());cookie=runtime/'erlang.cookie'
if not cookie.exists():cookie.write_text(secrets.token_hex(32));cookie.chmod(0o600)
conf=runtime/'rabbitmq.conf'
if not conf.exists():
 conf.write_text('''listeners.tcp.1 = 127.0.0.1:5672
management.tcp.ip = 127.0.0.1
management.tcp.port = 15672
distribution.listener.port_range.min = 25672
distribution.listener.port_range.max = 25672
vm_memory_high_watermark.absolute = 512MiB
loopback_users.guest = true
'''+f"default_user = {values['RABBITMQ_USERNAME']}\ndefault_pass = {values['RABBITMQ_PASSWORD']}\ndefault_vhost = /campus-market\n"+'''default_permissions.configure = ^campus[.]order[.].*
default_permissions.write = ^campus[.]order[.].*
default_permissions.read = ^campus[.]order[.].*
''');conf.chmod(0o600)
(runtime/'enabled_plugins').write_text('[rabbitmq_management].\n');(runtime/'env.conf').touch()
env=os.environ.copy();env.update(RABBITMQ_CONF_ENV_FILE=str(runtime/'env.conf'),RABBITMQ_CONFIG_FILE=str(runtime/'rabbitmq'),RABBITMQ_MNESIA_BASE=str(runtime/'mnesia'),RABBITMQ_LOG_BASE=str(runtime/'logs'),RABBITMQ_PID_FILE=str(runtime/'rabbitmq.pid'),RABBITMQ_ENABLED_PLUGINS_FILE=str(runtime/'enabled_plugins'),RABBITMQ_NODENAME=node,RABBITMQ_ERLANG_COOKIE=cookie.read_text(),ERL_EPMD_ADDRESS='127.0.0.1',RABBITMQ_DIST_PORT='25672',RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS='-kernel inet_dist_use_interface {127,0,0,1} +S 2:2 +A 4',RABBITMQ_CTL_ERL_ARGS='+S 1:1 +A 2')
def ctl(*parts,check=True):
 r=subprocess.run([str(sbin/'rabbitmqctl'),'-n',node,'-q',*parts],env=env,capture_output=True,text=True,timeout=25)
 if check and r.returncode:raise RuntimeError('RabbitMQ节点命令失败；请查看项目data/rabbitmq/logs，不输出凭证')
 return r

def owned():
 pid=runtime/'rabbitmq.pid'
 if not pid.exists():return False
 r=ctl('eval','os:getpid().',check=False)
 return r.returncode==0 and r.stdout.strip().strip('"')==pid.read_text().strip()
if args.action=='start':
 with socket.socket() as probe:
  if probe.connect_ex(('127.0.0.1',5672))==0:
   if owned():print('本项目RabbitMQ已运行：127.0.0.1:5672');sys.exit(0)
   raise SystemExit('5672已有其他程序，未改动；请先确认节点归属')
 for port in [15672,25672]:
  with socket.socket() as probe:
   probe.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
   try:probe.bind(('127.0.0.1',port))
   except OSError:raise SystemExit(str(port)+'已被占用，未启动其他实例')
 (runtime/'logs').mkdir(exist_ok=True)
 with (runtime/'startup.log').open('ab') as log:
  subprocess.Popen([str(sbin/'rabbitmq-server')],env=env,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
 for _ in range(180):
  with socket.socket() as probe:
   if probe.connect_ex(('127.0.0.1',5672))==0:break
  time.sleep(.25)
 else:raise SystemExit('启动尚未完成，请查看data/rabbitmq/startup.log')
 if not owned():raise SystemExit('节点归属核对失败，未修改权限')
 ctl('set_user_tags',values['RABBITMQ_USERNAME'],'management')
 print('RabbitMQ已启动：AMQP127.0.0.1:5672；管理页http://127.0.0.1:15672；专用vhost /campus-market。凭证仅保存在data/rabbitmq。')
elif args.action=='stop':
 if not owned():raise SystemExit('无法确认项目节点归属，未停止任何程序')
 ctl('stop')
 for _ in range(100):
  open_ports=[]
  for port in [5672,15672,25672]:
   with socket.socket() as probe:
    if probe.connect_ex(('127.0.0.1',port))==0:open_ports.append(port)
  if not open_ports:break
  time.sleep(.1)
 else:raise SystemExit('节点已请求停止，监听端口尚在退出，请稍后检查')
 print('本项目RabbitMQ已停止，磁盘消息保留；Outbox和数据库扫描仍保护订单流程')
elif args.action=='status':
 if not owned():raise SystemExit('本项目RabbitMQ未运行')
 r=ctl('list_queues','-p','/campus-market','name','type','messages_ready','messages_unacknowledged','consumers');print(r.stdout.strip())
else:
 file=root/'.idea/workspace.xml'
 if not file.is_file():raise SystemExit('IDEA运行配置尚不存在，请先创建Spring Boot运行配置')
 backup=runtime/('idea-workspace-before-'+time.strftime('%Y%m%d-%H%M%S')+'.xml');shutil.copy2(file,backup);backup.chmod(0o600)
 tree=ET.parse(file);config=next(c for c in tree.getroot().iter('configuration') if c.get('name')=='CampusMarketServerApplication' and c.get('type')=='SpringBootApplicationConfigurationType')
 envs=config.find('envs')
 if envs is None:envs=ET.SubElement(config,'envs')
 for key,value in values.items():
  entry=next((e for e in envs.findall('env') if e.get('name')==key),None)
  if entry is None:entry=ET.SubElement(envs,'env',{'name':key})
  entry.set('value',value)
 tree.write(file,encoding='UTF-8',xml_declaration=True)
 print('已将项目MQ连接配置写入本机IDEA；原配置保存在data/rabbitmq，未输出密码')

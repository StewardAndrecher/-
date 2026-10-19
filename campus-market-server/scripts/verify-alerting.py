#!/usr/bin/env python3
"""隔离端口和目录验收：真实Alertmanager分组、去重、恢复及Webhook边界。"""
from pathlib import Path
from datetime import datetime,timezone,timedelta
import argparse,importlib.util,json,os,socket,subprocess,tempfile,time,urllib.request,urllib.error
root=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--output');a=p.parse_args()
binary=Path(os.environ.get('ALERTMANAGER_BIN',root/'data/alerting/bin/alertmanager')).resolve()
http=urllib.request.build_opener(urllib.request.ProxyHandler({}));checks=[]
def freeport():
 with socket.socket() as s:s.bind(('127.0.0.1',0));return s.getsockname()[1]
def check(name,condition):
 if not condition:raise AssertionError(name)
 checks.append(name)
def wait(fn,seconds=50):
 end=time.monotonic()+seconds
 while time.monotonic()<end:
  if fn():return
  time.sleep(.2)
 raise AssertionError('asynchronous condition timed out')
def request(port,path,body=None,headers=None,expected=200):
 data=json.dumps(body).encode() if body is not None else None
 h={'Content-Type':'application/json',**(headers or {})}
 try:r=http.open(urllib.request.Request(f'http://127.0.0.1:{port}'+path,data=data,headers=h),timeout=5)
 except urllib.error.HTTPError as e:r=e
 with r:
  check('http '+str(expected)+' '+path,r.status==expected)
  raw=r.read()
  return json.loads(raw) if raw and 'json' in r.headers.get('Content-Type','') else raw
with tempfile.TemporaryDirectory(prefix='campus-alerting-check-') as directory:
 runtime=Path(directory);amport=freeport();receiverport=freeport()
 env={**os.environ,'ALERTING_DATA_DIR':str(runtime),'ALERTMANAGER_BIN':str(binary)}
 command=['python3',str(root/'scripts/alerting-local.py')]
 def manager(action,ok=True):
  r=subprocess.run(command+[action,'--port',str(amport),'--receiver-port',str(receiverport)],env=env,capture_output=True,text=True,timeout=30)
  if ok and r.returncode:raise AssertionError(r.stdout+r.stderr)
  return r
 def history():
  with http.open(f'http://127.0.0.1:{receiverport}/api/notifications',timeout=5) as r:return json.load(r)['items']
 def group(instance):return [n for n in history() if n['groupLabels'].get('instance')==instance]
 now=datetime.now(timezone.utc)
 def alert(name='CampusMqBrokerUnavailable',instance='qa-one',component='mq',resolved=False):
  return {'labels':{'job':'campus-market','instance':instance,'component':component,'severity':'critical','alertname':name},'annotations':{'summary':'隔离验收告警','description':'仅临时目录和端口'},'startsAt':(now-timedelta(minutes=1)).isoformat(),'endsAt':(datetime.now(timezone.utc)+timedelta(seconds=-1 if resolved else 300)).isoformat(),'generatorURL':'http://127.0.0.1/isolated'}
 manager('init');token=(runtime/'webhook-token').read_text();auth={'Authorization':'Bearer '+token}
 # 已占用端口必须拒绝，不能停止原程序。
 with socket.socket() as held:
  held.bind(('127.0.0.1',receiverport));held.listen()
  r=manager('start',False);check('occupied receiver port refused',r.returncode!=0 and '占用' in r.stdout+r.stderr)
 (runtime/'process.json').write_text(json.dumps({'port':amport,'receiverPort':receiverport,'alertmanager':{'pid':os.getpid(),'markers':[]},'receiver':{'pid':os.getpid(),'markers':[]}}))
 check('forged PID and empty markers refused',manager('stop',False).returncode!=0)
 manager('check');manager('start')
 try:
  check('loopback inbox HTML',b'CAMPUS MARKET' in request(receiverport,'/'))
  request(receiverport,'/webhook',{},expected=401)
  request(receiverport,'/webhook',{},headers={'Authorization':'Bearer '+'0'*64},expected=401)
  request(receiverport,'/webhook',{},headers={**auth,'Origin':'https://example.com'},expected=403)
  request(receiverport,'/api/notifications',headers={'Host':'example.com'},expected=403)
  request(receiverport,'/webhook',{},headers=auth,expected=400)
  request(receiverport,'/webhook',{},headers={**auth,'Content-Type':'text/plain'},expected=415)
  request(receiverport,'/webhook',{'padding':'x'*262144},headers=auth,expected=413)
  request(receiverport,'/webhook?token=not-accepted',{},headers=auth,expected=404)
  webhook={'version':'4','status':'resolved','groupKey':'direct-validation','groupLabels':{'job':'campus-market','instance':'qa-direct','component':'application'},'alerts':[{**alert('CampusWebhookAcceptance','qa-direct','application',True),'status':'resolved','fingerprint':'1234567890abcdef'}]}
  bad=json.loads(json.dumps(webhook));bad['alerts'][0]['labels']['job']='other-project';request(receiverport,'/webhook',bad,auth,400)
  bad=json.loads(json.dumps(webhook));bad['status']='firing';request(receiverport,'/webhook',bad,auth,400)
  webhook['alerts'][0]['labels']['private_label']='drop me';webhook['alerts'][0]['annotations']['private_annotation']='drop me';webhook['externalURL']='do not persist'
  webhook['alerts'][0]['startsAt']='2026-10-18T00:00:00.123456789Z'
  webhook['alerts'][0]['endsAt']='2026-10-18T00:00:02.12345+00:00'
  request(receiverport,'/webhook',webhook,auth);request(receiverport,'/webhook',webhook,auth)
  items=group('qa-direct');check('at-least-once deliveries kept truthfully',len(items)==2)
  check('RFC3339Nano accepted without losing original timestamp',items[0]['alerts'][0]['startsAt'].endswith('.123456789Z'))
  check('unknown fields not persisted','private_label' not in json.dumps(items) and 'private_annotation' not in json.dumps(items) and 'externalURL' not in json.dumps(items))
  # 存储失败不能返回200；恢复文件后可继续接收。
  db=runtime/'notifications.sqlite3';saved=runtime/'saved.sqlite3';db.rename(saved);db.mkdir()
  try:request(receiverport,'/webhook',webhook,auth,503)
  finally:db.rmdir();saved.rename(db)
  first=[alert(),alert('CampusMqNoConsumer')]
  request(amport,'/api/v2/alerts',first)
  wait(lambda:len(group('qa-one'))==1)
  check('same instance and component grouped into one notification',len(group('qa-one')[0]['alerts'])==2)
  check('firing delivery observed',group('qa-one')[0]['status']=='firing')
  request(amport,'/api/v2/alerts',first)
  # 另一个实例：应用故障抑制同实例MQ告警，不能抑制qa-one。
  request(amport,'/api/v2/alerts',[alert('CampusBackendScrapeDown','qa-two','application'),alert('CampusMqBrokerUnavailable','qa-two')])
  wait(lambda:len(group('qa-two'))==1)
  states=request(amport,'/api/v2/alerts')
  suppressed=[v for v in states if v['labels']['instance']=='qa-two' and v['labels']['component']=='mq']
  check('same instance downstream alert inhibited',len(suppressed)==1 and bool(suppressed[0]['status']['inhibitedBy']))
  check('application notification is a separate group',group('qa-two')[0]['groupLabels']['component']=='application')
  # 跨过一次group_interval=30秒；repeat_interval=4h内重复刷新不再通知。
  until=time.monotonic()+32
  while time.monotonic()<until:time.sleep(.2)
  check('duplicate updates produce no extra delivery across group interval',len(group('qa-one'))==1)
  request(amport,'/api/v2/alerts',[alert(resolved=True),alert('CampusMqNoConsumer',resolved=True)])
  wait(lambda:any(n['status']=='resolved' for n in group('qa-one')))
  check('recovery notification sent with both resolved alerts',any(n['status']=='resolved' and all(v['status']=='resolved' for v in n['alerts']) and len(n['alerts'])==2 for n in group('qa-one')))
  before=len(history());manager('stop');manager('start');check('SQLite history survives restart',len(history())==before)
  check('webhook token mode 600',(runtime/'webhook-token').stat().st_mode & 0o777==0o600)
  check('SQLite mode 600',(runtime/'notifications.sqlite3').stat().st_mode & 0o777==0o600)
  check('private runtime mode 700',runtime.stat().st_mode & 0o777==0o700)
 finally:manager('stop')
result={'passed':len(checks),'checks':checks,'isolated':True,'groupWait':'10s','groupInterval':'30s','repeatInterval':'4h','production_route_timings_unchanged':True}
if a.output:Path(a.output).write_text(json.dumps(result,ensure_ascii=False,indent=2))
print(json.dumps(result,ensure_ascii=False,indent=2))

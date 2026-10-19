#!/usr/bin/env python3
"""仅监听127.0.0.1的技术告警收件箱；SQLite提交成功后才返回200。"""
from pathlib import Path
from datetime import datetime,timezone
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
import argparse,hmac,json,re,sqlite3,os
MAX_BODY=262144
LABELS={'job','instance','component','severity','alertname','queue'}
def text(value,maximum):
 if not isinstance(value,str) or len(value)>maximum:raise ValueError('invalid text')
 return value
def labels(value):
 if not isinstance(value,dict) or len(value)>20:raise ValueError('invalid labels')
 return {k:text(v,256) for k,v in value.items() if k in LABELS}
def stamp(value):
 value=text(value,64)
 if not re.fullmatch(r'\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})',value):raise ValueError('invalid timestamp')
 # Alertmanager使用RFC3339Nano；Python3.9仅支持3或6位小数，需要归一化后校验。
 normalized=re.sub(r'\.(\d{1,9})',lambda m:'.'+m.group(1)[:6].ljust(6,'0'),value)
 d=datetime.fromisoformat(normalized.replace('Z','+00:00'))
 if d.tzinfo is None:raise ValueError('timezone required')
 return value
def validate(raw):
 if not isinstance(raw,dict) or raw.get('version')!='4' or raw.get('status') not in ['firing','resolved']:raise ValueError('invalid webhook')
 alerts=raw.get('alerts')
 if not isinstance(alerts,list) or not 1<=len(alerts)<=100:raise ValueError('invalid alerts')
 clean=[]
 for a in alerts:
  if not isinstance(a,dict) or a.get('status') not in ['firing','resolved']:raise ValueError('invalid alert')
  ls=labels(a.get('labels'))
  if ls.get('job')!='campus-market' or not re.fullmatch(r'Campus[A-Za-z0-9_]{1,120}',ls.get('alertname','')):raise ValueError('unexpected project')
  if ls.get('severity') not in ['warning','critical'] or ls.get('component') not in ['application','mq','outbox']:raise ValueError('unexpected classification')
  fp=text(a.get('fingerprint'),16)
  if not re.fullmatch('[0-9a-f]{16}',fp):raise ValueError('invalid fingerprint')
  annotations=a.get('annotations',{})
  if not isinstance(annotations,dict):raise ValueError('invalid annotations')
  clean.append({'status':a['status'],'labels':ls,'annotations':{k:text(v,2048) for k,v in annotations.items() if k in ['summary','description']},'fingerprint':fp,'startsAt':stamp(a.get('startsAt')),'endsAt':stamp(a.get('endsAt'))})
 if (raw['status']=='resolved')!=all(a['status']=='resolved' for a in clean):raise ValueError('inconsistent status')
 truncated=raw.get('truncatedAlerts',0)
 if type(truncated) is not int or truncated<0:raise ValueError('invalid truncated count')
 return {'version':'4','status':raw['status'],'groupKey':text(raw.get('groupKey'),2048),'groupLabels':labels(raw.get('groupLabels',{})),'truncatedAlerts':truncated,'alerts':clean}
def initialize(runtime):
 runtime.mkdir(parents=True,exist_ok=True,mode=0o700);runtime.chmod(0o700)
 db=runtime/'notifications.sqlite3'
 with sqlite3.connect(db) as c:
  c.execute('CREATE TABLE IF NOT EXISTS notifications (id INTEGER PRIMARY KEY, received_at TEXT NOT NULL, status TEXT NOT NULL, payload TEXT NOT NULL)')
 db.chmod(0o600)
 return db
def main():
 p=argparse.ArgumentParser();p.add_argument('--runtime',required=True);p.add_argument('--port',type=int,required=True);args=p.parse_args()
 if not 1024<=args.port<=65535:raise SystemExit('invalid port')
 runtime=Path(args.runtime).resolve();token=(runtime/'webhook-token').read_text().strip()
 if not re.fullmatch('[0-9a-f]{64}',token):raise SystemExit('invalid private token')
 db=initialize(runtime);assets=Path(__file__).resolve().parents[1]/'monitoring'
 class Handler(BaseHTTPRequestHandler):
  def log_message(self,*args):pass # 不记录请求头或请求体，避免凭证进入日志。
  def setup(self):
   super().setup();self.connection.settimeout(5)
  def reply(self,code,body,kind='application/json; charset=utf-8'):
   data=body if isinstance(body,bytes) else json.dumps(body,ensure_ascii=False).encode()
   self.send_response(code);self.send_header('Content-Type',kind);self.send_header('Content-Length',str(len(data)));self.send_header('Cache-Control','no-store');self.send_header('X-Content-Type-Options','nosniff');self.send_header('Content-Security-Policy',"default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'");self.end_headers();self.wfile.write(data)
  def allowed(self):
   if self.headers.get_all('Host',[]) not in [[f'127.0.0.1:{args.port}'],[f'localhost:{args.port}']]:self.reply(403,{'error':'invalid host'});return False
   if self.headers.get('Origin') is not None:self.reply(403,{'error':'origin forbidden'});return False
   return True
  def do_GET(self):
   if not self.allowed():return
   if self.path=='/health':return self.reply(200,{'status':'UP'})
   if self.path=='/api/notifications':
    with sqlite3.connect(db,timeout=5) as c:
     rows=c.execute('SELECT id, received_at, status, payload FROM notifications ORDER BY id DESC LIMIT 100').fetchall();total=c.execute('SELECT COUNT(*) FROM notifications').fetchone()[0]
    return self.reply(200,{'total':total,'items':[{'id':i,'receivedAt':t,**json.loads(payload)} for i,t,s,payload in rows]})
   routes={'/':('alert-inbox.html','text/html; charset=utf-8'),'/inbox.css':('alert-inbox.css','text/css; charset=utf-8'),'/inbox.js':('alert-inbox.js','text/javascript; charset=utf-8')}
   if self.path in routes:
    file,kind=routes[self.path];return self.reply(200,(assets/file).read_bytes(),kind)
   self.reply(404,{'error':'not found'})
  def do_POST(self):
   if not self.allowed():return
   if self.path!='/webhook':return self.reply(404,{'error':'not found'})
   auth=self.headers.get_all('Authorization',[])
   if len(auth)!=1 or not hmac.compare_digest(auth[0].encode(),('Bearer '+token).encode()):return self.reply(401,{'error':'unauthorized'})
   if self.headers.get('Transfer-Encoding') or len(self.headers.get_all('Content-Length',[]))!=1:return self.reply(400,{'error':'length required'})
   try:length=int(self.headers['Content-Length'])
   except ValueError:return self.reply(400,{'error':'invalid length'})
   if not 0<length<=MAX_BODY:return self.reply(413,{'error':'body too large'})
   if self.headers.get_content_type()!='application/json':return self.reply(415,{'error':'JSON required'})
   try:
    raw=self.rfile.read(length)
    if len(raw)!=length:raise ValueError('incomplete')
    payload=validate(json.loads(raw));encoded=json.dumps(payload,ensure_ascii=False)
   except (ValueError,TypeError,KeyError,OverflowError):return self.reply(400,{'error':'invalid webhook'})
   try:
    with sqlite3.connect(db,timeout=5) as c:
     c.execute('INSERT INTO notifications(received_at,status,payload) VALUES(?,?,?)',(datetime.now(timezone.utc).isoformat(),payload['status'],encoded))
     # 仅保留最新1000次投递；本机技术演示收件箱，不是永久审计系统。
     c.execute('DELETE FROM notifications WHERE id NOT IN (SELECT id FROM notifications ORDER BY id DESC LIMIT 1000)')
   except sqlite3.Error:return self.reply(503,{'error':'storage unavailable'})
   self.reply(200,{'accepted':True})
 server=ThreadingHTTPServer(('127.0.0.1',args.port),Handler);server.daemon_threads=True
 server.serve_forever()
if __name__=='__main__':
 os.umask(0o077);main()

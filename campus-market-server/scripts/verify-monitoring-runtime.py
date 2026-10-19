#!/usr/bin/env python3
"""验证运维脚本遇到未知进程或已占用端口时拒绝操作，不接触业务实例。"""
from pathlib import Path
import json,os,socket,subprocess,sys,tempfile
root=Path(__file__).resolve().parents[1];checks=[]
with tempfile.TemporaryDirectory(prefix='campus-monitor-ownership-') as temp:
 for script,variable in [('grafana-local.py','GRAFANA_DATA_DIR'),('monitoring-local.py','MONITORING_DATA_DIR')]:
  directory=Path(temp)/script;directory.mkdir();env=os.environ.copy();env[variable]=str(directory)
  with socket.socket() as listener:
   listener.bind(('127.0.0.1',0));listener.listen();port=listener.getsockname()[1]
   result=subprocess.run([sys.executable,str(root/'scripts'/script),'start','--port',str(port)],env=env,capture_output=True,text=True,timeout=10)
   assert result.returncode!=0,'Must reject an occupied port'
   with socket.create_connection(('127.0.0.1',port),timeout=1):pass
   checks.append(script+':occupied-port-refused')
  (directory/'process.json').write_text(json.dumps({'pid':os.getpid(),'binary':sys.executable,'port':port}))
  result=subprocess.run([sys.executable,str(root/'scripts'/script),'stop'],env=env,capture_output=True,text=True,timeout=10)
  assert result.returncode!=0,'Must refuse unknown process ownership'
  os.kill(os.getpid(),0);checks.append(script+':unknown-process-not-stopped')
print(json.dumps({'passed':len(checks),'checks':checks,'businessServicesTouched':False},ensure_ascii=False,indent=2))

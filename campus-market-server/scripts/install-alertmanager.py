#!/usr/bin/env python3
"""下载固定官方发行版，核对官方SHA-256清单；不注册系统服务。"""
from pathlib import Path
import hashlib,io,json,os,platform,tarfile,urllib.request
VERSION='0.34.1'
root=Path(__file__).resolve().parents[1]
runtime=Path(os.environ.get('ALERTING_DATA_DIR',root/'data/alerting')).resolve()
system={'Darwin':'darwin','Linux':'linux'}.get(platform.system())
arch={'arm64':'arm64','aarch64':'arm64','x86_64':'amd64'}.get(platform.machine())
if not system or not arch:raise SystemExit('请从官方发行页手动安装并设置ALERTMANAGER_BIN')
name=f'alertmanager-{VERSION}.{system}-{arch}.tar.gz'
base=f'https://github.com/prometheus/alertmanager/releases/download/v{VERSION}/'
http=urllib.request.build_opener(urllib.request.ProxyHandler({}))
def fetch(name):
 with http.open(base+name,timeout=90) as r:return r.read()
runtime.mkdir(parents=True,exist_ok=True,mode=0o700);runtime.chmod(0o700)
dest=runtime/'bin';dest.mkdir(exist_ok=True,mode=0o700)
if any((dest/n).exists() for n in ['alertmanager','amtool']):raise SystemExit('安装路径已有文件，未覆盖；请先核对版本')
checks=fetch('sha256sums.txt').decode()
expected=next(line.split()[0] for line in checks.splitlines() if line.split()[-1].lstrip('*')==name)
archive=fetch(name);actual=hashlib.sha256(archive).hexdigest()
if actual!=expected:raise SystemExit('SHA-256不匹配，未安装')
with tarfile.open(fileobj=io.BytesIO(archive),mode='r:gz') as tar:
 for binary in ['alertmanager','amtool']:
  member=tar.getmember(name.removesuffix('.tar.gz')+'/'+binary)
  if not member.isfile():raise SystemExit('发行包内容异常')
  content=tar.extractfile(member).read();target=dest/binary;target.write_bytes(content);target.chmod(0o700)
receipt={'version':VERSION,'asset':name,'sha256':actual,'source':base+name}
(runtime/'install-receipt.json').write_text(json.dumps(receipt,indent=2))
print(json.dumps(receipt,indent=2))

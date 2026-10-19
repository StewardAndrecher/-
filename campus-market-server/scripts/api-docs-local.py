#!/usr/bin/env python3
"""开启/关闭本机IDEA开发文档，或导出当前OpenAPI JSON；不修改业务数据。"""
from pathlib import Path
import argparse,json,os,shutil,tempfile,time,urllib.request,xml.etree.ElementTree as ET
root=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('action',choices=['enable-idea','disable-idea','export'])
p.add_argument('--port',type=int,default=8080)
p.add_argument('--output',type=Path)
a=p.parse_args()
if not 1024<=a.port<=65535:raise SystemExit('端口必须为1024～65535')
if a.action=='export':
    target=(a.output or root/'data/api-docs/openapi.json').resolve()
    if target.exists():raise SystemExit('目标文件已存在，请选择新的输出路径')
    http=urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with http.open(f'http://127.0.0.1:{a.port}/api-docs',timeout=15) as response:data=json.load(response)
    if not str(data.get('openapi','')).startswith('3.'):raise SystemExit('响应不是OpenAPI 3文档')
    target.parent.mkdir(parents=True,exist_ok=True)
    with target.open('x',encoding='utf-8') as f:json.dump(data,f,ensure_ascii=False,indent=2)
    print('已导出OpenAPI：'+str(target))
else:
    file=root/'.idea/workspace.xml'
    if not file.exists():raise SystemExit('请先建立IDEA Spring Boot运行配置')
    original=file.read_bytes();tree=ET.ElementTree(ET.fromstring(original))
    configs=[c for c in tree.getroot().iter('configuration') if c.get('name')=='CampusMarketServerApplication' and c.get('type')=='SpringBootApplicationConfigurationType']
    if len(configs)!=1:raise SystemExit('未找到唯一的目标Spring Boot运行配置，未修改文件')
    envs=configs[0].find('envs')
    if envs is None:envs=ET.SubElement(configs[0],'envs')
    entry=next((e for e in envs.findall('env') if e.get('name')=='API_DOCS_ENABLED'),None)
    if entry is None:entry=ET.SubElement(envs,'env',{'name':'API_DOCS_ENABLED'})
    entry.set('value','true' if a.action=='enable-idea' else 'false')
    runtime=root/'data/api-docs';runtime.mkdir(parents=True,exist_ok=True,mode=0o700);runtime.chmod(0o700)
    backup=runtime/('idea-workspace-before-'+str(time.time_ns())+'.xml');backup.write_bytes(original);backup.chmod(0o600)
    fd,name=tempfile.mkstemp(prefix='.workspace-api-docs-',suffix='.xml',dir=file.parent)
    try:
        with os.fdopen(fd,'wb') as f:tree.write(f,encoding='UTF-8',xml_declaration=True)
        os.chmod(name,file.stat().st_mode & 0o777)
        if file.read_bytes()!=original:raise SystemExit('IDEA同时修改了配置，原文件未覆盖；请稍后重试')
        os.replace(name,file)
    finally:
        if os.path.exists(name):os.unlink(name)
    print('已备份并'+('开启' if a.action=='enable-idea' else '关闭')+'本机文档配置；重启IDEA后端后生效。原凭证未输出。')

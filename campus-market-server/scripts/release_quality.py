#!/usr/bin/env python3
"""Verify completed test/build evidence for this exact source tree; never deploy anything."""
from pathlib import Path
import argparse,hashlib,json,os,sys
from backend_test_support import IGNORED as BACKEND_IGNORED
from release_support import verify_bundle,sha256

CLIENT_IGNORED={'.git','.idea','.vscode','.hbuilderx','node_modules','unpackage','dist','web','.web-history','data','target','__pycache__','.DS_Store'}
BUILD_IGNORED={'.git','.idea','.hbuilderx','node_modules','unpackage','__pycache__','.DS_Store'}

def inventory(root,ignored,env=False):
    root=Path(root);result={}
    if not root.is_dir() or root.is_symlink():raise ValueError('Source directory missing or unsafe')
    for folder,dirs,files in os.walk(root,followlinks=False):
        def skip(name):return name in ignored or env and (name=='.env' or name.startswith('.env.'))
        for name in dirs+files:
            if not skip(name) and (Path(folder)/name).is_symlink():raise ValueError('Source symlink rejected')
        dirs[:]=[name for name in dirs if not skip(name)]
        for name in files:
            if skip(name):continue
            p=Path(folder)/name;result[p.relative_to(root).as_posix()]=sha256(p)
    return result

def digest(files):
    value=hashlib.sha256()
    for name,hash_value in sorted(files.items(),key=lambda item:Path(item[0]).parts):value.update(name.encode());value.update(b'\0');value.update(bytes.fromhex(hash_value))
    return value.hexdigest()

def read(path):
    path=Path(path)
    if path.is_symlink():raise ValueError('Report symlink rejected')
    return json.loads(path.read_text())

def passed(record,label):
    junit=record.get('junit',{})
    if record.get('status')!='passed' or junit.get('tests',0)<1 or any(junit.get(k)!=0 for k in ['failures','errors','skipped']):raise ValueError(label+': missing or failed tests')
    if not record.get('temporaryRuntimeRemoved',record.get('temporaryBuildRemoved',False)):raise ValueError(label+': cleanup not confirmed')

def verify(server,client,desktop):
    server,client,desktop=map(Path,[server,client,desktop])
    backend=read(server/'data/test-results/latest.json');frontend=read(client/'unpackage/quick-tests/latest.json');release=read(server/'data/releases/latest.json')
    passed(backend,'backend');passed(frontend,'client/desktop');passed(release,'release')
    if backend.get('suite')!='all' or frontend.get('suite')!='all':raise ValueError('Full regression suites are required')
    backend_hash=digest(inventory(server,BACKEND_IGNORED))
    client_hash=digest(inventory(client,CLIENT_IGNORED,True));desktop_hash=digest(inventory(desktop,CLIENT_IGNORED,True))
    if backend['sourceSha256']!=backend_hash or release['sourceSha256']!=backend_hash:raise ValueError('Backend source changed after tests or packaging')
    if frontend['sourceSha256']!={'client':client_hash,'desktop':desktop_hash}:raise ValueError('Client/desktop source changed after tests')
    expected_source=inventory(client,BUILD_IGNORED,True);builds={}
    for platform in ['h5','mp-weixin','app']:
        folder=client/'unpackage/verified'/platform;meta=read(folder/'build-meta.json')
        if meta.get('platform')!=platform or meta.get('source')!=expected_source:raise ValueError(platform+': source/build mismatch')
        actual=inventory(folder,{'build-meta.json','project.private.config.json','.DS_Store'})
        if actual!=meta.get('artifacts'):raise ValueError(platform+': build artifact changed')
        if meta.get('validation',{}).get('files')!=len(actual):raise ValueError(platform+': incomplete build evidence')
        builds[platform]={'runId':meta['runId'],'files':len(actual)}
    bundle=Path(release['bundleDirectory'])
    if not bundle.resolve().is_relative_to((server/'data/releases').resolve()):raise ValueError('Release path must belong to this project')
    verify_bundle(bundle)
    if sha256(release['archive'])!=release['archiveSha256']:raise ValueError('Release ZIP changed')
    return {'status':'passed','backendTests':backend['junit']['tests'],'clientDesktopTests':frontend['junit']['tests'],'builds':builds,'sourceSha256':{'backend':backend_hash,'client':client_hash,'desktop':desktop_hash},'deployed':False,'publicLaunchApproved':False,'note':'Quality checks do not replace merchant onboarding, real payment/refund tests, device acceptance or release approval.'}

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--client',type=Path);parser.add_argument('--desktop',type=Path);parser.add_argument('--output',type=Path);args=parser.parse_args()
    server=Path(__file__).resolve().parents[1];client=args.client or server.parent/'campus-market-client';desktop=args.desktop or server.parent/'campus-market-desktop'
    try:result=verify(server,client,desktop);code=0
    except (OSError,ValueError,KeyError,TypeError,RuntimeError) as error:result={'status':'blocked','reason':str(error),'deployed':False};code=1
    destination=args.output or server/'data/quality-reports/latest.json';destination.parent.mkdir(parents=True,exist_ok=True);destination.write_text(json.dumps(result,ensure_ascii=False,indent=2));print(json.dumps(result,ensure_ascii=False,indent=2));return code
if __name__=='__main__':sys.exit(main())

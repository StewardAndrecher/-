from pathlib import Path
import os,sys,json,subprocess,shutil
root=Path(__file__).resolve().parents[1];os.chdir(root)
compiler=Path(os.environ.get('UNI_COMPILER_HOME','/Applications/HBuilderX.app/Contents/HBuilderX/plugins/uniapp-cli-vite'))
node=os.environ.get('NODE_BIN') or shutil.which('node')
if not node:raise SystemExit('Node.js22+ is required')
env=os.environ.copy();env['UNI_COMPILER_HOME']=str(compiler);env['NODE_BIN']=node
subprocess.run([sys.executable,'scripts/prepare_frontend_checks.py'],env=env,check=True)
subprocess.run([node,'campus-market-client/tools/build.mjs','all'],env=env,check=True)
results=[]
for p in sorted(Path('scripts/frontend').glob('verify_*.mjs')):
 r=subprocess.run([node,str(p)],env=env,capture_output=True,text=True)
 results.append({'group':p.stem,'ok':r.returncode==0,'output':(r.stdout+r.stderr)[-4000:]})
 print(p.stem,'PASS' if r.returncode==0 else 'FAIL: '+(r.stdout+r.stderr)[-2500:],flush=True)
Path('.test-results').mkdir(exist_ok=True);Path('.test-results/frontend.json').write_text(json.dumps(results,ensure_ascii=False,indent=2))
if len(results)!=47 or not all(r['ok'] for r in results):raise SystemExit(1)
tests=sorted(str(p) for p in Path('campus-market-client/tools').glob('*.test.mjs'))
subprocess.run([node,'--test',*tests],check=True,env=env)
print('PASS:47 frontend groups and50 build/storage/network/request tests; all3platform builds verified.')

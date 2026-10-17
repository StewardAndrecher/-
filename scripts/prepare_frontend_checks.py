from pathlib import Path
import shutil,re
root=Path(__file__).resolve().parents[1];w=root/'scripts/.frontend-work'
w.mkdir(parents=True,exist_ok=True)
import os
compiler=Path(os.environ.get('UNI_COMPILER_HOME','/Applications/HBuilderX.app/Contents/HBuilderX/plugins/uniapp-cli-vite'))
assert (compiler/'node_modules/@vue/compiler-sfc').is_dir(),'Set UNI_COMPILER_HOME to the HBuilderX compiler directory'
def preprocess(text,platform):
 lines=[];active=[True]
 for line in text.splitlines():
  directive=re.match(r'\s*//\s*#(ifdef|ifndef)\s+([\w-]+)',line)
  if directive:
   matched=directive[2]==platform
   active.append(active[-1] and (matched if directive[1]=='ifdef' else not matched))
  elif re.match(r'\s*//\s*#endif',line):active.pop()
  elif active[-1]:lines.append(line)
 assert len(active)==1
 return '\n'.join(lines)+'\n'
for scenario,platform in [('esm','H5'),('esm-weixin-develop','MP-WEIXIN'),('esm-weixin-trial','MP-WEIXIN'),('esm-weixin-unknown','MP-WEIXIN'),('esm-weixin-release','MP-WEIXIN'),('esm-weixin-bad-device','MP-WEIXIN'),('esm-app-develop','APP-PLUS'),('esm-app-release','APP-PLUS'),('esm-app-unknown-device','APP-PLUS'),('esm-app-unknown-env','APP-PLUS')]:
 dest=w/scenario
 if dest.exists():shutil.rmtree(dest)
 dest.mkdir();(dest/'package.json').write_text('{"type":"module"}')
 for folder in ['api','utils','config']:
  shutil.copytree(root/'campus-market-client'/folder,dest/folder)
 for p in dest.rglob('*.js'):p.write_text(preprocess(p.read_text(),platform).replace('@/', '../').replace('process.env.NODE_ENV', repr('development' if scenario in ['esm-app-develop','esm-app-unknown-device'] else 'production' if scenario=='esm-app-release' else 'unknown')) if platform=='APP-PLUS' else preprocess(p.read_text(),platform).replace('@/', '../'))
 (dest/'node_modules').symlink_to(compiler/'node_modules',target_is_directory=True)

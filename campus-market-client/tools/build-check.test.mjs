import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import os from 'node:os'
import { spawn } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { checkScopedFiles, checkPlatform, verifyBuild } from './build-check.mjs'
import { fileInventory, sameInventory, sha256 } from './build-files.mjs'
const tools = path.dirname(fileURLToPath(import.meta.url))
async function fixture(action) {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'campus-build-test-'))
  try { await action(root) } finally { await fs.rm(root, { recursive: true, force: true }) }
}
async function put(root,name,text){await fs.mkdir(path.dirname(path.join(root,name)),{recursive:true});await fs.writeFile(path.join(root,name),text)}
async function mpFixture(root) {
  await put(root,'app.json',JSON.stringify({pages:['pages/index/index']}))
  await put(root,'project.config.json',JSON.stringify({compileType:'miniprogram',appid:'wx0000000000000000'}))
  await put(root,'pages/index/index.wxml','<view class="page data-v-12345678"/>')
  await put(root,'pages/index/index.wxss','.page.data-v-12345678{display:block}')
  await put(root,'pages/index/index.js','const component=["__scopeId","data-v-12345678"]')
  await put(root,'pages/index/index.json','{}')
}
async function appFixture(root) {
  await put(root,'manifest.json',JSON.stringify({id:'__UNI__0000000',name:'校园集市','@platforms':['android','iPhone'],launch_path:'__uniappview.html'}))
  for (const name of ['app-service.js','app-config.js','app.css','__uniappview.html','uni-app-view.umd.js','pages/index/index.css']) await put(root,name,'resource')
  await put(root,'app-config-service.js','const __uniConfig = {"entryPagePath":"pages/index/index"};\nconst __uniRoutes = [{"path":"pages/index/index","meta":{"isNVue":false}}].map(uniRoute=>uniRoute);')
}
async function writeMeta(root,platform){await put(root,'build-meta.json',JSON.stringify({schemaVersion:1,platform,artifacts:await fileInventory(root,{metadata:true})}))}
async function run(project,compiler,args=['all'],env={}) {
  return new Promise((resolve,reject)=>{
    const child=spawn(process.execPath,[path.join(project,'tools/build.mjs'),...args],{env:{...process.env,UNI_COMPILER_HOME:compiler,...env}})
    let output='';child.stdout.on('data',b=>output+=b);child.stderr.on('data',b=>output+=b);child.on('error',reject);child.on('exit',code=>resolve({code,output}))
  })
}
async function buildFixture(root) {
  const project=path.join(root,'project'),compiler=path.join(root,'compiler')
  await fs.mkdir(path.join(project,'tools'),{recursive:true})
  for(const name of ['build.mjs','build-check.mjs','build-files.mjs'])await fs.copyFile(path.join(tools,name),path.join(project,'tools',name))
  await put(project,'App.vue','<template><view/></template>')
  await put(project,'unpackage/dist/dev/mp-weixin/keep.txt','HBuilderX owns this')
  await put(compiler,'package.json',JSON.stringify({version:'fixture-1'}))
  await put(compiler,'node_modules/@dcloudio/vite-plugin-uni/bin/uni.js',`
const fs=require('node:fs'),path=require('node:path');const out=process.env.UNI_OUTPUT_DIR,platform=process.argv.at(-1);
if(process.env.FIXTURE_PAUSE){fs.writeFileSync(process.env.FIXTURE_PAUSE,'compiler started');setInterval(()=>{},1000);return}
if(process.env.FIXTURE_FAIL===platform){console.error('fixture compilation failure');process.exit(2)}
const put=(n,t)=>{fs.mkdirSync(path.dirname(path.join(out,n)),{recursive:true});fs.writeFileSync(path.join(out,n),t)};
if(platform==='h5'){put('index.html','<script src="./assets/main.js"></script>');put('assets/main.js','console.log(1)')}
else if(platform==='app'){
put('manifest.json',JSON.stringify({id:'__UNI__0000000',name:'校园集市','@platforms':['android','iPhone'],launch_path:'__uniappview.html'}));
for(const name of ['app-service.js','app-config.js','app.css','__uniappview.html','uni-app-view.umd.js','pages/index/index.css'])put(name,'resource');
put('app-config-service.js','const __uniConfig = {"entryPagePath":"pages/index/index"};\\nconst __uniRoutes = [{"path":"pages/index/index"}].map(route=>route);');
}
else {put('app.json',JSON.stringify({pages:['pages/index/index']}));put('project.config.json',JSON.stringify({compileType:'miniprogram',appid:'wx0000000000000000'}));put('pages/index/index.wxml','<view class="data-v-12345678"/>');put('pages/index/index.wxss','.page.data-v-'+(process.env.FIXTURE_BAD_SCOPE?'87654321':'12345678')+'{display:block}');put('pages/index/index.js','const c=["__scopeId","data-v-12345678"]');put('pages/index/index.json','{}')}
if(process.env.FIXTURE_CHANGE_SOURCE)fs.writeFileSync(process.env.FIXTURE_CHANGE_SOURCE,'changed while compiling');
`)
  return {project,compiler}
}
test('matching scopes pass; mixed WXSS and JS fail',()=>{
 assert.equal(checkScopedFiles('<text class="data-v-12345678"/>','.title.data-v-12345678{}','["__scopeId","data-v-12345678"]','card'),1)
 assert.throws(()=>checkScopedFiles('<text class="data-v-12345678"/>','.title.data-v-87654321{}','','card'),/模板与样式/)
 assert.throws(()=>checkScopedFiles('<text class="data-v-12345678"/>','','["__scopeId","data-v-87654321"]','card'),/JS/)
})
test('inventory ignores generated/source-only directories and sorts comparison',()=>fixture(async root=>{
 await put(root,'App.vue','source');await put(root,'unpackage/dev/app.js','generated');await put(root,'.idea/private','excluded')
 await put(root,'tools/__pycache__/quick_test_support.pyc','excluded bytecode');await put(root,'unpackage/quick-tests/report.xml','excluded test results')
 const source=await fileInventory(root,{source:true});assert.deepEqual(Object.keys(source),['App.vue']);assert.equal(source['App.vue'],sha256(Buffer.from('source')))
 assert.ok(sameInventory({a:'1',b:'2'},{b:'2',a:'1'}));assert.ok(!sameInventory({a:'1'},{a:'2'}))
}))
test('generated page completeness and component scope consistency',()=>fixture(async root=>{
 await mpFixture(root);assert.equal((await checkPlatform(root,'mp-weixin')).pages,1)
 await fs.rm(path.join(root,'pages/index/index.json'));await assert.rejects(checkPlatform(root,'mp-weixin'),/文件缺失/)
}))
test('manifest detects overwritten, extra and deleted files',()=>fixture(async root=>{
 await mpFixture(root);await writeMeta(root,'mp-weixin');await verifyBuild(root)
 await put(root,'project.private.config.json','local IDE preferences');await verifyBuild(root)
 await put(root,'pages/index/index.wxss','changed');await assert.rejects(verifyBuild(root),/清单不一致/)
 await mpFixture(root);await put(root,'unexpected.js','extra');await assert.rejects(verifyBuild(root),/清单不一致/)
 await fs.rm(path.join(root,'unexpected.js'));await fs.rm(path.join(root,'pages/index/index.js'));await assert.rejects(verifyBuild(root),/清单不一致/)
}))
test('web referenced assets must exist',()=>fixture(async root=>{
 await put(root,'index.html','<script src="/assets/main.js"></script>');await assert.rejects(checkPlatform(root,'h5'),/资源缺失/)
 await put(root,'assets/main.js','console.log(1)');assert.equal((await checkPlatform(root,'h5')).files,2)
}))
test('complete build produces three verified outputs, archive and source metadata without touching dev',()=>fixture(async root=>{
 const {project,compiler}=await buildFixture(root);assert.equal((await run(project,compiler)).code,0)
 for(const platform of ['h5','mp-weixin','app'])await verifyBuild(path.join(project,'unpackage/verified',platform))
 assert.equal(await fs.readFile(path.join(project,'unpackage/dist/dev/mp-weixin/keep.txt'),'utf8'),'HBuilderX owns this')
 assert.equal((await run(project,compiler,['mp-weixin'])).code,0)
 const history=await fs.readdir(path.join(project,'unpackage/verified/history'));assert.equal(history.length,1)
 const meta=JSON.parse(await fs.readFile(path.join(project,'unpackage/verified/mp-weixin/build-meta.json'),'utf8'));assert.equal(meta.compiler.version,'fixture-1');assert.ok(meta.source['App.vue'])
 await assert.rejects(fs.access(path.join(project,'unpackage/verified/.build-lock')))
}))
test('failed second platform leaves both previous outputs intact and removes build lock',()=>fixture(async root=>{
 const {project,compiler}=await buildFixture(root);assert.equal((await run(project,compiler)).code,0)
 const before=await fileInventory(path.join(project,'unpackage/verified/h5'))
 const beforeMp=await fileInventory(path.join(project,'unpackage/verified/mp-weixin'))
 const result=await run(project,compiler,['all'],{FIXTURE_FAIL:'mp-weixin'});assert.equal(result.code,1);assert.match(result.output,/编译失败/)
 assert.ok(sameInventory(before,await fileInventory(path.join(project,'unpackage/verified/h5'))))
 assert.ok(sameInventory(beforeMp,await fileInventory(path.join(project,'unpackage/verified/mp-weixin'))))
 await assert.rejects(fs.access(path.join(project,'unpackage/verified/.build-lock')))
}))
test('scope mismatch prevents replacing previously verified output',()=>fixture(async root=>{
 const {project,compiler}=await buildFixture(root);assert.equal((await run(project,compiler,['mp-weixin'])).code,0)
 const before=await fileInventory(path.join(project,'unpackage/verified/mp-weixin'))
 const result=await run(project,compiler,['mp-weixin'],{FIXTURE_BAD_SCOPE:'yes'});assert.equal(result.code,1);assert.match(result.output,/模板与样式/)
 assert.ok(sameInventory(before,await fileInventory(path.join(project,'unpackage/verified/mp-weixin'))))
}))
test('source edits during build block preview replacement',()=>fixture(async root=>{
 const {project,compiler}=await buildFixture(root);const result=await run(project,compiler,['h5'],{FIXTURE_CHANGE_SOURCE:path.join(project,'App.vue')})
 assert.equal(result.code,1);assert.match(result.output,/源码已改变/);await assert.rejects(fs.access(path.join(project,'unpackage/verified/h5')))
}))
test('existing lock is respected and not removed by another process',()=>fixture(async root=>{
 const {project,compiler}=await buildFixture(root);await put(project,'unpackage/verified/.build-lock/owner.json','existing owner')
 const result=await run(project,compiler);assert.equal(result.code,1);assert.match(result.output,/已有构建/)
 assert.equal(await fs.readFile(path.join(project,'unpackage/verified/.build-lock/owner.json'),'utf8'),'existing owner')
}))
test('unknown platform fails before creating output directories',()=>fixture(async root=>{
 const {project,compiler}=await buildFixture(root);const result=await run(project,compiler,['unknown']);assert.equal(result.code,1);assert.match(result.output,/平台只能/)
 await assert.rejects(fs.access(path.join(project,'unpackage/verified')))
}))

test('Ctrl+C cancels compiler and removes lock without replacing prior outputs',()=>fixture(async root=>{
 const {project,compiler}=await buildFixture(root);assert.equal((await run(project,compiler,['h5'])).code,0)
 const before=await fileInventory(path.join(project,'unpackage/verified/h5')),marker=path.join(root,'started')
 const child=spawn(process.execPath,[path.join(project,'tools/build.mjs'),'h5'],{env:{...process.env,UNI_COMPILER_HOME:compiler,FIXTURE_PAUSE:marker}})
 let output='';child.stdout.on('data',b=>output+=b);child.stderr.on('data',b=>output+=b)
 const completed=new Promise((resolve,reject)=>{child.once('error',reject);child.once('exit',code=>resolve(code))})
 try {
  for(let i=0;i<100;i++){try{await fs.access(marker);break}catch{if(i===99)throw new Error('fixture compiler not started');await new Promise(r=>setTimeout(r,10))}}
  child.kill('SIGINT');assert.equal(await completed,130);assert.match(output,/构建已取消/)
  assert.ok(sameInventory(before,await fileInventory(path.join(project,'unpackage/verified/h5'))))
  await assert.rejects(fs.access(path.join(project,'unpackage/verified/.build-lock')))
 } finally {if(child.exitCode===null)child.kill('SIGTERM')}
}))

// App产物是原生壳加载的资源；测试不把它当成APK/IPA或手机运行结果。
test('App resources parse compiler route map without executing it',()=>fixture(async root=>{
 await appFixture(root);const checked=await checkPlatform(root,'app');assert.equal(checked.pages,1);assert.equal(checked.installable,false)
 await writeMeta(root,'app');assert.equal((await verifyBuild(root)).platform,'app')
 await put(root,'app-config-service.js','const __uniConfig = {"entryPagePath":"pages/index/index"};\nconst __uniRoutes = [{"path":"pages/index/index"}].map(()=>{throw new Error("must not execute")});')
 assert.equal((await checkPlatform(root,'app')).pages,1)
}))
test('App resources reject missing runtime files and page styles',()=>fixture(async root=>{
 await appFixture(root);await fs.rm(path.join(root,'uni-app-view.umd.js'));await assert.rejects(checkPlatform(root,'app'),/App资源文件缺失/)
 await appFixture(root);await fs.rm(path.join(root,'pages/index/index.css'));await assert.rejects(checkPlatform(root,'app'),/App页面样式缺失/)
}))
test('App validates DCloud identity, launch path, duplicate routes and entry page',()=>fixture(async root=>{
 await appFixture(root);const name=path.join(root,'manifest.json'),manifest=JSON.parse(await fs.readFile(name,'utf8'))
 await put(root,'manifest.json',JSON.stringify({...manifest,id:'wx0000000000000000'}));await assert.rejects(checkPlatform(root,'app'),/DCloud AppID/)
 await put(root,'manifest.json',JSON.stringify({...manifest,launch_path:'other.html'}));await assert.rejects(checkPlatform(root,'app'),/启动资源路径/)
 await appFixture(root)
 for(const routes of [[{path:'pages/index/index'},{path:'pages/index/index'}],[{path:'pages/other/index'}],[]]){
 await put(root,'app-config-service.js','const __uniConfig = {"entryPagePath":"pages/index/index"};\nconst __uniRoutes = '+JSON.stringify(routes)+'.map(route=>route);');await assert.rejects(checkPlatform(root,'app'),/App路由/)
 }
}))
test('failed App compile preserves all three previous verified outputs',()=>fixture(async root=>{
 const {project,compiler}=await buildFixture(root);assert.equal((await run(project,compiler)).code,0)
 const platforms=['h5','mp-weixin','app'],before=await Promise.all(platforms.map(p=>fileInventory(path.join(project,'unpackage/verified',p))))
 const result=await run(project,compiler,['all'],{FIXTURE_FAIL:'app'});assert.equal(result.code,1);assert.match(result.output,/编译失败：app/)
 for(let i=0;i<platforms.length;i++)assert.ok(sameInventory(before[i],await fileInventory(path.join(project,'unpackage/verified',platforms[i]))))
 await assert.rejects(fs.access(path.join(project,'unpackage/verified/.build-lock')))
}))

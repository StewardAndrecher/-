import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { inventory, verifyWeb } from '../src/web-artifacts.mjs'
async function fixture(action){const root=await fs.mkdtemp(path.join(os.tmpdir(),'campus-web-artifacts-'));try{await action(root)}finally{await fs.rm(root,{recursive:true,force:true})}}
async function createWeb(root){await fs.mkdir(root,{recursive:true});await fs.writeFile(path.join(root,'index.html'),'<div>app</div>');await fs.writeFile(path.join(root,'app.js'),'console.log(1)');await fs.writeFile(path.join(root,'build-meta.json'),JSON.stringify({schemaVersion:1,platform:'h5',artifacts:await inventory(root)}))}
test('valid web metadata accepted; modified and extra files rejected',()=>fixture(async root=>{
 await createWeb(root);await verifyWeb(root);await fs.writeFile(path.join(root,'app.js'),'modified');await assert.rejects(verifyWeb(root),/校验失败/)
 await createWeb(root);await fs.writeFile(path.join(root,'extra.js'),'extra');await assert.rejects(verifyWeb(root),/校验失败/)
}))
test('wrong platform, missing index and symlinks rejected',()=>fixture(async root=>{
 await createWeb(root);const meta=JSON.parse(await fs.readFile(path.join(root,'build-meta.json'),'utf8'));meta.platform='mp-weixin';await fs.writeFile(path.join(root,'build-meta.json'),JSON.stringify(meta));await assert.rejects(verifyWeb(root),/校验失败/)
 await createWeb(root);await fs.rm(path.join(root,'index.html'));await assert.rejects(verifyWeb(root),/校验失败/)
 await createWeb(root);await fs.symlink(path.join(root,'index.html'),path.join(root,'link.html'));await assert.rejects(verifyWeb(root),/符号链接/)
}))
test('sync verifies source and copied output; failed sync preserves previous web',()=>fixture(async root=>{
 const project=path.join(root,'desktop'),client=path.join(root,'client'),web=path.join(client,'unpackage/verified/h5')
 await fs.mkdir(path.join(project,'scripts'),{recursive:true})
 const scripts=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../scripts')
 await fs.copyFile(path.join(scripts,'sync-web.mjs'),path.join(project,'scripts/sync-web.mjs'))
 await fs.mkdir(path.join(project,'src'),{recursive:true});await fs.copyFile(path.resolve(scripts,'../src/web-artifacts.mjs'),path.join(project,'src/web-artifacts.mjs'))
 await createWeb(web)
 const run=()=>spawnSync(process.execPath,[path.join(project,'scripts/sync-web.mjs')],{env:{...process.env,CAMPUS_CLIENT_DIR:client},encoding:'utf8'})
 assert.equal(run().status,0);await verifyWeb(path.join(project,'web'));const before=await inventory(path.join(project,'web'))
 await fs.writeFile(path.join(web,'app.js'),'corrupted');assert.equal(run().status,1);assert.deepEqual(await inventory(path.join(project,'web')),before)
}))

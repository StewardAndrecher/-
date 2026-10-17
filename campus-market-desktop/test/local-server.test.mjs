import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import http from 'node:http'
import net from 'node:net'
import { createHash } from 'node:crypto'
import { startLocalServer, contentSecurityPolicy } from '../src/local-server.mjs'

async function fixture(action) {
 const directory=await fs.mkdtemp(path.join(os.tmpdir(),'campus-desktop-'))
 const root=path.join(directory,'web');await fs.mkdir(root)
 await fs.writeFile(path.join(root,'index.html'),'<script>window.example=1</script><script src="/main.js"></script><div>校园集市</div>')
 await fs.writeFile(path.join(root,'main.js'),'console.log("app")')
 await fs.writeFile(path.join(root,'main.css'),'.title{display:block}')
 await fs.writeFile(path.join(directory,'secret.txt'),'fixture private file')
 const local=await startLocalServer({root,port:0})
 try {await action({local,root,directory})}finally{await local.close();await fs.rm(directory,{recursive:true,force:true})}
}
function request(origin,pathname='/',options={}) {
 const url=new URL(origin)
 return new Promise((resolve,reject)=>{
  const r=http.request({host:url.hostname,port:url.port,path:pathname,method:options.method||'GET',headers:options.headers||{}},response=>{
   const bytes=[];response.on('data',b=>bytes.push(b));response.on('end',()=>resolve({status:response.statusCode,headers:response.headers,body:Buffer.concat(bytes).toString()}))
  });r.on('error',reject);r.end()
 })
}
test('GET and HEAD serve correct local resources and MIME with no-store',()=>fixture(async({local})=>{
 const page=await request(local.origin);assert.equal(page.status,200);assert.match(page.body,/校园集市/);assert.match(page.headers['content-type'],/text\/html/);assert.equal(page.headers['cache-control'],'no-store');assert.equal(page.headers['x-content-type-options'],'nosniff')
 const head=await request(local.origin,'/main.js',{method:'HEAD'});assert.equal(head.status,200);assert.equal(head.body,'');assert.match(head.headers['content-type'],/javascript/)
 assert.equal((await request(local.origin,'/main.css')).status,200)
}))
test('only GET/HEAD accepted and unknown paths do not fall back to arbitrary files',()=>fixture(async({local})=>{
 for(const method of ['POST','PUT','DELETE']){const res=await request(local.origin,'/',{method});assert.equal(res.status,405);assert.equal(res.headers.allow,'GET, HEAD')}
 assert.equal((await request(local.origin,'/missing')).status,404);assert.equal((await request(local.origin,'/api/products')).status,404)
}))
test('foreign Host and absolute foreign URL rejected',()=>fixture(async({local})=>{
 assert.equal((await request(local.origin,'/',{headers:{Host:'foreign.example'}})).status,403)
 assert.equal((await request(local.origin,'http://foreign.example/main.js')).status,403)
}))
test('encoded traversal cannot read parent files',()=>fixture(async({local})=>{
 for(const pathname of ['/%2e%2e%2fsecret.txt','/..%2fsecret.txt','/%252e%252e%252fsecret.txt']){
  const result=await request(local.origin,pathname);assert.ok([403,404].includes(result.status));assert.ok(!result.body.includes('fixture private file'))
 }
}))
test('symlinks outside bundled web directory rejected',()=>fixture(async({local,root,directory})=>{
 await fs.symlink(path.join(directory,'secret.txt'),path.join(root,'leak.txt'))
 assert.equal((await request(local.origin,'/leak.txt')).status,403)
}))
test('invalid encoding, null bytes and backslashes rejected',()=>fixture(async({local})=>{
 for(const pathname of ['/%ZZ','/x%00','/%5csecret.txt'])assert.equal((await request(local.origin,pathname)).status,400)
}))
test('CSP allows exact existing inline script hash and intended backend only',()=>{
 const content='window.example=1',csp=contentSecurityPolicy(`<script>${content}</script><script src="/main.js"></script>`)
 const hash=createHash('sha256').update(content).digest('base64');assert.ok(csp.includes(`'sha256-${hash}'`));assert.match(csp,/connect-src 'self' http:\/\/localhost:8080 http:\/\/127\.0\.0\.1:8080/)
 assert.ok(!csp.includes('unsafe-eval'));assert.match(csp,/frame-src 'none'/);assert.match(csp,/script-src 'self' 'sha256-/)
})
test('occupied port fails instead of loading another service',()=>fixture(async({local,root})=>{
 await assert.rejects(startLocalServer({root,port:Number(new URL(local.origin).port)}),e=>e.code==='EADDRINUSE')
}))
test('server only listens on loopback and closes cleanly',()=>fixture(async({local})=>{
 assert.equal(local.server.address().address,'127.0.0.1');assert.ok(Number(new URL(local.origin).port)>0)
}))

test('shutdown closes unfinished connections and remains idempotent',()=>fixture(async({local})=>{
 const url=new URL(local.origin),socket=net.connect(Number(url.port),url.hostname)
 await new Promise((resolve,reject)=>{socket.once('connect',resolve);socket.once('error',reject)})
 socket.write('GET / HTTP/1.1\r\nHost: '+url.host+'\r\n')
 const closed=new Promise(resolve=>socket.once('close',resolve))
 await local.close();await closed;await local.close();assert.equal(local.server.listening,false)
}))

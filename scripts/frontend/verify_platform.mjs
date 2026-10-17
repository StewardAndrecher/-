import assert from 'node:assert/strict'
import fs from 'node:fs'
const config = await import('../.frontend-work/esm/config/server.js')
const {resolveServerConfig,SERVER_ENDPOINTS}=config
assert.equal(config.SERVER_CONFIG.platform,'web')
assert.equal(config.API_BASE_URL,'http://localhost:8080')
assert.equal(resolveServerConfig('weixin','develop').baseUrl,'http://127.0.0.1:8080')
for(const version of ['trial','release','bad',''])assert(resolveServerConfig('weixin',version).error)
assert(resolveServerConfig('unsupported').error)
const deployed={...SERVER_ENDPOINTS,weixinTrial:'https://trial.example.edu/',weixinRelease:'https://api.example.edu'}
assert.equal(resolveServerConfig('weixin','trial',deployed).baseUrl,'https://trial.example.edu')
assert.equal(resolveServerConfig('weixin','release',deployed).baseUrl,'https://api.example.edu')
for(const address of ['http://api.example.edu','https://localhost','https://127.0.0.1','https://api.example.edu/api','https://user:password@api.example.edu','https://api.example.edu?token=abc','https://api.example.edu#test','https://api.example.edu:0','https://api.example.edu:65536','not-a-url']) {
 assert(resolveServerConfig('weixin','release',{...deployed,weixinRelease:address}).error,address)
}
const user={id:1,username:'student_01',nickname:'测试'}
const id='aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee'
for(const version of ['develop','trial','unknown']) {
 const calls=[]
 globalThis.uni={getDeviceInfo:()=>({platform:'devtools'}),getAccountInfoSync:()=>{if(version==='unknown')throw new Error('unsupported');return {miniProgram:{envVersion:version}}},request:o=>calls.push(o),uploadFile:o=>calls.push(o)}
 const root=`../.frontend-work/esm-weixin-${version}`
 const current=await import(root+'/config/server.js')
 const session=await import(root+'/utils/session.js')
 const api=await import(root+'/api/request.js')
 const image=await import(root+'/api/image.js')
 session.saveSession({accessToken:'A'.repeat(43),tokenType:'Bearer',expiresInSeconds:7200,user})
 if(version==='develop') {
  assert.equal(current.SERVER_CONFIG.platform,'weixin')
  let pending=api.request({path:'/api/products/7/comments',method:'POST',auth:true,data:{content:'留言',requestId:id}})
  assert.equal(calls.at(-1).url,'http://127.0.0.1:8080/api/products/7/comments')
  assert.equal(calls.at(-1).header.Authorization,'Bearer '+'A'.repeat(43))
  calls.at(-1).success({statusCode:200,data:{replayed:true}});assert.equal((await pending).replayed,true)
  assert.equal(image.imageUrl(id),'http://127.0.0.1:8080/api/images/'+id)
  pending=image.uploadImage('/tmp/test.png')
  assert.equal(calls.at(-1).url,'http://127.0.0.1:8080/api/images')
  assert.equal(calls.at(-1).header['Content-Type'],undefined)
  calls.at(-1).success({statusCode:201,data:JSON.stringify({id,url:'/api/images/'+id,width:72,height:72})});assert.equal((await pending).id,id)
 } else {
  await assert.rejects(api.request({path:'/api/products'}),e=>e.code==='CLIENT_CONFIG_ERROR'&&e.statusCode===-1)
  await assert.rejects(image.uploadImage('/tmp/test.png'),e=>e.code==='CLIENT_CONFIG_ERROR')
  assert.equal(image.imageUrl(id),'');assert.equal(calls.length,0)
 }
}
const app=JSON.parse(fs.readFileSync('campus-market-client/unpackage/verified/mp-weixin/app.json','utf8'))
assert.equal(app.pages.length,12)
for(const page of app.pages)for(const extension of ['js','json','wxml','wxss'])assert(fs.existsSync(`campus-market-client/unpackage/verified/mp-weixin/${page}.${extension}`))
const project=JSON.parse(fs.readFileSync('campus-market-client/unpackage/verified/mp-weixin/project.config.json','utf8'))
assert.equal(project.appid,'wx0000000000000000');assert.equal(project.compileType,'miniprogram')
const platformModule=fs.readFileSync('campus-market-client/unpackage/verified/mp-weixin/config/server.js','utf8')
assert(platformModule.includes('getAccountInfoSync'));assert(platformModule.includes('weixin'))
console.log('PASS: Web/WeChat platform selection, develop/trial/release, invalid/unknown config blocks requests and uploads, shared API/image base, Token/multipart, supplied AppID, and all twelve compiled page artifacts.')

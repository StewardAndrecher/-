import assert from 'node:assert/strict'
import fs from 'node:fs'
let calls=[],uploads=[]
globalThis.uni={getDeviceInfo:()=>({platform:'android'}),getAccountInfoSync:()=>{throw new Error('App must not use WeChat API')},request:o=>{calls.push(o);return {abort(){}}},uploadFile:o=>uploads.push(o)}
const config=await import('../.frontend-work/esm-app-develop/config/server.js')
const {SERVER_CONFIG,SERVER_ENDPOINTS,resolveServerConfig,DIAGNOSTICS_ENABLED}=config
const lan='http://192.168.1.100:8080'
assert.equal(SERVER_CONFIG.baseUrl,lan);assert.equal(SERVER_CONFIG.platform,'app');assert.equal(SERVER_CONFIG.devicePlatform,'android');assert.equal(DIAGNOSTICS_ENABLED,true)
for(const device of ['android','ios']){
 assert.equal(resolveServerConfig('app','develop',SERVER_ENDPOINTS,device).baseUrl,lan)
 assert.equal(resolveServerConfig('app','release',{...SERVER_ENDPOINTS,appRelease:'https://api.example.edu'},device).baseUrl,'https://api.example.edu')
 assert.equal(resolveServerConfig('app','release',SERVER_ENDPOINTS,device).baseUrl,'')
}
for(const endpoint of ['http://localhost:8080','http://127.0.0.1:8080','http://127.10.0.1:8080','http://u:p@host:8080','http://host:70000','http://host:0','http://host/api','http://host?q=1']) assert.ok(resolveServerConfig('app','develop',{...SERVER_ENDPOINTS,appDevelop:endpoint},'android').error,endpoint)
for(const endpoint of ['http://api.example.edu','https://127.0.0.1:8080','https://localhost:8080'])assert.ok(resolveServerConfig('app','release',{...SERVER_ENDPOINTS,appRelease:endpoint},'ios').error)
assert.ok(resolveServerConfig('app','develop',SERVER_ENDPOINTS,'unknown').error);assert.ok(resolveServerConfig('app','unknown',SERVER_ENDPOINTS,'android').error)
const request=await import('../.frontend-work/esm-app-develop/api/request.js'),session=await import('../.frontend-work/esm-app-develop/utils/session.js'),image=await import('../.frontend-work/esm-app-develop/api/image.js')
const token='A'.repeat(43),user={id:1,username:'student_01',nickname:'小明'}
const login=()=>session.saveSession({accessToken:token,tokenType:'Bearer',expiresInSeconds:7200,user})
let pending=request.request({path:'/api/products'});assert.equal(calls.at(-1).url,lan+'/api/products');assert.equal(calls.at(-1).header.Authorization,undefined);calls.at(-1).success({statusCode:200,data:{records:[]}});await pending
login();pending=request.request({path:'/api/products/favorites',auth:true});assert.equal(calls.at(-1).header.Authorization,'Bearer '+token);calls.at(-1).success({statusCode:401,data:{message:'请重新登录'}});await assert.rejects(pending,e=>e.statusCode===401);assert.equal(session.getToken(),'')
login();const id='aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee',result={id,url:'/api/images/'+id,width:100,height:80}
pending=image.uploadImage('/device/photo.png');assert.equal(uploads.at(-1).url,lan+'/api/images');assert.equal(uploads.at(-1).header.Authorization,'Bearer '+token);assert.equal(uploads.at(-1).filePath,'/device/photo.png');uploads.at(-1).success({statusCode:201,data:JSON.stringify(result)});await pending;assert.equal(image.imageUrl(id),lan+'/api/images/'+id)
const diagnosis=await import('../.frontend-work/esm-app-develop/utils/connection-check.js');calls=[];const check=diagnosis.startConnectionCheck();assert.equal(calls.length,2)
for(const o of calls){assert.ok(o.url.startsWith(lan+'/api/health/'));assert.equal(o.header.Authorization,undefined);o.success({statusCode:200,data:{status:'UP'}})}
assert.match((await check.promise).summary.title,/数据库就绪/);assert.equal(session.getToken(),token)
for(const [scenario,device] of [['release','ios'],['unknown-device','unknown'],['unknown-env','android']]){
 calls=[];uploads=[];globalThis.uni.getDeviceInfo=()=>({platform:device})
 const cfg=await import(`../.frontend-work/esm-app-${scenario}/config/server.js`),api=await import(`../.frontend-work/esm-app-${scenario}/api/request.js`),photos=await import(`../.frontend-work/esm-app-${scenario}/api/image.js`),sess=await import(`../.frontend-work/esm-app-${scenario}/utils/session.js`),diag=await import(`../.frontend-work/esm-app-${scenario}/utils/connection-check.js`)
 assert.equal(cfg.API_BASE_URL,'');assert.ok(cfg.SERVER_CONFIG.error);sess.saveSession({accessToken:token,tokenType:'Bearer',expiresInSeconds:7200,user})
 await assert.rejects(api.request({path:'/api/products'}),e=>e.code==='CLIENT_CONFIG_ERROR')
 await assert.rejects(photos.uploadImage('/device/photo.png'),e=>e.code==='CLIENT_CONFIG_ERROR');assert.equal(photos.imageUrl(id),'')
 const answer=await diag.startConnectionCheck().promise;assert.equal(answer.liveness.state,scenario==='unknown-device'?'CONFIG_ERROR':'DISABLED')
 assert.equal(calls.length,0);assert.equal(uploads.length,0)
}
// 编译产物只读核验；不执行原生SDK，也不把mock当手机验收。
const {verifyBuild}=await import('../../campus-market-client/tools/build-check.mjs')
const output=process.argv[2]||'campus-market-client/unpackage/verified/app';const checked=await verifyBuild(output)
assert.equal(checked.pages,14);assert.equal(checked.installable,false)
const manifest=JSON.parse(fs.readFileSync(output+'/manifest.json','utf8'));assert.equal(manifest.name,'校园集市');assert.equal(manifest.id,'__UNI__CAMPUSDEMO')
const compiled=fs.readFileSync(output+'/app-service.js','utf8');assert.ok(compiled.includes('无法识别App设备'));assert.ok(compiled.includes('uni.getDeviceInfo'))
console.log('PASS: Android/iOS developer and production configuration; unsafe/local-only origins blocked; anonymous/authenticated App requests and 401 logout; LAN image upload/preview; anonymous development health probes; production/unknown devices/environment block requests before transport; 14-page production App resources verified. Native device UI/installation not tested.')

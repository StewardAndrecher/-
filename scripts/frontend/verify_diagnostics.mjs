import assert from 'node:assert/strict'
import fs from 'node:fs'
import {createRequire} from 'node:module'
const require=createRequire(import.meta.url)
const {parse,compileScript,compileTemplate}=require(process.env.UNI_COMPILER_HOME+'/node_modules/@vue/compiler-sfc')
const config=await import('../.frontend-work/esm/config/server.js')
const {resolveServerConfig,SERVER_ENDPOINTS}=config
assert.equal(resolveServerConfig('weixin','develop',SERVER_ENDPOINTS,'devtools').baseUrl,'http://127.0.0.1:8080')
for(const platform of ['ios','android','ohos','harmonyos','windows','mac'])assert.equal(resolveServerConfig('weixin','develop',SERVER_ENDPOINTS,platform).baseUrl,SERVER_ENDPOINTS.weixinDevice)
assert(resolveServerConfig('weixin','develop',SERVER_ENDPOINTS,'unknown').error)
assert(resolveServerConfig('weixin','develop',{...SERVER_ENDPOINTS,weixinDevice:''},'ios').error)
let calls=[]
globalThis.uni={request:o=>{calls.push(o);return {abort:()=>o.fail({errMsg:'abort'})}}}
const api=await import('../.frontend-work/esm/utils/connection-check.js')
const session=await import('../.frontend-work/esm/utils/session.js')
session.saveSession({accessToken:'A'.repeat(43),tokenType:'Bearer',expiresInSeconds:7200,user:{id:1,username:'student_01',nickname:'测试'}})
const begin=()=>{calls=[];const handle=api.startConnectionCheck();assert.equal(calls.length,2);assert(calls.every(o=>o.method==='GET'&&o.timeout===5000&&o.header.Authorization===undefined));return handle}
let handle=begin();calls[0].success({statusCode:200,data:{status:'UP'}});calls[1].success({statusCode:200,data:{status:'UP'}})
assert.match((await handle.promise).summary.title,/数据库就绪/)
handle=begin();calls[0].success({statusCode:200,data:{status:'UP'}});calls[1].success({statusCode:503,data:{status:'DOWN'}})
assert.match((await handle.promise).summary.title,/尚未就绪/)
handle=begin();calls[0].fail({errMsg:'request:fail timeout'});calls[1].fail({errMsg:'connection refused'})
let result=await handle.promise;assert.equal(result.liveness.state,'TIMEOUT');assert.equal(result.readiness.state,'NETWORK_ERROR');assert.match(result.summary.hint,/不能仅凭超时/)
handle=begin();calls[0].success({statusCode:404,data:{}});calls[1].success({statusCode:200,data:'<html>'})
result=await handle.promise;assert.equal(result.readiness.state,'INVALID_RESPONSE');assert.match(result.summary.title,/响应异常/)
handle=begin();calls[0].success({statusCode:401,data:{}});calls[1].success({statusCode:503,data:{status:'OUT_OF_SERVICE'}});await handle.promise
assert.equal(session.getToken(),'A'.repeat(43),'diagnosis must never clear login')
handle=begin();handle.abort();calls[0].success({statusCode:200,data:{status:'UP'}});result=await handle.promise;assert.equal(result.liveness.state,'CANCELLED');assert.equal(result.readiness.state,'CANCELLED')
for(const version of ['trial','release','bad-device']) {
 calls=[];globalThis.uni={getAccountInfoSync:()=>({miniProgram:{envVersion:version==='bad-device'?'develop':version}}),getDeviceInfo:()=>({platform:'unknown'}),request:o=>calls.push(o)}
 const module=await import(`../.frontend-work/esm-weixin-${version}/utils/connection-check.js`)
 const checked=await module.startConnectionCheck().promise
 assert.equal(calls.length,0)
 assert.equal(checked.liveness.state,version==='bad-device'?'CONFIG_ERROR':'DISABLED')
}
const text=fs.readFileSync('campus-market-client/pages/dev/connection.vue','utf8')
const {descriptor,errors}=parse(text);assert.deepEqual(errors,[])
const compiled=compileScript(descriptor,{id:'connection'});assert.deepEqual(compileTemplate({source:descriptor.template.content,id:'connection',filename:'connection.vue',compilerOptions:{bindingMetadata:compiled.bindings}}).errors,[])
const script=descriptor.scriptSetup.content.replace(/^import .*$/mg,'')
const factory=new Function('ref','onUnload','SERVER_CONFIG','DIAGNOSTICS_ENABLED','startConnectionCheck','uni',script+'\nreturn {runCheck,working,report,back};')
let unload,finish,writes=0,aborts=0
const make=enabled=>factory(value=>({value}),cb=>unload=cb,config.SERVER_CONFIG,enabled,()=>{writes++;return {promise:new Promise(r=>finish=r),abort:()=>{aborts++;finish({summary:{title:'取消'}})}}},{navigateBack:()=>{}})
let page=make(true);let pending=page.runCheck();await page.runCheck();assert.equal(writes,1);assert.equal(page.working.value,true);finish({summary:{title:'成功'}});await pending;assert.equal(page.report.value.summary.title,'成功');assert.equal(page.working.value,false)
pending=page.runCheck();unload();await pending;assert.equal(aborts,1);assert.equal(page.report.value,null,'unloaded page ignores late result')
page=make(false);await page.runCheck();assert.equal(writes,2,'production page cannot run diagnosis')
console.log('PASS: devtools/device address selection; two anonymous parallel probes; healthy/not-ready/timeout/invalid HTTP; no login side effects; cancellation and late responses; trial/release disabled; malformed device config blocked; SFC, duplicate guard, unload and disabled page.')

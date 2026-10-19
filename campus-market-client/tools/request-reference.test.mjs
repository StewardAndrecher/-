import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import { pathToFileURL } from 'node:url'
import { responseRequestId, withRequestReference } from '../utils/request-reference.mjs'
const id='0123456789abcdef0123456789abcdef'
const dataUrl=source=>'data:text/javascript;base64,'+Buffer.from(source).toString('base64')
let generation=0
async function fixture() {
  const calls=[],cleared=[]
  globalThis.__refSession={token:'session-fixture',cleared}
  globalThis.__refConfig={error:''}
  globalThis.uni={request:o=>calls.push(o),uploadFile:o=>calls.push(o)}
  const session=dataUrl('export const getToken=()=>globalThis.__refSession.token;export const clearSession=t=>globalThis.__refSession.cleared.push(t);')
  const config=dataUrl('export const API_BASE_URL="http://localhost:8080";export const SERVER_CONFIG=globalThis.__refConfig;//'+(++generation))
  const reference=pathToFileURL(new URL('../utils/request-reference.mjs',import.meta.url).pathname).href
  let source=await fs.readFile(new URL('../api/request.js',import.meta.url),'utf8')
  source=source.replaceAll('@/utils/session.js',session).replaceAll('@/config/server.js',config).replaceAll('@/utils/request-reference.mjs',reference)
  const requestUrl=dataUrl(source+'\n//'+generation),request=await import(requestUrl)
  let image=await fs.readFile(new URL('../api/image.js',import.meta.url),'utf8')
  image=image.replaceAll('./request.js',requestUrl).replaceAll('@/utils/session.js',session).replaceAll('@/config/server.js',config).replaceAll('@/utils/request-reference.mjs',reference)
  return {...request,...await import(dataUrl(image)),calls,cleared}
}
test('response header names are case insensitive',()=>{
  for(const key of ['X-Request-Id','x-request-id','X-REQUEST-ID']) assert.equal(responseRequestId({[key]:id}),id)
})
test('missing malformed ambiguous or injected identifiers are ignored',()=>{
  for(const headers of [null,undefined,[],{},'header',{'X-Request-Id':id,'x-request-id':id},{'X-Request-Id':[id]},{'X-Request-Id':' '+id},{'X-Request-Id':id.toUpperCase()},{'X-Request-Id':id+'\nsecret'},{'X-Request-Id':id+','+id}]) assert.equal(responseRequestId(headers),'')
})
test('only 5xx responses with a valid server identifier add the reference',()=>{
  for(const status of [500,503,599]) assert.equal(withRequestReference('重试',status,id),`重试（请求编号：${id}）`)
  for(const status of [0,-1,400,401,409,429,600,'500']) assert.equal(withRequestReference('原提示',status,id),'原提示')
  assert.equal(withRequestReference('原提示',500,'invalid'),'原提示')
})
test('request 500 links the returned identifier while preserving status code and auth transport',async()=>{
  const f=await fixture(),promise=f.request({path:'/api/products',method:'POST',data:{title:'fixture'},auth:true})
  assert.equal(f.calls[0].header.Authorization,'Bearer session-fixture');assert.equal(f.calls[0].header['X-Request-Id'],undefined)
  f.calls[0].success({statusCode:500,header:{'x-request-id':id},data:{code:'SERVER_ERROR',message:'服务器暂时无法处理请求'}})
  await assert.rejects(promise,error=>error instanceof f.ApiError && error.statusCode===500 && error.code==='SERVER_ERROR' && error.requestId===id && error.message===`服务器暂时无法处理请求（请求编号：${id}）`)
})
test('malformed server header never enters the displayed error',async()=>{
  const f=await fixture(),promise=f.request({path:'/api/products'})
  f.calls[0].success({statusCode:500,header:{'X-Request-Id':'unsafe-secret'},data:{message:'暂时失败'}})
  await assert.rejects(promise,error=>error.message==='暂时失败' && error.requestId==='')
})
test('network timeout has no fabricated request reference',async()=>{
  const f=await fixture(),promise=f.request({path:'/api/products'});f.calls[0].fail({errMsg:'timeout'})
  await assert.rejects(promise,error=>error.statusCode===0 && error.requestId==='' && !error.message.includes('请求编号'))
})
test('401 retains concise message and the exact session invalidation token',async()=>{
  const f=await fixture(),promise=f.request({path:'/api/users/me',auth:true})
  f.calls[0].success({statusCode:401,header:{'X-Request-Id':id},data:{code:'UNAUTHORIZED',message:'请重新登录'}})
  await assert.rejects(promise,error=>error.message==='请重新登录' && error.requestId===id)
  assert.deepEqual(f.cleared,['session-fixture'])
})
test('successful response shape is unchanged',async()=>{
  const f=await fixture(),data={id:1,title:'教材'},promise=f.request({path:'/api/products/1'})
  f.calls[0].success({statusCode:200,header:{'X-Request-Id':id},data});assert.equal(await promise,data)
})
test('missing auth is rejected locally without a request or identifier',async()=>{
  const f=await fixture();globalThis.__refSession.token=''
  await assert.rejects(f.request({path:'/api/users/me',auth:true}),e=>e.statusCode===401 && e.requestId==='');assert.equal(f.calls.length,0)
})
test('bad client configuration is rejected locally without a request',async()=>{
  const f=await fixture();globalThis.__refConfig.error='地址未配置'
  await assert.rejects(f.request({path:'/api/products'}),e=>e.code==='CLIENT_CONFIG_ERROR' && e.requestId==='');assert.equal(f.calls.length,0)
})
test('upload 503 parses JSON string and uses the same server reference',async()=>{
  const f=await fixture(),promise=f.uploadImage('/tmp/fixture.jpg')
  f.calls[0].success({statusCode:503,header:{'X-REQUEST-ID':id},data:JSON.stringify({message:'上传服务暂时不可用'})})
  await assert.rejects(promise,e=>e.statusCode===503 && e.requestId===id && e.message===`上传服务暂时不可用（请求编号：${id}）`)
})
test('upload 401 clears session without expanding the user message',async()=>{
  const f=await fixture(),promise=f.uploadImage('/tmp/fixture.jpg')
  f.calls[0].success({statusCode:401,header:{'X-Request-Id':id},data:'{"message":"登录已失效"}'})
  await assert.rejects(promise,e=>e.requestId===id && e.message==='登录已失效');assert.deepEqual(f.cleared,['session-fixture'])
})
test('successful image upload still validates and returns its existing shape',async()=>{
  const f=await fixture(),promise=f.uploadImage('/tmp/fixture.jpg'),imageId='01234567-89ab-cdef-0123-456789abcdef'
  const data={id:imageId,url:`/api/images/${imageId}`,width:100,height:100}
  f.calls[0].success({statusCode:201,header:{'X-Request-Id':id},data});assert.deepEqual(await promise,data)
})

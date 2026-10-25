import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {resolveWebEndpoint} from '../utils/web-endpoint.mjs'
import {validateTradeCapabilities} from '../utils/trade-capabilities.mjs'
const local='http://localhost:8080',read=f=>readFileSync(new URL('../'+f,import.meta.url),'utf8')
const location=(origin)=>{const u=new URL(origin);return {origin:u.origin,hostname:u.hostname,protocol:u.protocol}}
for(const origin of ['http://localhost:5173','http://127.0.0.1:5193','http://[::1]:5173','https://localhost']) test('local preview preserves developer endpoint '+origin,()=>assert.deepEqual(resolveWebEndpoint(location(origin),local),{baseUrl:local,error:''}))
for(const origin of ['https://market.campus.edu','https://market.campus.edu:8443']) test('public page uses same-origin HTTPS '+origin,()=>assert.deepEqual(resolveWebEndpoint(location(origin),local),{baseUrl:origin,error:''}))
for(const origin of ['http://market.campus.edu','file:///tmp/index.html','https://127.1.2.3','https://market.invalid','https://10.0.0.1']) test('non-public or insecure origin cannot silently reach developer computer '+origin,()=>{
 const result=resolveWebEndpoint(location(origin),local)
 if(origin==='https://127.1.2.3')assert.equal(result.baseUrl,local)
 else {assert.equal(result.baseUrl,'');assert.ok(result.error)}
})
test('location field mismatch and userinfo cannot select an arbitrary server',()=>{
 for(const value of [{protocol:'https:',hostname:'market.campus.edu',origin:'https://other.campus.edu'},{protocol:'https:',hostname:'market.campus.edu',origin:'https://user:password@market.campus.edu'},null])assert.ok(resolveWebEndpoint(value,local).error)
})
const capability=(enabled=false)=>({paymentMode:enabled?'SIMULATED':'UNAVAILABLE',orderCreationEnabled:enabled,simulatedPaymentEnabled:enabled,message:enabled?'教学演示，不扣款':'在线支付尚未开通'})
test('trade capability validates coherent explicit modes',()=>{for(const enabled of [true,false])assert.deepEqual(validateTradeCapabilities(capability(enabled)),capability(enabled))})
test('missing, inconsistent or unknown capability cannot unlock checkout',()=>{
 for(const v of [null,{}, {...capability(),paymentMode:'WECHAT_PAY'}, {...capability(),orderCreationEnabled:true}, {...capability(true),simulatedPaymentEnabled:false},{...capability(),message:''}])assert.throws(()=>validateTradeCapabilities(v))
})
const ref=value=>({value})
function productFixture(){
 const props={product:{id:7,version:0,title:'测试',priceInCents:3500},active:true,disabled:false},user=ref({id:2}),state={emits:[],requests:0,writes:0,modals:0,token:'token',hooks:[]}
 let resolveState,resolveCapability
 const waitingState=new Promise(r=>resolveState=r),waitingCapability=new Promise(r=>resolveCapability=r)
 const script=read('components/ProductOrderAction.vue').split('<script setup>')[1].split('</script>')[0].replace(/^import .+$/gm,'')
 const app=new Function('ref','watch','onUnmounted','currentUser','getToken','defineProps','defineEmits','getOrderState','getTradeCapabilities','createOrder','orderPath','orderRequestId','uni',script+';return {load,submit,reset,state,capabilities,message,error,busy}')(
 ref,()=>{},fn=>state.hooks.push(fn),user,()=>state.token,()=>props,()=>((...v)=>state.emits.push(v)),()=>{state.requests++;return waitingState},()=>waitingCapability,()=>{state.writes++;throw new Error('unexpected write')},id=>'/'+id,()=> 'fixture', {showModal:()=>state.modals++})
 return {props,user,state,app,resolveState,resolveCapability}
}
test('disabled payment preserves product availability for purchase-intent UI but cannot create an order',async()=>{
 const f=productFixture(),pending=f.app.load();f.resolveState({available:true,myOrderId:null,message:'商品可交易'});f.resolveCapability(capability());await pending
 assert.deepEqual(f.state.emits.at(-1),['availability',true]);assert.equal(f.app.message.value,'在线支付尚未开通')
 await f.app.submit();assert.equal(f.state.writes,0);assert.equal(f.state.modals,0)
})
test('late capability result cannot reopen a hidden product component',async()=>{
 const f=productFixture(),pending=f.app.load();f.props.active=false;f.app.reset();f.resolveState({available:true});f.resolveCapability(capability(true));await pending
 assert.equal(f.app.state.value,null);assert.equal(f.app.capabilities.value,null);assert.equal(f.state.writes,0)
})
test('account switch discards the old capability and product result',async()=>{
 const f=productFixture(),pending=f.app.load();f.state.token='new-token';f.props.active=false;f.app.reset();f.resolveState({available:true});f.resolveCapability(capability(true));await pending;assert.equal(f.app.state.value,null);assert.equal(f.app.busy.value,false)
})
test('unmounted product component ignores late response',async()=>{
 const f=productFixture(),pending=f.app.load();f.state.hooks[0]();f.resolveState({available:true});f.resolveCapability(capability(true));await pending;assert.equal(f.app.state.value,null)
})
test('capability API is an anonymous read and rejects an inconsistent server body',async()=>{
 const source=read('api/trade-capabilities.js').replace(/^import .+$/gm,'').replace('export async function','async function')
 let call
 const api=new Function('request','validateTradeCapabilities',source+';return getTradeCapabilities')(async options=>{call=options;return capability()},validateTradeCapabilities)
 await api();assert.deepEqual(call,{path:'/api/trade-capabilities'})
})

import fs from 'node:fs';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const { parse } = require(process.env.UNI_COMPILER_HOME+'/node_modules/@vue/compiler-sfc');
const root = 'campus-market-client/';
const read = p => fs.readFileSync(root+p,'utf8');
const strip = t => t.replace(/^import .*$/mg,'').replaceAll('export ','');
const utils = new Function(strip(read('utils/order.js'))+';return {validOrder,validOrderTime,orderId,orderStatus};')();
const timelineUtils=new Function('validOrder','validOrderTime',strip(read('utils/order-timeline.js'))+';return{compatibleOrderProgress,orderEventLabel,validOrderTimeline};')(utils.validOrder,utils.validOrderTime);
const pending = {id:4,productId:11,productTitleSnapshot:'模拟付款台灯',priceInCentsSnapshot:4800,productVersionSnapshot:0,buyer:{id:2,nickname:'买家'},seller:{id:1,nickname:'卖家'},status:'PENDING_PAYMENT',createdAt:'2026-10-18T10:00:00',cancelledAt:null,paymentDueAt:'2026-10-18T10:15:00',paidAt:null,closedAt:null,paymentKind:null,deliveredAt:null,completedAt:null};
const paid = {...pending,status:'PAID',paidAt:'2026-10-18T10:05:00.123456',paymentKind:'SIMULATED'};
const closed = {...pending,status:'CLOSED_TIMEOUT',closedAt:'2026-10-18T10:15:00'};
for(const row of [pending,paid,closed])assert(utils.validOrder(row));
for(const row of [{...paid,paymentKind:'REAL'},{...paid,paymentKind:null,deliveredAt:null,completedAt:null},{...paid,paidAt:pending.paymentDueAt},{...paid,paidAt:'2026-10-18T09:59:59'},{...closed,closedAt:'2026-10-18T10:14:59'},{...closed,paidAt:paid.paidAt},{...pending,paymentDueAt:'bad'},{...pending,paymentDueAt:pending.createdAt},{...paid,cancelledAt:paid.paidAt},{...pending,status:'COMPLETED'}])assert(!utils.validOrder(row),JSON.stringify(row));
assert.match(utils.orderStatus('PAID'),/模拟付款/);assert.equal(utils.orderStatus('CLOSED_TIMEOUT'),'超时关闭');
let response={order:paid,replayed:false},calls=[];
const api=new Function('request','orderId','validOrder',strip(read('api/order.js'))+';return{simulateOrderPayment,cancelOrder};')(async q=>{calls.push(q);return response},utils.orderId,utils.validOrder);
await api.simulateOrderPayment(4);assert.deepEqual(calls[0],{path:'/api/orders/4/simulate-payment',method:'POST',auth:true});
response={order:paid,replayed:true};assert((await api.simulateOrderPayment(4)).replayed);
for(const row of [pending,closed,{...paid,id:7}]){response={order:row,replayed:false};await assert.rejects(api.simulateOrderPayment(4));}
await assert.rejects(api.simulateOrderPayment(0));response={order:closed,replayed:false};await api.cancelOrder(4);
const flush=()=>new Promise(r=>setImmediate(r)),ref=value=>({value});
function page(row=pending,userId=2){let show,hide,unload,load,modal,token='token-A',serverRow=null;const user=ref({id:userId}),watchers=[],calls=[],api={get:async()=>row,pay:async()=>({order:paid,replayed:false})};
 const env={ref,watch:(g,cb)=>watchers.push(cb),onLoad:cb=>load=cb,onShow:cb=>show=cb,onHide:cb=>hide=cb,onUnload:cb=>unload=cb,currentUser:user,getToken:()=>token,getOrderTimeline:async()=>{calls.push('get');return{order:serverRow||await api.get(),events:[]}},compatibleOrderProgress:timelineUtils.compatibleOrderProgress,orderEventLabel:timelineUtils.orderEventLabel,cancelOrder:async()=>{throw Error('unexpected cancel')},simulateOrderPayment:async()=>{calls.push('pay');const result=await api.pay();serverRow=result.order;return result},orderId:utils.orderId,orderStatus:utils.orderStatus,uni:{showModal:o=>modal=o,navigateTo:()=>{}}};
 const model=new Function(...Object.keys(env),strip(parse(read('pages/order/detail.vue')).descriptor.scriptSetup.content)+';return{pay,load,order,busy,error};')(...Object.values(env));load({id:'4'});show();
 return{model,api,calls,hide,unload,confirm:()=>modal.success({confirm:true}),dismiss:()=>modal.success({confirm:false}),switchUser:()=>{user.value={id:7};token='token-B';watchers[0]()},modal:()=>modal};
}
let p=page();await flush();let op=p.model.pay();assert.match(p.modal().content,/不会扣款/);assert.match(p.modal().content,/48.00/);p.dismiss();await op;assert(!p.calls.includes('pay'));
p=page();await flush();op=p.model.pay();await p.model.pay();p.confirm();await op;assert.equal(p.calls.filter(x=>x==='pay').length,1);assert.equal(p.model.order.value.status,'PAID');
for(const state of [paid,closed,{...pending,status:'CANCELLED'}]){p=page(state);await flush();await p.model.pay();assert(!p.calls.includes('pay'));}
p=page(pending,1);await flush();await p.model.pay();assert(!p.calls.includes('pay'));
for(const action of ['hide','unload','switchUser']){p=page();await flush();op=p.model.pay();p[action]();p.confirm();await op;assert(!p.calls.includes('pay'));assert.equal(p.model.order.value,null);}
p=page();await flush();p.api.pay=async()=>{throw Error('订单已超时关闭')};op=p.model.pay();p.confirm();await op;assert.equal(p.model.order.value,null);assert.match(p.model.error.value,/重新加载/);assert.equal(p.calls.filter(x=>x==='pay').length,1);p.api.get=async()=>closed;await p.model.load();assert.equal(p.model.order.value.status,'CLOSED_TIMEOUT');
p=page();await flush();let resolve;p.api.pay=()=>new Promise(r=>resolve=r);op=p.model.pay();p.confirm();await Promise.resolve();p.hide();resolve({order:paid,replayed:false});await op;assert.equal(p.model.order.value,null);
p=page();await flush();p.api.pay=async()=>({order:{...paid,priceInCentsSnapshot:1},replayed:false});op=p.model.pay();p.confirm();await op;assert.match(p.model.error.value,/不匹配/);assert.equal(p.model.order.value,null);
p=page();await flush();p.api.pay=async()=>({order:paid,replayed:true});op=p.model.pay();p.confirm();await op;assert.equal(p.model.order.value.status,'PAID');
console.log('PASS: four lifecycle DTO states, timestamp boundaries, simulation-only kind and API contract; modal amount/dismiss/confirmation, single-flight, buyer-only guards, replay, hidden/unloaded/account stale response protection, uncertain payment and timeout refresh recovery.');

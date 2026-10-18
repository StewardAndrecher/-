import fs from 'node:fs';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
const require=createRequire(import.meta.url),{parse}=require(process.env.UNI_COMPILER_HOME+'/node_modules/@vue/compiler-sfc');
const root='campus-market-client/',read=p=>fs.readFileSync(root+p,'utf8'),strip=t=>t.replace(/^import .*$/mg,'').replaceAll('export ','');
const utils=new Function(strip(read('utils/order.js'))+';return{validOrder,validOrderTime,orderId,orderStatus};')();
const timelineUtils=new Function('validOrder','validOrderTime',strip(read('utils/order-timeline.js'))+';return{compatibleOrderProgress,orderEventLabel,validOrderTimeline};')(utils.validOrder,utils.validOrderTime);
const paid={id:5,productId:11,productTitleSnapshot:'交付验收台灯',priceInCentsSnapshot:4800,productVersionSnapshot:0,buyer:{id:2,nickname:'买家'},seller:{id:1,nickname:'卖家'},status:'PAID',createdAt:'2026-10-18T11:30:00',paymentDueAt:'2026-10-18T11:45:00',paidAt:'2026-10-18T11:31:00',cancelledAt:null,closedAt:null,paymentKind:'SIMULATED',deliveredAt:null,completedAt:null};
const delivered={...paid,status:'DELIVERED',deliveredAt:'2026-10-18T11:35:00'},completed={...delivered,status:'COMPLETED',completedAt:'2026-10-18T12:00:00'};
for(const row of [paid,delivered,completed])assert(utils.validOrder(row));
for(const row of [{...delivered,deliveredAt:null},{...delivered,deliveredAt:'2026-10-18T11:30:59'},{...delivered,completedAt:completed.completedAt},{...completed,completedAt:null},{...completed,completedAt:'2026-10-18T11:34:59'},{...completed,paymentKind:null},{...completed,paidAt:null},{...paid,deliveredAt:delivered.deliveredAt},{...paid,completedAt:completed.completedAt}])assert(!utils.validOrder(row));
assert.match(utils.orderStatus('DELIVERED'),/待收货/);assert.match(utils.orderStatus('COMPLETED'),/模拟交易/);
let response={order:delivered,replayed:false},requests=[];
const api=new Function('request','orderId','validOrder',strip(read('api/order.js'))+';return{deliverOrder,completeOrder,simulateOrderPayment};')(async q=>{requests.push(q);return response},utils.orderId,utils.validOrder);
await api.deliverOrder(5);assert.deepEqual(requests[0],{path:'/api/orders/5/deliver',method:'POST',auth:true});
response={order:completed,replayed:false};await api.completeOrder(5);assert.equal(requests[1].path,'/api/orders/5/complete');
response={order:completed,replayed:true};assert((await api.deliverOrder(5)).replayed);assert((await api.simulateOrderPayment(5)).replayed);
response={order:delivered,replayed:true};await api.simulateOrderPayment(5);await assert.rejects(api.completeOrder(5));
response={order:paid,replayed:false};await assert.rejects(api.deliverOrder(5));
response={order:{...completed,id:6},replayed:false};await assert.rejects(api.completeOrder(5));
response={order:completed,replayed:'yes'};await assert.rejects(api.completeOrder(5));
await assert.rejects(api.deliverOrder(0));await assert.rejects(api.completeOrder('01'));
const ref=value=>({value}),flush=()=>new Promise(r=>setImmediate(r));
function page(row=paid,userId=1){let load,show,hide,unload,modal,token='A',serverRow=null;const user=ref({id:userId}),watchers=[],calls=[],api={get:async()=>row,deliver:async()=>({order:delivered,replayed:false}),complete:async()=>({order:completed,replayed:false})};
 const env={ref,watch:(g,cb)=>watchers.push(cb),onLoad:cb=>load=cb,onShow:cb=>show=cb,onHide:cb=>hide=cb,onUnload:cb=>unload=cb,currentUser:user,getToken:()=>token,getOrderTimeline:async()=>{calls.push('get');return{order:serverRow||await api.get(),events:[]}},compatibleOrderProgress:timelineUtils.compatibleOrderProgress,orderEventLabel:timelineUtils.orderEventLabel,cancelOrder:async()=>{calls.push('cancel');return{}},simulateOrderPayment:async()=>{calls.push('pay');return{}},deliverOrder:async()=>{calls.push('deliver');const result=await api.deliver();serverRow=result.order;return result},completeOrder:async()=>{calls.push('complete');const result=await api.complete();serverRow=result.order;return result},orderId:utils.orderId,orderStatus:utils.orderStatus,uni:{showModal:o=>modal=o,navigateTo:()=>{}}};
 const model=new Function(...Object.keys(env),strip(parse(read('pages/order/detail.vue')).descriptor.scriptSetup.content)+';return{deliver,complete,cancel,pay,load,order,busy,error};')(...Object.values(env));load({id:'5'});show();
 return{model,api,calls,hide,unload,confirm:()=>modal.success({confirm:true}),dismiss:()=>modal.success({confirm:false}),modal:()=>modal,switchUser:()=>{user.value={id:8};token='B';watchers[0]()}};
}
for(const action of ['deliver','complete']){
 const row=action==='deliver'?paid:delivered,user=action==='deliver'?1:2;
 let p=page(row,user);await flush();let op=p.model[action]();assert.match(p.modal().content,/台灯/);assert.match(p.modal().content,/模拟交易/);p.dismiss();await op;assert(!p.calls.includes(action));
 p=page(row,user);await flush();op=p.model[action]();await p.model[action]();await p.model.load();p.confirm();await op;assert.equal(p.calls.filter(x=>x===action).length,1);assert.equal(p.model.order.value.status,action==='deliver'?'DELIVERED':'COMPLETED');assert.equal(p.model.busy.value,false);
 p=page(row,action==='deliver'?2:1);await flush();await p.model[action]();assert(!p.calls.includes(action));
 for(const end of ['hide','unload','switchUser']){p=page(row,user);await flush();op=p.model[action]();p[end]();p.confirm();await op;assert(!p.calls.includes(action));assert.equal(p.model.order.value,null);}
 p=page(row,user);await flush();p.api[action]=async()=>{throw Error('结果未知')};op=p.model[action]();p.confirm();await op;assert.equal(p.model.order.value,null);assert.match(p.model.error.value,/重新加载/);p.api.get=async()=>action==='deliver'?delivered:completed;await p.model.load();assert.equal(p.model.order.value.status,action==='deliver'?'DELIVERED':'COMPLETED');
 p=page(row,user);await flush();let resolve;p.api[action]=()=>new Promise(r=>resolve=r);op=p.model[action]();p.confirm();await Promise.resolve();p.hide();resolve({order:completed,replayed:true});await op;assert.equal(p.model.order.value,null);
 p=page(row,user);await flush();p.api[action]=async()=>({order:{...completed,priceInCentsSnapshot:1},replayed:false});op=p.model[action]();p.confirm();await op;assert.equal(p.model.order.value,null);assert.match(p.model.error.value,/不匹配/);
 p=page(row,user);await flush();p.api[action]=async()=>({order:completed,replayed:true});op=p.model[action]();p.confirm();await op;assert.equal(p.model.order.value.status,'COMPLETED');
}
let p=page(paid,2);await flush();await p.model.complete();assert(!p.calls.includes('complete'));
p=page(completed,2);await flush();await p.model.complete();await p.model.cancel();await p.model.pay();assert.deepEqual(p.calls,['get']);
p=page(completed,1);await flush();await p.model.deliver();assert.deepEqual(p.calls,['get']);
console.log('PASS: six-state timestamp contracts, simulation labels, API role paths and late replay; confirmation/dismiss, seller delivery/buyer receipt, no skipping, shared busy exclusion, snapshot checks, unknown-result refresh, account/hide/unload and in-flight response guards.');

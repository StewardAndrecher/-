import test from 'node:test'
import assert from 'node:assert/strict'
import { adminOrderFilters, normalizeAdminOrderQuery as normalize, validateAdminOrderPage as validate, createAdminOrderList, adminOrderMoney, adminOrderStatus } from '../utils/admin-orders.mjs'
const row=(id,status='PENDING_PAYMENT')=>({id,productId:id,productTitleSnapshot:'隔离订单教材',priceInCentsSnapshot:3500,status,createdAt:'2026-10-20T10:00',paymentDueAt:'2026-10-20T10:15'})
const page=(records=[row(3),row(2,'PAID'),row(1,'COMPLETED')],change={})=>({records,total:records.length,page:1,size:10,totalPages:1,status:'ALL',orderId:null,sampledAt:'2026-10-20T10:20',...change})
test('query defaults, eight filters and exact ID remain canonical',()=>{
  assert.deepEqual(normalize(),{status:'ALL',orderId:'',page:1,size:10});assert.equal(adminOrderFilters.length,8)
  for(const f of adminOrderFilters)assert.equal(normalize({status:f.value}).status,f.value)
  assert.equal(normalize({orderId:'9007199254740991',page:2147483647,size:20}).orderId,'9007199254740991')
})
test('noncanonical, unsafe and non-string order IDs are rejected',()=>{for(const orderId of ['0','-1','01','1.0','1e3',' 1','true','9007199254740992','1 OR 1=1',1])assert.throws(()=>normalize({orderId}))})
test('invalid filters and pagination cannot enter a request',()=>{
  for(const change of [{status:'paid'},{status:'UNKNOWN'},{page:0},{page:1.5},{page:2147483648},{size:0},{size:21},{size:'10'}])assert.throws(()=>normalize(change))
})
test('empty and mixed-state snapshots are accepted',()=>{assert.equal(validate(page([]),{}).total,0);assert.equal(validate(page(),{}).records.length,3)})
test('filter, ID and page-size echoes must match the request',()=>{
  for(const change of [{status:'PAID'},{orderId:3},{size:20}])assert.throws(()=>validate(page(undefined,change),{}))
})
test('unsafe counts, inconsistent page totals and missing rows are rejected',()=>{
  for(const change of [{total:-1},{total:'3'},{total:Number.MAX_SAFE_INTEGER+1},{totalPages:2},{total:4},{records:[]}])assert.throws(()=>validate(page(undefined,change),{}))
})
test('record IDs and amounts must be positive safe integers',()=>{
  for(const change of [{id:0},{id:'3'},{productId:Number.MAX_SAFE_INTEGER+1},{priceInCentsSnapshot:0},{priceInCentsSnapshot:1.5},{priceInCentsSnapshot:100000000}])assert.throws(()=>validate(page([{...row(3),...change}]),{}))
})
test('every row must match the selected lifecycle status',()=>{assert.throws(()=>validate(page([row(3)],{status:'PAID'}),{status:'PAID'}));assert.equal(validate(page([row(3,'PAID')],{status:'PAID'}),{status:'PAID'}).total,1)})
test('overdue requires pending status and deadline at or before the fixed cutoff',()=>{
  const q={status:'OVERDUE_PENDING'};assert.equal(validate(page([row(3)],{status:q.status}),q).total,1)
  for(const change of [{status:'PAID'},{paymentDueAt:'2026-10-20T10:21'}])assert.throws(()=>validate(page([{...row(3),...change}],{status:q.status}),q))
  assert.equal(validate(page([{...row(3),paymentDueAt:'2026-10-20T10:20'}],{status:q.status}),q).total,1)
})
test('exact search cannot return another ID or multiple orders',()=>{
  assert.equal(validate(page([row(3)],{orderId:3}),{orderId:'3'}).records[0].id,3)
  assert.throws(()=>validate(page([row(2)],{orderId:3}),{orderId:'3'}));assert.throws(()=>validate(page([row(3),row(2)],{orderId:3}),{orderId:'3'}))
})
test('duplicates and non-descending time or ID order are rejected',()=>{
  for(const records of [[row(3),row(3)],[row(2),row(3)],[row(3),{...row(2),createdAt:'2026-10-20T10:01'}]])assert.throws(()=>validate(page(records),{}))
})
test('title and server timestamps must be present and deadline must follow creation',()=>{
  for(const change of [{productTitleSnapshot:''},{productTitleSnapshot:'a'.repeat(51)},{createdAt:'bad'},{paymentDueAt:'2026-10-20T09:00'}])assert.throws(()=>validate(page([{...row(3),...change}]),{}))
  assert.throws(()=>validate(page(undefined,{sampledAt:'bad'}),{}))
})
test('out-of-range page clamps to the last including empty results',()=>{
  assert.equal(validate(page([row(3),row(2)],{total:12,page:2,totalPages:2}),{page:99}).page,2)
  assert.equal(validate(page([]),{page:99}).page,1)
  assert.throws(()=>validate(page([row(3),row(2)],{total:12,page:2,totalPages:2}),{page:1}))
})
test('status labels and exact cents do not imply a real payment',()=>{assert.match(adminOrderStatus('PAID'),/模拟付款/);assert.equal(adminOrderMoney(3501),'35.01');assert.equal(adminOrderStatus('OTHER'),'未知状态')})
const deferred=()=>{let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b});return{promise,resolve,reject}}
test('request criteria are copied rather than changed by caller mutation',async()=>{
  const held=deferred();let captured,state;const q={status:'ALL',orderId:'',page:1,size:10}
  const loader=createAdminOrderList({load:query=>{captured=query;return held.promise},identity:()=>({userId:1,token:'a'}),notify:v=>{state=v}})
  const pending=loader.refresh(q);q.status='PAID';held.resolve(page());await pending;assert.equal(captured.status,'ALL');assert.equal(state.data.status,'ALL')
})
test('a newer query cannot be overwritten by an earlier response',async()=>{
  const old=deferred();let state;const loader=createAdminOrderList({load:q=>q.status==='ALL'?old.promise:Promise.resolve(page([row(3,'PAID')],{status:'PAID'})),identity:()=>({userId:1,token:'a'}),notify:v=>{state=v}})
  const pending=loader.refresh({});await loader.refresh({status:'PAID'});old.resolve(page());await pending;assert.equal(state.data.status,'PAID')
})
test('account and token changes invalidate private results',async()=>{
  for(const next of [{userId:2,token:'a'},{userId:1,token:'b'}]){const old=deferred();let who={userId:1,token:'a'},state;const loader=createAdminOrderList({load:()=>old.promise,identity:()=>who,notify:v=>{state=v}});const pending=loader.refresh({});who=next;await loader.refresh({},false);old.resolve(page());await pending;assert.equal(state.data,null)}
})
test('hidden or disposed pages cannot receive late records',async()=>{
  const old=deferred();let state;const loader=createAdminOrderList({load:()=>old.promise,identity:()=>({userId:1,token:'a'}),notify:v=>{state=v}});const pending=loader.refresh({});await loader.refresh({},false);loader.dispose();old.resolve(page());await pending;assert.equal(state.data,null);assert.equal(state.busy,false)
})
test('failed queries clear old successful records',async()=>{
  let fail=false,state;const loader=createAdminOrderList({load:async()=>{if(fail)throw {statusCode:403,message:'无权限'};return page()},identity:()=>({userId:1,token:'a'}),notify:v=>{state=v}});await loader.refresh({});assert.equal(state.data.total,3);fail=true;await loader.refresh({});assert.equal(state.data,null);assert.equal(state.status,403)
})

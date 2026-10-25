import test from 'node:test'
import assert from 'node:assert/strict'
import { validateOrderOverview as validate, formatOverviewMoney as money, orderOverviewHint as hint } from '../utils/order-overview.mjs'
import { createAdminLoader } from '../utils/admin-access.mjs'
const empty = () => ({total:0,pendingPayment:0,paid:0,delivered:0,completed:0,cancelled:0,closedTimeout:0,completedAmountInCents:0,overduePendingPayment:0,oldestPendingPaymentSeconds:0,sampledAt:'2026-10-20T21:00:00.123456'})
const mixed = () => ({...empty(),total:8,pendingPayment:2,paid:1,delivered:1,completed:2,cancelled:1,closedTimeout:1,completedAmountInCents:12499,overduePendingPayment:1,oldestPendingPaymentSeconds:1200})
test('empty and all six status snapshots are accepted',()=>{assert.deepEqual(validate(empty()),empty());assert.deepEqual(validate(mixed()),mixed())})
test('missing, negative, fractional, unsafe and string counters are rejected',()=>{
  for(const value of [-1,1.5,Infinity,NaN,Number.MAX_SAFE_INTEGER+1,'0',null,undefined])assert.throws(()=>validate({...empty(),paid:value}))
  assert.throws(()=>validate(null));assert.throws(()=>validate({}))
})
test('status partition must equal total',()=>{assert.throws(()=>validate({...mixed(),total:9}));assert.throws(()=>validate({...mixed(),cancelled:0}))})
test('sum cannot exceed safe integer range even if individual counters are safe',()=>{assert.throws(()=>validate({...empty(),total:Number.MAX_SAFE_INTEGER,pendingPayment:Number.MAX_SAFE_INTEGER,paid:1}))})
test('overdue is a pending subset',()=>{assert.throws(()=>validate({...mixed(),overduePendingPayment:3}));assert.throws(()=>validate({...empty(),overduePendingPayment:1}))})
test('pending ages agree with empty and overdue subsets',()=>{
  assert.throws(()=>validate({...empty(),oldestPendingPaymentSeconds:1}));assert.throws(()=>validate({...mixed(),oldestPendingPaymentSeconds:899}))
  assert.equal(validate({...mixed(),oldestPendingPaymentSeconds:900}).overduePendingPayment,1)
})
test('no completed orders means zero completed amount',()=>{assert.throws(()=>validate({...empty(),completedAmountInCents:100}));assert.equal(validate({...empty(),total:1,paid:1}).completedAmountInCents,0)})
test('completed snapshot amount respects one-cent minimum and per-order upper bound',()=>{
  assert.throws(()=>validate({...mixed(),completedAmountInCents:1}));assert.throws(()=>validate({...mixed(),completedAmountInCents:199999999}))
  assert.equal(validate({...empty(),total:1,completed:1,completedAmountInCents:99999999}).completedAmountInCents,99999999)
  const completed=100000000;assert.equal(validate({...empty(),total:completed,completed,completedAmountInCents:Number.MAX_SAFE_INTEGER}).completed,completed)
})
test('server sampling timestamp is required without client timezone conversion',()=>{
  for(const sampledAt of ['',null,'2026-10-20','bad','2026-10-20T21:00:00Z'])assert.throws(()=>validate({...empty(),sampledAt}))
  assert.equal(validate({...empty(),sampledAt:'2026-10-20T21:00'}).sampledAt,'2026-10-20T21:00')
})
test('money formatting preserves integer cents including safe upper boundary',()=>{
  assert.equal(money(0),'0.00');assert.equal(money(1),'0.01');assert.equal(money(12499),'124.99');assert.equal(money(Number.MAX_SAFE_INTEGER),'90071992547409.91')
  for(const value of [-1,1.5,'100',Number.MAX_SAFE_INTEGER+1])assert.throws(()=>money(value))
})
test('overdue hint suggests diagnosis but never claims healthy queues or performs closing',()=>{assert.match(hint(mixed()),/查看本页不会关闭订单/);assert.match(hint(empty()),/不代表.*健康检查通过/)})
const deferred=()=>{let resolve;const promise=new Promise(r=>{resolve=r});return{resolve,promise}}
test('account and token switch cannot accept an old aggregate snapshot',async()=>{
  for(const next of [{userId:2,token:'b'},{userId:1,token:'b'}]){
    let who={userId:1,token:'a'},state;const old=deferred()
    const loader=createAdminLoader(()=>old.promise,()=>who,validate,v=>{state=v});const loading=loader.refresh();who=next;await loader.refresh(false);old.resolve(mixed());await loading;assert.equal(state.data,null)
  }
})
test('failed refresh clears previous numbers instead of displaying false zeros',async()=>{
  let fail=false,state;const loader=createAdminLoader(async()=>{if(fail)throw{statusCode:500,message:'采样失败'};return mixed()},()=>({userId:1,token:'a'}),validate,v=>{state=v})
  await loader.refresh();assert.equal(state.data.total,8);fail=true;await loader.refresh();assert.equal(state.data,null);assert.equal(state.status,500)
})
test('hidden and disposed pages ignore delayed private data',async()=>{
  const old=deferred();let state;const loader=createAdminLoader(()=>old.promise,()=>({userId:1,token:'a'}),validate,v=>{state=v});const loading=loader.refresh();await loader.refresh(false);loader.dispose();old.resolve(mixed());await loading;assert.equal(state.data,null);assert.equal(state.busy,false)
})

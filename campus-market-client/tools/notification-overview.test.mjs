import test from 'node:test'
import assert from 'node:assert/strict'
import { validateNotificationOverview as validate, notificationOverviewHint as hint } from '../utils/notification-overview.mjs'
import { createAdminLoader } from '../utils/admin-access.mjs'
const empty = () => ({ deliveryEnabled:true,pending:0,unconfirmed:0,awaitingDelivery:0,retrying:0,leased:0,delivered:10,oldestPendingSeconds:0,maxPendingAttempts:0,sampledAt:'2026-10-20T20:00:00.123456' })
const mixed = () => ({ ...empty(),pending:4,unconfirmed:3,awaitingDelivery:1,retrying:1,leased:1,oldestPendingSeconds:120,maxPendingAttempts:3 })
test('empty snapshot and overlapping pending subsets are accepted', () => {
  assert.deepEqual(validate(empty()),empty());assert.deepEqual(validate(mixed()),mixed())
})
test('malformed, negative, unsafe and string counts are rejected', () => {
  for (const count of [-1,1.5,Infinity,NaN,Number.MAX_SAFE_INTEGER+1,'0',null]) assert.throws(() => validate({...empty(),delivered:count}))
  assert.throws(() => validate(null));assert.throws(() => validate({...empty(),deliveryEnabled:'true'}))
})
test('inconsistent partitions and subsets are rejected', () => {
  for (const change of [{pending:5},{retrying:5},{leased:5},{maxPendingAttempts:1}]) assert.throws(() => validate({...mixed(),...change}))
})
test('empty pending cannot claim an old or retried task', () => {
  assert.throws(() => validate({...empty(),oldestPendingSeconds:1}));assert.throws(() => validate({...empty(),maxPendingAttempts:2}))
})
test('retry counter agrees with maximum pending attempts', () => {
  assert.throws(() => validate({...mixed(),retrying:0}));assert.throws(() => validate({...mixed(),maxPendingAttempts:0}))
})
test('sampling timestamp is required and not interpreted as client time', () => {
  for(const sampledAt of ['',null,'not a date','2026-10-20','2026-10-20T20:00:00Z']) assert.throws(() => validate({...empty(),sampledAt}))
  assert.equal(validate({...empty(),sampledAt:'2026-10-20T20:00'}).sampledAt,'2026-10-20T20:00')
})
test('disabled delivery takes precedence over backlog age', () => {
  assert.match(hint({...mixed(),deliveryEnabled:false}),/投递已关闭/);assert.match(hint({...empty(),deliveryEnabled:false}),/没有待投递/)
})
test('empty pending never claims broker health', () => {assert.match(hint(empty()),/不代表消息队列健康/)} )
test('sixty-second warning boundary distinguishes processing from accumulation', () => {
  assert.match(hint({...mixed(),oldestPendingSeconds:59}),/异步处理中/);assert.match(hint({...mixed(),oldestPendingSeconds:60}),/超过60秒/)
})
const deferred = () => { let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b});return {promise,resolve,reject} }
test('account switch clears the snapshot and cannot accept previous response',async()=>{
  const old=deferred();let identity={userId:1,token:'a'},state
  const loader=createAdminLoader(()=>old.promise,()=>identity,validate,value=>{state=value})
  const loading=loader.refresh();identity={userId:2,token:'b'};await loader.refresh(false)
  old.resolve(mixed());await loading;assert.equal(state.data,null);assert.equal(state.busy,false)
})
test('failed refresh clears previous snapshot instead of showing healthy zeros',async()=>{
  let fail=false,state
  const loader=createAdminLoader(async()=>{if(fail)throw {statusCode:500,message:'服务暂不可用'};return mixed()},()=>({userId:1,token:'a'}),validate,value=>{state=value})
  await loader.refresh();assert.equal(state.data.pending,4);fail=true;await loader.refresh();assert.equal(state.data,null);assert.equal(state.status,500)
})
test('hidden or disposed page ignores late snapshot',async()=>{
  const delayed=deferred();let state
  const loader=createAdminLoader(()=>delayed.promise,()=>({userId:1,token:'a'}),validate,value=>{state=value})
  const loading=loader.refresh();await loader.refresh(false);loader.dispose();delayed.resolve(mixed());await loading;assert.equal(state.data,null)
})

import test from 'node:test'
import assert from 'node:assert/strict'
import { createNotificationInbox, validateReadNotificationBatch } from '../utils/notifications.mjs'
const notice=(id,readAt=null)=>({id,category:'ORDER',target:'ORDER_DETAIL',resourceId:1,title:'订单进度',body:'私有通知',occurredAt:'2026-10-20 20:00:00',createdAt:'2026-10-20 20:00:01',readAt})
const query=()=>({page:1,size:10,status:'ALL'})
const frame=()=>({page:1,size:10,total:3,records:[notice(9),notice(7),notice(5,'2026-10-20 20:01:00')]})
const marked=ids=>({records:ids.map(id=>notice(id,'2026-10-20 20:02:00'))})
const deferred=()=>{let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b});return {promise,resolve,reject}}
function setup(options={}) {
  let scope={userId:1,token:'one'},state;const batches=[],confirmations=[],navigation=[]
  const controller=createNotificationInbox({identity:()=>scope,load:async()=>frame(),unread:async()=>({unreadCount:2}),read:async()=>notice(9,'2026-10-20 20:01:00'),navigate:async p=>navigation.push(p),
    confirmBatch:async count=>{confirmations.push(count);return true},readBatch:async ids=>{batches.push(ids);return marked(ids)},notify:v=>{state=v},...options})
  return {controller,batches,confirmations,navigation,state:()=>state,setScope:v=>{scope=v}}
}
test('batch receipt accepts exactly the requested identities in either order',()=>{
  const expected=[notice(9),notice(7)];assert.equal(validateReadNotificationBatch(marked([7,9]),expected).records.length,2)
})
test('receipt rejects missing extra duplicate and unrelated identities',()=>{
  for(const ids of [[9],[9,7,3],[9,9],[9,8]])assert.throws(()=>validateReadNotificationBatch(marked(ids),[notice(9),notice(7)]))
})
test('receipt rejects unread or changed business content',()=>{
  for(const patch of [{readAt:null},{resourceId:2},{body:'changed'}])assert.throws(()=>validateReadNotificationBatch({records:[{...notice(9,'read'),...patch}]},[notice(9)]))
})
test('confirmation receives only current-page unread count',async()=>{
  const h=setup();await h.controller.refresh(query());assert.equal(await h.controller.markPageRead(),true);assert.deepEqual(h.confirmations,[2]);assert.deepEqual(h.batches,[[9,7]]);assert.equal(h.navigation.length,0);assert.equal(h.state().page,null);assert.equal(h.state().batchReading,false)
})
test('cancel leaves data intact and sends no write',async()=>{
  const h=setup({confirmBatch:async()=>false});await h.controller.refresh(query());assert.equal(await h.controller.markPageRead(),false);assert.equal(h.batches.length,0);assert.equal(h.state().page.total,3);assert.equal(h.state().unreadCount,2)
})
test('confirmation blocks duplicate batch opening and refresh',async()=>{
  const d=deferred();let loads=0;const h=setup({confirmBatch:()=>d.promise,load:async()=>{loads++;return frame()}});await h.controller.refresh(query());const pending=h.controller.markPageRead();assert.equal(h.state().batchReading,true);assert.equal(await h.controller.markPageRead(),false);assert.equal(await h.controller.open(9),null);await h.controller.refresh(query());assert.equal(loads,1);assert.equal(h.batches.length,0);d.resolve(true);await pending;assert.equal(h.batches.length,1)
})
test('new arrivals during confirmation are excluded from the frozen ids',async()=>{
  const d=deferred(),data=frame(),h=setup({load:async()=>data,confirmBatch:()=>d.promise});await h.controller.refresh(query());const pending=h.controller.markPageRead();data.records.unshift(notice(11));data.total++;d.resolve(true);await pending;assert.deepEqual(h.batches,[[9,7]])
})
test('successful batch requires a fresh page and independent count',async()=>{
  let loaded=0;const h=setup({load:async()=>++loaded===1?frame():{page:1,size:10,total:1,records:[notice(11)]},unread:async()=>({unreadCount:loaded===1?2:1})});await h.controller.refresh(query());await h.controller.markPageRead();assert.equal(h.state().unreadCount,null);await h.controller.refresh(query());assert.equal(h.state().unreadCount,1);assert.equal(h.state().page.records[0].id,11)
})
test('unknown write outcome clears stale data and never retries automatically',async()=>{
  let calls=0;const h=setup({readBatch:async()=>{calls++;throw new Error('timeout')}});await h.controller.refresh(query());assert.equal(await h.controller.markPageRead(),false);assert.equal(h.state().page,null);assert.equal(h.state().unreadCount,null);assert.match(h.state().error,/timeout/);await h.controller.markPageRead();assert.equal(calls,1)
})
test('invalid receipt clears page without claiming success',async()=>{
  const h=setup({readBatch:async()=>marked([9,8])});await h.controller.refresh(query());assert.equal(await h.controller.markPageRead(),false);assert.equal(h.state().page,null);assert.equal(h.state().batchReading,false)
})
test('account switch while modal is open prevents old write',async()=>{
  const d=deferred(),h=setup({confirmBatch:()=>d.promise});await h.controller.refresh(query());const pending=h.controller.markPageRead();h.setScope({userId:2,token:'two'});await h.controller.refresh(query(),false);d.resolve(true);assert.equal(await pending,false);assert.equal(h.batches.length,0);assert.equal(h.state().page,null)
})
test('account switch during write cannot overwrite the new page',async()=>{
  const d=deferred();let loads=0;const h=setup({readBatch:()=>d.promise,load:async()=>++loads===1?frame():{page:1,size:10,total:1,records:[notice(21)]}});await h.controller.refresh(query());const pending=h.controller.markPageRead();await Promise.resolve();h.setScope({userId:2,token:'two'});await h.controller.refresh(query());d.resolve(marked([9,7]));assert.equal(await pending,false);assert.equal(h.state().page.records[0].id,21)
})
test('token rotation invalidates confirmation even for same user',async()=>{
  const d=deferred(),h=setup({confirmBatch:()=>d.promise});await h.controller.refresh(query());const pending=h.controller.markPageRead();h.setScope({userId:1,token:'rotated'});d.resolve(true);assert.equal(await pending,false);assert.equal(h.batches.length,0);assert.equal(h.state().page,null)
})
test('hidden page ignores confirmation and sends no write',async()=>{
  const d=deferred(),h=setup({confirmBatch:()=>d.promise});await h.controller.refresh(query());const pending=h.controller.markPageRead();h.controller.deactivate();d.resolve(true);assert.equal(await pending,false);assert.equal(h.batches.length,0)
})
test('disposed page ignores in-flight response',async()=>{
  const d=deferred(),h=setup({readBatch:()=>d.promise});await h.controller.refresh(query());const pending=h.controller.markPageRead();await Promise.resolve();h.controller.dispose();d.resolve(marked([9,7]));assert.equal(await pending,false);assert.equal(h.state().page,null)
})
test('no unread rows or anonymous scope sends neither modal nor write',async()=>{
  const h=setup({load:async()=>({page:1,size:10,total:1,records:[notice(9,'read')]})});await h.controller.refresh(query());assert.equal(await h.controller.markPageRead(),false);h.setScope(null);await h.controller.refresh(query());assert.equal(await h.controller.markPageRead(),false);assert.equal(h.confirmations.length,0);assert.equal(h.batches.length,0)
})
test('single read in progress suppresses batch confirmation',async()=>{
  const d=deferred(),h=setup({read:()=>d.promise});await h.controller.refresh(query());const opening=h.controller.open(9);assert.equal(await h.controller.markPageRead(),false);assert.equal(h.confirmations.length,0);d.resolve(notice(9,'read'));await opening
})
test('failed refresh after acknowledged batch cannot restore old unread data',async()=>{
  let loads=0;const h=setup({load:async()=>{if(++loads>1)throw {statusCode:500,message:'refresh failed'};return frame()}});await h.controller.refresh(query());assert.equal(await h.controller.markPageRead(),true);await h.controller.refresh(query());assert.equal(h.state().page,null);assert.equal(h.state().unreadCount,null);assert.equal(h.state().status,500)
})

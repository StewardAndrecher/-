import test from 'node:test'
import assert from 'node:assert/strict'
import { validateAdminOrderDetail as validate, adminOrderDetailPath, createAdminOrderDetail } from '../utils/admin-order-detail.mjs'
const date=m=>`2026-10-21T10:${m}`
function fixture(status='COMPLETED') {
  const o={id:8,productId:18,productTitleSnapshot:'二手高数教材',priceInCentsSnapshot:3500,status,createdAt:date('00'),paymentDueAt:date('15'),paidAt:null,deliveredAt:null,completedAt:null,cancelledAt:null,closedAt:null,paymentKind:null}
  const e=(sequence,action,fromStatus,toStatus,actorRole,time)=>({sequence,action,fromStatus,toStatus,actorRole,occurredAt:time,recordedAt:time})
  const events=[e(1,'CREATED',null,'PENDING_PAYMENT','BUYER',o.createdAt)]
  if(status==='CANCELLED'){o.cancelledAt=date('02');events.push(e(2,'CANCELLED','PENDING_PAYMENT',status,'BUYER',o.cancelledAt))}
  else if(status==='CLOSED_TIMEOUT'){o.closedAt=date('15');events.push(e(2,'TIMEOUT_CLOSED','PENDING_PAYMENT',status,'SYSTEM',o.closedAt))}
  else if(status!=='PENDING_PAYMENT'){
    o.paidAt=date('01');o.paymentKind='SIMULATED';events.push(e(2,'SIMULATED_PAYMENT','PENDING_PAYMENT','PAID','BUYER',o.paidAt))
    if(status!=='PAID'){o.deliveredAt=date('02');events.push(e(3,'DELIVERED','PAID','DELIVERED','SELLER',o.deliveredAt))}
    if(status==='COMPLETED'){o.completedAt=date('03');events.push(e(4,'COMPLETED','DELIVERED','COMPLETED','BUYER',o.completedAt))}
  }
  return {order:o,events}
}
const history=(status='COMPLETED')=>{const d=fixture(status);d.events=[{sequence:1,action:'HISTORY_SNAPSHOT',fromStatus:null,toStatus:status,actorRole:'SYSTEM',occurredAt:date('05'),recordedAt:date('05')}];return d}
test('six valid lifecycle states and ordered events are accepted',()=>{for(const status of ['PENDING_PAYMENT','PAID','DELIVERED','COMPLETED','CANCELLED','CLOSED_TIMEOUT'])assert.equal(validate(fixture(status),8).order.status,status)})
test('completed timeline retains four role-only stages',()=>{const d=validate(fixture(),8);assert.equal(d.events.length,4);assert.equal(d.events[2].actorRole,'SELLER')})
test('explicit historical snapshots for all states are accepted',()=>{for(const status of ['PENDING_PAYMENT','PAID','DELIVERED','COMPLETED','CANCELLED','CLOSED_TIMEOUT'])assert.equal(validate(history(status),8).events[0].action,'HISTORY_SNAPSHOT')})
test('history snapshot can precede a genuine later cancellation',()=>{const d=fixture('CANCELLED');d.events[0]={...history('PENDING_PAYMENT').events[0],occurredAt:date('00'),recordedAt:date('00')};assert.equal(validate(d,8).events.length,2)})
test('IDs, price, title and real calendar times must be valid',()=>{
  for(const change of [{id:9},{productId:0},{priceInCentsSnapshot:0},{priceInCentsSnapshot:100000000},{productTitleSnapshot:''},{createdAt:'2026-02-30T10:00'},{paymentDueAt:date('00')}]){const d=fixture();Object.assign(d.order,change);assert.throws(()=>validate(d,8))}
})
test('unpaid and terminal timestamps must agree with lifecycle status',()=>{
  for(const [status,change] of [['PENDING_PAYMENT',{paidAt:date('01')}],['CANCELLED',{cancelledAt:date('00').replace('10:00','09:59')}],['CLOSED_TIMEOUT',{closedAt:date('14')}],['COMPLETED',{completedAt:date('01')}],['DELIVERED',{completedAt:date('03')}]]){const d=fixture(status);Object.assign(d.order,change);assert.throws(()=>validate(d,8))}
})
test('paid orders cannot imply real payment or pass the payment deadline',()=>{for(const change of [{paymentKind:'WECHAT'},{paymentKind:null},{paidAt:date('15')}]){const d=fixture('PAID');Object.assign(d.order,change);assert.throws(()=>validate(d,8))}})
test('detail links validate canonical positive safe IDs',()=>{assert.equal(adminOrderDetailPath('8'),'/pages/admin/order-detail?id=8');for(const id of ['01',0,-1,'1.5',Number.MAX_SAFE_INTEGER+1])assert.throws(()=>adminOrderDetailPath(id))})
test('missing or excessive event history is rejected',()=>{for(const events of [[],null,Array(5).fill(fixture().events[0])])assert.throws(()=>validate({...fixture(),events},8))})
test('sequence gaps and duplicate actions are rejected',()=>{const gap=fixture();gap.events[1].sequence=3;assert.throws(()=>validate(gap,8));const duplicate=fixture();duplicate.events[2].action='SIMULATED_PAYMENT';assert.throws(()=>validate(duplicate,8))})
test('roles and transition edges must follow the recorded action',()=>{for(const change of [{actorRole:'BUYER'},{fromStatus:'PENDING_PAYMENT'},{toStatus:'COMPLETED'}]){const d=fixture();Object.assign(d.events[2],change);assert.throws(()=>validate(d,8))}})
test('event occurrence must match its order stage timestamp',()=>{const d=fixture();d.events[1].occurredAt=date('01:00.000001');d.events[1].recordedAt=d.events[1].occurredAt;assert.throws(()=>validate(d,8))})
test('recorded timestamps cannot predate occurrence or move backwards',()=>{
  const early=fixture();early.events[1].recordedAt=date('00');assert.throws(()=>validate(early,8))
  const reverse=fixture();reverse.events[1].recordedAt=date('04');assert.throws(()=>validate(reverse,8))
})
test('latest event must equal the current order state',()=>{const d=fixture();d.events.pop();assert.throws(()=>validate(d,8))})
test('historical snapshot must be first and cannot invent a participant role',()=>{const late=fixture();late.events[1]={...history().events[0],sequence:2};assert.throws(()=>validate(late,8));const buyer=history();buyer.events[0].actorRole='BUYER';assert.throws(()=>validate(buyer,8))})
test('participant and actor identity fields indicate a wrong response contract',()=>{for(const change of [{buyer:{id:1}},{requestId:'private'}]){const d=fixture();Object.assign(d.order,change);assert.throws(()=>validate(d,8))}for(const change of [{actorId:1},{actorNickname:'private'}]){const d=fixture();Object.assign(d.events[0],change);assert.throws(()=>validate(d,8))}})
test('equivalent timestamp precision is normalized without device timezone conversion',()=>{const d=fixture();d.events[1].occurredAt=date('01:00.000');assert.equal(validate(d,8).order.paidAt,date('01'))})
const deferred=()=>{let resolve;const promise=new Promise(r=>{resolve=r});return{promise,resolve}}
test('a previous order response cannot overwrite a newer detail',async()=>{
  const old=deferred();let state;const loader=createAdminOrderDetail({load:id=>id===8?old.promise:Promise.resolve({...fixture(),order:{...fixture().order,id:9}}),identity:()=>({userId:1,token:'a'}),notify:v=>{state=v}})
  const pending=loader.refresh(8);await loader.refresh(9);old.resolve(fixture());await pending;assert.equal(state.data.order.id,9)
})
test('account or token changes invalidate old private details',async()=>{
  for(const next of [{userId:2,token:'a'},{userId:1,token:'b'}]){let who={userId:1,token:'a'},state;const old=deferred();const loader=createAdminOrderDetail({load:()=>old.promise,identity:()=>who,notify:v=>{state=v}});const pending=loader.refresh(8);who=next;await loader.refresh(8,false);old.resolve(fixture());await pending;assert.equal(state.data,null)}
})
test('hidden and disposed pages ignore late responses',async()=>{const old=deferred();let state;const loader=createAdminOrderDetail({load:()=>old.promise,identity:()=>({userId:1,token:'a'}),notify:v=>{state=v}});const pending=loader.refresh(8);await loader.refresh(8,false);loader.dispose();old.resolve(fixture());await pending;assert.equal(state.data,null);assert.equal(state.busy,false)})
test('unavailable history clears previous data rather than guessing a timeline',async()=>{let fail=false,state;const loader=createAdminOrderDetail({load:async()=>{if(fail)throw{statusCode:503,message:'记录不一致'};return fixture()},identity:()=>({userId:1,token:'a'}),notify:v=>{state=v}});await loader.refresh(8);assert.equal(state.data.events.length,4);fail=true;await loader.refresh(8);assert.equal(state.data,null);assert.equal(state.status,503)})

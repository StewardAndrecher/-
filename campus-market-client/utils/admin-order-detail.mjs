import { createAdminLoader } from './admin-access.mjs'
import { orderId, validOrderTime } from './order.js'
const stages=['PENDING_PAYMENT','PAID','DELIVERED','COMPLETED','CANCELLED','CLOSED_TIMEOUT']
const rules={
  CREATED:[null,'PENDING_PAYMENT','BUYER','createdAt'],
  SIMULATED_PAYMENT:['PENDING_PAYMENT','PAID','BUYER','paidAt'],
  DELIVERED:['PAID','DELIVERED','SELLER','deliveredAt'],
  COMPLETED:['DELIVERED','COMPLETED','BUYER','completedAt'],
  CANCELLED:['PENDING_PAYMENT','CANCELLED','BUYER','cancelledAt'],
  TIMEOUT_CLOSED:['PENDING_PAYMENT','CLOSED_TIMEOUT','SYSTEM','closedAt']
}
function key(value) {
  const [date,time]=value.split('T'),[h,m,seconds='00']=time.split(':'),[s,fraction='']=seconds.split('.')
  return `${date}T${h}:${m}:${s}.${fraction.padEnd(9,'0')}`
}
const before=(a,b)=>key(a)<key(b)
function snapshot(o,id) {
  if(!o||o.id!==id||!Number.isSafeInteger(o.productId)||o.productId<1||
      typeof o.productTitleSnapshot!=='string'||o.productTitleSnapshot.length<2||o.productTitleSnapshot.length>50||
      !Number.isSafeInteger(o.priceInCentsSnapshot)||o.priceInCentsSnapshot<1||o.priceInCentsSnapshot>99999999||
      !stages.includes(o.status)||!validOrderTime(o.createdAt)||!validOrderTime(o.paymentDueAt)||!before(o.createdAt,o.paymentDueAt)||
      ['buyer','seller','requestId','buyerId','sellerId'].some(name=>name in o))return false
  const unpaid=o.paidAt===null&&o.paymentKind===null,unfulfilled=o.deliveredAt===null&&o.completedAt===null
  if(o.status==='PENDING_PAYMENT')return unpaid&&unfulfilled&&o.cancelledAt===null&&o.closedAt===null
  if(o.status==='CANCELLED')return unpaid&&unfulfilled&&o.closedAt===null&&validOrderTime(o.cancelledAt)&&!before(o.cancelledAt,o.createdAt)
  if(o.status==='CLOSED_TIMEOUT')return unpaid&&unfulfilled&&o.cancelledAt===null&&validOrderTime(o.closedAt)&&!before(o.closedAt,o.paymentDueAt)
  const paid=o.paymentKind==='SIMULATED'&&o.cancelledAt===null&&o.closedAt===null&&validOrderTime(o.paidAt)&&!before(o.paidAt,o.createdAt)&&before(o.paidAt,o.paymentDueAt)
  if(!paid)return false
  if(o.status==='PAID')return unfulfilled
  const delivered=validOrderTime(o.deliveredAt)&&!before(o.deliveredAt,o.paidAt)
  if(o.status==='DELIVERED')return delivered&&o.completedAt===null
  return delivered&&validOrderTime(o.completedAt)&&!before(o.completedAt,o.deliveredAt)
}
export function validateAdminOrderDetail(data,expectedId) {
  const id=orderId(expectedId),o=data?.order,events=data?.events
  if(!snapshot(o,id)||!Array.isArray(events)||events.length<1||events.length>4)throw new Error('管理员订单详情响应无效，请重新加载')
  let previous=null,occurred=null,recorded=null;const actions=new Set()
  for(let i=0;i<events.length;i++) {
    const e=events[i]
    if(!e||e.sequence!==i+1||actions.has(e.action)||!validOrderTime(e.occurredAt)||!validOrderTime(e.recordedAt)||
        before(e.recordedAt,e.occurredAt)||(occurred!==null&&before(e.occurredAt,occurred))||(recorded!==null&&before(e.recordedAt,recorded))||
        'actorId' in e||'actorNickname' in e)throw new Error('订单进度记录不完整或不一致，请重新加载')
    if(e.action==='HISTORY_SNAPSHOT') {
      if(i!==0||e.fromStatus!==null||!stages.includes(e.toStatus)||e.actorRole!=='SYSTEM')throw new Error('历史快照记录无效')
    } else {
      const rule=rules[e.action]
      if(!rule||e.fromStatus!==rule[0]||e.toStatus!==rule[1]||e.actorRole!==rule[2]||
          !validOrderTime(o[rule[3]])||key(e.occurredAt)!==key(o[rule[3]])||
          (i===0&&e.action!=='CREATED')||(i>0&&e.fromStatus!==previous))throw new Error('订单进度与状态快照不一致')
    }
    actions.add(e.action);previous=e.toStatus;occurred=e.occurredAt;recorded=e.recordedAt
  }
  if(previous!==o.status)throw new Error('订单最新状态与时间线不一致')
  return data
}
export function adminOrderDetailPath(id){return `/pages/admin/order-detail?id=${orderId(id)}`}
export function createAdminOrderDetail({load,identity,notify}) {
  let requestedId=0
  const loader=createAdminLoader(()=>load(requestedId),identity,data=>validateAdminOrderDetail(data,requestedId),notify)
  return {refresh(id,active=true){requestedId=orderId(id);return loader.refresh(active)},dispose(){loader.dispose()}}
}

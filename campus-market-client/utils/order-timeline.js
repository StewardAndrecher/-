import { validOrder, validOrderTime } from './order.js'
const transitions = {
  CREATED: [null, 'PENDING_PAYMENT', 'BUYER'],
  SIMULATED_PAYMENT: ['PENDING_PAYMENT', 'PAID', 'BUYER'],
  DELIVERED: ['PAID', 'DELIVERED', 'SELLER'],
  COMPLETED: ['DELIVERED', 'COMPLETED', 'BUYER'],
  CANCELLED: ['PENDING_PAYMENT', 'CANCELLED', 'BUYER'],
  TIMEOUT_CLOSED: ['PENDING_PAYMENT', 'CLOSED_TIMEOUT', 'SYSTEM']
}
export const orderEventLabel = action => ({ CREATED:'创建订单', SIMULATED_PAYMENT:'模拟付款（不扣款）', DELIVERED:'卖家确认交付', COMPLETED:'买家确认收货', CANCELLED:'买家取消订单', TIMEOUT_CLOSED:'系统超时关闭', HISTORY_SNAPSHOT:'历史状态快照（迁移补录）' })[action] || '未知操作'
function timeKey(value) {
 const [date,time]=value.split('T'),[h,m,seconds='00']=time.split(':'),[s,fraction='']=seconds.split('.')
 return `${date}T${h}:${m}:${s}.${fraction.padEnd(9,'0')}`
}
export function validOrderTimeline(result) {
 if(!result || !validOrder(result.order) || !Array.isArray(result.events) || result.events.length<1 || result.events.length>4)return false
 let previous=null,occurred=null,recorded=null
 const actions=new Set(),order=result.order
 for(let i=0;i<result.events.length;i++){
  const event=result.events[i]
  if(!event || event.sequence!==i+1 || actions.has(event.action) || typeof event.actorNickname!=='string' || !event.actorNickname.trim() || event.actorNickname.length>50 || !validOrderTime(event.occurredAt) || !validOrderTime(event.recordedAt))return false
  const now=timeKey(event.occurredAt),saved=timeKey(event.recordedAt)
  if(saved<now || (occurred!==null&&now<occurred) || (recorded!==null&&saved<recorded))return false
  if(event.action==='HISTORY_SNAPSHOT'){
   if(i!==0 || event.fromStatus!==null || !['PENDING_PAYMENT','PAID','DELIVERED','COMPLETED','CANCELLED','CLOSED_TIMEOUT'].includes(event.toStatus) || event.actorRole!=='SYSTEM' || event.actorId!==null)return false
  }else{
   const rule=transitions[event.action]
   if(!rule || event.fromStatus!==rule[0] || event.toStatus!==rule[1] || event.actorRole!==rule[2] || (i===0&&event.action!=='CREATED') || (i>0&&event.fromStatus!==previous))return false
  }
  if(event.actorRole==='SYSTEM'){if(event.actorId!==null)return false}
  else if(event.actorId!==(event.actorRole==='BUYER'?order.buyer.id:order.seller.id))return false
  actions.add(event.action);previous=event.toStatus;occurred=now;recorded=saved
 }
 return previous===order.status
}
export function compatibleOrderProgress(next,previous) {
 if(!validOrder(next)||!validOrder(previous))return false
 for(const key of ['id','productId','productTitleSnapshot','priceInCentsSnapshot','productVersionSnapshot','createdAt','paymentDueAt'])if(next[key]!==previous[key])return false
 for(const role of ['buyer','seller'])if(next[role].id!==previous[role].id||next[role].nickname!==previous[role].nickname)return false
 for(const key of ['paidAt','deliveredAt','completedAt','cancelledAt','closedAt','paymentKind'])if(previous[key]!==null&&next[key]!==previous[key])return false
 if(['CANCELLED','CLOSED_TIMEOUT'].includes(previous.status))return next.status===previous.status
 if(previous.status==='PENDING_PAYMENT')return true
 const stages=['PAID','DELIVERED','COMPLETED']
 return stages.indexOf(next.status)>=stages.indexOf(previous.status)
}

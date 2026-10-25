import { orderId, validOrderTime } from './order.js'
import { normalizeOrderIssueInput } from './order-issues.mjs'
export const refundReasons = Object.freeze([{code:'NOT_AS_DESCRIBED',name:'商品与描述不符'},{code:'DAMAGED',name:'商品损坏或故障'},{code:'HANDOVER',name:'交接或履约问题'},{code:'OTHER',name:'其他原因'}])
export const refundStates = Object.freeze([{code:'ALL',name:'全部进度'},{code:'REQUESTED',name:'待卖家处理'},{code:'APPROVED',name:'已同意申请'},{code:'REJECTED',name:'申请被拒绝'},{code:'WITHDRAWN',name:'买家已撤回'}])
export const refundStateName = code => refundStates.find(item => item.code === code)?.name || '进度未知'
export const refundReasonName = code => refundReasons.find(item => item.code === code)?.name || '原因未知'
export const refundPath = id => `/pages/order/refund?id=${orderId(id)}`
const positive = value => Number.isSafeInteger(value) && value > 0
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
export function refundText(value) { return normalizeOrderIssueInput({reason:'OTHER',description:value}).description }
export function validateRefund(value,id) {
 if (!value || !positive(value.id) || value.orderId !== orderId(id) || !positive(value.buyerId) || !positive(value.sellerId) || value.buyerId === value.sellerId || !uuid.test(value.requestId) || !positive(value.amountInCents) || value.amountInCents > 99999999 || !refundReasons.some(item => item.code === value.reason) || refundText(value.description) !== value.description || !refundStates.slice(1).some(item => item.code === value.status) || !Number.isSafeInteger(value.version) || value.version < 0 || value.paymentKind !== 'SIMULATED' || !validOrderTime(value.createdAt) || !validOrderTime(value.updatedAt) || !Array.isArray(value.events)) throw new Error('退款申请响应格式不正确')
 if (['APPROVED','REJECTED'].includes(value.status) ? refundText(value.explanation) !== value.explanation : value.explanation !== '') throw new Error('退款处理说明无效')
 const terminal = value.status !== 'REQUESTED'
 if (value.version !== (terminal ? 1 : 0) || value.events.length !== (terminal ? 2 : 1)) throw new Error('退款申请进度记录不完整')
 const seen = new Set()
 for (const [index,event] of value.events.entries()) {
  const expected = index === 0 ? 'REQUESTED' : value.status, actor = expected === 'APPROVED' || expected === 'REJECTED' ? 'SELLER' : 'BUYER'
  if (!positive(event.id) || seen.has(event.id) || event.action !== expected || event.actorRole !== actor || !validOrderTime(event.createdAt) || event.explanation !== (index === 0 ? value.description : value.explanation)) throw new Error('退款申请时间线不匹配')
  seen.add(event.id)
 }
 return value
}
export function validateRefundSummary(value, owner) {
 if (!value || value.orderId !== orderId(owner.orderId) || value.userId !== owner.userId || !['BUYER','SELLER'].includes(value.viewerRole) || !['PENDING_PAYMENT','PAID','DELIVERED','COMPLETED','CANCELLED','CLOSED_TIMEOUT'].includes(value.orderStatus) || typeof value.productTitle !== 'string' || !positive(value.amountInCents) || value.amountInCents > 99999999 || ![null,'SIMULATED'].includes(value.paymentKind) || typeof value.canApply !== 'boolean' || typeof value.hasActiveRefund !== 'boolean' || !Array.isArray(value.requests) || value.requests.length > 5) throw new Error('售后信息与当前订单或账号不匹配')
 const seen = new Set()
 for (const request of value.requests) {
  validateRefund(request,value.orderId)
  if (seen.has(request.id) || request.amountInCents !== value.amountInCents || request[value.viewerRole === 'BUYER' ? 'buyerId' : 'sellerId'] !== owner.userId) throw new Error('售后记录账号或金额不匹配')
  seen.add(request.id)
 }
 const active = value.requests.some(item => ['REQUESTED','APPROVED'].includes(item.status))
 const eligible = value.viewerRole === 'BUYER' && ['PAID','DELIVERED','COMPLETED'].includes(value.orderStatus) && value.paymentKind === 'SIMULATED' && !active && value.requests.length < 5
 if (value.hasActiveRefund !== active || value.canApply !== eligible) throw new Error('售后申请资格不一致')
 return value
}
export function validateRefundPage(value, owner, query) {
 if (!value || !Array.isArray(value.items) || !Number.isSafeInteger(value.total) || value.total < 0 || value.role !== query.role || value.status !== query.status || value.size !== query.size || value.totalPages !== Math.max(1,Math.ceil(value.total / query.size)) || value.page !== Math.min(query.page,value.totalPages) || value.items.length !== Math.min(query.size,Math.max(0,value.total - (value.page - 1) * query.size))) throw new Error('售后清单分页响应不正确')
 const seen = new Set()
 for (const item of value.items) { if (!positive(item.refundId) || seen.has(item.refundId) || !positive(item.orderId) || typeof item.productTitle !== 'string' || !positive(item.amountInCents) || !refundStates.slice(1).some(state => state.code === item.status) || query.status !== 'ALL' && item.status !== query.status || !validOrderTime(item.createdAt)) throw new Error('售后清单内容不正确'); seen.add(item.refundId) }
 return value
}

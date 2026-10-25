import {orderExportQuery,validateOrderExport} from '@/utils/order-export.mjs'
import {orderSearchQuery,validateOrderSearchPage} from '@/utils/order-search.mjs'
import {issueListQuery} from '@/utils/order-issue-list.mjs'
import {reviewTaskQuery} from '@/utils/order-review-tasks.mjs'
import { validOrderTimeline } from '@/utils/order-timeline.js'
import { request } from './request.js'
import { orderId, validOrder, orderFilters, validOrderSummary, orderSummaryCount } from '@/utils/order.js'
function checked(item) { if (!validOrder(item)) throw new Error('订单响应格式不正确'); return item }
export async function createOrder(productId, productVersion, requestId) {
  orderId(productId)
  if (!Number.isSafeInteger(productVersion) || productVersion < 0 || typeof requestId !== 'string' || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(requestId)) throw new Error('下单参数不正确')
  const result = await request({ path: `/api/products/${productId}/orders`, method: 'POST', auth: true, data: { productVersion, requestId } })
  checked(result?.order)
  if (result.order.productId !== productId || result.order.productVersionSnapshot !== productVersion || typeof result.replayed !== 'boolean') throw new Error('订单响应不匹配')
  return result
}
export async function getOrder(id) {
  const result = checked(await request({ path: `/api/orders/${orderId(id)}`, auth: true }))
  if (result.id !== orderId(id)) throw new Error('订单响应编号不匹配'); return result
}
export async function cancelOrder(id) {
  const result = await request({ path: `/api/orders/${orderId(id)}/cancel`, method: 'POST', auth: true })
  checked(result?.order)
  if (result.order.id !== orderId(id) || !['CANCELLED', 'CLOSED_TIMEOUT'].includes(result.order.status) || typeof result.replayed !== 'boolean') throw new Error('取消订单响应不匹配')
  return result
}
export async function getOrderState(productId, auth = false) {
  const result = await request({ path: `/api/products/${orderId(productId)}/order-state`, auth })
  if (result?.productId !== productId || typeof result.available !== 'boolean' || typeof result.message !== 'string' || (result.available && result.myOrderId !== null) ||
      !(result.myOrderId === null || (Number.isSafeInteger(result.myOrderId) && result.myOrderId > 0))) throw new Error('订单状态响应不正确')
  return result
}
export async function getOrders(role='BUYER',page=1,status='ALL',orderId='') {
  const q=orderSearchQuery({role,page,status,orderId})
  const result=await request({path:'/api/orders?role='+q.role+'&page='+q.page+'&size=10&status='+q.status+'&orderId='+q.orderId,auth:true})
  return validateOrderSearchPage(result,q)
}

export async function simulateOrderPayment(id) {
  const result = await request({ path: `/api/orders/${orderId(id)}/simulate-payment`, method: 'POST', auth: true })
  checked(result?.order)
  if (result.order.id !== orderId(id) || !['PAID', 'DELIVERED', 'COMPLETED'].includes(result.order.status) || result.order.paymentKind !== 'SIMULATED' || typeof result.replayed !== 'boolean') throw new Error('模拟付款响应不匹配')
  return result
}

async function progressOrder(id, action, statuses) {
  const result = await request({ path: `/api/orders/${orderId(id)}/${action}`, method: 'POST', auth: true })
  checked(result?.order)
  if (result.order.id !== orderId(id) || !statuses.includes(result.order.status) || typeof result.replayed !== 'boolean') throw new Error('订单进度响应不匹配')
  return result
}
export async function deliverOrder(id) {
  return progressOrder(id, 'deliver', ['DELIVERED', 'COMPLETED'])
}
export async function completeOrder(id) {
  return progressOrder(id, 'complete', ['COMPLETED'])
}

export async function getOrderTimeline(id) {
 const result=await request({path:`/api/orders/${orderId(id)}/timeline`,auth:true})
 if(!validOrderTimeline(result)||result.order.id!==orderId(id))throw new Error('订单时间线响应不匹配')
 return result
}

export function getOrderReviews(id) {
 return request({path:`/api/orders/${orderId(id)}/reviews`,auth:true})
}
export function createOrderReview(id,data) {
 return request({path:`/api/orders/${orderId(id)}/reviews`,method:'POST',auth:true,data})
}

export function getOrderReviewTasks(query) {
 const q=reviewTaskQuery(query)
 return request({path:`/api/orders/review-tasks?role=${q.role}&status=${q.status}&page=${q.page}&size=${q.size}`,auth:true})
}

export function getOrderIssue(id) {
 return request({path:`/api/orders/${orderId(id)}/issue`,auth:true})
}
export function createOrderIssue(id,data) {
 return request({path:`/api/orders/${orderId(id)}/issue`,method:'POST',auth:true,data})
}

export function getOwnOrderIssues(query) {
 const q=issueListQuery(query)
 return request({path:`/api/orders/issues?role=${q.role}&status=${q.status}&page=${q.page}&size=${q.size}`,auth:true})
}

export function getTradeTasks() {
 return request({path:'/api/orders/tasks/overview',auth:true})
}

export async function getOrderExport(value={}) {
 const q=orderExportQuery(value)
 const result=await request({path:'/api/orders/export?role='+q.role+'&status='+q.status+'&orderId='+q.orderId,auth:true})
 return validateOrderExport(result,q)
}

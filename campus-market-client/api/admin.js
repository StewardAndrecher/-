import { orderId } from '@/utils/order.js'
import { request } from './request.js'

export function getMyPermissions() {
  return request({ path: '/api/users/me/permissions', auth: true })
}
export async function getAdminDashboard() {
  const [identity, overview] = await Promise.all([
    request({ path: '/api/admin/me', auth: true }),
    request({ path: '/api/admin/overview', auth: true })
  ])
  return { identity, overview }
}

export function getNotificationOverview() {
  return request({ path: '/api/admin/notifications/overview', auth: true })
}

export function getOrderOverview() {
  return request({ path: '/api/admin/orders/overview', auth: true })
}

export function listAdminOrders(data) {
  return request({ path: '/api/admin/orders', auth: true, data })
}

export function getAdminOrderDetail(id) {
  return request({ path: `/api/admin/orders/${orderId(id)}`, auth: true })
}

export function listAdminOrderIssues(data) {
 return request({path:'/api/admin/order-issues',auth:true,data})
}
export function getAdminOrderIssue(order,applicant) {
 return request({path:`/api/admin/order-issues/${orderId(order)}/${orderId(applicant)}`,auth:true})
}
export function resolveAdminOrderIssue(order,applicant,data) {
 return request({path:`/api/admin/order-issues/${orderId(order)}/${orderId(applicant)}/resolve`,method:'POST',auth:true,data})
}

export function getIssueOverview(){return request({path:'/api/admin/order-issues/overview',auth:true})}

import { request } from './request.js'
export const getOrderRefunds = id => request({ path: `/api/orders/${id}/refunds`, auth: true })
export const createRefund = (id,data) => request({ path: `/api/orders/${id}/refunds`, method: 'POST', auth: true, data })
export const decideRefund = (id,refundId,data) => request({ path: `/api/orders/${id}/refunds/${refundId}/decision`, method: 'PUT', auth: true, data })
export const withdrawRefund = (id,refundId,data) => request({ path: `/api/orders/${id}/refunds/${refundId}/withdraw`, method: 'PUT', auth: true, data })
export const getOwnRefunds = data => request({ path: '/api/orders/refunds', auth: true, data })

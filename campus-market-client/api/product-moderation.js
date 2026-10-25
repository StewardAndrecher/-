import { request } from './request.js'
export function getModerationProducts(data) { return request({ path: '/api/admin/products', auth: true, data }) }
export function getProductModeration(id, mode) {
  return request({ path: mode === 'admin' ? `/api/admin/products/${id}/moderation` : `/api/products/${id}/moderation`, auth: true })
}
export function submitProductModeration(id, mode, data) {
  return request({ path: mode === 'admin' ? `/api/admin/products/${id}/moderation/actions` : `/api/products/${id}/moderation/appeals`, method: 'POST', auth: true, data })
}

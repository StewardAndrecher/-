import { request } from './request.js'
import { getToken } from '@/utils/session.js'

export function getComments(productId, { page = 1, size = 5 } = {}) {
  return request({ path: `/api/products/${productId}/comments`,
    auth: !!getToken(), data: { page, size } })
}

export function createComment(productId, submission) {
  return request({ path: `/api/products/${productId}/comments`,
    method: 'POST', auth: true, data: submission })
}

export function withdrawComment(productId, commentId) {
  return request({ path: `/api/products/${productId}/comments/${commentId}`,
    method: 'DELETE', auth: true })
}

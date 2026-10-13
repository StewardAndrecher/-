import { request } from './request.js'
import { getToken } from '@/utils/session.js'

export function getProducts({
  page = 1,
  size = 5,
  keyword = ''
} = {}) {
  return request({
    path: '/api/products',
    data: {
      page,
      size,
      keyword
    }
  })
}

export function getProduct(id) {
  return request({
    path: `/api/products/${id}`,
    auth: !!getToken()
  })
}

export function createProduct(data) {
  return request({
    path: '/api/products',
    method: 'POST',
    auth: true,
    data
  })
}

export function updateProduct(id, data) {
  return request({ path: `/api/products/${id}`, method: 'PUT', auth: true, data })
}

export function getMyProducts({ page = 1, size = 5 } = {}) {
  return request({ path: '/api/products/mine', auth: true, data: { page, size } })
}

export function updateProductStatus(id, status, version) {
  return request({ path: `/api/products/${id}/status`, method: 'PUT', auth: true, data: { status, version } })
}

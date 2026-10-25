import { request } from './request.js'
import { getToken } from '@/utils/session.js'

export function getProducts({
  page = 1,
  size = 5,
  keyword = '', category = '', sort = 'NEWEST', campus = ''
} = {}) {
  return request({
    path: '/api/products',
    data: {
      page,
      size,
      keyword,
      category,
      sort,
      campus
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

export function getMyProducts({ page = 1, size = 5, status = '', keyword = '', sort = 'NEWEST' } = {}) {
  return request({ path: '/api/products/mine', auth: true, data: { page, size, ...(status ? { status } : {}), ...(keyword ? { keyword } : {}), ...(sort && sort !== 'NEWEST' ? { sort } : {}) } })
}

export function updateProductStatus(id, status, version) {
  return request({ path: `/api/products/${id}/status`, method: 'PUT', auth: true, data: { status, version } })
}

export async function getMyProductSummary() {
  const data = await request({ path: '/api/products/mine/summary', auth: true })
  if (!data || !['total', 'onSale', 'offShelf', 'sold', 'soldPriceInCents'].every(
    key => Number.isSafeInteger(data[key]) && data[key] >= 0) ||
      data.onSale + data.offShelf + data.sold !== data.total ||
      (data.sold === 0 && data.soldPriceInCents !== 0) ||
      data.soldPriceInCents < data.sold || data.soldPriceInCents > data.sold * 99_999_999) {
    throw new Error('发布概览响应格式不正确')
  }
  return data
}

export function getProductFeed({ cursor = '', size = 10, keyword = '', category = '' } = {}) {
  return request({ path: '/api/products/feed', data: { cursor, size, keyword, category } })
}

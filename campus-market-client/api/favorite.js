import { request } from './request.js'

export function getFavoriteIds(productIds) {
  return request({ path: '/api/favorites/ids', auth: true,
    data: { productIds: productIds.join(',') } })
}

export function setProductFavorite(productId, favorite) {
  return request({ path: `/api/favorites/${productId}`,
    method: favorite ? 'PUT' : 'DELETE', auth: true })
}

export function getFavorites({ page = 1, size = 5 } = {}) {
  return request({ path: '/api/favorites', auth: true, data: { page, size } })
}

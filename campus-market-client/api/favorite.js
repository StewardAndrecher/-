import { request } from './request.js'
import { favoriteAvailability, favoriteKeyword } from '@/utils/favorite-inbox.mjs'

export function getFavoriteIds(productIds) {
  return request({ path: '/api/favorites/ids', auth: true,
    data: { productIds: productIds.join(',') } })
}

export function setProductFavorite(productId, favorite) {
  return request({ path: `/api/favorites/${productId}`,
    method: favorite ? 'PUT' : 'DELETE', auth: true })
}

export function getFavorites({ page = 1, size = 5, availability = 'ALL', keyword = '' } = {}) {
  const filter = favoriteAvailability(availability)
  const search = favoriteKeyword(keyword)
  return request({ path: '/api/favorites', auth: true, data: { page, size, ...(filter === 'ALL' ? {} : { availability: filter }), ...(search ? { keyword: search } : {}) } })
}

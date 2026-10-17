import { request } from './request.js'
import { parseSellerId, validateSellerStore } from '@/utils/seller-store.js'

export async function getSellerStore(id, { page = 1, size = 5, sort = 'NEWEST' } = {}) {
  const sellerId = parseSellerId(id)
  if (!sellerId) throw new Error('卖家ID不正确')
  const result = await request({ path: `/api/sellers/${sellerId}/products`, data: { page, size, sort } })
  return validateSellerStore(result, sellerId, page, size)
}

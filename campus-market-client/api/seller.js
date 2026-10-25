import { request } from './request.js'
import { parseSellerId, validateSellerStore, sellerStoreCategory } from '@/utils/seller-store.js'

export async function getSellerStore(id, { page = 1, size = 5, sort = 'NEWEST', category = '' } = {}) {
  const sellerId = parseSellerId(id)
  if (!sellerId) throw new Error('卖家ID不正确')
  const filter = sellerStoreCategory(category)
  if (!Number.isSafeInteger(page) || page < 1 || page > 1000000 ||
      !Number.isSafeInteger(size) || size < 1 || size > 50 ||
      !['NEWEST', 'PRICE_ASC', 'PRICE_DESC', 'MOST_FAVORITED'].includes(sort))
    throw new Error('卖家商品查询条件不正确')
  const result = await request({ path: `/api/sellers/${sellerId}/products`, data: { page, size, sort, ...(filter ? { category: filter } : {}) } })
  return validateSellerStore(result, sellerId, page, size, filter)
}

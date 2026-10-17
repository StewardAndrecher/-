import { parseProductId } from './product-share.js'
import { isValidFavoriteCount } from './product-popularity.js'

// 商品与卖家的编号都采用相同的正整数格式校验。
export function parseSellerId(value) { return parseProductId(value) }

export function sellerStorePath(value) {
  const id = parseSellerId(value)
  if (!id) throw new Error('卖家ID不正确')
  return `/pages/user/store?id=${id}`
}

export function webHashSellerId(hash) {
  const matched = /^#\/pages\/user\/store(?:\?(.*))?$/.exec(hash)
  if (!matched) return null
  const ids = new URLSearchParams(matched[1] || '').getAll('id')
  return ids.length === 1 ? parseSellerId(ids[0]) : 0
}

export function validateSellerStore(data, sellerId, page, size) {
  const seller = data?.seller, products = data?.products
  if (!seller || seller.id !== sellerId || typeof seller.nickname !== 'string' || !seller.nickname.trim() ||
      !products || !Array.isArray(products.records) || products.records.length > size ||
      !Number.isSafeInteger(products.total) || products.total < products.records.length ||
      products.page !== page || products.size !== size ||
      new Set(products.records.map(item => item?.id)).size !== products.records.length ||
      !products.records.every(item => item && Number.isSafeInteger(item.id) && item.id > 0 &&
        item.status === 'ON_SALE' && typeof item.title === 'string' && !!item.title.trim() &&
        typeof item.description === 'string' && Number.isSafeInteger(item.priceInCents) && item.priceInCents > 0 &&
        Number.isSafeInteger(item.version) && item.version >= 0 && isValidFavoriteCount(item.favoriteCount) &&
        item.seller?.id === sellerId && item.seller.nickname === seller.nickname)) {
    throw new Error('卖家主页响应格式不正确')
  }
  return data
}

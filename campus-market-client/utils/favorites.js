const FAVORITES_KEY = 'campus-market-favorite-ids-v1'
const LEGACY_PRODUCTS_KEY = 'campus-market-api-products-v1'

function isValidId(id) {
  return Number.isSafeInteger(id) && id > 0
}

export function loadFavoriteIds() {
  const savedText = uni.getStorageSync(FAVORITES_KEY)
  if (savedText) {
    const ids = JSON.parse(savedText)
    if (!Array.isArray(ids) || !ids.every(isValidId)) {
      throw new Error('收藏数据格式不正确')
    }
    return [...new Set(ids)]
  }

  // 首次升级时，从原来的商品缓存中提取收藏 ID
  const legacyText = uni.getStorageSync(LEGACY_PRODUCTS_KEY)
  if (!legacyText) return []
  const items = JSON.parse(legacyText)
  if (!Array.isArray(items)) throw new Error('旧商品缓存格式不正确')
  return [...new Set(items
    .filter(item => item?.isFavorite === true && isValidId(item.id))
    .map(item => item.id))]
}

export function saveFavoriteIds(ids) {
  uni.setStorageSync(FAVORITES_KEY, JSON.stringify(ids))
}

function importedKey(userId) {
  if (!isValidId(userId)) throw new Error('账号ID不正确')
  return `campus-market-imported-favorites-v1-user-${userId}`
}

function importedIds(userId) {
  const saved = uni.getStorageSync(importedKey(userId))
  const ids = saved ? JSON.parse(saved) : []
  if (!Array.isArray(ids) || !ids.every(isValidId)) throw new Error('导入记录格式不正确')
  return ids
}

export function loadLegacyFavoriteIds(userId) {
  const imported = new Set(importedIds(userId))
  return loadFavoriteIds().filter(id => !imported.has(id))
}

export function markFavoriteImported(userId, productId) {
  if (!isValidId(productId)) throw new Error('商品ID不正确')
  uni.setStorageSync(importedKey(userId), JSON.stringify([...new Set([...importedIds(userId), productId])]))
}

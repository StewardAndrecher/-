const PREFIX = 'campus-market-history-v1'
export const HISTORY_LIMIT = 20
export const HISTORY_DAYS = 30
const TTL = HISTORY_DAYS * 24 * 60 * 60 * 1000

// 0代表游客；账号ID来自内存会话，不从输入框或网址读取。
export function historyKey(userId = 0) {
  if (!Number.isSafeInteger(userId) || userId < 0) throw new Error('浏览记录账号不正确')
  return `${PREFIX}:${userId ? 'user:' + userId : 'guest'}`
}

export function normalizeHistory(raw, now = Date.now()) {
  if (!Array.isArray(raw) || raw.length > 200) return []
  const seen = new Set()
  return raw.filter(item => item && Number.isSafeInteger(item.productId) && item.productId > 0 &&
    Number.isSafeInteger(item.viewedAt) && item.viewedAt > now - TTL &&
    item.viewedAt <= now && item.viewedAt > 0)
    .sort((a, b) => b.viewedAt - a.viewedAt)
    .filter(item => {
      if (seen.has(item.productId)) return false
      seen.add(item.productId)
      return true
    }).slice(0, HISTORY_LIMIT)
    .map(item => ({ productId: item.productId, viewedAt: item.viewedAt }))
}

export function loadHistory(userId = 0, now = Date.now()) {
  const stored = uni.getStorageSync(historyKey(userId))
  if (!stored) return []
  let raw
  try { raw = typeof stored === 'string' ? JSON.parse(stored) : stored }
  catch { return [] }
  return normalizeHistory(raw, now)
}

export function recordHistory(userId, product, now = Date.now()) {
  if (!product || product.status !== 'ON_SALE' ||
      !Number.isSafeInteger(product.id) || product.id <= 0) return false
  const records = loadHistory(userId, now).filter(item => item.productId !== product.id)
  records.unshift({ productId: product.id, viewedAt: now })
  uni.setStorageSync(historyKey(userId), JSON.stringify(records.slice(0, HISTORY_LIMIT)))
  return true
}

export function removeHistory(userId, productId) {
  uni.setStorageSync(historyKey(userId), JSON.stringify(loadHistory(userId)
    .filter(item => item.productId !== productId)))
}

export function clearHistory(userId) {
  uni.removeStorageSync(historyKey(userId))
}

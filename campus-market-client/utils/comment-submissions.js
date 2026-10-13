const PREFIX = 'campus-comment-pending-v1:'
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/

function scope(userId, productId) {
  if (![userId, productId].every(id => Number.isSafeInteger(id) && id > 0)) throw new Error('草稿归属不正确')
  return `${PREFIX}${userId}:${productId}:`
}

function storageKeys(userId, productId) {
  const prefix = scope(userId, productId)
  const keys = uni.getStorageInfoSync().keys
  if (!Array.isArray(keys)) throw new Error('无法读取待确认草稿')
  return keys.filter(key => typeof key === 'string' && key.startsWith(prefix))
}

function valid(item, userId, productId) {
  return !!item && item.userId === userId && item.productId === productId && UUID.test(item.requestId) &&
    typeof item.content === 'string' && item.content === item.content.trim() &&
    Array.from(item.content).length >= 1 && Array.from(item.content).length <= 500 &&
    Number.isSafeInteger(item.createdAt) && item.createdAt > 0
}

export function newCommentRequestId() {
  const bytes = new Uint8Array(16)
  if (typeof globalThis.crypto?.getRandomValues === 'function') globalThis.crypto.getRandomValues(bytes)
  else for (let i = 0; i < bytes.length; i++) bytes[i] = Math.floor(Math.random() * 256)
  bytes[6] = (bytes[6] & 15) | 64
  bytes[8] = (bytes[8] & 63) | 128
  const hex = Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0,8)}-${hex.slice(8,12)}-${hex.slice(12,16)}-${hex.slice(16,20)}-${hex.slice(20)}`
}

export function loadPendingComments(userId, productId) {
  return storageKeys(userId, productId).map(key => {
    const item = JSON.parse(uni.getStorageSync(key))
    if (!valid(item, userId, productId) || key !== scope(userId, productId) + item.requestId) {
      throw new Error('待确认草稿格式不正确')
    }
    return item
  }).sort((a,b) => a.createdAt - b.createdAt || a.requestId.localeCompare(b.requestId))
}

export function savePendingComment(userId, productId, submission) {
  const item = { ...submission, userId, productId }
  if (!valid(item, userId, productId)) throw new Error('待确认草稿格式不正确')
  const key = scope(userId, productId) + item.requestId
  const saved = uni.getStorageSync(key)
  if (saved) {
    const previous = JSON.parse(saved)
    if (!valid(previous, userId, productId) || previous.content !== item.content) throw new Error('提交标识已用于其他草稿')
    return previous
  }
  if (storageKeys(userId, productId).length >= 20) throw new Error('请先处理已有待确认草稿')
  uni.setStorageSync(key, JSON.stringify(item))
  return item
}

export function clearPendingComment(userId, productId, requestId) {
  if (!UUID.test(requestId)) throw new Error('提交标识格式不正确')
  uni.removeStorageSync(scope(userId, productId) + requestId)
}

export function clearPendingComments(userId, productId) {
  for (const key of storageKeys(userId, productId)) uni.removeStorageSync(key)
}

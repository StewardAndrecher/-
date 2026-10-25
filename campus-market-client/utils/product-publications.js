import { productDetails } from './product-details.mjs'
import { newCommentRequestId } from './comment-submissions.js'
const PREFIX = 'campus-publication-pending-v1:'
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const CATEGORIES = ['UNCLASSIFIED', 'BOOK', 'DIGITAL', 'DAILY', 'SPORT', 'OTHER']
export const newPublicationRequestId = newCommentRequestId

function key(userId) {
  if (!Number.isSafeInteger(userId) || userId <= 0) throw new Error('发布草稿归属不正确')
  return PREFIX + userId
}
function snapshot(item, userId) {
  const p = item?.product
  if (item?.userId !== userId || !UUID.test(item.requestId) ||
      !Number.isSafeInteger(item.createdAt) || item.createdAt <= 0 ||
      typeof p?.title !== 'string' || p.title !== p.title.trim() || p.title.length < 2 || p.title.length > 50 ||
      typeof p.description !== 'string' || p.description !== p.description.trim() || p.description.length > 500 ||
      !Number.isSafeInteger(p.priceInCents) || p.priceInCents < 1 || p.priceInCents > 99999999 ||
      !(p.coverImageId === '' || UUID.test(p.coverImageId)) || !CATEGORIES.includes(p.categoryCode)) {
    throw new Error('待确认发布记录格式不正确，请保留记录并检查')
  }
  return Object.freeze({ userId, requestId: item.requestId, createdAt: item.createdAt,
    product: Object.freeze({ title: p.title, description: p.description, priceInCents: p.priceInCents,
      coverImageId: p.coverImageId, categoryCode: p.categoryCode, ...(p.details != null ? { details: productDetails(p.details,p.coverImageId) } : {}) }) })
}
export function loadPendingPublication(userId) {
  const saved = uni.getStorageSync(key(userId))
  return saved ? snapshot(JSON.parse(saved), userId) : null
}
export function savePendingPublication(userId, submission) {
  const item = snapshot({ ...submission, userId }, userId)
  const previous = loadPendingPublication(userId)
  if (previous) {
    if (JSON.stringify(previous) !== JSON.stringify(item)) throw new Error('已有待确认发布，请先确认原提交')
    return previous
  }
  uni.setStorageSync(key(userId), JSON.stringify(item))
  const saved = loadPendingPublication(userId)
  if (JSON.stringify(saved) !== JSON.stringify(item)) throw new Error('未能保存发布记录，本次尚未发送')
  return saved
}
export function clearPendingPublication(userId, requestId) {
  const previous = loadPendingPublication(userId)
  if (previous && previous.requestId !== requestId) throw new Error('待确认发布已变化，请重新加载')
  uni.removeStorageSync(key(userId))
  if (uni.getStorageSync(key(userId))) throw new Error('未能清除发布记录，请再次确认')
}

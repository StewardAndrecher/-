import { request } from './request.js'
const roles = ['BUYER', 'SELLER']
const sorts = ['NEWEST', 'RECENT_MESSAGE']
const statusFilters = ['', 'PENDING', 'ACCEPTED', 'CANCELLED']
function validTime(value) { return typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T/.test(value) }
function positive(value) { return Number.isSafeInteger(value) && value > 0 }
function decode(row) {
  if (!row || !positive(row.id) || !positive(row.productId) ||
      typeof row.productTitleSnapshot !== 'string' || !row.productTitleSnapshot.trim() || row.productTitleSnapshot.length > 50 ||
      !positive(row.priceInCentsSnapshot) || row.priceInCentsSnapshot > 99999999 ||
      !Number.isSafeInteger(row.productVersionSnapshot) || row.productVersionSnapshot < 0 ||
      !positive(row.buyer?.id) || !positive(row.seller?.id) || row.buyer.id === row.seller.id ||
      typeof row.buyer.nickname !== 'string' || typeof row.seller.nickname !== 'string' ||
      !['PENDING', 'CANCELLED', 'ACCEPTED'].includes(row.status) || !validTime(row.createdAt) ||
      (row.status === 'PENDING' && (row.cancelledAt !== null || row.acceptedAt !== null)) ||
      (row.status === 'CANCELLED' && (!validTime(row.cancelledAt) || row.acceptedAt !== null)) ||
      (row.status === 'ACCEPTED' && (!validTime(row.acceptedAt) || row.cancelledAt !== null))) throw new Error('购买意向响应格式不正确，请到首页查询确认')
  return row
}
export async function createPurchaseIntent(productId, productVersion) {
  if (!positive(productId) || !Number.isSafeInteger(productVersion) || productVersion < 0) throw new Error('商品 ID 或版本不正确')
  const data = await request({ path: `/api/products/${productId}/purchase-intent`, method: 'POST', auth: true, data: { productVersion } })
  if (!data || typeof data.replayed !== 'boolean') throw new Error('购买意向结果不完整，请到首页查询确认')
  const intent = decode(data.intent)
  if (intent.productId !== productId) throw new Error('购买意向商品不一致，请到首页查询确认')
  return { intent, replayed: data.replayed }
}
function decodeSummary(value, status, total, unreadOnly) {
  const selected = value?.[({ PENDING: 'pending', ACCEPTED: 'accepted', CANCELLED: 'cancelled' })[status] || 'total']
  if (!value || !['total', 'pending', 'accepted', 'cancelled'].every(key => Number.isSafeInteger(value[key]) && value[key] >= 0) ||
      value.pending + value.accepted + value.cancelled !== value.total ||
      !Number.isSafeInteger(value.pending + value.accepted + value.cancelled) ||
      (unreadOnly ? selected < total : selected !== total)) {
    throw new Error('购买意向统计与列表不一致，请刷新确认')
  }
  return { total: value.total, pending: value.pending, accepted: value.accepted, cancelled: value.cancelled }
}
function validReceivedTime(value) {
  if (typeof value !== 'string') return false
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.\d{1,9})?)?$/.exec(value)
  if (!match) return false
  const [, yearText, monthText, dayText, hourText, minuteText, secondText = '0'] = match
  const year = Number(yearText), month = Number(monthText), day = Number(dayText)
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0)
  const days = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
  return year >= 1000 && month >= 1 && month <= 12 && day >= 1 && day <= days[month - 1] &&
    Number(hourText) <= 23 && Number(minuteText) <= 59 && Number(secondText) <= 59
}
function decodeReceivedTimes(value, items) {
  // An absent field means the old server cannot supply it; null entries mean known absence.
  if (value === undefined) return null
  if (!Array.isArray(value) || value.length !== items.length) throw new Error('最近收到留言时间不完整，请刷新确认')
  const times = new Map()
  for (const row of value) {
    if (!row || !positive(row.intentId) || times.has(row.intentId) ||
        (row.latestReceivedAt !== null && !validReceivedTime(row.latestReceivedAt))) throw new Error('最近收到留言时间格式不正确，请刷新确认')
    times.set(row.intentId, { intentId: row.intentId, latestReceivedAt: row.latestReceivedAt })
  }
  return items.map(item => {
    if (!times.has(item.id)) throw new Error('最近收到留言时间与当前页不一致，请刷新确认')
    return times.get(item.id)
  })
}
export async function getPurchaseIntents({ role = 'BUYER', page = 1, size = 5, status = '', unreadOnly = false, sort = 'NEWEST' } = {}) {
  if (!sorts.includes(sort) || typeof unreadOnly !== 'boolean' || !roles.includes(role) || !statusFilters.includes(status) || !positive(page) || page > 1000000 || !positive(size) || size > 20) throw new Error('购买意向查询条件不正确')
  const data = await request({ path: '/api/purchase-intents/mine', auth: true, data: { role, page, size, status, unreadOnly, sort } })
  if (!data || !Array.isArray(data.items) || !Number.isSafeInteger(data.total) || data.total < 0 ||
      data.role !== role || data.status !== status || (data.sort ?? 'NEWEST') !== sort || (data.sort !== undefined && !sorts.includes(data.sort)) || (data.unreadOnly !== undefined && typeof data.unreadOnly !== 'boolean') || (data.unreadOnly === true) !== unreadOnly || data.size !== size || data.totalPages !== Math.max(1, Math.ceil(data.total / size)) ||
      data.page !== Math.min(page, data.totalPages) || data.items.length !== Math.min(size, Math.max(0, data.total - (data.page - 1) * size))) throw new Error('购买意向分页结果不正确')
  const summary = decodeSummary(data.summary, status, data.total, unreadOnly)
  const items = data.items.map(decode)
  if (status && items.some(item => item.status !== status)) throw new Error('购买意向记录与状态筛选不一致')
  if (new Set(items.map(item => item.id)).size !== items.length) throw new Error('购买意向分页结果出现重复记录')
  const receivedMessageTimes = decodeReceivedTimes(data.receivedMessageTimes, items)
  return { ...data, items, summary, unreadOnly, sort, receivedMessageTimes }
}

export async function cancelPurchaseIntent(intentId) {
  if (!positive(intentId)) throw new Error('购买意向编号不正确')
  const data = await request({ path: `/api/purchase-intents/${intentId}/cancel`, method: 'POST', auth: true })
  if (!data || typeof data.replayed !== 'boolean') throw new Error('取消结果不完整，请刷新意向列表确认')
  const intent = decode(data.intent)
  if (intent.id !== intentId || intent.status !== 'CANCELLED') throw new Error('取消结果与当前意向不一致，请刷新确认')
  return { intent, replayed: data.replayed }
}

export async function acceptPurchaseIntent(intentId) {
  if (!positive(intentId)) throw new Error('购买意向编号不正确')
  const data = await request({ path: `/api/purchase-intents/${intentId}/accept`, method: 'POST', auth: true })
  if (!data || typeof data.replayed !== 'boolean') throw new Error('接受结果不完整，请刷新意向列表确认')
  const intent = decode(data.intent)
  if (intent.id !== intentId || intent.status !== 'ACCEPTED') throw new Error('接受结果与当前意向不一致，请刷新确认')
  return { intent, replayed: data.replayed }
}

export async function getPurchaseIntent(intentId) {
  if (!positive(intentId)) throw new Error('购买意向编号不正确')
  const intent = decode(await request({ path: `/api/purchase-intents/${intentId}`, auth: true }))
  if (intent.id !== intentId) throw new Error('购买意向编号与请求不一致')
  return intent
}

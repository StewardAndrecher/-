import { request } from './request.js'
export async function getPurchaseIntentUnread({ role = 'BUYER', intentIds = [] } = {}) {
  const nonnegative = value => Number.isSafeInteger(value) && value >= 0
  if (!['BUYER', 'SELLER'].includes(role) || !Array.isArray(intentIds) || intentIds.length > 20 || intentIds.some(id => !Number.isSafeInteger(id) || id < 1) || new Set(intentIds).size !== intentIds.length) throw new Error('意向未读查询条件不正确')
  const data = await request({ path: '/api/purchase-intents/message-unread', auth: true, data: { role, intentIds: intentIds.join(',') } })
  if (!data || data.role !== role || !Array.isArray(data.items) || data.items.length !== intentIds.length || !nonnegative(data.pageUnreadMessages) || !nonnegative(data.pageUnreadIntents)) throw new Error('意向未读响应格式不正确')
  let messages = 0, intents = 0
  for (let i = 0; i < data.items.length; i++) {
    const item = data.items[i]
    if (!item || item.intentId !== intentIds[i] || !nonnegative(item.unreadCount)) throw new Error('意向未读记录与当前页不一致')
    messages += item.unreadCount
    if (!Number.isSafeInteger(messages)) throw new Error('意向未读数量不正确')
    if (item.unreadCount > 0) intents++
  }
  if (data.pageUnreadMessages !== messages || data.pageUnreadIntents !== intents) throw new Error('意向未读统计与当前页不一致')
  return data
}

export async function getPurchaseIntentUnreadSummary(role = 'BUYER') {
  if (!['BUYER', 'SELLER'].includes(role)) throw new Error('未读总览身份不正确')
  const data = await request({ path: '/api/purchase-intents/message-unread-summary', auth: true, data: { role } })
  const nonnegative = value => Number.isSafeInteger(value) && value >= 0
  if (!data || data.role !== role || !nonnegative(data.totalUnreadMessages) || !nonnegative(data.unreadIntentCount) || data.unreadIntentCount > data.totalUnreadMessages || (data.totalUnreadMessages === 0) !== (data.unreadIntentCount === 0)) throw new Error('未读总览响应不正确')
  return data
}

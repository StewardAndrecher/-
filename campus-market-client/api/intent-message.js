import { request } from './request.js'
const positive = value => Number.isSafeInteger(value) && value > 0
const statuses = ['PENDING', 'ACCEPTED', 'CANCELLED']
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
function content(value) {
  if (typeof value !== 'string' || !value.trim() || Array.from(value).length > 500 || /[\u0000-\u0008\u000b-\u001f\u007f-\u009f]/.test(value)) throw new Error('留言须为1～500个字符，不能全为空白或包含非法控制字符')
  return value
}
function decode(row, intentId) {
  if (!row || !positive(row.id) || row.intentId !== intentId || !positive(row.sender?.id) || typeof row.sender.nickname !== 'string' || typeof row.createdAt !== 'string' || !/^\d{4}-\d{2}-\d{2}T/.test(row.createdAt)) throw new Error('私密留言响应格式不正确')
  if (typeof row.withdrawn !== 'boolean' || (row.withdrawn
      ? row.content !== null || typeof row.withdrawnAt !== 'string' || !/^\d{4}-\d{2}-\d{2}T/.test(row.withdrawnAt)
      : row.withdrawnAt !== null)) throw new Error('私密留言撤回状态不正确')
  if (!row.withdrawn) content(row.content)
  return row
}
export async function getIntentMessages(intentId, { page = 1, size = 10 } = {}) {
  if (!positive(intentId) || !positive(page) || page > 1000000 || !positive(size) || size > 20) throw new Error('私密留言查询条件不正确')
  const data = await request({ path: `/api/purchase-intents/${intentId}/messages`, auth: true, data: { page, size } })
  if (!data || data.intentId !== intentId || !statuses.includes(data.intentStatus) || !Array.isArray(data.items) || !Number.isSafeInteger(data.total) || data.total < 0 || data.size !== size || data.totalPages !== Math.max(1, Math.ceil(data.total / size)) || data.page !== Math.min(page, data.totalPages) || data.items.length !== Math.min(size, Math.max(0, data.total - (data.page - 1) * size))) throw new Error('私密留言分页结果不正确')
  const items = data.items.map(row => decode(row, intentId))
  if (new Set(items.map(row => row.id)).size !== items.length) throw new Error('私密留言出现重复记录')
  return { ...data, items }
}
export async function sendIntentMessage(intentId, value) {
  if (!positive(intentId) || typeof value?.content !== 'string' || !uuid.test(value?.requestId || '')) throw new Error('私密留言提交参数不正确')
  const text = content(value.content.trim())
  const data = await request({ path: `/api/purchase-intents/${intentId}/messages`, method: 'POST', auth: true, data: { content: text, requestId: value.requestId } })
  if (!data || typeof data.replayed !== 'boolean') throw new Error('未能确认留言发送结果')
  const message = decode(data.message, intentId)
  if (message.withdrawn && !data.replayed) throw new Error('撤回留言不能作为新发送结果')
  return { message, replayed: data.replayed }
}

export async function withdrawIntentMessage(intentId, messageId) {
  if (!positive(intentId) || !positive(messageId)) throw new Error('意向或留言编号不正确')
  const data = await request({ path: `/api/purchase-intents/${intentId}/messages/${messageId}/withdraw`, method: 'POST', auth: true })
  if (!data || typeof data.replayed !== 'boolean') throw new Error('未能确认留言撤回结果')
  const message = decode(data.message, intentId)
  if (message.id !== messageId || !message.withdrawn) throw new Error('留言撤回结果与请求不一致')
  return { message, replayed: data.replayed }
}

function decodeReadState(data, intentId) {
  const nonnegative = value => Number.isSafeInteger(value) && value >= 0
  if (!data || data.intentId !== intentId || !nonnegative(data.lastReadMessageId) || !nonnegative(data.latestMessageId) || !nonnegative(data.unreadCount) || data.lastReadMessageId > data.latestMessageId || data.unreadCount > data.latestMessageId - data.lastReadMessageId) throw new Error('私密留言阅读进度不正确')
  return data
}
export async function getIntentMessageReadState(intentId) {
  if (!positive(intentId)) throw new Error('意向编号不正确')
  return decodeReadState(await request({ path: `/api/purchase-intents/${intentId}/messages/read-state`, auth: true }), intentId)
}
export async function markIntentMessagesRead(intentId, lastMessageId) {
  if (!positive(intentId) || !Number.isSafeInteger(lastMessageId) || lastMessageId < 0) throw new Error('已读留言编号不正确')
  const state = decodeReadState(await request({ path: `/api/purchase-intents/${intentId}/messages/read`, method: 'POST', auth: true, data: { lastMessageId } }), intentId)
  if (state.lastReadMessageId < lastMessageId) throw new Error('阅读进度未确认保存')
  return state
}

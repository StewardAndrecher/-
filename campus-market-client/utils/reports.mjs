import { validateModerationItem } from './product-moderation.mjs'
const positive = n => Number.isSafeInteger(n) && n > 0
const uuid = s => typeof s === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(s)
export const reportCategories = Object.freeze([{ value: 'MISLEADING', label: '描述不实' }, { value: 'PROHIBITED', label: '疑似违禁商品' }, { value: 'SPAM', label: '垃圾广告' }, { value: 'OTHER', label: '其他问题' }])
export const reportStates = Object.freeze({ OPEN: '待处理', DISMISSED: '已驳回', ACTIONED: '已下架处理' })
export const reportCategoryLabel = category => reportCategories.find(x => x.value === category)?.label || '未知类型'
export function reportText(value, max = 300) {
  const text = typeof value === 'string' ? value.trim() : ''
  if (text.length < 2 || text.length > max || /[\u0000-\u0008\u000b-\u001f\u007f-\u009f]/.test(text)) throw new Error(`说明需要2～${max}个字符`)
  return text
}
export function validateTicket(t, id) {
  const s = t?.snapshot
  if (!t || !positive(t.id) || (id !== undefined && t.id !== id) || !positive(t.productId) || !reportCategories.some(x => x.value === t.category) || !Object.hasOwn(reportStates, t.status) || reportText(t.reason) !== t.reason ||
      typeof t.createdAt !== 'string' || !t.createdAt || !s || typeof s.title !== 'string' || s.title.length < 2 || s.title.length > 50 || typeof s.description !== 'string' || s.description.length > 500 || !positive(s.priceInCents) || s.priceInCents > 99999999 ||
      !['UNCLASSIFIED','BOOK','DIGITAL','DAILY','SPORT','OTHER'].includes(s.categoryCode) || !(s.coverImageId === null || s.coverImageId === '' || uuid(s.coverImageId))) throw new Error('举报记录格式不正确，请刷新')
  if (t.status === 'OPEN' ? t.version !== 0 || t.resolution !== null || t.resolvedAt !== null || t.moderationEventId !== null
    : t.version !== 1 || reportText(t.resolution) !== t.resolution || typeof t.resolvedAt !== 'string' || !t.resolvedAt || (t.status === 'ACTIONED' ? !positive(t.moderationEventId) : t.moderationEventId !== null)) throw new Error('举报处理状态不一致，请刷新')
  return t
}
export function validateReportDetail(data, id, admin) {
  if (!admin) return validateTicket(data, id)
  validateTicket(data?.ticket, id)
  if (!positive(data?.reporter?.id) || typeof data.reporter.nickname !== 'string' || !data.reporter.nickname.trim()) throw new Error('举报人信息无效')
  validateModerationItem(data.currentProduct, data.ticket.productId)
  return data
}
export function validateReportPage(data, query) {
  if (!data || data.page !== query.page || data.size !== query.size || !Number.isSafeInteger(data.total) || data.total < 0 || !Array.isArray(data.records) || data.records.length > query.size || data.total < data.records.length) throw new Error('举报列表格式不正确')
  let previous = Infinity
  for (const t of data.records) { validateTicket(t); if (t.id >= previous || (query.status && t.status !== query.status)) throw new Error('举报列表顺序或筛选不正确'); previous = t.id }
  return data
}
export function validateReportReceipt(data, { id, productId, category, reason, action }) {
  validateTicket(data?.ticket, id)
  const t = data.ticket
  if (typeof data.replayed !== 'boolean' || t.productId !== productId || (category && (t.category !== category || t.reason !== reason)) ||
      (action && (t.status !== (action === 'BLOCK' ? 'ACTIONED' : 'DISMISSED') || t.resolution !== reason))) throw new Error('举报处理回执不一致，请刷新查看')
  return data
}
// Frozen pending payload stays only in this page instance, and never crosses accounts or page visibility.
export function createReportTask({ identity, load, send, validateLoad, validateReceipt, notify = () => {} }) {
  const empty = () => ({ data: null, loading: false, submitting: false, error: '', status: 0, pending: null })
  let state = empty(), generation = 0, disposed = false, pendingScope = null
  const emit = () => notify({ ...state })
  const matches = scope => { const now = identity(); return now?.userId === scope?.userId && now?.token === scope?.token }
  const valid = scope => positive(scope?.userId) && typeof scope.token === 'string' && !!scope.token
  async function refresh() {
    if (disposed || state.submitting) return
    const ticket = ++generation, scope = identity()
    if (state.pending && !matches(pendingScope)) { state = empty(); pendingScope = null }
    state = { ...state, data: null, loading: false, error: '', status: 0 }; emit()
    if (!valid(scope)) { state = empty(); emit(); return }
    state = { ...state, loading: true }; emit()
    try { const data = await load(); if (!disposed && ticket === generation && matches(scope)) state = { ...state, data: validateLoad(data), status: 200 } }
    catch (error) { if (!disposed && ticket === generation && matches(scope)) state = { ...state, data: null, error: error?.message || '读取失败，请刷新', status: error?.statusCode || 0 } }
    finally { if (!disposed && ticket === generation) { state = matches(scope) ? { ...state, loading: false } : empty(); emit() } }
  }
  async function execute(payload, replay = false) {
    if (disposed || state.loading || state.submitting || (state.pending && !replay)) return null
    const scope = identity(); if (!valid(scope)) return null
    if (replay && !matches(pendingScope)) { state = empty(); pendingScope = null; emit(); return null }
    const pending = replay ? state.pending : Object.freeze({ ...payload })
    if (!pending || !uuid(pending.requestId)) throw new Error('请求标识无效')
    const ticket = ++generation
    pendingScope = { ...scope }
    state = { ...state, submitting: true, pending, error: '', status: 0 }; emit()
    try {
      const receipt = validateReceipt(await send(pending), pending)
      if (disposed || ticket !== generation || !matches(scope)) return null
      state = { ...state, data: null, pending: null, error: '', status: 200 }; return receipt
    } catch (error) {
      if (!disposed && ticket === generation && matches(scope)) {
        const known = [400,401,403,404,409].includes(error?.statusCode)
        state = { ...state, data: null, pending: known ? null : pending, error: error?.message || '提交结果暂未确认', status: error?.statusCode || 0 }
      }
      return null
    } finally { if (!disposed && ticket === generation) { state = matches(scope) ? { ...state, submitting: false } : empty(); emit() } }
  }
  return { refresh, execute, retry: () => execute(null, true), deactivate() { generation++; state = empty(); emit() }, dispose() { disposed = true; generation++; state = empty(); emit() } }
}

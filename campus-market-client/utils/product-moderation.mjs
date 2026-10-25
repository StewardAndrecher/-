const positive = n => Number.isSafeInteger(n) && n > 0
const nonnegative = n => Number.isSafeInteger(n) && n >= 0
const states = ['CLEAR', 'BLOCKED', 'APPEALED']
const categories = ['UNCLASSIFIED', 'BOOK', 'DIGITAL', 'DAILY', 'SPORT', 'OTHER']
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
export const reviewLabels = Object.freeze({ CLEAR: '无平台限制', BLOCKED: '平台限制中', APPEALED: '等待复核' })
export const actionLabels = Object.freeze({ BLOCK: '下架并限制上架', APPEAL: '申请复核', RELEASE: '解除平台限制' })
export function normalizeReviewReason(value) {
  if (typeof value !== 'string') throw new Error('请填写处理说明')
  const text = value.trim()
  if (text.length < 2 || text.length > 200 || /[\u0000-\u0008\u000b-\u001f\u007f-\u009f]/.test(text)) throw new Error('说明需要2～200个字符')
  return text
}
function evidence(p) {
  return p && typeof p.title === 'string' && p.title.length >= 2 && p.title.length <= 50 &&
    typeof p.description === 'string' && p.description.length <= 500 && positive(p.priceInCents) && p.priceInCents <= 99999999 &&
    categories.includes(p.categoryCode) && (p.coverImageId === null || p.coverImageId === '' || UUID.test(p.coverImageId))
}
export function validateModerationItem(item, id) {
  const p = item?.product, m = item?.moderation
  if (!p || !positive(p.id) || (id !== undefined && p.id !== id) || !nonnegative(p.version) ||
      !['ON_SALE', 'OFF_SHELF', 'SOLD'].includes(p.status) ||
      !evidence({ ...p, categoryCode: p.category?.code }) || typeof p.category?.name !== 'string' ||
      (p.seller !== null && (!positive(p.seller?.id) || typeof p.seller?.nickname !== 'string')) ||
      !nonnegative(p.favoriteCount) || !m || !states.includes(m.state) || typeof m.reason !== 'string' || m.reason.length > 200 ||
      !nonnegative(m.lastEventId) || (m.lastEventId === 0 ? m.state !== 'CLEAR' || m.reason !== '' || m.updatedAt !== null : m.reason.length < 2 || typeof m.updatedAt !== 'string' || !m.updatedAt) ||
      (m.state === 'APPEALED' ? typeof m.appealReason !== 'string' || m.appealReason.length < 2 || m.appealReason.length > 200 : m.appealReason !== null) ||
      (m.state !== 'CLEAR' && p.status !== 'OFF_SHELF')) throw new Error('商品审核数据不完整，请刷新')
  return item
}
export function validateModerationDetail(data, userId, id, mode) {
  validateModerationItem(data?.item, id)
  if (!positive(userId) || !['admin', 'seller'].includes(mode) || (mode === 'seller' && data.item.product.seller?.id !== userId) || !Array.isArray(data.events) || data.events.length > 20) throw new Error('处理记录归属不正确')
  let previous = Infinity
  for (const event of data.events) {
    const transition = event?.action === 'BLOCK' ? event.actorRole === 'ADMIN' && event.fromState === 'CLEAR' && event.toState === 'BLOCKED'
      : event?.action === 'APPEAL' ? event.actorRole === 'SELLER' && event.fromState === 'BLOCKED' && event.toState === 'APPEALED'
      : event?.action === 'RELEASE' && event.actorRole === 'ADMIN' && ['BLOCKED', 'APPEALED'].includes(event.fromState) && event.toState === 'CLEAR'
    if (!event || !positive(event.id) || event.id >= previous || !transition || typeof event.reason !== 'string' || event.reason.length < 2 || event.reason.length > 200 ||
        !nonnegative(event.fromVersion) || !nonnegative(event.toVersion) || event.toVersion !== event.fromVersion + 1 || event.toVersion > data.item.product.version ||
        typeof event.createdAt !== 'string' || !event.createdAt || !evidence(event.snapshot)) throw new Error('处理记录格式不正确')
    previous = event.id
  }
  const latest = data.events[0], m = data.item.moderation
  if (m.lastEventId === 0 ? data.events.length !== 0 : !latest || latest.id !== m.lastEventId || latest.toState !== m.state || latest.reason !== (m.state === 'APPEALED' ? m.appealReason : m.reason)) throw new Error('处理记录与当前状态不一致')
  return data
}
export function validateModerationPage(data, query) {
  if (!data || data.page !== query.page || data.size !== query.size || !Array.isArray(data.records) || data.records.length > query.size || !nonnegative(data.total) || data.total < data.records.length) throw new Error('巡查列表格式不正确')
  let previous = Infinity
  for (const item of data.records) {
    validateModerationItem(item)
    if (item.product.id >= previous || (query.status && item.product.status !== query.status) || (query.moderationState && item.moderation.state !== query.moderationState)) throw new Error('巡查列表顺序或筛选不正确')
    previous = item.product.id
  }
  return data
}
export function allowedReviewActions(item, mode) {
  if (!item || item.product.status === 'SOLD') return []
  if (mode === 'seller') return item.moderation.state === 'BLOCKED' ? ['APPEAL'] : []
  return mode === 'admin' ? item.moderation.state === 'CLEAR' ? ['BLOCK'] : ['RELEASE'] : []
}
export function createReviewController({ load, submit, identity, productId, mode, newRequestId, notify = () => {} }) {
  let generation = 0, disposed = false
  const empty = () => ({ detail: null, loading: false, submitting: false, error: '', status: 0, pending: null })
  let state = empty()
  const emit = () => notify({ ...state })
  const matches = scope => { const now = identity(); return now?.userId === scope.userId && now?.token === scope.token }
  const validScope = scope => positive(scope?.userId) && !!scope?.token && positive(productId)
  async function refresh() {
    if (disposed || state.submitting) return
    const ticket = ++generation, scope = identity()
    state = { ...state, detail: null, loading: false, error: '', status: 0 }; emit()
    if (!validScope(scope)) { state = empty(); emit(); return }
    state = { ...state, loading: true }; emit()
    try {
      const data = await load()
      if (disposed || ticket !== generation || !matches(scope)) return
      state = { ...state, detail: validateModerationDetail(data, scope.userId, productId, mode), status: 200 }
    } catch (error) {
      if (!disposed && ticket === generation && matches(scope)) state = { ...state, detail: null, error: error?.message || '读取处理记录失败', status: error?.statusCode || 0 }
    } finally {
      if (!disposed && ticket === generation) { state = matches(scope) ? { ...state, loading: false } : empty(); emit() }
    }
  }
  async function send(pending) {
    const ticket = ++generation, scope = identity()
    if (!validScope(scope)) { state = empty(); emit(); return false }
    state = { ...state, submitting: true, pending, error: '', status: 0 }; emit()
    try {
      const receipt = await submit(pending)
      if (disposed || ticket !== generation || !matches(scope)) return false
      validateModerationDetail(receipt?.detail, scope.userId, productId, mode)
      if (typeof receipt.replayed !== 'boolean' || receipt.detail.item.product.version < pending.version + 1 ||
          (!receipt.replayed && (receipt.detail.events[0]?.action !== pending.action || receipt.detail.events[0]?.reason !== pending.reason || receipt.detail.events[0]?.fromVersion !== pending.version))) throw new Error('处理结果不完整，请核对后重试同一操作')
      state = { ...state, detail: receipt.detail, pending: null, status: 200 }; return true
    } catch (error) {
      if (!disposed && ticket === generation && matches(scope)) state = { ...state, detail: null, error: error?.message || '处理结果未知，请重试同一操作或重新查看记录', status: error?.statusCode || 0,
        pending: [400, 401, 403, 404, 409].includes(error?.statusCode) ? null : pending }
      return false
    } finally {
      if (!disposed && ticket === generation) { state = matches(scope) ? { ...state, submitting: false } : empty(); emit() }
    }
  }
  async function act(action, rawReason, expectedVersion) {
    if (disposed || state.loading || state.submitting || state.pending) return false
    if (!state.detail || !allowedReviewActions(state.detail.item, mode).includes(action) || state.detail.item.product.version !== expectedVersion) throw new Error('页面状态已变化，请先刷新')
    const reason = normalizeReviewReason(rawReason), requestId = newRequestId()
    if (!UUID.test(requestId)) throw new Error('无法生成有效请求标识')
    return send(Object.freeze({ action, reason, version: expectedVersion, requestId }))
  }
  return { refresh, act, retry() { return !disposed && !state.loading && !state.submitting && state.pending ? send(state.pending) : Promise.resolve(false) },
    deactivate() { if (!disposed) { generation++; state = empty(); emit() } }, dispose() { disposed = true; generation++ } }
}

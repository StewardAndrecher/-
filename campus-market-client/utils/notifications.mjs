const positive = n => Number.isSafeInteger(n) && n > 0
const nonnegative = n => Number.isSafeInteger(n) && n >= 0
export const notificationCategories = Object.freeze({ ORDER: '订单消息', REPORT: '举报结果', MODERATION: '商品处理' })
export const notificationTargets = Object.freeze({
 ORDER_DETAIL: { category:'ORDER', label:'查看订单', path:'/pages/order/detail' },
 ORDER_REVIEWS: { category:'ORDER', label:'查看交易评价', path:'/pages/order/reviews' },
 OWN_ORDER_ISSUE: { category:'ORDER', label:'查看本人反馈结果', path:'/pages/order/issue' },
 REPORT_DETAIL: { category:'REPORT', label:'查看举报结果', path:'/pages/report/detail' },
 PRODUCT_MODERATION: { category:'MODERATION', label:'查看商品处理', path:'/pages/product/moderation' }
})
const text = (s, max) => typeof s === 'string' && s.trim().length > 0 && s.length <= max
export function validateNotification(n, id) {
  if (!n || !positive(n.id) || (id !== undefined && n.id !== id) || !Object.hasOwn(notificationCategories, n.category) || !Object.hasOwn(notificationTargets,n.target) || notificationTargets[n.target].category!==n.category || !positive(n.resourceId) || !text(n.title,80) || !text(n.body,500) || !text(n.occurredAt,40) || !text(n.createdAt,40) || !(n.readAt === null || text(n.readAt,40))) throw new Error('通知格式不正确，请刷新')
  return n
}
export function notificationQuery(value={}) {
  if(!value||typeof value!=='object'||Array.isArray(value)||Object.keys(value).some(key=>!['page','size','status','category'].includes(key)))throw new Error('通知筛选条件不正确')
  const {page=1,size=10,status='ALL',category='ALL'}=value
  if(!positive(page)||page>2147483647||!positive(size)||size>20||!['ALL','UNREAD','READ'].includes(status)||!['ALL','ORDER','REPORT','MODERATION'].includes(category))throw new Error('通知筛选条件不正确')
  return {page,size,status,category}
}
export function validateNotificationPage(data, query) {
  query = notificationQuery(query)
  if (!data || data.page !== query.page || data.size !== query.size || !nonnegative(data.total) || !Array.isArray(data.records) || data.records.length > query.size || data.total < data.records.length ) throw new Error('通知列表格式不正确')
  let last = Infinity
  for (const n of data.records) { validateNotification(n); if (n.id >= last || (query.status === 'UNREAD' && n.readAt !== null) || (query.status === 'READ' && n.readAt === null) || (query.category !== 'ALL' && n.category !== query.category)) throw new Error('通知顺序或筛选不正确'); last = n.id }
  return data
}
export function validateNotificationUnread(data) {
  if (!data || !nonnegative(data.unreadCount)) throw new Error('未读数格式不正确')
  return data
}
export function notificationPath(n) {
  validateNotification(n)
  return notificationTargets[n.target].path+'?id='+n.resourceId
}
export function notificationActionLabel(n) {
  validateNotification(n)
  return notificationTargets[n.target].label+'（标记已读）'
}
export function validateReadNotification(data, expected) {
  validateNotification(data, expected.id)
  for (const key of ['category','resourceId','target','title','body','occurredAt','createdAt']) if (data[key] !== expected[key]) throw new Error('通知回执不一致，请刷新')
  if (data.readAt === null) throw new Error('通知已读状态尚未确认')
  return data
}
export function validateReadNotificationBatch(data, expected) {
  if (!Array.isArray(expected) || expected.length < 1 || expected.length > 20 || !Array.isArray(data?.records) || data.records.length !== expected.length) throw new Error('批量已读回执数量不一致，请刷新')
  const originals = new Map(expected.map(n => [n.id,n])), seen = new Set()
  if (originals.size !== expected.length) throw new Error('批量通知编号重复')
  for (const n of data.records) {
    if (!originals.has(n?.id) || seen.has(n.id)) throw new Error('批量已读回执编号不一致，请刷新')
    seen.add(n.id); validateReadNotification(n, originals.get(n.id))
  }
  return data
}
export function createNotificationInbox({ identity, load, unread, read, readBatch, confirmBatch, navigate, notify = () => {} }) {
  const empty = () => ({ page: null, unreadCount: null, loading: false, openingId: null, batchReading: false, error: '', status: 0 })
  let state = empty(), generation = 0, disposed = false, openingScope = null
  const emit = () => notify({ ...state })
  const valid = who => positive(who?.userId) && typeof who?.token === 'string' && !!who.token
  const matches = who => { const now = identity(); return now?.userId === who?.userId && now?.token === who?.token }
  async function refresh(query, active = true) {
    if (disposed) return
    if ((state.openingId !== null || state.batchReading) && matches(openingScope)) return
    const ticket = ++generation, who = identity(), expected = { ...query }
    state = empty(); emit()
    if (!active || !valid(who)) return
    state = { ...state, loading: true }; emit()
    try {
      const scoped = notificationQuery(expected)
      const [page,count] = await Promise.all([load(scoped),unread()])
      if (disposed || ticket !== generation || !matches(who)) return
      state = { ...state, page: validateNotificationPage(page,scoped), unreadCount: validateNotificationUnread(count).unreadCount, status: 200 }
    } catch(error) {
      if (!disposed && ticket === generation && matches(who)) state = { ...empty(), loading: true, error: error?.message || '暂时无法读取通知', status: error?.statusCode || 0 }
    } finally { if (!disposed && ticket === generation) { state = matches(who) ? { ...state, loading: false } : empty(); emit() } }
  }
  async function open(id) {
    if (disposed || state.loading || state.openingId !== null || state.batchReading) return null
    const who = identity(), expected = state.page?.records.find(n => n.id === id)
    if (!valid(who) || !expected) return null
    const ticket = ++generation
    openingScope = { ...who }
    state = { ...state, openingId: id, error: '', status: 0 }; emit()
    try {
      const result = await read(id)
      if (disposed || ticket !== generation || !matches(who)) return null
      validateReadNotification(result, expected)
      const path = notificationPath(result)
      await navigate(path)
      if (disposed || ticket !== generation || !matches(who)) return null
      return path
    } catch(error) {
      if (!disposed && ticket === generation && matches(who)) state = { ...empty(), openingId: id, error: error?.message || '暂时无法确认已读状态，请刷新后重试', status: error?.statusCode || 0 }
      return null
    } finally { if (!disposed && ticket === generation) { state = matches(who) ? { ...state, openingId: null } : empty(); emit() } }
  }
  async function markPageRead() {
    if (disposed || state.loading || state.openingId !== null || state.batchReading) return false
    const who = { ...identity() }, expected = (state.page?.records || []).filter(n => n.readAt === null).map(n => ({ ...n }))
    if (!valid(who) || !expected.length || typeof readBatch !== 'function' || typeof confirmBatch !== 'function') return false
    const ticket = ++generation
    openingScope = { ...who }
    state = { ...state, batchReading: true, error: '', status: 0 }; emit()
    try {
      const confirmed = await confirmBatch(expected.length)
      if (disposed || ticket !== generation || !matches(who) || !confirmed) return false
      const result = await readBatch(expected.map(n => n.id))
      if (disposed || ticket !== generation || !matches(who)) return false
      validateReadNotificationBatch(result, expected)
      state = { ...empty(), batchReading: true }; emit()
      return true
    } catch(error) {
      if (!disposed && ticket === generation && matches(who)) state = { ...empty(), batchReading: true, error: error?.message || '暂时无法确认批量已读状态，请刷新后重试', status: error?.statusCode || 0 }
      return false
    } finally {
      if (!disposed && ticket === generation) { state = matches(who) ? { ...state, batchReading: false } : empty(); emit() }
    }
  }
  return { refresh, open, markPageRead, deactivate() { generation++; state = empty(); emit() }, dispose() { disposed = true; generation++; state = empty(); emit() } }
}

import { validOrderTime } from './order.js'
const positive = n => Number.isSafeInteger(n) && n > 0
const exact = (value, keys) => value && typeof value === 'object' && !Array.isArray(value) &&
  Object.keys(value).sort().join(',') === keys.split(',').sort().join(',')
const timeKey = value => {
  const [date, time] = value.split('T'), [h, m, seconds = '00'] = time.split(':')
  const [s, f = ''] = seconds.split('.')
  return `${date}T${h}:${m}:${s}.${f.padEnd(9, '0')}`
}
export function sessionListQuery(value = {}) {
  const { page = 1, size = 10 } = value
  if (!positive(page) || page > 1000000 || !positive(size) || size > 20 ||
      Object.keys(value).some(key => !['page', 'size'].includes(key)))
    throw new Error('登录明细分页参数不正确')
  return { page, size }
}
export function validateSessionPage(data, query, userId) {
  const q = sessionListQuery(query)
  if (!positive(userId) || !exact(data, 'userId,items,total,page,size,totalPages,sampledAt') ||
      data.userId !== userId || !positive(data.total) || data.size !== q.size ||
      data.totalPages !== Math.floor((data.total - 1) / q.size) + 1 ||
      data.page !== Math.min(q.page, data.totalPages) || !validOrderTime(data.sampledAt) ||
      !Array.isArray(data.items) ||
      data.items.length !== Math.min(q.size, data.total - (data.page - 1) * q.size))
    throw new Error('登录明细与当前账号或分页不匹配，请重新刷新')
  let previous = null, currentCount = 0
  for (const item of data.items) {
    if (!exact(item, 'current,createdAt,expiresAt') || typeof item.current !== 'boolean' ||
        !validOrderTime(item.createdAt) || !validOrderTime(item.expiresAt) ||
        timeKey(item.createdAt) > timeKey(data.sampledAt) ||
        timeKey(item.expiresAt) <= timeKey(data.sampledAt) ||
        (previous !== null && timeKey(item.createdAt) > previous))
      throw new Error('登录明细记录格式或时间不正确')
    previous = timeKey(item.createdAt)
    if (item.current) currentCount++
  }
  if (currentCount > 1 || (data.totalPages === 1 && currentCount !== 1))
    throw new Error('当前登录标记不正确，请重新刷新')
  return data
}
export function createSessionListManager({ identity, load, notify = () => {} }) {
  const empty = () => ({ page: null, loading: false, error: '', status: 0 })
  let state = empty(), generation = 0, disposed = false
  const emit = () => notify({ ...state })
  const matches = who => {
    const actual = identity()
    return actual?.userId === who.userId && actual?.token === who.token
  }
  async function refresh(query = {}) {
    if (disposed) return
    const ticket = ++generation, who = { ...identity() }
    state = empty(); emit()
    if (!positive(who.userId) || typeof who.token !== 'string' || !who.token) {
      state.error = '请先登录后查看有效登录明细'; emit(); return
    }
    state.loading = true; emit()
    try {
      const q = sessionListQuery(query), result = await load(q)
      if (disposed || ticket !== generation || !matches(who)) return
      state = { ...state, page: validateSessionPage(result, q, who.userId), status: 200 }
    } catch (error) {
      if (!disposed && ticket === generation && matches(who))
        state = { ...empty(), loading: true, error: error?.message || '加载登录明细失败', status: error?.statusCode || 0 }
    } finally {
      if (!disposed && ticket === generation) { state = matches(who) ? { ...state, loading: false } : empty(); emit() }
    }
  }
  return {
    refresh,
    reset() { generation++; state = empty(); emit() },
    dispose() { disposed = true; generation++; state = empty(); emit() }
  }
}

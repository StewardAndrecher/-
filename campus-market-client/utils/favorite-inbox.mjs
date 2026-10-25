import { validateFavoriteManagePage } from './favorite-batch.mjs'

export const FAVORITE_PAGE_SIZE = 5
const positive = value => Number.isSafeInteger(value) && value > 0
export function favoriteAvailability(value = 'ALL') {
  if (!['ALL', 'AVAILABLE', 'UNAVAILABLE'].includes(value)) throw new Error('收藏筛选条件不正确')
  return value
}
export function favoriteKeyword(value = '') {
  if (typeof value !== 'string') throw new Error('收藏搜索关键词不正确')
  const keyword = value.trim()
  if (keyword.length > 50 || /[\u0000-\u001f\u007f-\u009f]/.test(keyword))
    throw new Error('收藏搜索关键词不能超过50个字符或包含控制字符')
  return keyword
}
export const FAVORITE_AVAILABILITY = Object.freeze([
  Object.freeze({ value: 'ALL', label: '全部收藏' }),
  Object.freeze({ value: 'AVAILABLE', label: '可查看商品' }),
  Object.freeze({ value: 'UNAVAILABLE', label: '已失效商品' })
])
const initial = (availability = 'ALL', keyword = '') => ({ availability, keyword, page: null, legacyIds: [], loading: false, removingId: 0,
  importing: false, error: '', notice: '', uncertain: false })

// An issued request may finish after hide/logout. Only its live generation may commit.
export function createFavoriteInbox({ identity, load, setFavorite, loadLegacy,
  markImported, notify = () => {} }) {
  let state = initial(), generation = 0, visible = false, disposed = false, scope = null
  const emit = () => notify({ ...state, legacyIds: [...state.legacyIds] })
  const valid = who => positive(who?.userId) && typeof who?.token === 'string' && !!who.token
  const matches = who => {
    const now = identity()
    return now?.userId === who?.userId && now?.token === who?.token
  }
  const alive = (ticket, who) => !disposed && visible && ticket === generation && matches(who)
  const saving = () => state.removingId > 0 || state.importing
  function clear() { generation++; scope = null; state = initial(); emit() }
  function finish(ticket, who, changes) {
    if (disposed || ticket !== generation) return
    state = visible && matches(who) ? { ...state, ...changes } : initial()
    emit()
  }
  const canWrite = () => !disposed && visible && !state.loading && !saving() &&
    !state.uncertain && state.page && valid(scope) && matches(scope)
  function receipt(result, id, favorite) {
    if (result?.productId !== id || result.favorite !== favorite) {
      throw new Error('收藏操作回执异常')
    }
  }
  async function refresh(requested = state.page?.page || 1, notice = '', availability = state.availability, keyword = state.keyword) {
    if (disposed || !visible || saving()) return false
    try { favoriteAvailability(availability); keyword = favoriteKeyword(keyword) } catch (error) { state.error = error.message; emit(); return false }
    const who = { ...identity() }, ticket = ++generation
    scope = who; state = { ...initial(availability, keyword), loading: true, notice }; emit()
    try {
      if (!valid(who)) throw new Error('请先登录后查看收藏')
      if (!positive(requested) || requested > 1000000) throw new Error('收藏页码不正确')
      let page = requested
      for (let attempt = 0; attempt < 2; attempt++) {
        const result = await load({ page, size: FAVORITE_PAGE_SIZE, ...(availability === 'ALL' ? {} : { availability }), ...(keyword ? { keyword } : {}) })
        if (!alive(ticket, who)) return false
        validateFavoriteManagePage(result, page, FAVORITE_PAGE_SIZE)
        if (keyword && result.records.some(row => row.product === null)) {
          throw new Error('标题搜索返回了不可查看商品，请刷新确认')
        }
        if (result.records.some(row => availability === 'AVAILABLE' ? row.product === null
          : availability === 'UNAVAILABLE' ? row.product !== null : false)) {
          throw new Error('收藏列表与当前筛选不匹配，请刷新确认')
        }
        const last = Math.max(1, Math.ceil(result.total / FAVORITE_PAGE_SIZE))
        if (page > last) { page = last; continue }
        state.page = result
        try {
          const ids = loadLegacy(who.userId)
          if (!Array.isArray(ids) || ids.some(id => !positive(id))) throw new Error('旧收藏编号异常')
          state.legacyIds = [...new Set(ids)]
        } catch { state.error = '旧本机收藏读取失败，账号收藏仍可使用' }
        return true
      }
      throw new Error('收藏数量正在变化，请重新刷新')
    } catch (error) {
      if (alive(ticket, who)) state.error = error?.statusCode === 401 ? '登录已失效，请重新登录' : error?.message || '读取收藏失败'
      return false
    } finally { finish(ticket, who, { loading: false }) }
  }
  async function remove(id) {
    if (!canWrite() || !state.page.records.some(row => row.productId === id)) return false
    const who = { ...scope }, ticket = ++generation, page = state.page.page
    state.removingId = id; state.error = ''; state.notice = ''; emit()
    let removed = false
    try {
      const result = await setFavorite(id, false)
      if (!alive(ticket, who)) return false
      receipt(result, id, false); removed = true
    } catch (error) {
      if (alive(ticket, who)) state = { ...initial(state.availability, state.keyword), removingId: id, uncertain: true,
        error: (error?.message || '未收到取消结果') + '；请先刷新核对，不会自动重发。' }
    } finally { finish(ticket, who, { removingId: 0 }) }
    if (removed && alive(ticket, who)) {
      await refresh(page, '已取消这件商品的收藏。')
      return true
    }
    return false
  }
  async function importLegacy() {
    if (!canWrite() || !state.legacyIds.length) return false
    const who = { ...scope }, ticket = ++generation, ids = state.legacyIds.slice(0, 20)
    let imported = 0, unavailable = 0, failure = '', uncertain = false
    state.importing = true; state.error = ''; state.notice = ''; emit()
    try {
      for (const id of ids) {
        if (!alive(ticket, who)) return false
        try {
          const result = await setFavorite(id, true)
          if (!alive(ticket, who)) return false
          receipt(result, id, true); imported++
        } catch (error) {
          if (!alive(ticket, who)) return false
          if (error?.statusCode === 404) { unavailable++; continue }
          uncertain = true
          failure = (error?.message || '未收到导入结果') + '；请先刷新核对，不会自动重发。'
          break
        }
        try { markImported(who.userId, id) }
        catch { failure = '云端收藏已合并，但本机导入记录保存失败；原收藏仍保留，可刷新后确认。'; break }
      }
    } finally { finish(ticket, who, { importing: false }) }
    if (!alive(ticket, who)) return false
    const notice = `已合并${imported}件，${unavailable}件不可用。`
    if (uncertain) {
      state = { ...initial(state.availability, state.keyword), uncertain: true, error: failure, notice }; emit(); return false
    }
    await refresh(1, notice + (failure || '原本机收藏保留。'))
    return true
  }
  return {
    refresh: page => refresh(page), filter: availability => refresh(1, '', availability),
    search: keyword => refresh(1, '', state.availability, keyword), remove, importLegacy,
    show() { if (disposed) return Promise.resolve(false); visible = true; clear(); return refresh(1) },
    hide() { visible = false; clear() },
    identityChanged() { clear(); return visible && !disposed ? refresh(1) : Promise.resolve(false) },
    dispose() { disposed = true; visible = false; clear() }
  }
}

const categories = ['UNCLASSIFIED', 'BOOK', 'DIGITAL', 'DAILY', 'SPORT', 'OTHER']
const positive = value => Number.isSafeInteger(value) && value > 0
const cursorText = value => typeof value === 'string' && /^[A-Za-z0-9_-]{1,256}$/.test(value)

export function validateFeedPage(data, size) {
  if (!data || data.size !== size || !Array.isArray(data.records) || data.records.length > size ||
      typeof data.hasMore !== 'boolean' || (data.hasMore ? data.records.length !== size || !cursorText(data.nextCursor) : data.nextCursor !== null)) {
    throw new Error('商品列表响应格式不正确，请刷新后重试')
  }
  const ids = new Set()
  for (const item of data.records) {
    if (!item || !positive(item.id) || ids.has(item.id) || typeof item.title !== 'string' ||
        typeof item.description !== 'string' || !positive(item.priceInCents) || item.priceInCents > 99_999_999 ||
        item.status !== 'ON_SALE' || !categories.includes(item.category?.code) || typeof item.category?.name !== 'string' ||
        !Number.isSafeInteger(item.favoriteCount) || item.favoriteCount < 0 ||
        (item.seller !== null && (!positive(item.seller?.id) || typeof item.seller?.nickname !== 'string'))) {
      throw new Error('商品数据不完整，请刷新后重试')
    }
    ids.add(item.id)
  }
  return data
}

// Framework independent so concurrency, retry and cursor advancement are tested without a browser.
export function createProductFeed(fetchPage, notify = () => {}, size = 10) {
  if (!Number.isSafeInteger(size) || size < 1 || size > 20) throw new Error('每次加载数量无效')
  let generation = 0
  let disposed = false
  let state = { items: [], cursor: '', hasMore: true, loading: false, error: '', keyword: '', category: '' }
  const emit = () => notify({ ...state, items: [...state.items] })
  async function loadMore() {
    if (disposed || state.loading || !state.hasMore) return
    const ticket = generation
    const requested = state.cursor
    state = { ...state, loading: true, error: '' }
    emit()
    try {
      const data = await fetchPage({ cursor: requested, size, keyword: state.keyword, category: state.category })
      if (disposed || ticket !== generation) return
      validateFeedPage(data, size)
      const seen = new Set(state.items.map(item => item.id))
      if (data.records.some(item => seen.has(item.id)) || (data.hasMore && data.nextCursor === requested)) {
        throw new Error('列表位置没有前进，请刷新列表后重试')
      }
      state = { ...state, items: [...state.items, ...data.records], cursor: data.nextCursor || '', hasMore: data.hasMore }
    } catch (error) {
      if (!disposed && ticket === generation) state = { ...state, error: error?.message || '加载失败，请稍后重试' }
    } finally {
      if (!disposed && ticket === generation) { state = { ...state, loading: false }; emit() }
    }
  }
  async function refresh({ keyword = '', category = '' } = {}) {
    if (disposed) return
    const normalized = typeof keyword === 'string' ? keyword.trim() : ''
    if (normalized.length > 50 || (category !== '' && !categories.includes(category))) throw new Error('搜索条件无效')
    generation++
    state = { items: [], cursor: '', hasMore: true, loading: false, error: '', keyword: normalized, category }
    emit()
    await loadMore()
  }
  return { refresh, loadMore, dispose() { disposed = true; generation++ } }
}

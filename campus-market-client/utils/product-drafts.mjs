const PREFIX = 'campus-product-drafts-v1:'
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const CATEGORIES = ['UNCLASSIFIED', 'BOOK', 'DIGITAL', 'DAILY', 'SPORT', 'OTHER']
export const PRODUCT_DRAFT_LIMIT = 10
function key(userId) {
  if (!Number.isSafeInteger(userId) || userId <= 0) throw new Error('请登录后管理草稿')
  return PREFIX + userId
}
export function draftContent(value) {
  if (!value || typeof value.title !== 'string' || value.title.length > 50 ||
      typeof value.description !== 'string' || value.description.length > 500 ||
      typeof value.priceText !== 'string' || !/^[0-9.]{0,9}$/.test(value.priceText) ||
      !(value.coverImageId === '' || typeof value.coverImageId === 'string' && UUID.test(value.coverImageId)) ||
      !CATEGORIES.includes(value.categoryCode)) throw new Error('草稿内容格式不正确')
  return { title: value.title, description: value.description, priceText: value.priceText,
    coverImageId: value.coverImageId, categoryCode: value.categoryCode }
}
export function hasDraftContent(value) {
  const p = draftContent(value)
  return !!(p.title.trim() || p.description.trim() || p.priceText || p.coverImageId || p.categoryCode !== 'OTHER')
}
function read(userId) {
  const storageKey = key(userId), raw = uni.getStorageSync(storageKey)
  if (!raw) return { storageKey, raw, records: [] }
  let saved
  try { saved = JSON.parse(raw) } catch { throw new Error('草稿记录无法读取，请保留本机数据并检查') }
  if (saved?.schema !== 1 || saved.userId !== userId || !Array.isArray(saved.records) || saved.records.length > PRODUCT_DRAFT_LIMIT) {
    throw new Error('草稿记录格式不正确，请保留本机数据并检查')
  }
  const seen = new Set()
  const records = saved.records.map(item => {
    if (item?.userId !== userId || !UUID.test(item.id) || seen.has(item.id) ||
        !Number.isSafeInteger(item.revision) || item.revision < 1 ||
        !Number.isSafeInteger(item.updatedAt) || item.updatedAt < 1) throw new Error('草稿记录格式不正确')
    seen.add(item.id)
    return { id: item.id, userId, revision: item.revision, updatedAt: item.updatedAt, content: draftContent(item.content) }
  }).sort((a, b) => b.updatedAt - a.updatedAt || a.id.localeCompare(b.id))
  return { storageKey, raw, records }
}
export function loadProductDrafts(userId) { return read(userId).records }
function write(userId, state, records) {
  if (uni.getStorageSync(state.storageKey) !== state.raw) throw new Error('草稿箱已变化，请刷新后重试')
  const encoded = JSON.stringify({ schema: 1, userId, records })
  uni.setStorageSync(state.storageKey, encoded)
  if (uni.getStorageSync(state.storageKey) !== encoded) throw new Error('未能确认草稿已保存，请刷新检查')
}
export function saveProductDraft(userId, content, { id, expectedRevision = 0 }, now = Date.now()) {
  if (!UUID.test(id) || !Number.isSafeInteger(expectedRevision) || expectedRevision < 0 ||
      expectedRevision >= Number.MAX_SAFE_INTEGER || !Number.isSafeInteger(now) || now < 1) throw new Error('草稿标识或版本不正确')
  const snapshot = draftContent(content)
  if (!hasDraftContent(snapshot)) throw new Error('请先填写一些商品内容')
  const state = read(userId), previous = state.records.find(item => item.id === id)
  if ((previous?.revision || 0) !== expectedRevision) throw new Error('这份草稿已变化或被删除，请刷新后选择，或另存新草稿')
  if (!previous && state.records.length >= PRODUCT_DRAFT_LIMIT) throw new Error('最多保存10份草稿，请先管理已有草稿')
  const item = { id, userId, revision: expectedRevision + 1, updatedAt: now, content: snapshot }
  write(userId, state, [item, ...state.records.filter(row => row.id !== id)])
  return item
}
export function removeProductDraft(userId, id, expectedRevision) {
  if (!UUID.test(id) || !Number.isSafeInteger(expectedRevision) || expectedRevision < 1) throw new Error('草稿标识或版本不正确')
  const state = read(userId), previous = state.records.find(item => item.id === id)
  if (!previous) return false
  if (previous.revision !== expectedRevision) throw new Error('这份草稿已变化，请刷新后确认')
  write(userId, state, state.records.filter(row => row.id !== id))
  return true
}

import { request } from './request.js'
import { draftContent } from '@/utils/product-drafts.mjs'
const DRAFT_SORTS = ['UPDATED_DESC', 'UPDATED_ASC', 'TITLE_ASC']
const DRAFT_CATEGORIES = ['', 'UNCLASSIFIED', 'BOOK', 'DIGITAL', 'DAILY', 'SPORT', 'OTHER']
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
function validId(id) { if (typeof id !== 'string' || !UUID.test(id)) throw new Error('草稿标识格式不正确') }
function decode(row) {
  validId(row?.draftId)
  if (!Number.isSafeInteger(row.sourceRevision) || row.sourceRevision < 1 ||
      !Number.isSafeInteger(row.version) || row.version < 0 || typeof row.updatedAt !== 'string' || !row.updatedAt) throw new Error('云端备份响应格式不正确')
  return { draftId: row.draftId, sourceRevision: row.sourceRevision, version: row.version, updatedAt: row.updatedAt, content: draftContent(row.content) }
}
export async function getDraftBackups() {
  const data = await request({ path: '/api/product-draft-backups', auth: true })
  if (!Array.isArray(data) || data.length > 10) throw new Error('云端备份列表格式不正确')
  const rows = data.map(decode)
  if (new Set(rows.map(row => row.draftId)).size !== rows.length) throw new Error('云端备份列表出现重复记录')
  return rows
}
export async function getDraftBackupPage({ page = 1, size = 3, keyword = '', category = '', sort = 'UPDATED_DESC' } = {}) {
  if (!Number.isSafeInteger(page) || page < 1 || page > 1_000_000 ||
      !Number.isSafeInteger(size) || size < 1 || size > 10 || typeof keyword !== 'string' || keyword.trim().length > 50 || !DRAFT_CATEGORIES.includes(category) || !DRAFT_SORTS.includes(sort)) throw new Error('草稿查询条件不正确')
  const normalized = keyword.trim()
  const data = await request({ path: '/api/product-draft-backups/page', auth: true, data: { page, size, keyword: normalized, category, sort } })
  if (!data || !Array.isArray(data.items) || !Number.isSafeInteger(data.total) || data.total < 0 || data.total > 10 ||
      data.size !== size || data.keyword !== normalized || data.category !== category || data.sort !== sort || data.totalPages !== Math.max(1, Math.ceil(data.total / size)) ||
      data.page !== Math.min(page, data.totalPages) || data.items.length !== Math.min(size, Math.max(0, data.total - (data.page - 1) * size))) throw new Error('草稿分页响应格式不正确')
  const items = data.items.map(decode)
  if (category && items.some(item => item.content.categoryCode !== category)) throw new Error('草稿分类响应与筛选不一致')
  if (new Set(items.map(item => item.draftId)).size !== items.length) throw new Error('草稿分页响应出现重复记录')
  return { items, total: data.total, page: data.page, size, totalPages: data.totalPages, keyword: normalized, category, sort }
}
export async function getDraftBackup(id) {
  validId(id)
  const row = decode(await request({ path: `/api/product-draft-backups/${id}`, auth: true }))
  if (row.draftId !== id) throw new Error('云端备份标识不一致，请刷新检查')
  return row
}
export async function backupDraft(item) {
  validId(item.id)
  if (!Number.isSafeInteger(item.revision) || item.revision < 1) throw new Error('本机草稿版本不正确')
  const content = draftContent(item.content)
  const row = decode(await request({ path: `/api/product-draft-backups/${item.id}`, method: 'PUT', auth: true, data: { sourceRevision: item.revision, content } }))
  if (row.draftId !== item.id || row.sourceRevision !== item.revision || JSON.stringify(row.content) !== JSON.stringify(content)) throw new Error('云端备份结果与本机版本不一致，请刷新确认')
  return row
}
export function deleteDraftBackup(id, version) {
  validId(id)
  if (!Number.isSafeInteger(version) || version < 0) throw new Error('云端备份版本不正确')
  return request({ path: `/api/product-draft-backups/${id}?version=${version}`, method: 'DELETE', auth: true })
}

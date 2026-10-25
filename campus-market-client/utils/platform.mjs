const id = value => Number.isSafeInteger(value) && value > 0
const uuid = value => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value)
const fail = () => { throw new Error('响应资料不完整，请重新加载确认') }
export function validateContentState(value, owner, username) {
 if (!value || !id(value.userId) || typeof value.username !== 'string' || typeof value.restricted !== 'boolean' || typeof value.reason !== 'string' || !Number.isSafeInteger(value.version) || value.version < 0 || !Array.isArray(value.events) || value.events.length > 50) fail()
 if (username ? value.username !== username : value.userId !== owner.userId) fail()
 const seen = new Set()
 for (const row of value.events) { if (!id(row.id) || seen.has(row.id) || !['RESTRICT','RELEASE','APPEAL'].includes(row.action) || typeof row.reason !== 'string' || typeof row.createdAt !== 'string') fail(); seen.add(row.id) }
 return value
}
export function validateLibrary(value, owner, scope) {
 if (!value || value.userId !== owner.userId || value.scope !== scope || !Array.isArray(value.items) || value.items.length > 12 || !id(value.page) || !id(value.totalPages) || value.page > value.totalPages || !Number.isSafeInteger(value.total) || value.total < value.items.length || !Number.isSafeInteger(value.chargedBytes) || value.chargedBytes < 0 || !id(value.quotaBytes) || !id(value.maxCount) || !Number.isSafeInteger(value.count) || value.count < 0) fail()
 const seen = new Set()
 for (const image of value.items) { if (!uuid(image.id) || seen.has(image.id) || typeof image.referenced !== 'boolean' || !Number.isSafeInteger(image.version) || image.version < 0 || typeof image.sizeKnown !== 'boolean' || !Number.isSafeInteger(image.bytes) || image.bytes < 0 || (scope === 'TRASH') !== (typeof image.trashedAt === 'string')) fail(); seen.add(image.id) }
 return value
}
export function validateCleanup(value) {
 if (!value || value.retentionDays !== 30 || value.storage !== 'LOCAL' || !Array.isArray(value.candidates) || value.candidates.length > 50 || typeof value.note !== 'string') fail()
 const seen = new Set()
 for (const row of value.candidates) { if (!uuid(row.id) || seen.has(row.id) || !Number.isSafeInteger(row.version) || row.version < 0 || !id(row.bytes)) fail(); seen.add(row.id) }
 return value
}
export function localProductChecks(draft) {
 const length = Array.from((draft.title || '').trim()).length
 const cents = /^\d{1,6}(\.\d{1,2})?$/.test(draft.priceText || '') ? Math.round(Number(draft.priceText) * 100) : 0
 return [
  {code:'TITLE',label:'标题为2～50个字符',ok:length >= 2 && length <= 50,required:true},
  {code:'PRICE',label:'价格格式正确且大于0',ok:cents > 0 && cents <= 99999999,required:true},
  {code:'DESCRIPTION',label:'描述使用情况与瑕疵（建议至少20字）',ok:Array.from((draft.description || '').trim()).length >= 20,required:false},
  {code:'IMAGES',label:'上传实物图片',ok:(draft.details?.imageIds?.length || 0) > 0,required:false},
  {code:'CAMPUS',label:'填写校区与成色',ok:!!draft.details && draft.details.campusCode !== 'UNSPECIFIED' && draft.details.conditionCode !== 'UNSPECIFIED',required:false},
  {code:'HANDOVER',label:'填写公共交接地点',ok:!!draft.details?.handoverLocation?.trim(),required:false}
 ]
}
export const displayBytes = value => `${(value / 1048576).toFixed(2)} MB`
export const validEmail = value => typeof value === 'string' && value.length <= 254 && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value)

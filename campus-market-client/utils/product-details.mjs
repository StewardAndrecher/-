export const campuses = Object.freeze([{ code: 'UNSPECIFIED', name: '暂未填写' }, { code: 'EAST', name: '东校区' }, { code: 'WEST', name: '西校区' }, { code: 'OTHER', name: '其他校区' }])
export const conditions = Object.freeze([{ code: 'UNSPECIFIED', name: '成色未填写' }, { code: 'NEW', name: '全新' }, { code: 'LIKE_NEW', name: '近全新' }, { code: 'GOOD', name: '九成新' }, { code: 'FAIR', name: '有使用痕迹' }])
export const imageIdPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
export const campusName = code => campuses.find(item => item.code === code)?.name || '暂未填写'
export const conditionName = code => conditions.find(item => item.code === code)?.name || '成色未填写'
export function legacyDetails(cover = '') { return { campusCode: 'UNSPECIFIED', conditionCode: 'UNSPECIFIED', handoverLocation: '', imageIds: cover ? [cover] : [] } }
export function productDetails(value, cover = '') {
  if (value == null) return legacyDetails(cover)
  if (!campuses.some(item => item.code === value.campusCode) || !conditions.some(item => item.code === value.conditionCode) ||
      typeof value.handoverLocation !== 'string' || Array.from(value.handoverLocation).length > 80 || /[\u0000-\u001f\u007f-\u009f\ud800-\udfff]/u.test(value.handoverLocation) ||
      !Array.isArray(value.imageIds) || value.imageIds.length > 6 || value.imageIds.some(id => typeof id !== 'string' || !imageIdPattern.test(id)) || new Set(value.imageIds).size !== value.imageIds.length ||
      (value.imageIds[0] || '') !== (cover || '')) throw new Error('商品校区、成色或图片资料格式不正确')
  return { campusCode: value.campusCode, conditionCode: value.conditionCode, handoverLocation: value.handoverLocation, imageIds: [...value.imageIds] }
}

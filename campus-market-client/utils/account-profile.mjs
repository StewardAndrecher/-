import { campuses,imageIdPattern } from './product-details.mjs'
export function profileInput(value) {
 if (!value || typeof value.avatarImageId !== 'string' || value.avatarImageId && !imageIdPattern.test(value.avatarImageId) || typeof value.bio !== 'string' || Array.from(value.bio).length > 160 || /[\u0000-\u001f\u007f-\u009f\ud800-\udfff]/u.test(value.bio) || !campuses.some(item => item.code === value.campusCode)) throw new Error('简介最多160个字符，请选择有效校区和头像')
 return { avatarImageId:value.avatarImageId,bio:value.bio.trim(),campusCode:value.campusCode }
}
export function validateAccountProfile(value, owner) {
 if (!value || value.userId !== owner.userId || !Number.isSafeInteger(value.version) || value.version < 0 || !(value.avatarImageId === null || typeof value.avatarImageId === 'string' && imageIdPattern.test(value.avatarImageId))) throw new Error('个人资料与当前账号不匹配')
 const normalized=profileInput({...value,avatarImageId:value.avatarImageId || ''})
 if (normalized.bio !== value.bio) throw new Error('个人简介格式不正确')
 return value
}
export function recoveryPassword(value) { return typeof value === 'string' && !!value.trim() && Array.from(value).length >= 8 && Array.from(value).length <= 64 && !/[\ud800-\udfff]/u.test(value) && utf8Length(value) <= 72 }
function utf8Length(value) { let size=0;for(const character of value){const cp=character.codePointAt(0);size+=cp<128?1:cp<2048?2:cp<65536?3:4}return size }

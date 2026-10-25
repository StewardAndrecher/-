import { isValidFavoriteCount } from './product-popularity.js'

export function productLookupIds(value) {
  if (!Array.isArray(value) || value.length < 1 || value.length > 20 ||
      Array.from(value).some(id => !Number.isSafeInteger(id) || id < 1) || new Set(value).size !== value.length)
    throw new Error('一次只能查询1至20个不重复的有效商品编号')
  return [...value]
}
const exact = (value, names) => value && typeof value === 'object' && !Array.isArray(value) &&
  Object.keys(value).length === names.length && names.every(name => Object.hasOwn(value, name))
const positive = value => Number.isSafeInteger(value) && value > 0
export function validateProductLookup(data, requested) {
  const ids = productLookupIds(requested)
  if (!exact(data, ['records']) || !Array.isArray(data.records) || data.records.length !== ids.length)
    throw new Error('批量商品响应格式不正确')
  for (let i = 0; i < ids.length; i++) {
    const row = data.records[i], p = row?.product
    if (!exact(row, ['productId', 'product']) || row.productId !== ids[i])
      throw new Error('批量商品编号或顺序不匹配')
    if (p === null) continue
    if (!exact(p, ['id','title','description','priceInCents','seller','status','coverImageId','coverImageUrl','version','category','favoriteCount']) ||
        p.id !== ids[i] || p.status !== 'ON_SALE' || typeof p.title !== 'string' || !p.title.trim() || p.title.length > 50 ||
        typeof p.description !== 'string' || p.description.length > 500 || !positive(p.priceInCents) || p.priceInCents > 99999999 ||
        !Number.isSafeInteger(p.version) || p.version < 0 || !isValidFavoriteCount(p.favoriteCount) ||
        (p.seller !== null && (!exact(p.seller, ['id','nickname']) || !positive(p.seller.id) || typeof p.seller.nickname !== 'string' || !p.seller.nickname.trim())) ||
        !exact(p.category, ['code','name']) || !['UNCLASSIFIED','BOOK','DIGITAL','DAILY','SPORT','OTHER'].includes(p.category.code) ||
        typeof p.category.name !== 'string' || !p.category.name.trim() ||
        (p.coverImageId !== null && typeof p.coverImageId !== 'string') ||
        (p.coverImageUrl !== null && typeof p.coverImageUrl !== 'string'))
      throw new Error('批量商品公开信息不正确')
  }
  return data.records
}

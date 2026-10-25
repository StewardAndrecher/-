import { request } from './request.js'
import { productLookupIds, validateProductLookup } from '@/utils/product-lookup.mjs'

export async function getPublicProducts(value) {
  const ids = productLookupIds(value)
  const data = await request({ path: '/api/products/lookup', method: 'POST', data: { productIds: ids } })
  return validateProductLookup(data, ids)
}

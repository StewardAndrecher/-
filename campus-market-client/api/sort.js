import { request } from './request.js'

export async function getProductSorts() {
  const items = await request({ path: '/api/product-sorts' })
  if (!Array.isArray(items) || !items.length || items.some(item =>
    !item || typeof item.code !== 'string' || !/^[A-Z_]{1,16}$/.test(item.code) ||
    typeof item.name !== 'string' || !item.name.trim()) ||
    new Set(items.map(item => item.code)).size !== items.length) {
    throw new Error('排序列表格式不正确')
  }
  return items
}

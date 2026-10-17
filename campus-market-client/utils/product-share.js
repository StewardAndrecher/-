export function parseProductId(value) {
  if (typeof value === 'number') return Number.isSafeInteger(value) && value > 0 ? value : 0
  if (typeof value !== 'string' || !/^[1-9][0-9]{0,15}$/.test(value)) return 0
  const id = Number(value)
  return Number.isSafeInteger(id) && id > 0 ? id : 0
}

export function detailPath(id) {
  const value = parseProductId(id)
  if (!value) throw new Error('商品编号格式不正确')
  return `/pages/product/detail?id=${value}`
}

export function canShareProduct(product) {
  return product && Number.isSafeInteger(product.id) && product.id > 0 &&
    product.status === 'ON_SALE' && typeof product.title === 'string' && !!product.title.trim()
}

export function webProductLink(id, origin, documentPath = '/') {
  const base = new URL(origin)
  if (!['http:', 'https:'].includes(base.protocol) || base.username || base.password ||
      base.pathname !== '/' || base.search || base.hash ||
      typeof documentPath !== 'string' || !/^\/(?!\/)[a-zA-Z0-9/._%-]*$/.test(documentPath)) {
    throw new Error('当前网页地址无法生成商品链接')
  }
  return `${new URL(documentPath, base.origin).href}#${detailPath(id)}`
}

export function wechatProductShare(product) {
  // 显式挑选公开字段，不复制商品对象、用户信息、会话或页面查询参数。
  if (!canShareProduct(product)) return {
    title: '校园集市', path: '/pages/index/index', imageUrl: '/static/logo.png'
  }
  return {
    title: `校园集市｜${product.title.trim()}`,
    path: detailPath(product.id),
    imageUrl: '/static/logo.png'
  }
}

export function webHashProductId(hash) {
  const match = typeof hash === 'string' && /^#\/pages\/product\/detail(?:\?(.*))?$/.exec(hash)
  if (!match) return null
  const values = new URLSearchParams(match[1] || '').getAll('id')
  return values.length === 1 ? parseProductId(values[0]) : 0
}

// Intention IDs and product IDs identify different records; keep their paths explicit.
export function parseIntentId(value) {
  if (typeof value === 'number') return Number.isSafeInteger(value) && value > 0 ? value : 0
  if (typeof value !== 'string' || !/^[1-9][0-9]{0,15}$/.test(value)) return 0
  const id = Number(value)
  return Number.isSafeInteger(id) && id > 0 ? id : 0
}
export function intentDetailPath(id) {
  const value = parseIntentId(id)
  if (!value) throw new Error('购买意向编号不正确')
  return `/pages/intent/detail?id=${value}`
}
export function webHashIntentId(hash) {
  const match = typeof hash === 'string' && /^#\/pages\/intent\/detail(?:\?(.*))?$/.exec(hash)
  if (!match) return null
  const values = new URLSearchParams(match[1] || '').getAll('id')
  return values.length === 1 ? parseIntentId(values[0]) : 0
}

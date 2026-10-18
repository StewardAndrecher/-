export function validOrderTime(value) {
  if (typeof value !== 'string') return false
  const m = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.\d{1,9})?)?$/.exec(value)
  if (!m) return false
  const [, y, month, day, hour, minute, second = '0'] = m
  const year = Number(y), leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0)
  const days = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
  return year >= 1000 && Number(month) >= 1 && Number(month) <= 12 && Number(day) >= 1 &&
    Number(day) <= days[Number(month) - 1] && Number(hour) <= 23 && Number(minute) <= 59 && Number(second) <= 59
}
export function orderId(value) {
  if (typeof value === 'string' && !/^[1-9][0-9]*$/.test(value)) throw new Error('订单编号不正确')
  const id = typeof value === 'string' ? Number(value) : value
  if (!Number.isSafeInteger(id) || id <= 0) throw new Error('订单编号不正确')
  return id
}
export function orderPath(id) { return `/pages/order/detail?id=${orderId(id)}` }
function comparableTime(value) {
  const [date, time] = value.split('T')
  const [h, m, seconds = '00'] = time.split(':')
  const [s, fraction = ''] = seconds.split('.')
  return `${date}T${h}:${m}:${s}.${fraction.padEnd(9, '0')}`
}
export function validOrder(item) {
  const person = p => p && Number.isSafeInteger(p.id) && p.id > 0 && typeof p.nickname === 'string' && p.nickname.trim()
  if (!item || !Number.isSafeInteger(item.id) || item.id <= 0 || !Number.isSafeInteger(item.productId) || item.productId <= 0 ||
    typeof item.productTitleSnapshot !== 'string' || item.productTitleSnapshot.length < 2 || item.productTitleSnapshot.length > 50 ||
    !Number.isSafeInteger(item.priceInCentsSnapshot) || item.priceInCentsSnapshot <= 0 || item.priceInCentsSnapshot > 99999999 ||
    !Number.isSafeInteger(item.productVersionSnapshot) || item.productVersionSnapshot < 0 || !person(item.buyer) || !person(item.seller) ||
    item.buyer.id === item.seller.id || !validOrderTime(item.createdAt) || !validOrderTime(item.paymentDueAt) ||
    comparableTime(item.paymentDueAt) <= comparableTime(item.createdAt)) return false
  const unpaid = item.paidAt === null && item.paymentKind === null
  const unfulfilled = item.deliveredAt === null && item.completedAt === null
  if (item.status === 'PENDING_PAYMENT') return unpaid && unfulfilled && item.cancelledAt === null && item.closedAt === null
  if (item.status === 'CANCELLED') return unpaid && unfulfilled && item.closedAt === null && validOrderTime(item.cancelledAt)
  if (item.status === 'CLOSED_TIMEOUT') return unpaid && unfulfilled && item.cancelledAt === null && validOrderTime(item.closedAt) && comparableTime(item.closedAt) >= comparableTime(item.paymentDueAt)
  const paid = item.paymentKind === 'SIMULATED' && item.cancelledAt === null && item.closedAt === null && validOrderTime(item.paidAt) &&
    comparableTime(item.paidAt) >= comparableTime(item.createdAt) && comparableTime(item.paidAt) < comparableTime(item.paymentDueAt)
  if (!paid) return false
  if (item.status === 'PAID') return unfulfilled
  const delivered = validOrderTime(item.deliveredAt) && comparableTime(item.deliveredAt) >= comparableTime(item.paidAt)
  if (item.status === 'DELIVERED') return delivered && item.completedAt === null
  if (item.status === 'COMPLETED') return delivered && validOrderTime(item.completedAt) && comparableTime(item.completedAt) >= comparableTime(item.deliveredAt)
  return false
}
export const orderStatus = status => ({
  PENDING_PAYMENT: '待付款', CANCELLED: '已取消', PAID: '已模拟付款（待交付）', CLOSED_TIMEOUT: '超时关闭',
  DELIVERED: '已交付，待收货（模拟交易）', COMPLETED: '交易完成（模拟交易）'
})[status] || '状态未知'
export function orderRequestId() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, value => {
    const n = Math.floor(Math.random() * 16); return (value === 'x' ? n : (n & 3) | 8).toString(16)
  })
}

export const orderFilters = [
  { value: 'ALL', label: '全部状态' },
  { value: 'PENDING_PAYMENT', label: '待付款' },
  { value: 'PAID', label: '待交付' },
  { value: 'DELIVERED', label: '待收货' },
  { value: 'COMPLETED', label: '交易完成' },
  { value: 'CANCELLED', label: '已取消' },
  { value: 'CLOSED_TIMEOUT', label: '超时关闭' }
]
const summaryKeys = { PENDING_PAYMENT: 'pendingPayment', PAID: 'paid', DELIVERED: 'delivered', COMPLETED: 'completed', CANCELLED: 'cancelled', CLOSED_TIMEOUT: 'closedTimeout' }
export function orderSummaryCount(summary, status) {
  return status === 'ALL' ? summary.total : summary[summaryKeys[status]]
}
export function validOrderSummary(summary) {
  if (!summary || !Number.isSafeInteger(summary.total) || summary.total < 0 || !Number.isSafeInteger(summary.completedAmountInCents) || summary.completedAmountInCents < 0) return false
  let sum = 0
  for (const key of Object.values(summaryKeys)) {
    const value = summary[key]
    if (!Number.isSafeInteger(value) || value < 0 || value > Number.MAX_SAFE_INTEGER - sum) return false
    sum += value
  }
  if (sum !== summary.total) return false
  const amount = summary.completedAmountInCents
  if (summary.completed === 0) return amount === 0
  return amount >= summary.completed && Math.floor(amount / 99999999) + (amount % 99999999 ? 1 : 0) <= summary.completed
}
export function formatOrderMoney(cents) {
  if (!Number.isSafeInteger(cents) || cents < 0) throw new Error('统计金额不正确')
  return `${Math.floor(cents / 100)}.${String(cents % 100).padStart(2, '0')}`
}

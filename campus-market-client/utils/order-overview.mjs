const statuses = ['pendingPayment', 'paid', 'delivered', 'completed', 'cancelled', 'closedTimeout']
const counters = ['total', ...statuses, 'completedAmountInCents', 'overduePendingPayment', 'oldestPendingPaymentSeconds']
const safe = value => Number.isSafeInteger(value) && value >= 0
export function validateOrderOverview(data) {
  if (!data || !counters.every(key => safe(data[key])) ||
      !safe(statuses.reduce((sum, key) => sum + data[key], 0)) ||
      statuses.reduce((sum, key) => sum + data[key], 0) !== data.total ||
      data.overduePendingPayment > data.pendingPayment ||
      (data.pendingPayment === 0 && data.oldestPendingPaymentSeconds !== 0) ||
      (data.overduePendingPayment > 0 && data.oldestPendingPaymentSeconds < 900) ||
      (data.completed === 0 && data.completedAmountInCents !== 0) ||
      (data.completed > 0 && (data.completedAmountInCents < data.completed ||
        (data.completed <= Math.floor(Number.MAX_SAFE_INTEGER / 99999999) &&
          data.completedAmountInCents > data.completed * 99999999))) ||
      typeof data.sampledAt !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d{1,9})?)?$/.test(data.sampledAt)) {
    throw new Error('交易运营概览响应无效，请刷新重试')
  }
  return data
}
export function formatOverviewMoney(cents) {
  if (!safe(cents)) throw new Error('交易金额无效')
  return `${Math.floor(cents / 100)}.${String(cents % 100).padStart(2, '0')}`
}
export function orderOverviewHint(data) {
  validateOrderOverview(data)
  if (data.overduePendingPayment > 0) return '存在已到付款期限但仍待付款的订单，请检查订单超时扫描、延迟队列与后端日志。查看本页不会关闭订单。'
  return '本次采样没有已到期的待付款订单；这不代表超时处理或消息队列健康检查通过。'
}

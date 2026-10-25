const counters = ['pending','unconfirmed','awaitingDelivery','retrying','leased','delivered','oldestPendingSeconds','maxPendingAttempts']
export function validateNotificationOverview(data) {
  if (!data || typeof data.deliveryEnabled !== 'boolean' ||
      !counters.every(key => Number.isSafeInteger(data[key]) && data[key] >= 0) ||
      data.unconfirmed + data.awaitingDelivery !== data.pending ||
      data.retrying > data.pending || data.leased > data.pending ||
      (data.pending === 0 && (data.oldestPendingSeconds !== 0 || data.maxPendingAttempts !== 0)) ||
      (data.retrying === 0 && data.maxPendingAttempts > 1) ||
      (data.retrying > 0 && data.maxPendingAttempts < 2) ||
      typeof data.sampledAt !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d{1,9})?)?$/.test(data.sampledAt)) {
    throw new Error('通知投递概览响应无效，请刷新重试')
  }
  return data
}
export function notificationOverviewHint(data) {
  validateNotificationOverview(data)
  if (!data.deliveryEnabled) return data.pending > 0 ? '通知投递已关闭，待投递任务将保留，开启后继续处理。' : '通知投递已关闭，目前没有待投递任务。'
  if (data.pending === 0) return '本次采样没有待投递任务；这不代表消息队列健康检查通过。'
  if (data.oldestPendingSeconds >= 60) return '存在等待超过60秒的任务，请检查后端日志、数据库连接及通知队列。'
  return '存在异步处理中任务，可稍后手动刷新核对。'
}

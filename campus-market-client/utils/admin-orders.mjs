import { createAdminLoader } from './admin-access.mjs'
import { formatOverviewMoney } from './order-overview.mjs'
export const adminOrderFilters = [
  {value:'ALL',label:'全部订单'}, {value:'PENDING_PAYMENT',label:'待付款'},
  {value:'PAID',label:'已模拟付款，待交付'}, {value:'DELIVERED',label:'已交付，待收货'},
  {value:'COMPLETED',label:'已完成'}, {value:'CANCELLED',label:'买家已取消'},
  {value:'CLOSED_TIMEOUT',label:'超时已关闭'}, {value:'OVERDUE_PENDING',label:'已到期仍待付款'}
]
const states = adminOrderFilters.slice(1,7).map(f=>f.value)
const safe = n => Number.isSafeInteger(n) && n >= 0
const positive = n => safe(n) && n > 0
const timestamp = s => typeof s === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d{1,9})?)?$/.test(s)
export function normalizeAdminOrderQuery(query = {}) {
  const status = query.status ?? 'ALL', orderId = query.orderId ?? '', page = query.page ?? 1, size = query.size ?? 10
  if (!adminOrderFilters.some(f=>f.value===status) || typeof orderId !== 'string' ||
      (orderId !== '' && (!/^[1-9]\d{0,15}$/.test(orderId) || !positive(Number(orderId)))) ||
      !positive(page) || page > 2147483647 || !positive(size) || size > 20) throw new Error('请输入有效订单编号、筛选或分页条件')
  return {status,orderId,page,size}
}
export function validateAdminOrderPage(data, query) {
  const q = normalizeAdminOrderQuery(query), exact = q.orderId === '' ? null : Number(q.orderId)
  if (!data || !Array.isArray(data.records) || !safe(data.total) || !positive(data.page) ||
      data.size !== q.size || data.status !== q.status || data.orderId !== exact ||
      !positive(data.totalPages) || data.totalPages !== Math.max(1,Math.ceil(data.total/q.size)) ||
      data.page !== Math.min(q.page,data.totalPages) || !timestamp(data.sampledAt) ||
      data.records.length !== Math.min(q.size, Math.max(0,data.total-(data.page-1)*q.size)) ||
      (exact !== null && data.total > 1)) throw new Error('管理员订单列表响应无效，请重新查询')
  const seen = new Set();let previous
  for (const row of data.records) {
    if (!row || !positive(row.id) || !positive(row.productId) || !positive(row.priceInCentsSnapshot) || row.priceInCentsSnapshot > 99999999 ||
        typeof row.productTitleSnapshot !== 'string' || !row.productTitleSnapshot.trim() || row.productTitleSnapshot.length > 50 ||
        !states.includes(row.status) || !timestamp(row.createdAt) || !timestamp(row.paymentDueAt) || row.paymentDueAt <= row.createdAt ||
        (exact !== null && row.id !== exact) || seen.has(row.id) ||
        (q.status === 'OVERDUE_PENDING' && (row.status !== 'PENDING_PAYMENT' || row.paymentDueAt > data.sampledAt)) ||
        (!['ALL','OVERDUE_PENDING'].includes(q.status) && row.status !== q.status) ||
        (previous && (row.createdAt > previous.createdAt || (row.createdAt === previous.createdAt && row.id >= previous.id)))) {
      throw new Error('管理员订单记录与查询条件不一致，请重新查询')
    }
    seen.add(row.id);previous=row
  }
  return data
}
export function adminOrderStatus(status) { return adminOrderFilters.find(f=>f.value===status)?.label || '未知状态' }
export const adminOrderMoney = formatOverviewMoney
export function createAdminOrderList({load, identity, notify}) {
  let requested = normalizeAdminOrderQuery()
  const loader = createAdminLoader(()=>load({...requested}),identity,data=>validateAdminOrderPage(data,requested),notify)
  return {
    refresh(query,active=true) { requested=normalizeAdminOrderQuery(query);return loader.refresh(active) },
    dispose() { loader.dispose() }
  }
}

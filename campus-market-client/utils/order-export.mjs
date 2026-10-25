import {orderSearchQuery,readOrderSearchRoute} from './order-search.mjs'
import {validOrderTime,orderStatus,formatOrderMoney} from './order.js'
export const ORDER_EXPORT_LIMIT=200
const exact=(v,keys)=>v&&Object.keys(v).sort().join(',')===keys.split(',').sort().join(',')
const positive=n=>Number.isSafeInteger(n)&&n>0
export function orderExportQuery(value={}) {
 const {role,status,orderId}=orderSearchQuery({role:value.role,status:value.status,orderId:value.orderId})
 return {role,status,orderId}
}
export function orderExportPath(value={}) {
 const q=orderSearchQuery(value)
 return `/pages/order/export?role=${q.role}&status=${q.status}&orderId=${q.orderId}&page=${q.page}`
}
export function readOrderExportRoute(options={}) {return readOrderSearchRoute(options)}
const timeKey=value=>{const [d,t]=value.split('T'),[h,m,seconds='0']=t.split(':'),[s,f='']=seconds.split('.');return `${d}T${h}:${m}:${s.padStart(2,'0')}.${f.padEnd(9,'0')}`}
export function validateOrderExport(data,value={}) {
 const q=orderExportQuery(value),filter=q.orderId===''?null:Number(q.orderId)
 if(!exact(data,'role,status,orderId,total,limit,records')||data.role!==q.role||data.status!==q.status||data.orderId!==filter||data.limit!==ORDER_EXPORT_LIMIT||!Number.isSafeInteger(data.total)||data.total<0||data.total>ORDER_EXPORT_LIMIT||!Array.isArray(data.records)||data.records.length!==data.total||(filter!==null&&data.total>1))throw new Error('订单导出响应与当前条件不匹配')
 let previous=null;const seen=new Set()
 for(const r of data.records){
  if(!exact(r,'id,productTitleSnapshot,priceInCentsSnapshot,status,createdAt')||!positive(r.id)||seen.has(r.id)||(filter!==null&&r.id!==filter)||typeof r.productTitleSnapshot!=='string'||Array.from(r.productTitleSnapshot).length<2||Array.from(r.productTitleSnapshot).length>50||/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f]/.test(r.productTitleSnapshot)||!positive(r.priceInCentsSnapshot)||r.priceInCentsSnapshot>99999999||orderStatus(r.status)==='状态未知'||(q.status!=='ALL'&&r.status!==q.status)||!validOrderTime(r.createdAt))throw new Error('订单导出记录格式不正确')
  const time=timeKey(r.createdAt)
  if(previous&&(previous.time<time||(previous.time===time&&previous.id<=r.id)))throw new Error('订单导出排序不正确')
  seen.add(r.id);previous={time,id:r.id}
 }
 return data
}
// Visible text prefixes keep user-controlled titles and 16-digit identifiers textual.
// This is a human-readable snapshot, not a round-trip machine-import format.
const cell=value=>'"'+String(value).replaceAll('"','""')+'"'
export function orderExportCsv(data,value={}) {
 validateOrderExport(data,value)
 const lines=[['订单编号（文本）','交易身份','商品标题（文本）','下单金额（元，模拟）','订单状态','下单时间']]
 for(const r of data.records)lines.push(['订单：'+r.id,data.role==='BUYER'?'买家':'卖家','商品：'+r.productTitleSnapshot,formatOrderMoney(r.priceInCentsSnapshot),orderStatus(r.status),r.createdAt.replace('T',' ')])
 return '\uFEFF'+lines.map(row=>row.map(cell).join(',')).join('\r\n')+'\r\n'
}
export function orderExportFilename(value={}) {
 const q=orderExportQuery(value)
 return `campus-orders-${q.role}-${q.status}-${q.orderId||'all'}.csv`
}

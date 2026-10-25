import {orderId,orderFilters,validOrder,validOrderSummary,orderSummaryCount} from './order.js'
const positive=n=>Number.isSafeInteger(n)&&n>0
const exact=(v,keys)=>v&&Object.keys(v).sort().join(',')===keys.split(',').sort().join(',')
export function orderSearchQuery(value={}) {
 const {role='BUYER',status='ALL',orderId:filter='',page=1}=value
 if(!['BUYER','SELLER'].includes(role)||!orderFilters.some(item=>item.value===status)||typeof filter!=='string'||(filter!==''&&(!/^[1-9][0-9]{0,15}$/.test(filter)||!positive(Number(filter))))||!positive(page)||page>1000000)throw new Error('订单检索条件不正确')
 return {role,status,orderId:filter,page}
}
export function readOrderSearchRoute(options={}) {
 const raw=options.page
 if(raw!==undefined&&(typeof raw!=='string'||! /^[1-9][0-9]{0,6}$/.test(raw)))throw new Error('订单返回页码不正确')
 return orderSearchQuery({...options,page:raw===undefined?1:Number(raw)})
}
export function orderSearchListPath(value={}) {
 const q=orderSearchQuery(value)
 return `/pages/order/list?role=${q.role}&status=${q.status}&orderId=${q.orderId}&page=${q.page}`
}
export function orderSearchDetailPath(id,value) {
 const q=orderSearchQuery(value),target=orderId(id)
 if(q.orderId!==''&&Number(q.orderId)!==target)throw new Error('详情与订单检索条件不匹配')
 return `/pages/order/detail?id=${target}&fromRole=${q.role}&fromStatus=${q.status}&fromOrderId=${q.orderId}&fromPage=${q.page}`
}
export function orderSearchBackPath(options={}) {
 if(['fromRole','fromStatus','fromOrderId','fromPage'].every(key=>options[key]===undefined))return null
 const q=readOrderSearchRoute({role:options.fromRole,status:options.fromStatus,orderId:options.fromOrderId,page:options.fromPage})
 if(q.orderId!==''&&Number(q.orderId)!==orderId(options.id))throw new Error('返回条件与详情订单不匹配')
 return orderSearchListPath(q)
}
export function validateOrderSearchPage(data,value) {
 const q=orderSearchQuery(value),expected=q.orderId===''?null:Number(q.orderId)
 if(!exact(data,'items,total,page,size,totalPages,role,status,orderId,summary')||data.role!==q.role||data.status!==q.status||data.orderId!==expected||data.size!==10
  ||!validOrderSummary(data.summary)||!Number.isSafeInteger(data.total)||data.total<0||data.total>orderSummaryCount(data.summary,q.status)
  ||(expected===null?data.total!==orderSummaryCount(data.summary,q.status):data.total>1)
  ||data.totalPages!==Math.max(1,Math.ceil(data.total/10))||data.page!==Math.min(q.page,data.totalPages)
  ||!Array.isArray(data.items)||data.items.length!==Math.min(10,Math.max(0,data.total-(data.page-1)*10))||!data.items.every(validOrder)
  ||new Set(data.items.map(item=>item.id)).size!==data.items.length||!data.items.every(item=>(q.status==='ALL'||item.status===q.status)&&(expected===null||item.id===expected)))throw new Error('订单列表与当前检索条件不匹配')
 return data
}

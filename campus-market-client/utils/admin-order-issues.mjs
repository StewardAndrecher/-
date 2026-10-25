import {issueTimeKey,issueAgeCutoff} from './issue-overview.mjs'
import {orderId,orderRequestId,validOrderTime} from './order.js'
import {issueReasons,normalizeOrderIssueInput,validateIssueResolution} from './order-issues.mjs'
const exact=(v,keys)=>v&&Object.keys(v).sort().join(',')===keys.split(',').sort().join(',')
const positive=n=>Number.isSafeInteger(n)&&n>0
const nonnegative=n=>Number.isSafeInteger(n)&&n>=0
const stages=['PAID','DELIVERED','COMPLETED']
export const issueDecisions=Object.freeze([{value:'ANSWERED',label:'答复处理'},{value:'DISMISSED',label:'不予支持'}])
export function adminIssueQuery(value={}) {
 const {status='OPEN',age='ALL',orderId:filterId='',page=1,size=10}=value
 if(!['OPEN','RESOLVED','ALL'].includes(status)||!['ALL','OVER_24H','OVER_72H'].includes(age)||(age!=='ALL'&&status!=='OPEN')||typeof filterId!=='string'||(filterId!==''&&(!/^[1-9][0-9]{0,15}$/.test(filterId)||!positive(Number(filterId))))||!positive(page)||page>1000000||!positive(size)||size>20)throw new Error('工单筛选参数不正确')
 return {status,age,orderId:filterId,page,size}
}
export function adminIssueListPath(status='OPEN',age='ALL',context={}) {
 const q=adminIssueQuery({status,age,orderId:context.orderId??'',page:context.page??1,size:context.size??10})
 return `/pages/admin/order-issues?status=${q.status}&age=${q.age}&orderId=${q.orderId}&page=${q.page}&size=${q.size}`
}
const routeNumber=(value,fallback)=>{if(value===undefined)return fallback;if(typeof value!=='string'||! /^[1-9][0-9]{0,6}$/.test(value))throw new Error('工单返回页码不正确');return Number(value)}
export function readAdminIssueListRoute(options={}) {
 return adminIssueQuery({status:options.status??'OPEN',age:options.age??'ALL',orderId:options.orderId??'',page:routeNumber(options.page,1),size:routeNumber(options.size,10)})
}
export function adminIssuePath(order,applicant,context) {
 const base=`/pages/admin/order-issue?orderId=${orderId(order)}&applicantId=${orderId(applicant)}`
 if(context===undefined)return base
 const q=adminIssueQuery(context)
 if(q.orderId!==''&&Number(q.orderId)!==orderId(order))throw new Error('工单与返回订单条件不匹配')
 return base+`&fromStatus=${q.status}&fromAge=${q.age}&fromOrderId=${q.orderId}&fromPage=${q.page}&fromSize=${q.size}`
}
export function adminIssueBackPath(options={}) {
 const q=readAdminIssueListRoute({status:options.fromStatus,age:options.fromAge,orderId:options.fromOrderId,page:options.fromPage,size:options.fromSize})
 if(q.orderId!==''&&options.orderId!==undefined&&Number(q.orderId)!==orderId(options.orderId))throw new Error('工单与返回订单条件不匹配')
 return adminIssueListPath(q.status,q.age,q)
}
export function validateAdminIssuePage(data,query) {
 const q=adminIssueQuery(query)
 if(!exact(data,'status,age,orderId,sampledAt,page,size,total,totalPages,items')||data.status!==q.status||data.age!==q.age||data.orderId!==(q.orderId===''?null:Number(q.orderId))||(q.orderId!==''&&data.total>2)||!validOrderTime(data.sampledAt)||data.size!==q.size||!nonnegative(data.total)||data.totalPages!==Math.max(1,Math.ceil(data.total/q.size))||data.page!==Math.min(q.page,data.totalPages)||!Array.isArray(data.items)||data.items.length!==Math.min(q.size,Math.max(0,data.total-(data.page-1)*q.size)))throw new Error('工单列表与当前筛选不匹配')
 const cutoff=q.age==='ALL'?null:issueAgeCutoff(data.sampledAt,q.age==='OVER_24H'?24:72)
 let previous=null;const seen=new Set()
 for(const item of data.items) {
  if(!exact(item,'orderId,applicantId,applicantRole,reason,createdAt,status')||!positive(item.orderId)||(q.orderId!==''&&item.orderId!==Number(q.orderId))||!positive(item.applicantId)||!['BUYER','SELLER'].includes(item.applicantRole)||!issueReasons.some(r=>r.value===item.reason)||!validOrderTime(item.createdAt)||!['OPEN','RESOLVED'].includes(item.status)||(q.status!=='ALL'&&item.status!==q.status)||issueTimeKey(item.createdAt)>issueTimeKey(data.sampledAt)||(cutoff!==null&&issueTimeKey(item.createdAt)>cutoff))throw new Error('工单列表记录无效')
  const key=`${item.orderId}:${item.applicantId}`;if(seen.has(key))throw new Error('工单列表重复')
  if(previous){
   const a=issueTimeKey(item.createdAt),b=issueTimeKey(previous.createdAt),cmp=a===b?(item.orderId===previous.orderId?item.applicantId-previous.applicantId:item.orderId-previous.orderId):(a>b?1:-1)
   if(q.age==='ALL'?cmp>=0:cmp<=0)throw new Error('工单列表顺序不正确')
  }
  seen.add(key);previous=item
 }
 return data
}
export function validateAdminIssueDetail(data,order,applicant) {
 if(!exact(data,'orderId,applicantId,applicantRole,orderStatus,productTitleSnapshot,priceInCentsSnapshot,reason,description,createdAt,status,resolution')||data.orderId!==orderId(order)||data.applicantId!==orderId(applicant)||!['BUYER','SELLER'].includes(data.applicantRole)||!stages.includes(data.orderStatus)||typeof data.productTitleSnapshot!=='string'||data.productTitleSnapshot.length<2||data.productTitleSnapshot.length>50||!positive(data.priceInCentsSnapshot)||data.priceInCentsSnapshot>99999999||!validOrderTime(data.createdAt)||!['OPEN','RESOLVED'].includes(data.status)||normalizeOrderIssueInput(data).description!==data.description)throw new Error('反馈工单与当前目标不匹配')
 validateIssueResolution(data.resolution,data.createdAt)
 if((data.status==='OPEN')!==(data.resolution===null))throw new Error('工单状态与处理结果不一致')
 return data
}
export function normalizeAdminIssueResolution(input) {
 if(!issueDecisions.some(item=>item.value===input?.decision)||typeof input?.requestId!=='string'||! /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(input.requestId))throw new Error('处理结果或请求编号无效')
 return {decision:input.decision,explanation:normalizeOrderIssueInput({reason:'OTHER',description:input.explanation}).description,requestId:input.requestId}
}
export function validateAdminIssueReceipt(data,before,payload) {
 if(!exact(data,'detail,replayed')||typeof data.replayed!=='boolean')throw new Error('处理回执无效')
 const item=validateAdminIssueDetail(data.detail,before.orderId,before.applicantId)
 for(const field of ['applicantRole','productTitleSnapshot','priceInCentsSnapshot','reason','description','createdAt'])if(item[field]!==before[field])throw new Error('处理回执与原反馈不一致')
 if(stages.indexOf(item.orderStatus)<stages.indexOf(before.orderStatus)||item.status!=='RESOLVED'||item.resolution?.decision!==payload.decision||item.resolution?.explanation!==payload.explanation)throw new Error('处理回执与本次提交不一致')
 return data
}
export function createAdminIssueManager({identity,load,resolve,confirm,newKey=orderRequestId,notify=()=>{}}) {
 const empty=()=>({detail:null,loading:false,saving:false,error:'',status:0,result:null,uncertain:false})
 let state=empty(),generation=0,disposed=false,scope=null
 const emit=()=>notify({...state})
 const matches=who=>{const now=identity();return ['orderId','applicantId','userId','token'].every(k=>now?.[k]===who[k])}
 const valid=who=>positive(who.orderId)&&positive(who.applicantId)&&positive(who.userId)&&typeof who.token==='string'&&!!who.token
 const alive=(ticket,who)=>!disposed&&ticket===generation&&matches(who)
 async function refresh() {
  if(disposed||state.saving)return
  const ticket=++generation,who={...identity()};state=empty();scope=null;emit();if(!valid(who))return
  state.loading=true;emit()
  try{const data=await load(who.orderId,who.applicantId);if(alive(ticket,who)){state={...state,detail:validateAdminIssueDetail(data,who.orderId,who.applicantId),status:200};scope=who}}
  catch(error){if(alive(ticket,who))state={...state,error:error?.message||'读取工单失败',status:error?.statusCode||0}}
  finally{if(!disposed&&ticket===generation){state=matches(who)?{...state,loading:false}:empty();emit()}}
 }
 async function submit(input) {
  if(disposed||state.loading||state.saving||state.detail?.status!=='OPEN'||!scope||!matches(scope))return false
  const who={...identity()},before=state.detail;if(!valid(who)||before.orderId!==who.orderId||before.applicantId!==who.applicantId)return false
  let payload;try{payload=normalizeAdminIssueResolution({...input,requestId:newKey()})}catch(error){state.error=error.message;emit();return false}
  const ticket=++generation;let sent=false;state={...state,saving:true,error:'',status:0,result:null,uncertain:false};emit()
  try {
   if(!await confirm(payload,before)||!alive(ticket,who))return false
   sent=true;const data=await resolve(who.orderId,who.applicantId,payload);if(!alive(ticket,who))return false
   const result=validateAdminIssueReceipt(data,before,payload);state={...state,detail:result.detail,result,status:200};return true
  }catch(error){if(alive(ticket,who))state={...state,detail:sent?null:state.detail,error:sent?(error?.message||'处理结果暂不确定')+'；请先重新加载核对，不会自动重复提交':error?.message||'无法显示确认窗口',status:error?.statusCode||0,uncertain:sent&&(!Number.isInteger(error?.statusCode)||error.statusCode===0||error.statusCode>=500)};return false}
  finally{if(!disposed&&ticket===generation){state=matches(who)?{...state,saving:false}:empty();emit()}}
 }
 return {refresh,submit,reset(){generation++;scope=null;state=empty();emit()},dispose(){disposed=true;generation++;scope=null;state=empty();emit()}}
}

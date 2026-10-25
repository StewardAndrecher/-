import { orderId, validOrderTime } from './order.js'

const roles=['BUYER','SELLER'],statuses=['PENDING_PAYMENT','PAID','DELIVERED','COMPLETED','CANCELLED','CLOSED_TIMEOUT']
export function reviewPath(id){return `/pages/order/reviews?id=${orderId(id)}`}
export function normalizeReviewInput(input) {
  if(!Number.isInteger(input?.rating)||input.rating<1||input.rating>5)throw new Error('请选择1至5分')
  if(input.content!=null&&typeof input.content!=='string')throw new Error('评价内容格式不正确')
  const content=(input.content||'').replace(/\r\n?/g,'\n').trim()
  if(Array.from(content).length>500||Array.from(content).some(c=>{const n=c.codePointAt(0);return(n<32&&n!==9&&n!==10)||(n>=127&&n<=159)||(n>=0xd800&&n<=0xdfff)}))throw new Error('评价最多500个字符，不能包含无效或控制字符')
  return {rating:input.rating,content}
}
export function validateReviewSummary(data,id,userId) {
  if(!data||Object.keys(data).sort().join(',')!=='canReview,orderId,orderStatus,reviews,userId,viewerRole'||data.orderId!==orderId(id)||data.userId!==userId||!Number.isSafeInteger(userId)||userId<1||!statuses.includes(data.orderStatus)||!roles.includes(data.viewerRole)||typeof data.canReview!=='boolean'||!Array.isArray(data.reviews)||data.reviews.length>2)throw new Error('评价响应与当前订单或账号不匹配')
  const seen=new Set()
  for(const item of data.reviews) {
    if(!item||Object.keys(item).sort().join(',')!=='authorRole,content,createdAt,mine,rating'||!roles.includes(item.authorRole)||seen.has(item.authorRole)||typeof item.mine!=='boolean'||item.mine!==(item.authorRole===data.viewerRole)||!validOrderTime(item.createdAt)||typeof item.content!=='string'||normalizeReviewInput(item).content!==item.content)throw new Error('评价记录格式不正确')
    seen.add(item.authorRole)
  }
  if(data.canReview!==(data.orderStatus==='COMPLETED'&&!seen.has(data.viewerRole))||(data.orderStatus!=='COMPLETED'&&data.reviews.length))throw new Error('评价资格与订单状态不一致')
  return data
}
export function validateReviewReceipt(data,id,userId,payload) {
  if(!data||Object.keys(data).sort().join(',')!=='replayed,summary'||typeof data.replayed!=='boolean')throw new Error('评价提交回执无效')
  const summary=validateReviewSummary(data.summary,id,userId),mine=summary.reviews.find(r=>r.mine)
  if(!mine||mine.rating!==payload.rating||mine.content!==payload.content||summary.canReview)throw new Error('评价回执与本次内容不一致')
  return data
}
export function createReviewManager({identity,load,create,confirm,notify=()=>{}}) {
  const empty=()=>({summary:null,loading:false,saving:false,error:'',result:null,uncertain:false})
  let state=empty(),generation=0,disposed=false
  const emit=()=>notify({...state})
  const matches=who=>{const actual=identity();return actual?.orderId===who.orderId&&actual?.userId===who.userId&&actual?.token===who.token}
  const valid=who=>Number.isSafeInteger(who.orderId)&&who.orderId>0&&Number.isSafeInteger(who.userId)&&who.userId>0&&!!who.token
  const alive=(n,who)=>!disposed&&n===generation&&matches(who)
  async function refresh() {
    if(disposed||state.saving)return
    const who={...identity()},n=++generation;state=empty();emit()
    if(!valid(who))return
    state={...state,loading:true};emit()
    try{const data=await load(who.orderId);if(alive(n,who))state={...state,summary:validateReviewSummary(data,who.orderId,who.userId)}}
    catch(e){if(alive(n,who))state={...state,error:e?.message||'暂时无法读取评价'}}
    finally{if(!disposed&&n===generation){state=matches(who)?{...state,loading:false}:empty();emit()}}
  }
  async function submit(input) {
    if(disposed||state.loading||state.saving||!state.summary?.canReview)return false
    const who={...identity()};if(!valid(who)||state.summary.orderId!==who.orderId||state.summary.userId!==who.userId)return false
    let payload
    try{payload=normalizeReviewInput(input)}catch(e){state={...state,error:e.message};emit();return false}
    const n=++generation;let sent=false;state={...state,saving:true,error:'',result:null,uncertain:false};emit()
    try{
      if(!await confirm(payload.rating)||!alive(n,who))return false
      sent=true;const data=await create(who.orderId,payload)
      if(!alive(n,who))return false
      const result=validateReviewReceipt(data,who.orderId,who.userId,payload)
      state={...state,summary:result.summary,result};return true
    }catch(e){if(alive(n,who))state={...state,summary:sent?null:state.summary,error:sent?(e?.message||'提交结果暂不确定')+'；请先重新加载评价确认，不会自动重复提交':e?.message||'无法显示确认窗口',uncertain:sent&&(!Number.isInteger(e?.statusCode)||e.statusCode===0||e.statusCode>=500)};return false}
    finally{if(!disposed&&n===generation){state=matches(who)?{...state,saving:false}:empty();emit()}}
  }
  return {refresh,submit,reset(){generation++;state=empty();emit()},dispose(){disposed=true;generation++;state=empty();emit()}}
}

export function reviewSubmissionMessage(receipt) {
  if(typeof receipt?.replayed!=='boolean')throw new Error('评价回执无效')
  return receipt.replayed?'此前已提交相同评价，保留首次记录；不会重复提醒对方。':'评价已保存，已安排站内提醒对方。'
}

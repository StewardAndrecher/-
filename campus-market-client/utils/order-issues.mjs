import {orderId,validOrderTime} from './order.js'
export const issueReasons=Object.freeze([{value:'ITEM_CONDITION',label:'商品状况问题'},{value:'HANDOVER',label:'交接或履约问题'},{value:'OTHER',label:'其他交易问题'}])
const eligible=['PAID','DELIVERED','COMPLETED'],statuses=['PENDING_PAYMENT','PAID','DELIVERED','COMPLETED','CANCELLED','CLOSED_TIMEOUT']
const exact=(value,keys)=>value&&Object.keys(value).sort().join(',')===keys.split(',').sort().join(',')
export function orderIssuePath(id){return `/pages/order/issue?id=${orderId(id)}`}
export function normalizeOrderIssueInput(input) {
 if(!issueReasons.some(item=>item.value===input?.reason))throw new Error('请选择有效的问题类型')
 if(typeof input?.description!=='string')throw new Error('请填写10至1000个字符的问题说明')
 const description=input.description.replace(/\r\n?/g,'\n').trim(),chars=Array.from(description)
 if(chars.length<10||chars.length>1000||chars.some(c=>{const n=c.codePointAt(0);return(n<32&&n!==9&&n!==10)||(n>=127&&n<=159)||(n>=0xd800&&n<=0xdfff)}))throw new Error('问题说明须为10至1000个字符，不能包含无效或控制字符')
 return {reason:input.reason,description}
}
export function validateOrderIssueSummary(data,id,userId) {
 if(!exact(data,'orderId,userId,orderStatus,viewerRole,canSubmit,issue')||data.orderId!==orderId(id)||!Number.isSafeInteger(userId)||userId<1||data.userId!==userId||!statuses.includes(data.orderStatus)||!['BUYER','SELLER'].includes(data.viewerRole)||typeof data.canSubmit!=='boolean')throw new Error('交易问题记录与当前订单或账号不匹配')
 if(data.issue!==null) {
  if(!exact(data.issue,'reason,description,createdAt,resolution')||!validOrderTime(data.issue.createdAt)||!eligible.includes(data.orderStatus)||normalizeOrderIssueInput(data.issue).description!==data.issue.description)throw new Error('交易问题记录格式不正确')
  validateIssueResolution(data.issue.resolution,data.issue.createdAt)
 }
 if(data.canSubmit!==(eligible.includes(data.orderStatus)&&data.issue===null))throw new Error('交易问题提交资格不一致')
 return data
}
export function validateOrderIssueReceipt(data,id,userId,payload) {
 if(!exact(data,'summary,replayed')||typeof data.replayed!=='boolean')throw new Error('交易问题提交回执无效')
 const summary=validateOrderIssueSummary(data.summary,id,userId)
 if(!summary.issue||summary.canSubmit||summary.issue.reason!==payload.reason||summary.issue.description!==payload.description)throw new Error('反馈回执与本次内容不一致')
 return data
}
export function createOrderIssueManager({identity,load,create,confirm,notify=()=>{}}) {
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
    try{const data=await load(who.orderId);if(alive(n,who))state={...state,summary:validateOrderIssueSummary(data,who.orderId,who.userId)}}
    catch(e){if(alive(n,who))state={...state,error:e?.message||'暂时无法读取交易问题反馈'}}
    finally{if(!disposed&&n===generation){state=matches(who)?{...state,loading:false}:empty();emit()}}
  }
  async function submit(input) {
    if(disposed||state.loading||state.saving||!state.summary?.canSubmit)return false
    const who={...identity()};if(!valid(who)||state.summary.orderId!==who.orderId||state.summary.userId!==who.userId)return false
    let payload
    try{payload=normalizeOrderIssueInput(input)}catch(e){state={...state,error:e.message};emit();return false}
    const n=++generation;let sent=false;state={...state,saving:true,error:'',result:null,uncertain:false};emit()
    try{
      if(!await confirm(payload.reason)||!alive(n,who))return false
      sent=true;const data=await create(who.orderId,payload)
      if(!alive(n,who))return false
      const result=validateOrderIssueReceipt(data,who.orderId,who.userId,payload)
      state={...state,summary:result.summary,result};return true
    }catch(e){if(alive(n,who))state={...state,summary:sent?null:state.summary,error:sent?(e?.message||'提交结果暂不确定')+'；请先重新加载交易问题反馈确认，不会自动重复提交':e?.message||'无法显示确认窗口',uncertain:sent&&(!Number.isInteger(e?.statusCode)||e.statusCode===0||e.statusCode>=500)};return false}
    finally{if(!disposed&&n===generation){state=matches(who)?{...state,saving:false}:empty();emit()}}
  }
  return {refresh,submit,reset(){generation++;state=empty();emit()},dispose(){disposed=true;generation++;state=empty();emit()}}
}

export function issueSubmissionMessage(receipt) {
 if(typeof receipt?.replayed!=='boolean')throw new Error('反馈回执无效')
 return receipt.replayed?'此前已提交相同反馈，保留首次记录。':'交易问题反馈已保存，可在此查看首次记录。'
}

export function issueDecisionLabel(decision){return {ANSWERED:'已答复',DISMISSED:'不予支持'}[decision]||'结果未知'}
const timeKey=value=>{const [date,time]=value.split('T'),[h,m,seconds='00']=time.split(':'),[s,f='']=seconds.split('.');return `${date}T${h}:${m}:${s}.${f.padEnd(9,'0')}`}
export function validateIssueResolution(value,submittedAt) {
 if(value===null)return null
 if(!exact(value,'decision,explanation,createdAt')||!['ANSWERED','DISMISSED'].includes(value.decision)||typeof value.explanation!=='string'||normalizeOrderIssueInput({reason:'OTHER',description:value.explanation}).description!==value.explanation||!validOrderTime(value.createdAt)||!validOrderTime(submittedAt)||timeKey(value.createdAt)<timeKey(submittedAt))throw new Error('反馈处理结果格式不正确')
 return value
}

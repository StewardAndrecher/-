import {validOrderTime} from './order.js'
import {orderIssuePath} from './order-issues.mjs'

const positive=n=>Number.isSafeInteger(n)&&n>0
const nonnegative=n=>Number.isSafeInteger(n)&&n>=0
const exact=(value,keys)=>value&&Object.keys(value).sort().join(',')===keys.split(',').sort().join(',')
const timeKey=value=>{const [date,time]=value.split('T'),[h,m,seconds='00']=time.split(':'),[s,f='']=seconds.split('.');return `${date}T${h}:${m}:${s}.${f.padEnd(9,'0')}`}
export function issueListQuery(query={}) {
  const {role='BUYER',status='ALL',page=1,size=10}=query
  if(!['BUYER','SELLER'].includes(role)||!['ALL','OPEN','RESOLVED'].includes(status)||!positive(page)||page>1000000||!positive(size)||size>20)throw new Error('交易反馈清单参数不正确')
  return {role,status,page,size}
}
export function issueListPath(role='BUYER') {
  return `/pages/order/issues?role=${issueListQuery({role}).role}`
}
export function validateIssueListPage(data,query,userId) {
  const q=issueListQuery(query),s=data?.summary
  if(!positive(userId)||!exact(data,'userId,role,status,page,size,total,totalPages,summary,items')||data.userId!==userId||data.role!==q.role||data.status!==q.status||data.size!==q.size
    ||!exact(s,'total,open,resolved')||![s.total,s.open,s.resolved].every(nonnegative)||s.open>Number.MAX_SAFE_INTEGER-s.resolved||s.open+s.resolved!==s.total
    ||data.total!==(q.status==='ALL'?s.total:q.status==='OPEN'?s.open:s.resolved)||data.totalPages!==Math.max(1,Math.ceil(data.total/q.size))||data.page!==Math.min(q.page,data.totalPages)
    ||!Array.isArray(data.items)||data.items.length!==Math.min(q.size,Math.max(0,data.total-(data.page-1)*q.size)))throw new Error('交易反馈清单与当前账号或筛选不匹配')
  let previous=null;const ids=new Set()
  for(const item of data.items) {
    if(!exact(item,'orderId,productTitleSnapshot,reason,createdAt,resolutionDecision,resolvedAt')||!positive(item.orderId)||ids.has(item.orderId)
      ||typeof item.productTitleSnapshot!=='string'||item.productTitleSnapshot.length<2||item.productTitleSnapshot.length>50
      ||!['ITEM_CONDITION','HANDOVER','OTHER'].includes(item.reason)||!validOrderTime(item.createdAt)
      ||(item.resolutionDecision===null?item.resolvedAt!==null:(!['ANSWERED','DISMISSED'].includes(item.resolutionDecision)||!validOrderTime(item.resolvedAt)))
      ||(q.status==='OPEN'&&item.resolutionDecision!==null)||(q.status==='RESOLVED'&&item.resolutionDecision===null)
      ||(item.resolvedAt!==null&&timeKey(item.resolvedAt)<timeKey(item.createdAt)))throw new Error('交易反馈清单记录格式不正确')
    if(previous&&(timeKey(item.createdAt)>timeKey(previous.createdAt)||(timeKey(item.createdAt)===timeKey(previous.createdAt)&&item.orderId>=previous.orderId)))throw new Error('交易反馈清单顺序不正确')
    ids.add(item.orderId);previous=item
  }
  const openCount=data.items.filter(item=>item.resolutionDecision===null).length
  if(openCount>s.open||data.items.length-openCount>s.resolved)throw new Error('反馈清单行数与汇总不匹配')
  return data
}
export function createIssueListManager({identity,load,navigate,notify=()=>{}}) {
  const empty=()=>({page:null,loading:false,openingId:null,error:''})
  let state=empty(),generation=0,disposed=false,scope=null
  const emit=()=>notify({...state})
  const valid=who=>positive(who?.userId)&&typeof who?.token==='string'&&!!who.token
  const matches=who=>{const now=identity();return now?.userId===who?.userId&&now?.token===who?.token}
  async function refresh(query={}) {
    if(disposed)return
    const ticket=++generation,who={...identity()};state=empty();scope=who;emit()
    if(!valid(who)){state.error='请先登录后查看交易反馈清单';emit();return}
    state.loading=true;emit()
    try {
      const q=issueListQuery(query),result=await load(q)
      if(disposed||ticket!==generation||!matches(who))return
      state.page=validateIssueListPage(result,q,who.userId)
    } catch(error) {
      if(!disposed&&ticket===generation&&matches(who))state={...empty(),loading:true,error:error?.message||'加载交易反馈清单失败'}
    } finally {
      if(!disposed&&ticket===generation){state=matches(who)?{...state,loading:false}:empty();emit()}
    }
  }
  async function open(id) {
    if(disposed||state.loading||state.openingId!==null||!valid(scope)||!matches(scope)||!state.page?.items.some(item=>item.orderId===id))return false
    const ticket=generation,who={...scope};state.openingId=id;emit()
    try{await navigate(orderIssuePath(id));return !disposed&&ticket===generation&&matches(who)}catch(error){if(!disposed&&ticket===generation&&matches(who)){state.error='打开交易反馈失败';emit()}return false}
    finally{if(!disposed&&ticket===generation){state.openingId=null;emit()}}
  }
  return {refresh,open,reset(){generation++;state=empty();scope=null;emit()},dispose(){disposed=true;generation++;state=empty();scope=null;emit()}}
}

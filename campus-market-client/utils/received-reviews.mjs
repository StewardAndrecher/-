import {validOrderTime} from './order.js'
import {reviewPath,normalizeReviewInput} from './order-reviews.mjs'
const positive=n=>Number.isSafeInteger(n)&&n>0
const nonnegative=n=>Number.isSafeInteger(n)&&n>=0
const exact=(value,keys)=>value!==null&&typeof value==='object'&&!Array.isArray(value)&&Object.keys(value).sort().join(',')===keys.split(',').sort().join(',')
const timeKey=value=>{const [date,time]=value.split('T'),[h,m,seconds='00']=time.split(':'),[s,f='']=seconds.split('.');return `${date}T${h}:${m}:${s}.${f.padEnd(9,'0')}`}
export function receivedReviewQuery(query={}) {
 if(!query||typeof query!=='object'||Array.isArray(query)||Object.keys(query).some(k=>!['role','rating','page','size'].includes(k)))throw new Error('收到评价的筛选参数不正确')
 const {role='BUYER',rating='ALL',page=1,size=10}=query
 if(!['BUYER','SELLER'].includes(role)||!['ALL','GOOD','NEUTRAL','LOW'].includes(rating)||!positive(page)||page>1000000||!positive(size)||size>20)throw new Error('收到评价的筛选参数不正确')
 return {role,rating,page,size}
}
export function receivedReviewPath(role='BUYER') {return `/pages/order/received-reviews?role=${receivedReviewQuery({role}).role}`}
export function validateReceivedReviewPage(data,query,userId) {
 const q=receivedReviewQuery(query),s=data?.summary
 if(!positive(userId)||!exact(data,'userId,role,rating,page,size,total,totalPages,summary,items,sampledAt')||data.userId!==userId||data.role!==q.role||data.rating!==q.rating||data.size!==q.size
  ||!exact(s,'total,good,neutral,low')||![s.total,s.good,s.neutral,s.low].every(nonnegative)||s.good>Number.MAX_SAFE_INTEGER-s.neutral||s.good+s.neutral>Number.MAX_SAFE_INTEGER-s.low||s.good+s.neutral+s.low!==s.total
  ||data.total!==(q.rating==='ALL'?s.total:s[q.rating.toLowerCase()])||data.totalPages!==Math.max(1,Math.ceil(data.total/q.size))||data.page!==Math.min(q.page,data.totalPages)||!validOrderTime(data.sampledAt)
  ||!Array.isArray(data.items)||data.items.length!==Math.min(q.size,Math.max(0,data.total-(data.page-1)*q.size)))throw new Error('收到评价的响应与当前账号或筛选不匹配')
 let previous=null;const ids=new Set()
 for(const item of data.items) {
  if(!exact(item,'orderId,productTitleSnapshot,rating,content,completedAt,reviewedAt')||!positive(item.orderId)||ids.has(item.orderId)
   ||typeof item.productTitleSnapshot!=='string'||item.productTitleSnapshot.length<2||item.productTitleSnapshot.length>50
   ||typeof item.content!=='string'||normalizeReviewInput(item).content!==item.content||!validOrderTime(item.completedAt)||!validOrderTime(item.reviewedAt)
   ||timeKey(item.reviewedAt)<timeKey(item.completedAt)||timeKey(item.reviewedAt)>timeKey(data.sampledAt)
   ||(q.rating==='GOOD'&&item.rating<4)||(q.rating==='NEUTRAL'&&item.rating!==3)||(q.rating==='LOW'&&item.rating>2))throw new Error('收到评价的记录格式不正确')
  if(previous&&(timeKey(item.reviewedAt)>timeKey(previous.reviewedAt)||(timeKey(item.reviewedAt)===timeKey(previous.reviewedAt)&&item.orderId>=previous.orderId)))throw new Error('收到评价的顺序不正确')
  ids.add(item.orderId);previous=item
 }
 return data
}
export function createReceivedReviewManager({identity,load,navigate,notify=()=>{}}) {
  const empty=()=>({page:null,loading:false,openingId:null,error:''})
  let state=empty(),generation=0,disposed=false,scope=null
  const emit=()=>notify({...state})
  const valid=who=>positive(who?.userId)&&typeof who?.token==='string'&&!!who.token
  const matches=who=>{const now=identity();return now?.userId===who?.userId&&now?.token===who?.token}
  async function refresh(query={}) {
    if(disposed)return
    const ticket=++generation,who={...identity()};state=empty();scope=who;emit()
    if(!valid(who)){state.error='请先登录后查看收到的评价';emit();return}
    state.loading=true;emit()
    try {
      const q=receivedReviewQuery(query),result=await load(q)
      if(disposed||ticket!==generation||!matches(who))return
      state.page=validateReceivedReviewPage(result,q,who.userId)
    } catch(error) {
      if(!disposed&&ticket===generation&&matches(who))state={...empty(),loading:true,error:error?.message||'加载收到的评价失败'}
    } finally {
      if(!disposed&&ticket===generation){state=matches(who)?{...state,loading:false}:empty();emit()}
    }
  }
  async function open(id) {
    if(disposed||state.loading||state.openingId!==null||!valid(scope)||!matches(scope)||!state.page?.items.some(item=>item.orderId===id))return false
    const ticket=generation,who={...scope};state.openingId=id;emit()
    try{await navigate(reviewPath(id));return !disposed&&ticket===generation&&matches(who)}catch(error){if(!disposed&&ticket===generation&&matches(who)){state.error='打开交易评价失败';emit()}return false}
    finally{if(!disposed&&ticket===generation){state.openingId=null;emit()}}
  }
  return {refresh,open,reset(){generation++;state=empty();scope=null;emit()},dispose(){disposed=true;generation++;state=empty();scope=null;emit()}}
}

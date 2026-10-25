import {validOrderTime,orderFilters} from './order.js'
import {reviewTaskPath} from './order-review-tasks.mjs'
const positive=n=>Number.isSafeInteger(n)&&n>0
const safe=n=>Number.isSafeInteger(n)&&n>=0
const exact=(v,keys)=>v&&Object.keys(v).sort().join(',')===keys.split(',').sort().join(',')
export function readOrderListRoute(options={}) {
 const {role='BUYER',status='ALL'}=options
 if(!['BUYER','SELLER'].includes(role)||!orderFilters.some(item=>item.value===status))throw new Error('订单列表筛选参数不正确')
 return {role,status}
}
export function filteredOrderPath(role,status) {
 const q=readOrderListRoute({role,status})
 return `/pages/order/list?role=${q.role}&status=${q.status}`
}
export function validateTradeTasks(data,userId) {
 const b=data?.buyer,s=data?.seller
 if(!positive(userId)||!exact(data,'userId,sampledAt,buyer,seller')||data.userId!==userId||!validOrderTime(data.sampledAt)
  ||!exact(b,'pendingPayment,payable,overduePendingPayment,toConfirm,toReview')||!exact(s,'toDeliver,toReview')
  ||![...Object.values(b),...Object.values(s)].every(safe)||b.payable>b.pendingPayment||b.overduePendingPayment!==b.pendingPayment-b.payable)throw new Error('交易待办与当前账号不匹配，请刷新重试')
 return data
}
const taskKeys=['BUYER_PAYMENT','BUYER_CONFIRM','BUYER_REVIEW','SELLER_DELIVER','SELLER_REVIEW']
export function tradeTaskTarget(data,userId,key) {
 validateTradeTasks(data,userId)
 if(!taskKeys.includes(key))throw new Error('交易待办入口不正确')
 return {
  BUYER_PAYMENT:{count:data.buyer.pendingPayment,path:filteredOrderPath('BUYER','PENDING_PAYMENT')},
  BUYER_CONFIRM:{count:data.buyer.toConfirm,path:filteredOrderPath('BUYER','DELIVERED')},
  BUYER_REVIEW:{count:data.buyer.toReview,path:reviewTaskPath('BUYER')},
  SELLER_DELIVER:{count:data.seller.toDeliver,path:filteredOrderPath('SELLER','PAID')},
  SELLER_REVIEW:{count:data.seller.toReview,path:reviewTaskPath('SELLER')}
 }[key]
}
export function createTradeTaskManager({identity,load,navigate,notify=()=>{}}) {
 const empty=()=>({data:null,loading:false,opening:false,error:''})
 let state=empty(),scope=null,generation=0,disposed=false
 const emit=()=>notify({...state})
 const valid=who=>positive(who?.userId)&&typeof who?.token==='string'&&!!who.token
 const matches=who=>{const now=identity();return now?.userId===who?.userId&&now?.token===who?.token}
 async function refresh() {
  if(disposed)return
  const ticket=++generation,who={...identity()};state=empty();scope=null;emit()
  if(!valid(who)){state.error='请先登录后查看交易待办';emit();return}
  state.loading=true;emit()
  try{const result=await load();if(!disposed&&ticket===generation&&matches(who)){state.data=validateTradeTasks(result,who.userId);scope=who}}
  catch(error){if(!disposed&&ticket===generation&&matches(who))state.error=error?.message||'读取交易待办失败'}
  finally{if(!disposed&&ticket===generation){state=matches(who)?{...state,loading:false}:empty();emit()}}
 }
 async function open(key) {
  if(disposed||state.loading||state.opening||!valid(scope)||!matches(scope)||!state.data)return false
  let target;try{target=tradeTaskTarget(state.data,scope.userId,key)}catch(error){state.error=error.message;emit();return false}
  if(target.count===0)return false
  const ticket=generation,who={...scope};state.opening=true;emit()
  try{await navigate(target.path);return !disposed&&ticket===generation&&matches(who)}
  catch(error){if(!disposed&&ticket===generation&&matches(who)){state.error='打开交易待办列表失败';emit()}return false}
  finally{if(!disposed&&ticket===generation){state=matches(who)?{...state,opening:false}:empty();emit()}}
 }
 return {refresh,open,reset(){generation++;scope=null;state=empty();emit()},dispose(){disposed=true;generation++;scope=null;state=empty();emit()}}
}

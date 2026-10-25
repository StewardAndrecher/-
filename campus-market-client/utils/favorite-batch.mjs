const positive=n=>Number.isSafeInteger(n)&&n>0
const exact=(v,keys)=>v&&typeof v==='object'&&!Array.isArray(v)&&Object.keys(v).sort().join(',')===keys.split(',').sort().join(',')
export const FAVORITE_BATCH_SIZE=10
export function favoriteBatchIds(ids) {
 if(!Array.isArray(ids)||ids.length<1||ids.length>20||ids.some(id=>!positive(id))||new Set(ids).size!==ids.length)throw new Error('请选择1至20件不重复的商品')
 return [...ids]
}
export function validateFavoriteBatchReceipt(data,ids,userId) {
 const wanted=favoriteBatchIds(ids)
 if(!positive(userId)||!exact(data,'userId,productIds,removedCount')||data.userId!==userId||!Array.isArray(data.productIds)||data.productIds.length!==wanted.length||data.productIds.some((id,n)=>id!==wanted[n])||!Number.isSafeInteger(data.removedCount)||data.removedCount<0||data.removedCount>wanted.length)throw new Error('批量取消回执与本次选择或账号不匹配')
 return data
}
export function validateFavoriteManagePage(data,page,size=FAVORITE_BATCH_SIZE) {
 if(!positive(page)||page>1000000||!positive(size)||size>20||!exact(data,'records,total,page,size')||data.page!==page||data.size!==size||!Number.isSafeInteger(data.total)||data.total<0||!Array.isArray(data.records)||data.records.length!==Math.min(size,Math.max(0,data.total-(page-1)*size)))throw new Error('收藏列表格式不正确，请刷新确认')
 const seen=new Set()
 for(const item of data.records) {
  if(!exact(item,'productId,product')||!positive(item.productId)||seen.has(item.productId))throw new Error('收藏编号无效或重复')
  seen.add(item.productId)
  if(item.product===null)continue
  const p=item.product
  if(!p||p.id!==item.productId||p.status!=='ON_SALE'||typeof p.title!=='string'||p.title.length<2||p.title.length>50||typeof p.description!=='string'||!positive(p.priceInCents)||p.priceInCents>99999999||!Number.isSafeInteger(p.version)||p.version<0)throw new Error('收藏商品格式不正确')
 }
 return data
}
export function createFavoriteBatchManager({identity,load,remove,confirm,notify=()=>{}}) {
 const empty=()=>({page:null,selected:[],loading:false,saving:false,error:'',notice:'',uncertain:false})
 let state=empty(),generation=0,disposed=false,scope=null
 const emit=()=>notify({...state,selected:[...state.selected]})
 const valid=who=>positive(who?.userId)&&typeof who?.token==='string'&&!!who.token
 const matches=who=>{const now=identity();return now?.userId===who?.userId&&now?.token===who?.token}
 const alive=(ticket,who)=>!disposed&&ticket===generation&&matches(who)
 const selectable=()=>!disposed&&!state.loading&&!state.saving&&!state.uncertain&&state.page&&valid(scope)&&matches(scope)
 async function refresh(requested=1,notice='') {
  if(disposed||state.saving)return
  const who={...identity()},ticket=++generation;scope=who;state={...empty(),notice};emit()
  if(!valid(who)){state.error='请先登录后管理收藏';emit();return}
  state.loading=true;emit()
  try {
   if(!positive(requested)||requested>1000000)throw new Error('收藏页码不正确')
   let page=requested
   for(let attempt=0;attempt<2;attempt++) {
    const result=await load({page,size:FAVORITE_BATCH_SIZE})
    if(!alive(ticket,who))return
    validateFavoriteManagePage(result,page)
    const last=Math.max(1,Math.ceil(result.total/FAVORITE_BATCH_SIZE))
    if(page>last){page=last;continue}
    state.page=result;return
   }
   throw new Error('收藏数量正在变化，请重新刷新')
  }catch(error){if(alive(ticket,who))state={...empty(),loading:true,notice,error:error?.message||'读取收藏失败'}}
  finally{if(!disposed&&ticket===generation){state=matches(who)?{...state,loading:false}:empty();emit()}}
 }
 function toggle(id) {
  if(!selectable()||!state.page.records.some(row=>row.productId===id))return false
  state.selected=state.selected.includes(id)?state.selected.filter(value=>value!==id):[...state.selected,id];state.notice='';emit();return true
 }
 function select(kind) {
  if(!selectable()||!['ALL','UNAVAILABLE','NONE'].includes(kind))return false
  state.selected=kind==='NONE'?[]:state.page.records.filter(row=>kind==='ALL'||row.product===null).map(row=>row.productId);state.notice='';emit();return true
 }
 async function submit() {
  if(!selectable()||!state.selected.length)return false
  const who={...scope},ticket=++generation,ids=favoriteBatchIds(state.selected),page=state.page.page
  let sent=false;state.saving=true;state.error='';state.notice='';emit()
  try {
   if(!await confirm([...ids])||!alive(ticket,who))return false
   sent=true;const result=await remove([...ids])
   if(!alive(ticket,who))return false
   validateFavoriteBatchReceipt(result,ids,who.userId)
   const notice=`本次取消${result.removedCount}条收藏；已处理所选${ids.length}个商品编号。`
   state={...empty(),notice};emit()
   await refresh(page,notice);return true
  }catch(error){if(alive(ticket,who))state=sent?{...empty(),saving:true,uncertain:true,error:(error?.message||'未收到取消结果')+'；请先刷新核对，不会自动重发。'}:{...state,error:error?.message||'无法显示确认窗口'};return false}
  finally{if(!disposed&&ticket===generation){state=matches(who)?{...state,saving:false}:empty();emit()}}
 }
 return {refresh:page=>refresh(page),toggle,select,submit,reset(){generation++;scope=null;state=empty();emit()},dispose(){disposed=true;generation++;scope=null;state=empty();emit()}}
}

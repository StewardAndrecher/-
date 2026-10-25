const positive=n=>Number.isSafeInteger(n)&&n>0
const nonnegative=n=>Number.isSafeInteger(n)&&n>=0
function time(value) {
  if(typeof value!=='string')return false
  const m=/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.\d{1,9})?)?$/.exec(value)
  if(!m)return false
  const [,y,month,day,h,minute,s='0']=m,year=Number(y),leap=year%4===0&&(year%100!==0||year%400===0)
  const days=[31,leap?29:28,31,30,31,30,31,31,30,31,30,31]
  return year>=1000&&Number(month)>=1&&Number(month)<=12&&Number(day)>=1&&Number(day)<=days[Number(month)-1]&&Number(h)<24&&Number(minute)<60&&Number(s)<60
}
function key(value) {const [d,t]=value.split('T'),[h,m,seconds='00']=t.split(':'),[s,f='']=seconds.split('.');return `${d}T${h}:${m}:${s}.${f.padEnd(9,'0')}`}
export function validateSessionSummary(data,userId) {
  if(!data||!positive(userId)||data.userId!==userId||!positive(data.activeSessions)||!nonnegative(data.otherActiveSessions)||
      data.otherActiveSessions!==data.activeSessions-1||!time(data.currentCreatedAt)||!time(data.currentExpiresAt)||!time(data.sampledAt)||
      key(data.currentCreatedAt)>key(data.sampledAt)||key(data.currentExpiresAt)<=key(data.sampledAt)||
      ['accessToken','tokenHash','token_hash','sessions'].some(name=>name in data))throw new Error('登录状态响应无效，请重新刷新')
  return data
}
export function validateSessionRevocation(data,userId) {
  if(!data||!positive(userId)||data.userId!==userId||!nonnegative(data.revokedSessions)||!time(data.processedAt)||
      ['accessToken','tokenHash','token_hash'].some(name=>name in data))throw new Error('退出其他登录的回执无效')
  return data
}
export function createSessionManager({load,revoke,confirm,identity,notify=()=>{}}) {
  const empty=()=>({summary:null,loading:false,revoking:false,result:null,error:'',status:0,uncertain:false})
  let state=empty(),generation=0,disposed=false,scope=null
  const emit=()=>notify({...state})
  const matches=who=>{const actual=identity();return !!who&&actual?.userId===who.userId&&actual?.token===who.token}
  const valid=who=>positive(who?.userId)&&typeof who.token==='string'&&!!who.token
  const alive=(ticket,who)=>!disposed&&ticket===generation&&matches(who)
  async function refresh(active=true) {
    if(disposed||(state.revoking&&matches(scope)))return
    const who={...identity()},result=matches(scope)?state.result:null,ticket=++generation;scope=who
    state={...empty(),result};emit()
    if(!active||!valid(who))return
    state={...state,loading:true};emit()
    try {const data=await load();if(!alive(ticket,who))return;state={...state,summary:validateSessionSummary(data,who.userId),status:200}}
    catch(e){if(alive(ticket,who))state={...state,summary:null,error:e?.message||'暂时无法读取登录状态',status:e?.statusCode||0}}
    finally {if(!disposed&&ticket===generation){state=matches(who)?{...state,loading:false}:empty();emit()}}
  }
  async function revokeOthers() {
    if(disposed||state.loading||state.revoking||!state.summary||state.summary.otherActiveSessions===0)return false
    const who={...identity()};if(!valid(who)||!matches(scope))return false
    const ticket=++generation;let sent=false,acknowledged=false
    state={...state,revoking:true,result:null,error:'',status:0,uncertain:false};emit()
    try {
      const confirmed=await confirm(state.summary.otherActiveSessions)
      if(!alive(ticket,who)||!confirmed)return false
      sent=true;const result=await revoke()
      if(!alive(ticket,who))return false
      validateSessionRevocation(result,who.userId);acknowledged=true
      state={...empty(),revoking:true,result};emit()
      const summary=await load()
      if(!alive(ticket,who))return false
      state={...state,summary:validateSessionSummary(summary,who.userId),status:200};return true
    } catch(e) {
      if(alive(ticket,who))state={...state,summary:sent?null:state.summary,error:acknowledged?'退出已完成，但刷新登录状态失败，请手动刷新':sent?'退出结果暂不确定，请先刷新登录状态，不要自动重试':e?.message||'暂时无法确认操作',status:e?.statusCode||0,uncertain:sent&&!acknowledged}
      return false
    } finally {if(!disposed&&ticket===generation){state=matches(who)?{...state,revoking:false}:empty();emit()}}
  }
  return {refresh,revokeOthers,deactivate(){generation++;scope=null;state=empty();emit()},dispose(){disposed=true;generation++;scope=null;state=empty();emit()}}
}

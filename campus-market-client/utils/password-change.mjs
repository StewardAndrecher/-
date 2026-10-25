import { validateSessionRevocation } from './session-management.mjs'

function bytes(value) {
  let total=0
  for(const c of value) {
    const n=c.codePointAt(0)
    if(n>=0xd800&&n<=0xdfff)throw new Error('密码包含无效字符')
    total+=n<0x80?1:n<0x800?2:n<0x10000?3:4
  }
  return total
}
export function validatePasswordChange(form) {
  const {currentPassword,newPassword,confirmPassword}=form||{}
  if(typeof currentPassword!=='string'||!currentPassword||Array.from(currentPassword).length>64||bytes(currentPassword)>72)throw new Error('请填写有效的原密码')
  if(typeof newPassword!=='string'||!newPassword.trim()||Array.from(newPassword).length<8||Array.from(newPassword).length>64||bytes(newPassword)>72)throw new Error('新密码需要8～64个字符，UTF-8长度不超过72字节，不能全为空白')
  if(newPassword===currentPassword)throw new Error('新密码不能与原密码相同')
  if(newPassword!==confirmPassword)throw new Error('两次填写的新密码不一致')
  return {currentPassword,newPassword}
}
export function validatePasswordReceipt(data,userId) {
  if(!data||Object.keys(data).sort().join(',')!=='changedAt,revokedSessions,userId'||data.revokedSessions<1)throw new Error('修改结果回执无效')
  validateSessionRevocation({...data,processedAt:data.changedAt},userId)
  return data
}
export function createPasswordChanger({identity,confirm,change,clear,notify=()=>{}}) {
  const empty=()=>({saving:false,done:false,uncertain:false,message:'',result:null})
  let state=empty(),generation=0,disposed=false
  const emit=()=>notify({...state})
  const matches=who=>{const actual=identity();return actual?.userId===who.userId&&actual?.token===who.token}
  async function submit(form) {
    if(disposed||state.saving||state.done||state.uncertain)return false
    const who={...identity()};let payload
    if(!Number.isSafeInteger(who.userId)||who.userId<1||!who.token)return false
    try{payload=validatePasswordChange(form)}catch(e){state={...state,message:e.message};emit();return false}
    const ticket=++generation;let sent=false
    const alive=()=>!disposed&&ticket===generation&&matches(who)
    state={...empty(),saving:true};emit()
    try {
      if(!await confirm()||!alive())return false
      sent=true;const result=await change(payload)
      if(!alive())return false
      validatePasswordReceipt(result,who.userId)
      state={...empty(),done:true,message:'密码已修改，所有登录已退出，请使用新密码重新登录',result};emit()
      clear(who.token)
      return true
    } catch(e) {
      if(!alive())return false
      const status=e?.statusCode
      const unknown=sent&&(!Number.isInteger(status)||status===0||(status>=500&&status!==503))
      if(unknown){state={...empty(),uncertain:true,message:'修改结果暂不确定，当前本机登录已清理。请使用新密码重新登录确认，不会自动重复提交'};emit();clear(who.token)}
      else {state={...empty(),message:e?.message||'本次未修改密码，请核对后重试'};emit()}
      return false
    } finally {
      payload=null
      if(!disposed&&ticket===generation){state={...state,saving:false};emit()}
    }
  }
  return {submit,reset(){generation++;state=empty();emit()},dispose(){disposed=true;generation++;state=empty();emit()}}
}

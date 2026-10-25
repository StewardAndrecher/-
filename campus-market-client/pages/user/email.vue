<template>
 <view class="service-page"><view class="page-heading"><text class="eyebrow">ACCOUNT CONNECTION</text><text class="page-title">{{ currentUser?'邮箱绑定与验证':'通过邮箱找回账号' }}</text><text class="page-subtitle">验证你控制的邮箱，为账号增加可靠的恢复方式。</text></view>
 <view class="notice"><text>验证码10分钟有效，最多尝试5次，只能使用一次。更换邮箱需先验证并解除旧邮箱绑定，再绑定新邮箱；验证码和密码不会写入本地缓存。</text></view>
 <text v-if="error" class="error">{{ error }}</text><text v-if="message" class="success">{{ message }}</text>
 <view v-if="loading" class="surface"><text class="muted">正在读取邮件服务与绑定状态…</text></view>
 <view v-else-if="delivery===false" class="surface"><text class="section-title">邮件服务尚未配置</text><text class="muted">平台配置邮件服务并验收真实收信后，才能使用邮箱绑定和邮件找回。现在可以继续使用提前保存的恢复码。</text><button class="secondary spaced" @click="recovery">使用恢复码</button></view>
 <view v-else-if="delivery===true && !completed" class="surface">
  <text class="section-title">{{ purpose==='UNBIND'?'验证旧邮箱并解除绑定':purpose==='BIND'?'验证并绑定邮箱':'发送找回验证码' }}</text><text v-if="status?.bound" class="muted">已绑定：{{ status.maskedEmail }}</text>
  <template v-if="!challenge"><text v-if="!currentUser" class="label">注册用户名</text><input v-if="!currentUser" v-model="username" class="field" :disabled="busy" :maxlength="32" placeholder="注册时使用的用户名"/><text class="label">{{ purpose==='UNBIND'?'当前完整邮箱':'邮箱地址' }}</text><input v-model="email" class="field" :disabled="busy" :maxlength="254" placeholder="name@example.com"/><text v-if="currentUser" class="label">当前密码</text><input v-if="currentUser" v-model="password" class="field" password :disabled="busy" :maxlength="-1" placeholder="验证当前密码后才会发送邮件"/><button class="primary spaced" :disabled="busy || !canSend" @click="send">{{ saving?'正在请求…':'发送验证码' }}</button></template>
  <template v-else><text class="muted">{{ challenge.message }}</text><text class="label">邮件中的8位验证码</text><input v-model="code" class="field" type="number" :maxlength="8" :disabled="busy" placeholder="输入8位数字"/><template v-if="!currentUser"><text class="label">新密码</text><input v-model="newPassword" class="field" password :maxlength="-1" :disabled="busy" placeholder="8～64个字符，UTF-8不超过72字节"/><text class="label">确认新密码</text><input v-model="confirmation" class="field" password :maxlength="-1" :disabled="busy" placeholder="再次输入新密码"/></template><button class="primary spaced" :disabled="busy || code.length!==8" @click="verify">{{ saving?'正在确认…':currentUser?(purpose==='UNBIND'?'确认解除绑定':'确认绑定邮箱'):'重设密码并退出旧登录' }}</button><button class="secondary spaced" :disabled="busy || cooldown>0" @click="restart">{{ cooldown>0?`${cooldown}秒后可重新获取`:'未收到邮件？重新填写并获取' }}</button></template>
 </view>
 <view v-if="completed" class="surface"><text class="section-title">{{ currentUser?'绑定状态已更新':'密码已重设' }}</text><text class="muted">{{ currentUser?'请刷新查看最新绑定状态。':'旧会话及旧恢复码已失效，请使用新密码登录。' }}</text><button class="primary spaced" @click="currentUser?refresh():login()">{{ currentUser?'查看最新状态':'前往登录' }}</button></view>
 <view class="page-actions"><button class="secondary" :disabled="busy" @click="refresh">刷新状态</button><button class="text-button" :disabled="busy" @click="recovery">恢复码与其他找回方式</button><button class="text-button" :disabled="busy" @click="home">返回首页</button></view></view>
</template>
<script setup>
import {ref,computed,watch} from 'vue'
import {onShow,onHide,onUnload} from '@dcloudio/uni-app'
import {currentUser,getToken} from '@/utils/session.js'
import {emailStatus,emailCapabilities,sendEmailChallenge,confirmEmail,recoverByEmail} from '@/api/platform.js'
import {validEmail} from '@/utils/platform.mjs'
import {recoveryPassword} from '@/utils/account-profile.mjs'
import {ask,login,home} from '@/composables/usePrivatePage.js'
const status=ref(null),delivery=ref(null),username=ref(''),email=ref(''),password=ref(''),code=ref(''),newPassword=ref(''),confirmation=ref(''),challenge=ref(null),loading=ref(false),saving=ref(false),completed=ref(false),error=ref(''),message=ref(''),now=ref(Date.now())
let active=false,disposed=false,generation=0,timer=null,requestedAt=0
const busy=computed(()=>loading.value||saving.value),purpose=computed(()=>currentUser.value?(status.value?.bound?'UNBIND':'BIND'):'RECOVER'),cooldown=computed(()=>Math.max(0,Math.ceil((requestedAt+60000-now.value)/1000))),canSend=computed(()=>validEmail(email.value)&&(currentUser.value?!!password.value:/^[a-z0-9_]{4,32}$/.test(username.value)))
const owner=()=>`${currentUser.value?.id||''}:${getToken()||''}`,alive=(turn,identity)=>active&&!disposed&&turn===generation&&owner()===identity
function clear(){generation++;status.value=null;delivery.value=null;challenge.value=null;username.value='';email.value='';password.value='';code.value='';newPassword.value='';confirmation.value='';loading.value=false;saving.value=false;completed.value=false;error.value='';message.value='';requestedAt=0}
async function refresh(){
 if(!active||disposed||busy.value)return
 clear();const turn=++generation,identity=owner();loading.value=true
 try{const value=currentUser.value?await emailStatus():await emailCapabilities();if(!alive(turn,identity))return
  if(currentUser.value){if(typeof value?.deliveryAvailable!=='boolean'||typeof value?.bound!=='boolean'||!Number.isSafeInteger(value.version)||value.version<0||(value.bound?typeof value.maskedEmail!=='string':value.maskedEmail!==null))throw new Error('绑定状态响应不完整');status.value=value;delivery.value=value.deliveryAvailable}
  else{if(typeof value?.emailDeliveryAvailable!=='boolean')throw new Error('邮件服务状态不完整');delivery.value=value.emailDeliveryAvailable}
 }catch(failure){if(alive(turn,identity))error.value=failure.message||'读取失败'}finally{if(alive(turn,identity))loading.value=false}
}
async function send(){
 if(!active||disposed||busy.value||challenge.value||delivery.value!==true||!canSend.value)return
 const identity=owner(),turn=++generation,auth=!!currentUser.value,body={purpose:purpose.value,email:email.value,currentPassword:password.value,username:username.value};saving.value=true;error.value='';message.value=''
 try{const result=await sendEmailChallenge(body,auth);if(!alive(turn,identity))return;if(!result||!/^[0-9a-f-]{36}$/.test(result.challengeId)||result.expiresInSeconds!==600||result.resendAfterSeconds!==60||typeof result.message!=='string')throw new Error('发送结果不完整，请稍后重新获取');challenge.value=result;requestedAt=Date.now();now.value=requestedAt;message.value='请求已受理，请检查邮箱；页面不会自动发送或重试。'}
 catch(failure){if(alive(turn,identity))error.value=(failure.message||'发送结果暂不确定')+'；请勿反复提交。'}
 finally{body.currentPassword='';if(alive(turn,identity)){password.value='';saving.value=false}}
}
async function verify(){
 if(!active||disposed||busy.value||!challenge.value||!/^\d{8}$/.test(code.value))return
 error.value='';const auth=!!currentUser.value
 if(!auth&&(!recoveryPassword(newPassword.value)||newPassword.value!==confirmation.value)){error.value='请填写符合要求且两次一致的新密码';return}
 const turn=++generation,identity=owner(),body={challengeId:challenge.value.challengeId,code:code.value,username:username.value,newPassword:newPassword.value};saving.value=true
 try{if(!await ask(auth?'确认邮箱操作':'重设账号密码',auth?'请确认这是你本人控制的邮箱。解除绑定后将不能通过旧邮箱找回账号。':'成功后旧登录、旧恢复码和当前验证码全部失效。')||!alive(turn,identity))return
  const value=auth?await confirmEmail({challengeId:body.challengeId,code:body.code}):await recoverByEmail(body);if(!alive(turn,identity))return
  if(auth){if(typeof value?.bound!=='boolean'||value.bound!==(purpose.value==='BIND'))throw new Error('绑定结果不匹配')}else if(value?.passwordChanged!==true||value?.sessionsRevoked!==true)throw new Error('找回结果不完整')
  completed.value=true;challenge.value=null;message.value=auth?'邮箱操作已完成。':'密码已重设，请重新登录。'
 }catch(failure){if(alive(turn,identity)){error.value=(failure.message||'操作结果暂不确定')+'；网络中断时请先核对绑定状态或尝试新密码登录，不会自动重发。'}}
 finally{body.code='';body.newPassword='';if(alive(turn,identity)){code.value='';newPassword.value='';confirmation.value='';saving.value=false}}
}
function restart(){if(!busy.value&&cooldown.value===0){challenge.value=null;code.value='';newPassword.value='';confirmation.value='';error.value='';message.value=''}}
function recovery(){if(!busy.value)uni.navigateTo({url:'/pages/user/recovery'})}
watch(()=>[currentUser.value?.id,getToken()],()=>{clear();if(active)refresh()},{flush:'sync'})
onShow(()=>{if(disposed)return;active=true;clear();clearInterval(timer);timer=setInterval(()=>now.value=Date.now(),1000);refresh()})
onHide(()=>{active=false;clearInterval(timer);timer=null;clear()})
onUnload(()=>{active=false;disposed=true;clearInterval(timer);timer=null;clear()})
</script>
<style scoped src="@/styles/service-pages.css"></style>
<style scoped>.spaced{margin-top:18px}</style>

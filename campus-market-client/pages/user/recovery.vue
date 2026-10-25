<template>
 <view class="service-page">
  <view class="page-heading"><text class="eyebrow">ACCOUNT SECURITY · 账号安全</text><text class="page-title">{{ currentUser ? '账号恢复码' : '找回账号密码' }}</text><text class="page-subtitle">为忘记密码留一条安全的恢复途径。</text></view>
  <view class="notice"><text class="notice-title">恢复码相当于账号钥匙</text><text>需要提前登录并生成恢复码，忘记密码后才能使用。服务端只保存摘要，不保存可还原的原码；每个恢复码只能成功使用一次。不要把恢复码发给其他人。</text></view>
  <text v-if="error" class="error">{{ error }}</text><text v-if="message" class="success">{{ message }}</text>
  <view v-if="currentUser" class="surface">
   <text class="section-title">恢复方式</text><text class="muted">{{ loading ? '正在查询恢复码状态…' : status ? status.enabled ? '已设置恢复码；生成新码会让旧码立即失效。' : '尚未设置恢复码，建议离线妥善保存一份。' : '状态未加载，请先刷新。' }}</text>
   <text v-if="status?.createdAt" class="muted">生成时间：{{ status.createdAt.replace('T',' ') }}</text>
   <template v-if="status"><text class="label">验证当前密码</text><input v-model="password" class="field" password :disabled="busy" placeholder="输入当前密码后生成新恢复码" :maxlength="-1" /><button class="primary generate" :disabled="busy || !password" @click="generate">{{ saving ? '正在生成…' : '生成 / 更换恢复码' }}</button></template>
   <view v-if="generated" class="generated"><text class="section-title">请现在保存，离开后不再显示</text><text selectable class="security-code">{{ generated }}</text><text class="privacy-note">请保存在你控制的密码管理器或离线位置。页面不会自动复制、写入本地缓存或再次展示这段恢复码。</text></view>
   <button class="secondary generate" :disabled="busy" @click="loadStatus">刷新恢复方式</button>
  </view>
  <view v-else-if="!success" class="surface">
   <text class="section-title">使用已保存的恢复码</text><text class="label">用户名</text><input v-model="username" class="field" :maxlength="32" :disabled="busy" placeholder="注册时的用户名" />
   <text class="label">一次性恢复码</text><input v-model="recoveryCode" class="field" password :maxlength="43" :disabled="busy" placeholder="粘贴你提前保存的恢复码" />
   <text class="label">新密码</text><input v-model="newPassword" class="field" password :maxlength="-1" :disabled="busy" placeholder="8～64个字符，UTF-8不超过72字节" />
   <text class="label">确认新密码</text><input v-model="confirmation" class="field" password :maxlength="-1" :disabled="busy" placeholder="再次输入新密码" />
   <text class="privacy-note">成功后全部登录会话退出，恢复码立即失效。没有提前设置恢复码的账号，无法通过此方式找回；也可使用已绑定邮箱找回（需要平台邮件服务可用）。</text>
   <button class="primary" :disabled="busy" @click="resetPassword">{{ saving ? '正在确认…' : '重设密码并退出全部登录' }}</button>
  </view>
  <view v-else class="surface empty"><text class="section-title">密码已重设</text><text class="muted">原有登录已退出，恢复码已失效。请使用新密码登录，再生成一份新的恢复码。</text><button class="primary" @click="login">使用新密码登录</button></view>
  <button class="secondary" :disabled="busy" @click="openPage('/pages/user/email')">邮箱绑定与邮件找回</button>
  <view class="page-actions"><button v-if="!currentUser && !success" class="secondary" :disabled="busy" @click="login">返回登录</button><button class="text-button" :disabled="busy" @click="back">返回上一页</button></view>
 </view>
</template>
<script setup>
import { openPage } from '@/composables/usePrivatePage.js'
import { ref,computed,watch } from 'vue'
import { onShow,onHide,onUnload } from '@dcloudio/uni-app'
import { currentUser,getToken } from '@/utils/session.js'
import { getRecoveryStatus,generateRecoveryCode,recoverAccount } from '@/api/account-profile.js'
import { recoveryPassword } from '@/utils/account-profile.mjs'
import { validOrderTime } from '@/utils/order.js'
const status=ref(null),generated=ref(''),password=ref(''),username=ref(''),recoveryCode=ref(''),newPassword=ref(''),confirmation=ref(''),error=ref(''),message=ref(''),success=ref(false),loading=ref(false),saving=ref(false)
const busy=computed(()=>loading.value||saving.value)
let active=false,closed=false,generation=0
const alive=(turn,token,user)=>active&&!closed&&turn===generation&&token===getToken()&&user===currentUser.value?.id
function clear(){generation++;status.value=null;generated.value='';password.value='';username.value='';recoveryCode.value='';newPassword.value='';confirmation.value='';error.value='';message.value='';success.value=false;loading.value=false;saving.value=false}
async function loadStatus(){
 if(!active||closed||busy.value||!currentUser.value||!getToken())return
 const turn=++generation,token=getToken(),user=currentUser.value.id;status.value=null;error.value='';loading.value=true
 try{const value=await getRecoveryStatus();if(!alive(turn,token,user))return;if(!value||typeof value.enabled!=='boolean'||(value.enabled?!validOrderTime(value.createdAt):value.createdAt!==null))throw new Error('恢复方式响应格式不正确');status.value=value}
 catch(failure){if(alive(turn,token,user))error.value=failure.message||'读取失败'}finally{if(alive(turn,token,user))loading.value=false}
}
function confirm(title,content){return new Promise(resolve=>uni.showModal({title,content,success:r=>resolve(r.confirm===true),fail:()=>resolve(false)}))}
async function generate(){
 if(!active||closed||busy.value||!status.value||!getToken()||!currentUser.value||!password.value)return
 const turn=++generation,token=getToken(),user=currentUser.value.id,body={currentPassword:password.value};saving.value=true;generated.value='';error.value='';message.value=''
 let sent=false
 try{if(!await confirm('生成新的恢复码','旧恢复码将立即失效，新码只展示这一次。请确认你可以妥善保存。')||!alive(turn,token,user))return;sent=true;const value=await generateRecoveryCode(body);if(!alive(turn,token,user))return;if(!value||typeof value.recoveryCode!=='string'||! /^[A-Za-z0-9_-]{43}$/.test(value.recoveryCode)||!validOrderTime(value.createdAt))throw new Error('生成结果格式不正确');generated.value=value.recoveryCode;status.value={enabled:true,createdAt:value.createdAt};message.value='新的恢复码已生成，请立即保存。'}
 catch(failure){if(alive(turn,token,user)){error.value=(failure.message||'生成结果暂不确定')+(sent?'；页面不会自动重试。若未收到新码，请核验密码后重新生成。':'');if(sent)status.value=null}}
 finally{body.currentPassword='';if(alive(turn,token,user)){password.value='';saving.value=false}}
}
async function resetPassword(){
 if(!active||closed||busy.value||currentUser.value||getToken())return
 error.value='';if(!/^[a-z0-9_]{4,32}$/.test(username.value)||!/^[A-Za-z0-9_-]{43}$/.test(recoveryCode.value)){error.value='请填写有效用户名和43位恢复码';return}if(!recoveryPassword(newPassword.value)){error.value='新密码需要8～64个字符，UTF-8不超过72字节，不能全为空白';return}if(newPassword.value!==confirmation.value){error.value='两次输入的新密码不一致';return}
 const turn=++generation,token=getToken(),user=currentUser.value?.id,body={username:username.value,recoveryCode:recoveryCode.value,newPassword:newPassword.value};saving.value=true;let sent=false
 try{if(!await confirm('重设账号密码','成功后会使全部登录和本次恢复码失效，请确认已记住新密码。')||!alive(turn,token,user))return;sent=true;const value=await recoverAccount(body);if(!alive(turn,token,user))return;if(value?.passwordChanged!==true||value?.sessionsRevoked!==true)throw new Error('重设结果格式不正确');success.value=true;message.value='密码已重设，请重新登录。'}
 catch(failure){if(alive(turn,token,user))error.value=(failure.message||'结果暂不确定')+(sent?'；若发生网络中断，请先尝试用新密码登录，勿反复提交。':'')}
 finally{body.recoveryCode='';body.newPassword='';if(alive(turn,token,user)){recoveryCode.value='';newPassword.value='';confirmation.value='';saving.value=false}}
}
function login(){if(!busy.value)openPage('/pages/user/login')}
function back(){if(!busy.value)uni.navigateBack({fail:()=>uni.reLaunch({url:'/pages/index/index'})})}
watch(()=>[currentUser.value?.id,getToken()],()=>{clear();if(active)loadStatus()},{flush:'sync'})
onShow(()=>{if(closed)return;active=true;clear();loadStatus()});onHide(()=>{active=false;clear()});onUnload(()=>{active=false;closed=true;clear()})
</script>
<style scoped src="@/styles/service-pages.css"></style>
<style scoped>.generate{margin-top:20px}.generated{margin-top:25px;padding-top:24px;border-top:1px solid #e7edf7}.security-code{margin-top:14px}</style>

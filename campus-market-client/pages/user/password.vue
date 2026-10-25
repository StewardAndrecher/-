<template>
  <view class="page">
    <text class="title">修改密码</text>
    <text class="hint">修改成功后，包括当前在内的所有登录都会退出，需要使用新密码重新登录。</text>
    <view v-if="currentUser && !state.done && !state.uncertain" class="panel">
      <text class="label">原密码</text>
      <input v-model="currentPassword" :password="true" :maxlength="-1" :disabled="state.saving" class="field" placeholder="填写原密码" />
      <text class="label">新密码</text>
      <input v-model="newPassword" :password="true" :maxlength="-1" :disabled="state.saving" class="field" placeholder="填写8～64个字符的新密码" />
      <text class="hint">不能与原密码相同，不能全为空白；中文或表情较多时可能超出长度限制。首尾空格会保留。</text>
      <text class="label">再次填写新密码</text>
      <input v-model="confirmPassword" :password="true" :maxlength="-1" :disabled="state.saving" class="field" placeholder="再次填写新密码" />
      <button type="primary" :disabled="state.saving" :loading="state.saving" @click="submit">{{ state.saving?'正在确认或修改…':'修改密码并退出所有登录' }}</button>
    </view>
    <text v-if="!currentUser && !state.done && !state.uncertain" class="hint">请先登录后修改密码</text>
    <text v-if="state.message" class="message">{{ state.message }}</text>
    <text v-if="state.result" class="hint">已退出 {{ state.result.revokedSessions }} 个有效登录；服务器处理时间：{{ state.result.changedAt }}</text>
    <button v-if="!currentUser || state.done || state.uncertain" @click="openLogin">前往登录</button>
    <button :disabled="state.saving" @click="goProfile">返回账号资料</button>
  </view>
</template>
<script setup>
import { ref,watch } from 'vue'
import { onShow,onHide,onUnload } from '@dcloudio/uni-app'
import { currentUser,getToken,clearSession } from '@/utils/session.js'
import { changeMyPassword } from '@/api/user.js'
import { createPasswordChanger } from '@/utils/password-change.mjs'
const currentPassword=ref(''),newPassword=ref(''),confirmPassword=ref('')
const state=ref({saving:false,done:false,uncertain:false,message:'',result:null})
function wipe(){currentPassword.value='';newPassword.value='';confirmPassword.value=''}
const changer=createPasswordChanger({identity:()=>({userId:currentUser.value?.id,token:getToken()}),change:changeMyPassword,clear:clearSession,notify:value=>{state.value=value},
  confirm:()=>new Promise((resolve,reject)=>uni.showModal({title:'确认修改密码',content:'修改后将退出此账号的所有有效登录，包括当前登录。请确认已记住新密码。',confirmText:'确认修改',cancelText:'取消',success:r=>resolve(r.confirm===true),fail:()=>reject(new Error('无法显示确认窗口，本次未提交'))}))})
async function submit(){try{await changer.submit({currentPassword:currentPassword.value,newPassword:newPassword.value,confirmPassword:confirmPassword.value})}finally{wipe()}}
watch(()=>[currentUser.value,getToken()],()=>{wipe();if(currentUser.value||(!state.value.done&&!state.value.uncertain))changer.reset()},{flush:'sync'})
onShow(()=>{wipe();changer.reset()})
onHide(()=>{wipe();changer.reset()})
onUnload(()=>{wipe();changer.dispose()})
function openLogin(){uni.navigateTo({url:'/pages/user/login'})}
function goProfile(){uni.navigateBack({fail:()=>uni.redirectTo({url:'/pages/user/profile'})})}
</script>
<style scoped>
.page{padding:32rpx;min-height:100vh;background:#f5f7fa;box-sizing:border-box}.title{display:block;font-size:36rpx;font-weight:bold}.panel{padding:28rpx;margin:24rpx 0;background:#fff;border-radius:16rpx}.label{display:block;margin:24rpx 0 12rpx}.field{height:88rpx;padding:0 20rpx;border:1px solid #d9dfe8;border-radius:12rpx}.hint,.message{display:block;line-height:1.6;margin:20rpx 0;color:#64748b}.message{color:#b42318}button{margin-top:24rpx}
</style>

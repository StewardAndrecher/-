<template>
  <view class="page">
    <text class="title">登录会话管理</text>
    <text class="hint">每次成功登录形成一个会话，会话数量不等于设备数量。这里不保存或展示登录令牌。</text>
    <text v-if="!currentUser" class="hint">请先登录后查看登录状态</text>
    <button v-if="!currentUser" @click="openLogin">前往登录</button>
    <text v-if="state.loading" class="hint">正在读取登录状态…</text>
    <view v-if="state.summary" class="panel">
      <text class="heading">有效登录会话：{{ state.summary.activeSessions }} 个（含当前）</text>
      <text>其他有效登录：{{ state.summary.otherActiveSessions }} 个</text>
      <text>当前登录创建：{{ state.summary.currentCreatedAt }}</text>
      <text>当前登录到期：{{ state.summary.currentExpiresAt }}</text>
      <text>服务器采样时间：{{ state.summary.sampledAt }}</text>
      <text class="hint">到期由服务器判断，请刷新查看最新状态。</text>
    </view>
    <view v-if="state.result" class="panel">
      <text>已退出 {{ state.result.revokedSessions }} 个其他有效登录，当前登录保留。</text>
      <text>服务器处理时间：{{ state.result.processedAt }}</text>
    </view>
    <text v-if="state.error" class="error">{{ state.error }}</text>
    <text v-if="state.uncertain" class="hint">请求可能已经生效；请先刷新核对，不会自动重复退出。</text>
    <button v-if="currentUser" :disabled="busy" @click="openList">查看有效登录明细</button>
    <button v-if="currentUser" :disabled="busy" @click="manager.refresh()">刷新登录状态</button>
    <button v-if="currentUser" :disabled="busy || !state.summary || state.summary.otherActiveSessions===0" @click="manager.revokeOthers()">{{ state.revoking?'正在确认或处理…':'退出其他登录' }}</button>
    <text class="hint">确认后退出服务器处理时当前账号的其他有效会话；当前会话保留，此后新登录不受影响。该操作不修改密码。</text>
    <button :disabled="busy" @click="goProfile">返回账号资料</button>
  </view>
</template>
<script setup>
import { ref,computed,watch } from 'vue'
import { onShow,onHide,onUnload } from '@dcloudio/uni-app'
import { currentUser,getToken } from '@/utils/session.js'
import { getMySessionSummary,revokeMyOtherSessions } from '@/api/user.js'
import { createSessionManager } from '@/utils/session-management.mjs'
const active=ref(false),state=ref({summary:null,loading:false,revoking:false,result:null,error:'',status:0,uncertain:false})
const busy=computed(()=>state.value.loading||state.value.revoking)
const manager=createSessionManager({load:getMySessionSummary,revoke:revokeMyOtherSessions,
  identity:()=>({userId:currentUser.value?.id,token:getToken()}),notify:value=>{state.value=value},
  confirm:count=>new Promise((resolve,reject)=>uni.showModal({title:'退出其他登录',content:`本次采样有${count}个其他有效登录。确认后退出服务器处理时当前账号的其他有效会话，当前登录保留；此后新登录不受影响。`,confirmText:'确认退出',cancelText:'取消',success:r=>resolve(r.confirm===true),fail:()=>reject(new Error('暂时无法显示确认窗口'))}))})
watch(()=>[currentUser.value?.id,getToken()],()=>manager.refresh(active.value),{flush:'sync'})
onShow(()=>{active.value=true;manager.refresh()})
onHide(()=>{active.value=false;manager.deactivate()})
onUnload(()=>{active.value=false;manager.dispose()})
function openList(){uni.navigateTo({url:'/pages/user/session-list',fail:()=>uni.showToast({title:'打开登录明细失败',icon:'none'})})}
function openLogin(){uni.navigateTo({url:'/pages/user/login'})}
function goProfile(){uni.navigateBack({fail:()=>uni.redirectTo({url:'/pages/user/profile'})})}
</script>
<style scoped>
.page{padding:32rpx;min-height:100vh;background:#f5f7fa}.title{display:block;font-size:40rpx;font-weight:bold;margin-bottom:20rpx}
.panel{padding:28rpx;margin:20rpx 0;background:white;border-radius:16rpx}.panel text{display:block;margin:16rpx 0}.heading{font-size:32rpx;font-weight:bold}
.hint{display:block;color:#64748b;line-height:1.6;padding:16rpx 0}.error{display:block;color:#b42318;padding:20rpx 0}button{margin-top:20rpx}
</style>

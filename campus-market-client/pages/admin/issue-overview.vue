<template>
 <view class="page">
  <text class="title">反馈处理概览</text>
  <text class="hint">统计平台全部交易反馈，按服务器时间判断等待时长。每次刷新重新采样。</text>
  <text v-if="!currentUser" class="hint">请先登录管理员账号</text><button v-if="!currentUser" @click="login">前往登录</button>
  <text v-if="state.busy" class="hint">正在采样反馈处理情况…</text>
  <view v-if="state.data">
   <text class="hint">采样时间（服务器时间）：{{ state.data.sampledAt.replace('T',' ') }}</text>
   <view class="stats"><view v-for="field in fields" :key="field.key" class="panel"><text class="number">{{ state.data[field.key] }}</text><text>{{ field.label }}</text></view></view>
   <view class="panel"><text>最早待处理提交时间</text><text>{{ state.data.oldestOpenAt?state.data.oldestOpenAt.replace('T',' '):'当前暂无待处理反馈' }}</text></view>
   <text class="hint">等待满72小时属于等待满24小时的子集，二者不能相加。统计的是反馈条数，订单双方各自提交时分别计数。</text>
   <button :disabled="state.busy" @click="open('OPEN','OVER_72H')">优先查看等待满72小时的工单</button>
   <button :disabled="state.busy" @click="open('OPEN','OVER_24H')">查看等待满24小时的工单</button>
   <button :disabled="state.busy" @click="open('OPEN','ALL')">查看全部待处理工单</button>
   <button :disabled="state.busy" @click="open('RESOLVED','ALL')">查看已处理工单</button>
   <text class="hint">列表进入时会重新采样，数量可能随新反馈或处理而变化。本页只读，不自动处理、关闭工单或执行退款。</text>
  </view>
  <text v-if="state.status===403" class="error">当前账号没有管理权限。</text><text v-else-if="state.error" class="error">{{ state.error }}</text>
  <button v-if="currentUser" :disabled="state.busy" @click="loader.refresh(active)">刷新反馈概览</button><button @click="back">返回管理中心</button>
 </view>
</template>
<script setup>
import {ref,watch} from 'vue'
import {onShow,onHide,onUnload} from '@dcloudio/uni-app'
import {currentUser,getToken} from '@/utils/session.js'
import {getIssueOverview} from '@/api/admin.js'
import {createAdminLoader} from '@/utils/admin-access.mjs'
import {validateIssueOverview} from '@/utils/issue-overview.mjs'
import {adminIssueListPath} from '@/utils/admin-order-issues.mjs'
const active=ref(false),state=ref({data:null,busy:false,error:'',status:0})
const fields=[{key:'total',label:'反馈总数'},{key:'open',label:'待处理'},{key:'resolved',label:'已处理'},{key:'answered',label:'已答复'},{key:'dismissed',label:'不予支持'},{key:'openOver24h',label:'待处理满24小时'},{key:'openOver72h',label:'待处理满72小时'}]
const loader=createAdminLoader(getIssueOverview,()=>({userId:currentUser.value?.id,token:getToken()}),validateIssueOverview,value=>{state.value=value})
function open(status,age){if(active.value&&!state.value.busy&&state.value.data)uni.navigateTo({url:adminIssueListPath(status,age)})}
function login(){uni.navigateTo({url:'/pages/user/login'})}
function back(){uni.redirectTo({url:'/pages/admin/index'})}
watch(()=>[active.value,currentUser.value?.id,getToken()],()=>loader.refresh(active.value),{flush:'sync'})
onShow(()=>{active.value=true})
onHide(()=>{active.value=false})
onUnload(()=>{active.value=false;loader.dispose()})
</script>
<style scoped>
.page{padding:30rpx;min-height:100vh;background:#f5f7fa;box-sizing:border-box}.title{display:block;font-size:36rpx;font-weight:bold}.hint{display:block;color:#64748b;line-height:1.6;margin:20rpx 0}.error{display:block;color:#b42318;margin:20rpx 0}.stats{display:flex;flex-wrap:wrap;gap:20rpx}.panel{background:white;border-radius:16rpx;padding:24rpx;margin:16rpx 0}.stats .panel{flex:1 1 35%}.panel text{display:block;margin:12rpx 0}.number{font-size:44rpx;color:#2563eb}button{margin-top:20rpx}
</style>

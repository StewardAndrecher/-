<template>
 <view class="page">
  <text class="title">交易问题反馈</text>
  <text class="hint">请清楚描述商品或交接中遇到的问题，勿填写密码、证件号码等敏感信息。此页仅展示你提交的记录；每笔订单每方可提交一次，暂不支持修改。提交不会改变订单状态或执行退款。</text>
  <text v-if="routeError||state.error" class="error">{{ routeError||state.error }}</text>
  <text v-if="!currentUser" class="hint">请先登录后查看或提交交易问题</text>
  <button v-if="!currentUser" @click="login">前往登录</button>
  <text v-if="state.loading" class="hint">正在读取本人记录…</text>
  <text v-if="state.result" class="success">{{ issueSubmissionMessage(state.result) }}</text>
  <view v-if="state.summary" class="panel">
   <text>订单编号：{{ state.summary.orderId }}</text><text>订单状态：{{ orderStatus(state.summary.orderStatus) }}</text>
   <text>当前身份：{{ state.summary.viewerRole==='BUYER'?'买家':'卖家' }}</text>
   <view v-if="state.summary.issue">
    <text class="heading">我的首次反馈</text><text>问题类型：{{ reasonLabel(state.summary.issue.reason) }}</text>
    <text class="description">{{ state.summary.issue.description }}</text><text class="hint">提交时间：{{ state.summary.issue.createdAt.replace('T',' ') }}</text>
    <view v-if="state.summary.issue.resolution" class="result">
     <text class="heading">平台处理结果：{{ issueDecisionLabel(state.summary.issue.resolution.decision) }}</text>
     <text class="description">{{ state.summary.issue.resolution.explanation }}</text>
     <text class="hint">处理时间：{{ state.summary.issue.resolution.createdAt.replace('T',' ') }}</text>
    </view>
    <text v-else class="hint">处理状态：待处理</text>
   </view>
   <text v-else-if="!state.summary.canSubmit" class="hint">已模拟付款、已交付或已完成订单可提交交易问题。当前暂无本人记录。</text>
   <text v-else class="hint">当前暂无本人记录。</text>
  </view>
  <view v-if="state.summary?.canSubmit" class="panel">
   <text>问题类型</text><picker :range="reasonLabels" :value="reasonIndex" :disabled="busy" @change="choose"><view class="picker">{{ reasonLabels[reasonIndex] }} ▾</view></picker>
   <textarea v-model="description" :maxlength="-1" :disabled="busy" class="field" placeholder="说明发生了什么、交接情况和你的诉求，10至1000个字符" />
   <text class="hint">{{ Array.from(description).length }} / 1000 个字符</text>
   <button type="primary" :disabled="busy" @click="submit">{{ state.saving?'正在确认或提交…':'提交交易问题' }}</button>
  </view>
  <button v-if="currentUser && id" :disabled="busy" @click="openRefund">退款申请与卖家处理进度</button>
  <button v-if="currentUser&&!routeError" :disabled="busy" @click="manager.refresh()">重新加载本人记录</button>
  <button :disabled="busy" @click="back">返回上一页</button>
 </view>
</template>
<script setup>
import {ref,computed,watch} from 'vue'
import {onLoad,onShow,onHide,onUnload} from '@dcloudio/uni-app'
import {currentUser,getToken} from '@/utils/session.js'
import {getOrderIssue,createOrderIssue} from '@/api/order.js'
import {orderId,orderStatus,orderPath} from '@/utils/order.js'
import {issueReasons,createOrderIssueManager,issueSubmissionMessage,issueDecisionLabel} from '@/utils/order-issues.mjs'
const id=ref(0),routeError=ref(''),reasonIndex=ref(0),description=ref(''),reasonLabels=issueReasons.map(item=>item.label)
const state=ref({summary:null,loading:false,saving:false,error:'',result:null,uncertain:false}),busy=computed(()=>state.value.loading||state.value.saving)
let active=false
const manager=createOrderIssueManager({identity:()=>({orderId:id.value,userId:currentUser.value?.id,token:getToken()}),load:getOrderIssue,create:createOrderIssue,notify:value=>{state.value=value},
 confirm:reason=>new Promise((resolve,reject)=>uni.showModal({title:'确认提交交易问题',content:`订单 #${id.value}；类型：${reasonLabel(reason)}。提交后可查看首次记录，暂不支持修改；不会改变订单状态或触发退款。确认提交？`,confirmText:'确认提交',cancelText:'取消',success:r=>resolve(r.confirm===true),fail:()=>reject(new Error('无法显示确认窗口'))}))})
function reasonLabel(reason){return issueReasons.find(item=>item.value===reason)?.label||'类型未知'}
function choose(event){const value=Number(event?.detail?.value);if(Number.isInteger(value)&&value>=0&&value<issueReasons.length)reasonIndex.value=value}
async function submit(){if(await manager.submit({reason:issueReasons[reasonIndex.value].value,description:description.value})){description.value='';reasonIndex.value=0}}
function reset(){manager.reset();description.value='';reasonIndex.value=0}
function openRefund(){uni.navigateTo({url:`/pages/order/refund?id=${id.value}`})}
function login(){uni.navigateTo({url:'/pages/user/login'})}
function back(){uni.navigateBack({fail:()=>uni.redirectTo({url:id.value?orderPath(id.value):'/pages/order/list'})})}
watch(()=>[currentUser.value?.id,getToken()],()=>{reset();if(active&&id.value)manager.refresh()},{flush:'sync'})
onLoad(options=>{try{id.value=orderId(options?.id)}catch(error){routeError.value=error.message}})
onShow(()=>{active=true;if(id.value)manager.refresh()})
onHide(()=>{active=false;reset()})
onUnload(()=>{active=false;description.value='';manager.dispose()})
</script>
<style scoped>
.page{padding:30rpx;min-height:100vh;background:#f5f7fa;box-sizing:border-box}.title{display:block;font-size:36rpx;font-weight:bold}.hint{display:block;color:#64748b;line-height:1.6;margin:20rpx 0}.error{display:block;color:#b42318;margin:20rpx 0}.success{display:block;color:#137333;margin:20rpx 0}.panel{background:#fff;padding:28rpx;border-radius:16rpx;margin:24rpx 0}.panel text{display:block;margin:16rpx 0}.heading{font-weight:bold}.description{white-space:pre-wrap;overflow-wrap:anywhere}.picker{padding:24rpx;border:1px solid #d9dfe8}.field{width:100%;min-height:220rpx;border:1px solid #d9dfe8;padding:16rpx;box-sizing:border-box;margin-top:20rpx}button{margin-top:24rpx}
</style>

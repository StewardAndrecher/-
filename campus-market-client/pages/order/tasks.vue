<template>
 <view class="page">
  <text class="title">我的交易待办</text><text class="hint">买入和卖出分别统计，仅你本人可见。点击进入对应列表，再核对订单当前状态。</text>
  <button v-if="!currentUser" @click="login">前往登录</button>
  <text v-if="state.loading" class="hint">正在读取交易待办…</text><text v-if="state.error" class="error">{{ state.error }}</text>
  <view v-if="state.data">
   <view class="panel"><text class="heading">我买到的</text>
    <button :disabled="blocked||!state.data.buyer.pendingPayment" @click="manager.open('BUYER_PAYMENT')">待付款订单：{{ state.data.buyer.pendingPayment }}笔</button>
    <text class="hint">其中可模拟付款{{ state.data.buyer.payable }}笔，已到期仍待关闭{{ state.data.buyer.overduePendingPayment }}笔。</text>
    <text v-if="state.data.buyer.overduePendingPayment" class="warning">已到期订单不能继续付款，进入订单查看最新结果。本页不会关闭订单。</text>
    <button :disabled="blocked||!state.data.buyer.toConfirm" @click="manager.open('BUYER_CONFIRM')">待确认收货：{{ state.data.buyer.toConfirm }}笔</button>
    <button :disabled="blocked||!state.data.buyer.toReview" @click="manager.open('BUYER_REVIEW')">买入待评价：{{ state.data.buyer.toReview }}笔</button>
   </view>
   <view class="panel"><text class="heading">我卖出的</text>
    <button :disabled="blocked||!state.data.seller.toDeliver" @click="manager.open('SELLER_DELIVER')">待交付商品：{{ state.data.seller.toDeliver }}笔</button>
    <button :disabled="blocked||!state.data.seller.toReview" @click="manager.open('SELLER_REVIEW')">卖出待评价：{{ state.data.seller.toReview }}笔</button>
   </view>
   <text class="hint">采样时间（服务器时间）：{{ state.data.sampledAt.replace('T',' ') }}。待评价只看你是否已评价，与对方是否评价无关。</text>
   <text class="hint">待付款列表包含上述已到期订单，数量可能随交易进度变化；本项目使用模拟付款。</text>
  </view>
  <button :disabled="blocked" @click="manager.refresh()">刷新交易待办</button><button :disabled="blocked" @click="orders">查看全部订单</button>
 </view>
</template>
<script setup>
import {ref,computed,watch} from 'vue'
import {onShow,onHide,onUnload} from '@dcloudio/uni-app'
import {currentUser,getToken} from '@/utils/session.js'
import {getTradeTasks} from '@/api/order.js'
import {createTradeTaskManager} from '@/utils/trade-tasks.mjs'
const state=ref({data:null,loading:false,opening:false,error:''}),blocked=computed(()=>state.value.loading||state.value.opening)
let active=false
const manager=createTradeTaskManager({identity:()=>({userId:currentUser.value?.id,token:getToken()}),load:getTradeTasks,navigate:url=>new Promise((resolve,reject)=>uni.navigateTo({url,success:resolve,fail:reject})),notify:value=>{state.value=value}})
function login(){uni.navigateTo({url:'/pages/user/login'})}
function orders(){if(active&&!blocked.value)uni.navigateTo({url:'/pages/order/list'})}
watch(()=>[currentUser.value?.id,getToken()],()=>{manager.reset();if(active)manager.refresh()},{flush:'sync'})
onShow(()=>{active=true;manager.refresh()})
onHide(()=>{active=false;manager.reset()})
onUnload(()=>{active=false;manager.dispose()})
</script>
<style scoped>
.page{padding:30rpx;min-height:100vh;background:#f5f7fa;box-sizing:border-box}.title,.heading{display:block;font-weight:bold;font-size:36rpx}.heading{font-size:30rpx}.panel{padding:24rpx;margin:24rpx 0;background:white;border-radius:16rpx}.hint{display:block;color:#64748b;line-height:1.6;margin:20rpx 0}.error,.warning{display:block;color:#b42318;margin:20rpx 0}button{margin-top:20rpx}
</style>

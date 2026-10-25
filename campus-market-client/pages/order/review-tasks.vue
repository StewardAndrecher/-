<template>
 <view class="page">
  <text class="title">我的交易评价</text>
  <text class="hint">仅统计当前身份下已完成的订单；是否已评价以你自己的提交记录为准。</text>
  <view class="tabs"><button v-for="item in roles" :key="item.value" :type="role===item.value?'primary':'default'" :disabled="state.loading" @click="choose(item.value,status)">{{ item.label }}</button></view>
  <view class="tabs"><button v-for="item in statuses" :key="item.value" :type="status===item.value?'primary':'default'" :disabled="state.loading" @click="choose(role,item.value)">{{ item.label }}</button></view>
  <text v-if="routeError||state.error" class="error">{{ routeError||state.error }}</text>
  <button v-if="!currentUser" @click="login">前往登录</button>
  <text v-if="state.loading" class="hint">正在加载评价清单…</text>
  <view v-if="state.page">
   <text class="summary">已完成{{ state.page.summary.total }}笔：待评价{{ state.page.summary.pending }}笔，已评价{{ state.page.summary.reviewed }}笔</text>
   <text v-if="!state.page.items.length" class="hint">{{ status==='PENDING'?'当前身份暂无待评价订单':'当前身份暂无已评价订单' }}</text>
   <view v-for="item in state.page.items" :key="item.orderId" class="card">
    <text>{{ item.productTitleSnapshot }}</text><text>订单{{ item.orderId }} · ¥{{ formatOrderMoney(item.priceInCentsSnapshot) }}</text>
    <text class="hint">交易完成：{{ item.completedAt.replace('T',' ') }}</text>
    <text v-if="item.reviewedAt" class="hint">我的评价提交于：{{ item.reviewedAt.replace('T',' ') }}</text>
    <button :disabled="state.loading||state.openingId!==null" @click="manager.open(item.orderId)">{{ status==='PENDING'?'去评价':'查看评价' }}</button>
   </view>
   <view class="tabs"><button :disabled="state.loading||state.page.page<=1" @click="load(state.page.page-1)">上一页</button><text>第{{ state.page.page }}/{{ state.page.totalPages }}页</text><button :disabled="state.loading||state.page.page>=state.page.totalPages" @click="load(state.page.page+1)">下一页</button></view>
  </view>
  <button :disabled="state.loading||!!routeError" @click="load(state.page?.page||1)">刷新评价清单</button>
  <button :disabled="state.loading" @click="orders">我的订单</button>
 </view>
</template>
<script setup>
import {ref,watch} from 'vue'
import {onLoad,onShow,onHide,onUnload} from '@dcloudio/uni-app'
import {currentUser,getToken} from '@/utils/session.js'
import {formatOrderMoney} from '@/utils/order.js'
import {getOrderReviewTasks} from '@/api/order.js'
import {reviewTaskQuery,createReviewTaskManager} from '@/utils/order-review-tasks.mjs'
const roles=[{value:'BUYER',label:'我买到的'},{value:'SELLER',label:'我卖出的'}],statuses=[{value:'PENDING',label:'待评价'},{value:'REVIEWED',label:'已评价'}]
const role=ref('BUYER'),status=ref('PENDING'),routeError=ref(''),state=ref({page:null,loading:false,openingId:null,error:''})
let active=false
const manager=createReviewTaskManager({identity:()=>({userId:currentUser.value?.id,token:getToken()}),load:getOrderReviewTasks,
 navigate:url=>new Promise((resolve,reject)=>uni.navigateTo({url,success:resolve,fail:reject})),notify:value=>{state.value=value}})
function load(page=1){if(active&&!routeError.value)return manager.refresh({role:role.value,status:status.value,page,size:10})}
function choose(nextRole,nextStatus){if(state.value.loading)return;role.value=nextRole;status.value=nextStatus;load(1)}
function login(){uni.navigateTo({url:'/pages/user/login'})}
function orders(){uni.navigateBack({fail:()=>uni.redirectTo({url:'/pages/order/list'})})}
watch(()=>[currentUser.value?.id,getToken()],()=>{manager.reset();role.value='BUYER';status.value='PENDING';if(active)load()},{flush:'sync'})
onLoad(options=>{try{role.value=reviewTaskQuery({role:options?.role??'BUYER'}).role}catch(error){routeError.value=error.message}})
onShow(()=>{active=true;load()})
onHide(()=>{active=false;manager.reset()})
onUnload(()=>{active=false;manager.dispose()})
</script>
<style scoped>
.page{padding:30rpx;min-height:100vh;background:#f5f7fa;box-sizing:border-box}.title{display:block;font-size:36rpx;font-weight:bold}.hint{display:block;color:#64748b;line-height:1.6;margin:18rpx 0}.summary{display:block;margin:24rpx 0}.tabs{display:flex;align-items:center;gap:20rpx;margin:20rpx 0}.tabs button{flex:1}.card{padding:24rpx;background:white;border-radius:16rpx;margin:24rpx 0}.card text{display:block;margin:12rpx 0}.error{display:block;color:#b42318;margin:20rpx 0}button{margin-top:20rpx}
</style>

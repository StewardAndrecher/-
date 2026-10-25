<template>
 <view class="page">
  <text class="title">我收到的交易评价</text>
  <text class="hint">仅本人可见：展示已完成订单中对方给你的评价，不会公开到卖家主页。</text>
  <view class="tabs"><button v-for="item in roles" :key="item.value" :type="role===item.value?'primary':'default'" :disabled="state.loading" @click="choose(item.value,rating)">{{ item.label }}</button></view>
  <view class="tabs"><button v-for="item in ratings" :key="item.value" :type="rating===item.value?'primary':'default'" :disabled="state.loading" @click="choose(role,item.value)">{{ item.label }}</button></view>
  <text v-if="routeError||state.error" class="error">{{ routeError||state.error }}</text>
  <button v-if="!currentUser" @click="login">前往登录</button>
  <text v-if="state.loading" class="hint">正在加载收到的评价…</text>
  <view v-if="state.page">
   <text class="summary">收到{{ state.page.summary.total }}条：4—5分{{ state.page.summary.good }}条，3分{{ state.page.summary.neutral }}条，1—2分{{ state.page.summary.low }}条</text>
   <text v-if="!state.page.items.length" class="hint">当前身份和评分条件下暂无收到的评价</text>
   <view v-for="item in state.page.items" :key="item.orderId" class="card">
    <text>{{ item.productTitleSnapshot }}</text><text>订单{{ item.orderId }} · 对方评分：{{ item.rating }} / 5</text><text class="content">{{ item.content || '对方未填写文字评价' }}</text>
    <text class="hint">交易完成：{{ item.completedAt.replace('T',' ') }}</text>
    <text v-if="item.reviewedAt" class="hint">对方评价提交于：{{ item.reviewedAt.replace('T',' ') }}</text>
    <button :disabled="state.loading||state.openingId!==null" @click="manager.open(item.orderId)">查看该订单评价</button>
   </view>
   <view class="tabs"><button :disabled="state.loading||state.page.page<=1" @click="load(state.page.page-1)">上一页</button><text>第{{ state.page.page }}/{{ state.page.totalPages }}页</text><button :disabled="state.loading||state.page.page>=state.page.totalPages" @click="load(state.page.page+1)">下一页</button></view>
  </view>
  <button :disabled="state.loading||!!routeError" @click="load(currentPage)">刷新收到的评价</button>
  <button :disabled="state.loading" @click="orders">我的订单</button>
 </view>
</template>
<script setup>
import {ref,watch} from 'vue'
import {onLoad,onShow,onHide,onUnload} from '@dcloudio/uni-app'
import {currentUser,getToken} from '@/utils/session.js'
import {getReceivedReviews} from '@/api/received-review.js'
import {receivedReviewQuery,createReceivedReviewManager} from '@/utils/received-reviews.mjs'
const roles=[{value:'BUYER',label:'我买到的'},{value:'SELLER',label:'我卖出的'}],ratings=[{value:'ALL',label:'全部'},{value:'GOOD',label:'4—5分'},{value:'NEUTRAL',label:'3分'},{value:'LOW',label:'1—2分'}]
const role=ref('BUYER'),rating=ref('ALL'),currentPage=ref(1),routeError=ref(''),state=ref({page:null,loading:false,openingId:null,error:''})
let active=false
const manager=createReceivedReviewManager({identity:()=>({userId:currentUser.value?.id,token:getToken()}),load:getReceivedReviews,
 navigate:url=>new Promise((resolve,reject)=>uni.navigateTo({url,success:resolve,fail:reject})),notify:value=>{state.value=value;if(value.page)currentPage.value=value.page.page}})
function load(page=currentPage.value){if(active&&!routeError.value)return manager.refresh({role:role.value,rating:rating.value,page,size:10})}
function choose(nextRole,nextRating){if(state.value.loading)return;role.value=nextRole;rating.value=nextRating;load(1)}
function login(){uni.navigateTo({url:'/pages/user/login'})}
function orders(){uni.navigateBack({fail:()=>uni.redirectTo({url:'/pages/order/list'})})}
watch(()=>[currentUser.value?.id,getToken()],()=>{manager.reset();role.value='BUYER';rating.value='ALL';currentPage.value=1;if(active)load()},{flush:'sync'})
onLoad(options=>{try{role.value=receivedReviewQuery({role:options?.role??'BUYER'}).role}catch(error){routeError.value=error.message}})
onShow(()=>{active=true;load()})
onHide(()=>{active=false;manager.reset()})
onUnload(()=>{active=false;manager.dispose()})
</script>
<style scoped>
.page{padding:30rpx;min-height:100vh;background:#f5f7fa;box-sizing:border-box}.title{display:block;font-size:36rpx;font-weight:bold}.hint{display:block;color:#64748b;line-height:1.6;margin:18rpx 0}.summary{display:block;margin:24rpx 0}.tabs{display:flex;align-items:center;gap:20rpx;margin:20rpx 0}.tabs button{flex:1}.card{padding:24rpx;background:white;border-radius:16rpx;margin:24rpx 0}.card text{display:block;margin:12rpx 0}.content{white-space:pre-wrap;overflow-wrap:anywhere}.error{display:block;color:#b42318;margin:20rpx 0}button{margin-top:20rpx}
</style>

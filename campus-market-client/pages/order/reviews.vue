<template>
 <view class="page">
  <text class="title">交易评价</text>
  <text class="hint">仅订单双方可见。完成交易后，买家和卖家可各评价一次；提交后暂不支持修改或删除。新的评价会提醒对方，通知不展示评分和文字。</text>
  <text v-if="routeError" class="error">{{ routeError }}</text>
  <text v-if="!currentUser" class="hint">请先登录后查看订单评价</text>
  <button v-if="!currentUser" @click="openLogin">前往登录</button>
  <text v-if="state.loading" class="hint">正在读取评价…</text>
  <text v-if="state.error" class="error">{{ state.error }}</text>
  <text v-if="state.result" class="message">{{ reviewSubmissionMessage(state.result) }}</text>
  <view v-if="state.summary" class="panel">
   <text>订单编号：{{ state.summary.orderId }}</text>
   <text>订单状态：{{ orderStatus(state.summary.orderStatus) }}</text>
   <text>当前身份：{{ state.summary.viewerRole==='BUYER'?'买家':'卖家' }}</text>
   <text v-if="state.summary.orderStatus!=='COMPLETED'" class="hint">交易完成后才能评价。</text>
   <text v-if="state.summary.orderStatus==='COMPLETED' && !state.summary.canReview" class="hint">你已评价，可查看双方记录。</text>
   <text v-if="!state.summary.reviews.length" class="hint">暂无评价</text>
   <view v-for="item in state.summary.reviews" :key="item.authorRole" class="review">
    <text>{{ item.authorRole==='BUYER'?'买家':'卖家' }}评价{{ item.mine?'（我）':'' }}：{{ item.rating }} / 5分</text>
    <text class="content">{{ item.content || '未填写文字评价' }}</text>
    <text class="hint">{{ item.createdAt.replace('T',' ') }}</text>
   </view>
  </view>
  <view v-if="state.summary?.canReview" class="panel">
   <text>为本次交易评分</text>
   <picker :range="ratingLabels" :value="rating-1" :disabled="busy" @change="changeRating"><view class="picker">{{ rating }}分 ▾</view></picker>
   <textarea v-model="content" :maxlength="-1" :disabled="busy" class="field" placeholder="评价本次交易，最多500个字符，可不填" />
   <text class="hint">{{ Array.from(content).length }} / 500 个字符；请勿填写联系方式等个人信息。</text>
   <button type="primary" :disabled="busy" @click="submit">{{ state.saving?'正在确认或提交…':'提交评价' }}</button>
  </view>
  <button v-if="currentUser && !routeError" :disabled="busy" @click="manager.refresh()">重新加载评价</button>
  <button :disabled="busy" @click="backOrder">返回上一页</button>
 </view>
</template>
<script setup>
import {ref,computed,watch} from 'vue'
import {onLoad,onShow,onHide,onUnload} from '@dcloudio/uni-app'
import {currentUser,getToken} from '@/utils/session.js'
import {getOrderReviews,createOrderReview} from '@/api/order.js'
import {orderId,orderStatus,orderPath} from '@/utils/order.js'
import {createReviewManager,reviewSubmissionMessage} from '@/utils/order-reviews.mjs'
const id=ref(0),routeError=ref(''),rating=ref(5),content=ref('')
const ratingLabels=['1分','2分','3分','4分','5分'],state=ref({summary:null,loading:false,saving:false,error:'',result:null,uncertain:false})
const busy=computed(()=>state.value.loading||state.value.saving)
let active=false
const manager=createReviewManager({identity:()=>({orderId:id.value,userId:currentUser.value?.id,token:getToken()}),load:getOrderReviews,create:createOrderReview,notify:value=>{state.value=value},
 confirm:score=>new Promise((resolve,reject)=>uni.showModal({title:'提交交易评价',content:`本次评分${score}分，评价仅订单双方可见，提交后暂不支持修改或删除。确认提交？`,confirmText:'确认提交',cancelText:'取消',success:r=>resolve(r.confirm===true),fail:()=>reject(new Error('无法显示确认窗口'))}))})
async function submit(){if(await manager.submit({rating:rating.value,content:content.value})){content.value='';rating.value=5}}
function changeRating(event){const n=Number(event.detail.value);if(Number.isInteger(n)&&n>=0&&n<5)rating.value=n+1}
function reset(){manager.reset();content.value='';rating.value=5}
function openLogin(){uni.navigateTo({url:'/pages/user/login'})}
function backOrder(){uni.navigateBack({fail:()=>{if(id.value)uni.redirectTo({url:orderPath(id.value)});else uni.redirectTo({url:'/pages/order/list'})}})}
watch(()=>[currentUser.value?.id,getToken()],()=>{reset();if(active&&id.value)manager.refresh()},{flush:'sync'})
onLoad(options=>{try{id.value=orderId(options?.id)}catch(e){routeError.value=e.message}})
onShow(()=>{active=true;if(id.value)manager.refresh()})
onHide(()=>{active=false;reset()})
onUnload(()=>{active=false;content.value='';manager.dispose()})
</script>
<style scoped>
.page{padding:30rpx;min-height:100vh;background:#f5f7fa;box-sizing:border-box}.title{display:block;font-size:36rpx;font-weight:bold}.panel{padding:28rpx;background:white;border-radius:16rpx;margin:24rpx 0}.panel text{display:block;margin:16rpx 0}.hint{display:block;color:#64748b;line-height:1.6;margin:20rpx 0}.error{display:block;color:#b42318;margin:20rpx 0}.message{display:block;color:#137333;margin:20rpx 0}.review{border-top:1px solid #e2e8f0;margin-top:20rpx;padding-top:12rpx}.content{white-space:pre-wrap;overflow-wrap:anywhere}.field{width:100%;min-height:180rpx;border:1px solid #d9dfe8;margin-top:20rpx;padding:16rpx;box-sizing:border-box}.picker{padding:24rpx;border:1px solid #d9dfe8;margin-top:16rpx}button{margin-top:24rpx}
</style>

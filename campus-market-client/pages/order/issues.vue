<template>
 <view class="page">
  <text class="title">我的交易反馈</text>
  <text class="hint">仅显示你在当前交易身份下提交过的反馈；待处理和已处理均以本人的记录为准。</text>
  <view class="tabs"><button v-for="item in roles" :key="item.value" :type="role===item.value?'primary':'default'" :disabled="state.loading" @click="choose(item.value,status)">{{ item.label }}</button></view>
  <view class="tabs"><button v-for="item in statuses" :key="item.value" :type="status===item.value?'primary':'default'" :disabled="state.loading" @click="choose(role,item.value)">{{ item.label }}</button></view>
  <text v-if="routeError||state.error" class="error">{{ routeError||state.error }}</text>
  <button v-if="!currentUser" @click="login">前往登录</button>
  <text v-if="state.loading" class="hint">正在加载交易反馈清单…</text>
  <view v-if="state.page">
   <text class="summary">本人反馈{{ state.page.summary.total }}条：待处理{{ state.page.summary.open }}条，已处理{{ state.page.summary.resolved }}条</text>
   <text v-if="!state.page.items.length" class="hint">{{ status==='OPEN'?'当前身份暂无待处理反馈':status==='RESOLVED'?'当前身份暂无已处理反馈':'当前身份暂无已提交的反馈' }}</text>
   <view v-for="item in state.page.items" :key="item.orderId" class="card">
    <text>{{ item.productTitleSnapshot }}</text><text>订单{{ item.orderId }} · {{ reasonLabel(item.reason) }}</text>
    <text class="hint">提交时间：{{ item.createdAt.replace('T',' ') }}</text>
    <text>{{ item.resolutionDecision?'处理结果：'+issueDecisionLabel(item.resolutionDecision):'处理状态：待处理' }}</text>
    <text v-if="item.resolvedAt" class="hint">处理时间：{{ item.resolvedAt.replace('T',' ') }}</text>
    <button :disabled="state.loading||state.openingId!==null" @click="manager.open(item.orderId)">查看本人反馈与结果</button>
   </view>
   <view class="tabs"><button :disabled="state.loading||state.page.page<=1" @click="load(state.page.page-1)">上一页</button><text>第{{ state.page.page }}/{{ state.page.totalPages }}页</text><button :disabled="state.loading||state.page.page>=state.page.totalPages" @click="load(state.page.page+1)">下一页</button></view>
  </view>
  <button :disabled="state.loading||!!routeError" @click="load(state.page?.page||1)">刷新交易反馈清单</button>
  <button :disabled="state.loading" @click="orders">我的订单</button>
 </view>
</template>
<script setup>
import {ref,watch} from 'vue'
import {onLoad,onShow,onHide,onUnload} from '@dcloudio/uni-app'
import {currentUser,getToken} from '@/utils/session.js'
import {issueReasons,issueDecisionLabel} from '@/utils/order-issues.mjs'
import {getOwnOrderIssues} from '@/api/order.js'
import {issueListQuery,createIssueListManager} from '@/utils/order-issue-list.mjs'
const roles=[{value:'BUYER',label:'我买到的'},{value:'SELLER',label:'我卖出的'}],statuses=[{value:'ALL',label:'全部'},{value:'OPEN',label:'待处理'},{value:'RESOLVED',label:'已处理'}]
const role=ref('BUYER'),status=ref('ALL'),routeError=ref(''),state=ref({page:null,loading:false,openingId:null,error:''})
let active=false
const manager=createIssueListManager({identity:()=>({userId:currentUser.value?.id,token:getToken()}),load:getOwnOrderIssues,
 navigate:url=>new Promise((resolve,reject)=>uni.navigateTo({url,success:resolve,fail:reject})),notify:value=>{state.value=value}})
function load(page=1){if(active&&!routeError.value)return manager.refresh({role:role.value,status:status.value,page,size:10})}
function choose(nextRole,nextStatus){if(state.value.loading)return;role.value=nextRole;status.value=nextStatus;load(1)}
function login(){uni.navigateTo({url:'/pages/user/login'})}
function reasonLabel(reason){return issueReasons.find(item=>item.value===reason)?.label||'类型未知'}
function orders(){uni.redirectTo({url:'/pages/order/list'})}
watch(()=>[currentUser.value?.id,getToken()],()=>{manager.reset();status.value='ALL';if(active)load()},{flush:'sync'})
onLoad(options=>{try{role.value=issueListQuery({role:options?.role??'BUYER'}).role}catch(error){routeError.value=error.message}})
onShow(()=>{active=true;load()})
onHide(()=>{active=false;manager.reset()})
onUnload(()=>{active=false;manager.dispose()})
</script>
<style scoped>
.page{padding:30rpx;min-height:100vh;background:#f5f7fa;box-sizing:border-box}.title{display:block;font-size:36rpx;font-weight:bold}.hint{display:block;color:#64748b;line-height:1.6;margin:18rpx 0}.summary{display:block;margin:24rpx 0}.tabs{display:flex;align-items:center;gap:20rpx;margin:20rpx 0}.tabs button{flex:1}.card{padding:24rpx;background:white;border-radius:16rpx;margin:24rpx 0}.card text{display:block;margin:12rpx 0}.error{display:block;color:#b42318;margin:20rpx 0}button{margin-top:20rpx}
</style>

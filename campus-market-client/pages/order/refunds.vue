<template>
 <view class="service-page">
  <view class="page-heading"><text class="eyebrow">SERVICE CENTER · 售后中心</text><text class="page-title">我的退款售后</text><text class="page-subtitle">查看申请进度，及时处理每一份答复。</text></view>
  <view class="surface"><view class="segmented"><button :class="{selected:query.role==='BUYER'}" :disabled="busy" @click="change({role:'BUYER'})">我申请的</button><button :class="{selected:query.role==='SELLER'}" :disabled="busy" @click="change({role:'SELLER'})">待我处理 / 我收到的</button></view><picker :range="refundStates" range-key="name" :value="statusIndex" :disabled="busy" @change="selectStatus"><view class="select">{{ refundStates[statusIndex].name }} <text>⌄</text></view></picker><text class="muted">申请处理记录不代表资金已经退款；演示订单没有真实扣款。</text></view>
  <text v-if="state.error" class="error">{{ state.error }}</text><text v-if="state.loading" class="muted">正在读取售后清单…</text>
  <view v-if="!currentUser" class="surface empty"><text>请先登录后查看售后记录</text><button class="primary" @click="login">前往登录</button></view>
  <view v-if="state.data && !state.data.items.length" class="surface empty"><text class="section-title">这里还没有申请</text><text class="muted">{{ query.role === 'BUYER' ? '如遇商品或交接问题，可从订单详情发起申请。' : '买家提交售后申请后，你可以在这里查看和答复。' }}</text></view>
  <view v-for="item in state.data?.items || []" :key="item.refundId" class="surface refund-list-card"><view class="case-heading"><text class="section-title">{{ item.productTitle }}</text><text class="status" :class="item.status.toLowerCase()">{{ refundStateName(item.status) }}</text></view><text class="muted">订单 #{{ item.orderId }} · 申请 #{{ item.refundId }}</text><text class="case-amount">¥{{ (item.amountInCents / 100).toFixed(2) }}</text><view class="case-heading"><text class="muted">{{ item.createdAt.replace('T',' ') }}</text><button class="inline-link" :disabled="busy" @click="open(item.orderId)">查看进度 →</button></view></view>
  <view v-if="state.data" class="pagination"><button class="secondary" :disabled="busy || state.data.page <= 1" @click="change({page:state.data.page-1})">上一页</button><text>{{ state.data.page }} / {{ state.data.totalPages }} · 共 {{ state.data.total }} 条</text><button class="secondary" :disabled="busy || state.data.page >= state.data.totalPages" @click="change({page:state.data.page+1})">下一页</button></view>
  <view class="page-actions"><button class="secondary" :disabled="busy" @click="manager.refresh()">刷新清单</button><button class="text-button" :disabled="busy" @click="back">返回订单</button></view>
 </view>
</template>
<script setup>
import { ref,computed,watch } from 'vue'
import { onShow,onHide,onUnload } from '@dcloudio/uni-app'
import { currentUser,getToken } from '@/utils/session.js'
import { getOwnRefunds } from '@/api/refund.js'
import { createOwnedResource } from '@/utils/owned-resource.mjs'
import { refundStates,refundStateName,refundPath,validateRefundPage } from '@/utils/refunds.mjs'
const query=ref({role:'BUYER',status:'ALL',page:1,size:10}),state=ref({data:null,loading:false,saving:false,error:''})
const busy=computed(()=>state.value.loading||state.value.saving),statusIndex=computed(()=>Math.max(0,refundStates.findIndex(item=>item.code===query.value.status)))
let active=false
const manager=createOwnedResource({identity:()=>({userId:currentUser.value?.id,token:getToken(),...query.value}),load:owner=>getOwnRefunds({role:owner.role,status:owner.status,page:owner.page,size:owner.size}),validate:(data,owner)=>validateRefundPage(data,owner,owner),notify:value=>{state.value=value}})
function change(value){if(busy.value)return;query.value={...query.value,page:1,...value};manager.refresh()}
function selectStatus(event){const item=refundStates[Number(event.detail.value)];if(item)change({status:item.code})}
function open(id){if(!busy.value)uni.navigateTo({url:refundPath(id)})}
function login(){uni.navigateTo({url:'/pages/user/login'})}
function back(){uni.navigateBack({fail:()=>uni.redirectTo({url:'/pages/order/list'})})}
watch(()=>[currentUser.value?.id,getToken()],()=>{manager.hide();query.value={role:'BUYER',status:'ALL',page:1,size:10};if(active)manager.show()},{flush:'sync'})
onShow(()=>{active=true;manager.show()});onHide(()=>{active=false;manager.hide()});onUnload(()=>{active=false;manager.dispose()})
</script>
<style scoped src="@/styles/service-pages.css"></style>

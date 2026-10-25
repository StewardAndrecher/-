<template>
  <view class="page">
    <text class="title">管理员订单详情</text>
    <text class="hint">核对下单快照与进度记录；这里只展示操作角色，不展示参与者身份。</text>
    <text v-if="!currentUser" class="hint">请先登录管理员账号</text>
    <button v-if="!currentUser" @click="openLogin">前往登录</button>
    <text v-if="state.busy" class="hint">正在读取订单详情…</text>
    <view v-if="state.data">
      <view class="panel">
        <text class="heading">订单 #{{ state.data.order.id }} · {{ state.data.order.productTitleSnapshot }}</text>
        <text>状态：{{ orderStatus(state.data.order.status) }}</text>
        <text>下单金额快照：¥{{ formatOrderMoney(state.data.order.priceInCentsSnapshot) }}</text>
        <text>商品编号：{{ state.data.order.productId }}</text>
        <text v-for="field in fields" v-show="state.data.order[field.key]" :key="field.key">{{ field.label }}：{{ state.data.order[field.key] }}</text>
        <text class="hint">付款为教学模拟，不代表真实扣款或资金结算。读取本页不会推进或关闭订单。</text>
      </view>
      <view class="panel">
        <text class="heading">订单进度时间线</text>
        <view v-for="event in state.data.events" :key="event.sequence" class="event">
          <text>{{ event.sequence }}. {{ orderEventLabel(event.action) }}</text>
          <text>操作方：{{ roleLabel(event.actorRole) }} · {{ orderStatus(event.toStatus) }}</text>
          <text>事件发生：{{ event.occurredAt }}</text>
          <text>记录入库：{{ event.recordedAt }}</text>
          <text v-if="event.action==='HISTORY_SNAPSHOT'" class="hint">仅表示迁移时观察到的状态，不能还原此前操作人或完整历史。</text>
        </view>
      </view>
    </view>
    <text v-if="parameterError" class="error">{{ parameterError }}</text>
    <text v-else-if="state.status===403" class="error">当前账号没有管理权限，请联系平台管理员。</text>
    <text v-else-if="state.error" class="error">{{ state.error }}</text>
    <button v-if="currentUser && id" :disabled="state.busy" @click="refresh">重新加载订单详情</button>
    <button @click="goList">返回订单列表</button>
  </view>
</template>
<script setup>
import { ref, watch } from 'vue'
import { onLoad, onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { currentUser, getToken } from '@/utils/session.js'
import { getAdminOrderDetail } from '@/api/admin.js'
import { orderId, orderStatus, formatOrderMoney } from '@/utils/order.js'
import { orderEventLabel } from '@/utils/order-timeline.js'
import { createAdminOrderDetail } from '@/utils/admin-order-detail.mjs'
const id=ref(0),active=ref(false),parameterError=ref(''),state=ref({data:null,busy:false,error:'',status:0})
const fields=[{key:'createdAt',label:'创建时间'},{key:'paymentDueAt',label:'付款截止'},{key:'paidAt',label:'模拟付款时间'},{key:'deliveredAt',label:'卖家确认交付'},{key:'completedAt',label:'买家确认收货'},{key:'cancelledAt',label:'买家取消时间'},{key:'closedAt',label:'超时关闭时间'}]
const loader=createAdminOrderDetail({load:getAdminOrderDetail,identity:()=>({userId:currentUser.value?.id,token:getToken()}),notify:value=>{state.value=value}})
function refresh(){if(id.value)return loader.refresh(id.value,active.value)}
watch(()=>[currentUser.value?.id,getToken()],refresh,{flush:'sync'})
onLoad(options=>{try{id.value=orderId(options?.id)}catch(e){parameterError.value=e.message}})
onShow(()=>{active.value=true;refresh()})
onHide(()=>{active.value=false;if(id.value)loader.refresh(id.value,false)})
onUnload(()=>{active.value=false;loader.dispose()})
function openLogin(){uni.navigateTo({url:'/pages/user/login'})}
function goList(){uni.navigateBack({fail:()=>uni.redirectTo({url:'/pages/admin/order-list'})})}
function roleLabel(role){return {BUYER:'买家',SELLER:'卖家',SYSTEM:'系统'}[role]||'未知角色'}
</script>
<style scoped>
.page{padding:32rpx;min-height:100vh;background:#f5f7fa}.title{display:block;font-size:40rpx;font-weight:bold;margin-bottom:20rpx}
.panel{padding:28rpx;margin:20rpx 0;background:white;border-radius:16rpx}.panel text{display:block;margin:14rpx 0}.heading{font-size:30rpx;font-weight:bold}
.hint{display:block;padding:16rpx 0;color:#64748b;line-height:1.6}.event{padding:20rpx 0;border-bottom:1px solid #e2e8f0}button{margin-top:20rpx}.error{display:block;color:#b42318;padding:20rpx 0}
</style>

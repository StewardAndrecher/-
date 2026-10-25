<template>
  <view class="page">
    <text class="title">管理员订单检索</text>
    <text class="hint">按状态或订单编号定位交易。只展示订单快照，不提供关闭、付款或收货操作。</text>
    <text v-if="!currentUser" class="hint">请先登录管理员账号</text>
    <button v-if="!currentUser" @click="openLogin">前往登录</button>
    <view v-if="currentUser" class="panel">
      <picker :range="adminOrderFilters" range-key="label" :value="filterIndex" :disabled="state.busy" @change="changeFilter">
        <view class="filter">状态：{{ adminOrderFilters[filterIndex].label }} ▾</view>
      </picker>
      <input v-model="orderIdInput" :disabled="state.busy" type="text" maxlength="16" placeholder="订单编号（可不填，精确查找）" />
      <text v-if="inputError" class="error">{{ inputError }}</text>
      <button :disabled="state.busy" @click="search">查询订单</button>
      <button :disabled="state.busy" @click="reset">重置条件</button>
    </view>
    <text v-if="state.busy" class="hint">正在查询订单…</text>
    <view v-if="state.data">
      <text class="hint">查询结果：{{ state.data.total }} 笔；{{ adminOrderStatus(state.data.status) }}；编号：{{ state.data.orderId ?? '不限' }}</text>
      <text class="hint">到期判断基准（服务器时间）：{{ state.data.sampledAt }}</text>
      <text v-if="state.data.records.length === 0" class="hint">没有符合条件的订单</text>
      <view v-for="item in state.data.records" :key="item.id" class="panel">
        <text class="heading">订单 #{{ item.id }} · {{ item.productTitleSnapshot }}</text>
        <text>{{ adminOrderStatus(item.status) }}</text>
        <text>下单金额快照：¥{{ adminOrderMoney(item.priceInCentsSnapshot) }}</text>
        <text>商品编号：{{ item.productId }}</text>
        <text>创建时间：{{ item.createdAt }}</text>
        <text>付款截止：{{ item.paymentDueAt }}</text>
        <button :disabled="state.busy" @click="openDetail(item.id)">查看详情与进度</button>
      </view>
      <view class="pagination">
        <button :disabled="state.busy || state.data.page <= 1" @click="turn(-1)">上一页</button>
        <text>第 {{ state.data.page }} / {{ state.data.totalPages }} 页</text>
        <button :disabled="state.busy || state.data.page >= state.data.totalPages" @click="turn(1)">下一页</button>
      </view>
    </view>
    <text v-if="state.status === 403" class="error">当前账号没有管理权限，请联系平台管理员。</text>
    <text v-else-if="state.error" class="error">{{ state.error }}</text>
    <text class="hint">到期待付款按本次基准时间判断，读取不关闭订单。统计概览与列表是独立采样，数字可能因订单流转而变化。</text>
    <button @click="goOverview">返回交易运营概览</button>
  </view>
</template>
<script setup>
import { ref, computed, watch } from 'vue'
import { onLoad, onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { currentUser, getToken } from '@/utils/session.js'
import { adminOrderDetailPath } from '@/utils/admin-order-detail.mjs'
import { listAdminOrders } from '@/api/admin.js'
import { adminOrderFilters, normalizeAdminOrderQuery, createAdminOrderList, adminOrderStatus, adminOrderMoney } from '@/utils/admin-orders.mjs'
const active=ref(false), state=ref({data:null,busy:false,error:'',status:0}), inputError=ref('')
const selectedStatus=ref('ALL'), orderIdInput=ref(''), query=ref(normalizeAdminOrderQuery())
const filterIndex=computed(()=>Math.max(0,adminOrderFilters.findIndex(f=>f.value===selectedStatus.value)))
const loader=createAdminOrderList({load:listAdminOrders,identity:()=>({userId:currentUser.value?.id,token:getToken()}),notify:value=>{state.value=value}})
async function refresh() { try { inputError.value='';await loader.refresh(query.value,active.value) } catch(e) { inputError.value=e.message } }
function search() {
  if(state.value.busy)return
  try { const next=normalizeAdminOrderQuery({status:selectedStatus.value,orderId:orderIdInput.value,page:1,size:10});query.value=next;refresh() }
  catch(e) { inputError.value=e.message }
}
function changeFilter(event) { if(state.value.busy)return;const i=Number(event.detail.value);if(!adminOrderFilters[i])return;selectedStatus.value=adminOrderFilters[i].value;search() }
function reset() { if(state.value.busy)return;selectedStatus.value='ALL';orderIdInput.value='';search() }
function turn(step) {
  if(state.value.busy||!state.value.data)return
  const d=state.value.data;query.value={status:d.status,orderId:d.orderId===null?'':String(d.orderId),page:d.page+step,size:d.size};refresh()
}
watch(()=>[currentUser.value?.id,getToken()],()=>{ selectedStatus.value='ALL';orderIdInput.value='';query.value=normalizeAdminOrderQuery();refresh() },{flush:'sync'})
onLoad(options=>{if(options?.status&&adminOrderFilters.some(f=>f.value===options.status)){selectedStatus.value=options.status;query.value=normalizeAdminOrderQuery({status:options.status})}})
onShow(()=>{active.value=true;refresh()})
onHide(()=>{active.value=false;loader.refresh(query.value,false)})
onUnload(()=>{active.value=false;loader.dispose()})
function openDetail(id){if(!state.value.busy)uni.navigateTo({url:adminOrderDetailPath(id)})}
function openLogin(){uni.navigateTo({url:'/pages/user/login'})}
function goOverview(){uni.redirectTo({url:'/pages/admin/orders'})}
</script>
<style scoped>
.page{padding:32rpx;min-height:100vh;background:#f5f7fa}.title{display:block;font-size:40rpx;font-weight:bold;margin-bottom:20rpx}
.panel{padding:28rpx;margin:20rpx 0;background:white;border-radius:16rpx}.panel text{display:block;margin:12rpx 0}.heading{font-size:30rpx;font-weight:bold}
.hint{display:block;padding:16rpx 0;color:#64748b;line-height:1.6}.filter{padding:20rpx 0}input{height:84rpx;padding:8rpx 16rpx;border:1px solid #ddd;border-radius:8rpx}
button{margin-top:20rpx}.pagination{display:flex;align-items:center;gap:16rpx}.pagination button{flex:1}.error{display:block;color:#b42318;padding:20rpx 0}
</style>

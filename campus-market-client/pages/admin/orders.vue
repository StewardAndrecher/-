<template>
  <view class="page">
    <text class="title">交易运营概览</text>
    <text class="hint">平台全部订单的状态分布与模拟交易统计，按服务器时间采样。</text>
    <text v-if="!currentUser" class="hint">请先登录管理员账号</text>
    <button v-if="!currentUser" @click="openLogin">前往登录</button>
    <text v-if="state.busy" class="hint">正在采样交易数据…</text>
    <view v-if="state.data">
      <view class="panel">
        <text class="heading">{{ orderOverviewHint(state.data) }}</text>
        <text class="hint">采样时间（服务器时间）：{{ state.data.sampledAt }}</text>
      </view>
      <button @click="openOrders('ALL')">查看订单明细</button>
      <button v-if="state.data.overduePendingPayment > 0" @click="openOrders('OVERDUE_PENDING')">查看到期待付款订单</button>
      <view class="stats">
        <view v-for="field in fields" :key="field.key" class="panel">
          <text class="number">{{ state.data[field.key] }}</text><text>{{ field.label }}</text>
        </view>
      </view>
      <view class="panel">
        <text class="number">¥{{ formatOverviewMoney(state.data.completedAmountInCents) }}</text>
        <text>已完成模拟交易金额</text>
        <text class="hint">只累加已完成订单的下单金额快照，不代表真实收款、余额或平台收入。</text>
      </view>
      <text class="hint">六种订单状态数量相加等于订单总数。已到期待付款是待付款的子集，不能再加到总数。</text>
      <text class="hint">已模拟付款、待交付与已交付、待收货的订单均未计入已完成金额。本页只读，不执行付款、关闭订单或收货。</text>
    </view>
    <text v-if="state.status === 403" class="error">当前账号没有管理权限，请联系平台管理员。</text>
    <text v-else-if="state.error" class="error">{{ state.error }}</text>
    <button v-if="currentUser" :disabled="state.busy" @click="loader.refresh(true)">刷新交易概览</button>
    <button @click="goAdmin">返回管理中心</button>
  </view>
</template>
<script setup>
import { ref, watch } from 'vue'
import { onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { currentUser, getToken } from '@/utils/session.js'
import { getOrderOverview } from '@/api/admin.js'
import { createAdminLoader } from '@/utils/admin-access.mjs'
import { validateOrderOverview, formatOverviewMoney, orderOverviewHint } from '@/utils/order-overview.mjs'
const active = ref(false), state = ref({ data: null, busy: false, error: '', status: 0 })
const fields = [
  { key: 'total', label: '订单总数' }, { key: 'pendingPayment', label: '待付款' },
  { key: 'paid', label: '已模拟付款，待交付' }, { key: 'delivered', label: '已交付，待收货' },
  { key: 'completed', label: '已完成' }, { key: 'cancelled', label: '买家已取消' },
  { key: 'closedTimeout', label: '超时已关闭' }, { key: 'overduePendingPayment', label: '已到期仍待付款' },
  { key: 'oldestPendingPaymentSeconds', label: '最老待付款时长（秒）' }
]
const loader = createAdminLoader(getOrderOverview,
  () => ({ userId: currentUser.value?.id, token: getToken() }),
  validateOrderOverview, value => { state.value = value })
watch(() => [active.value, currentUser.value?.id, getToken()], () => loader.refresh(active.value), { flush: 'sync' })
onShow(() => { active.value = true })
onHide(() => { active.value = false })
onUnload(() => { active.value = false; loader.dispose() })
function openOrders(status) { uni.navigateTo({ url: '/pages/admin/order-list?status=' + status }) }
function openLogin() { uni.navigateTo({ url: '/pages/user/login' }) }
function goAdmin() { uni.redirectTo({ url: '/pages/admin/index' }) }
</script>
<style scoped>
.page { min-height: 100vh; padding: 32rpx; background: #f5f7fa; }
.title { display: block; font-size: 40rpx; font-weight: bold; margin-bottom: 24rpx; }
.panel { padding: 28rpx; margin-bottom: 20rpx; border-radius: 16rpx; background: white; }
.heading { display: block; font-size: 30rpx; font-weight: bold; }
.hint { display: block; padding: 16rpx 0; color: #64748b; line-height: 1.6; }
.stats { display: flex; flex-wrap: wrap; gap: 20rpx; }
.stats .panel { flex: 1 1 38%; }
.number { display: block; font-size: 44rpx; color: #2563eb; }
.error { display: block; color: #b42318; padding: 24rpx 0; }
button { margin-top: 20rpx; }
</style>

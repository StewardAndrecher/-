<template>
  <view class="page">
    <text class="title">管理中心</text>
    <text v-if="!currentUser" class="hint">请先登录管理员账号</text>
    <button v-if="!currentUser" @click="openLogin">前往登录</button>
    <text v-if="state.busy" class="hint">正在加载管理信息…</text>
    <view v-if="state.data">
      <view class="panel">
        <text class="heading">{{ state.data.identity.user.nickname }}</text>
        <text class="hint">账号：{{ state.data.identity.user.username }}</text>
        <text class="badge">管理员</text>
      </view>
      <button @click="openProducts">商品审核</button>
      <button @click="openPage('/pages/admin/governance')">内容与账号治理</button>
      <button @click="openPage('/pages/admin/media')">图片存储与清理</button>
      <button @click="openReports">举报工单</button>
      <button @click="openOrderIssues">交易问题工单</button>
      <button @click="openIssueOverview">反馈处理概览</button>
      <button @click="openOrders">交易运营概览</button>
      <button @click="openNotifications">通知投递概览</button>
      <view class="stats">
        <view class="panel"><text class="number">{{ state.data.overview.users }}</text><text>用户总数</text></view>
        <view class="panel"><text class="number">{{ state.data.overview.products }}</text><text>商品总数</text></view>
        <view class="panel"><text class="number">{{ state.data.overview.onSaleProducts }}</text><text>在售商品</text></view>
        <view class="panel"><text class="number">{{ state.data.overview.orders }}</text><text>订单总数</text></view>
      </view>
    </view>
    <text v-if="state.status === 403" class="error">当前账号没有管理权限，请联系平台管理员。</text>
    <text v-else-if="state.error" class="error">{{ state.error }}</text>
    <button v-if="currentUser" :disabled="state.busy" @click="loader.refresh(true)">刷新管理信息</button>
    <button @click="goHome">返回首页</button>
  </view>
</template>
<script setup>
import { openPage } from '@/composables/usePrivatePage.js'
import { ref, watch } from 'vue'
import { onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { currentUser, getToken } from '@/utils/session.js'
import { getAdminDashboard } from '@/api/admin.js'
import { createAdminLoader, validateAdminDashboard } from '@/utils/admin-access.mjs'
const active = ref(false)
const state = ref({ data: null, busy: false, error: '', status: 0 })
const loader = createAdminLoader(getAdminDashboard, () => ({ userId: currentUser.value?.id, token: getToken() }), validateAdminDashboard, value => { state.value = value })
watch(() => [active.value, currentUser.value?.id, getToken()], () => loader.refresh(active.value), { flush: 'sync' })
onShow(() => { active.value = true })
onHide(() => { active.value = false })
onUnload(() => { active.value = false; loader.dispose() })
function openIssueOverview(){openPage('/pages/admin/issue-overview')}
function openOrderIssues(){openPage('/pages/admin/order-issues')}
function openOrders() { uni.navigateTo({ url: '/pages/admin/orders' }) }
function openNotifications() { uni.navigateTo({ url: '/pages/admin/notifications' }) }
function openReports() { uni.navigateTo({ url: '/pages/admin/reports' }) }
function openProducts() { uni.navigateTo({ url: '/pages/admin/products' }) }
function openLogin() { uni.navigateTo({ url: '/pages/user/login' }) }
function goHome() { uni.reLaunch({ url: '/pages/index/index' }) }
</script>
<style scoped>
.page { min-height: 100vh; padding: 32rpx; background: #f5f7fa; }
.title { display: block; font-size: 40rpx; font-weight: bold; margin-bottom: 24rpx; }
.panel { padding: 28rpx; margin-bottom: 24rpx; border-radius: 16rpx; background: white; }
.heading { display: block; font-size: 32rpx; font-weight: bold; }
.hint { display: block; padding: 16rpx 0; color: #64748b; }
.badge { color: #2563eb; }
.stats { display: flex; flex-wrap: wrap; gap: 20rpx; }
.stats .panel { flex: 1 1 38%; }
.number { display: block; font-size: 48rpx; color: #2563eb; }
.error { display: block; color: #b42318; padding: 24rpx 0; }
button { margin-top: 20rpx; }
</style>

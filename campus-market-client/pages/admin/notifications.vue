<template>
  <view class="page">
    <text class="title">通知投递概览</text>
    <text class="hint">查看站内通知任务是否积压。这里展示数据库采样结果，投递开关不代表队列连接健康。</text>
    <text v-if="!currentUser" class="hint">请先登录管理员账号</text>
    <button v-if="!currentUser" @click="openLogin">前往登录</button>
    <text v-if="state.busy" class="hint">正在采样通知任务…</text>
    <view v-if="state.data">
      <view class="panel">
        <text class="heading">通知投递：{{ state.data.deliveryEnabled ? '已开启' : '已关闭' }}</text>
        <text class="hint">{{ notificationOverviewHint(state.data) }}</text>
        <text class="hint">采样时间（服务器时间）：{{ state.data.sampledAt }}</text>
      </view>
      <view class="stats">
        <view v-for="item in fields" :key="item.key" class="panel">
          <text class="number">{{ state.data[item.key] }}</text>
          <text>{{ item.label }}</text>
        </view>
      </view>
      <text class="hint">未确认发布＋已确认待入库＝待投递。重试中、领取中是待投递的子集，彼此可能重叠，不能相加。</text>
      <text class="hint">重试中指尚未完成且发送领取次数大于1；不一定是永久失败。已完成按任务的入库标记统计，不代表用户已经阅读。</text>
    </view>
    <text v-if="state.status === 403" class="error">当前账号没有管理权限，请联系平台管理员。</text>
    <text v-else-if="state.error" class="error">{{ state.error }}</text>
    <button v-if="currentUser" :disabled="state.busy" @click="loader.refresh(true)">刷新投递概览</button>
    <button @click="goAdmin">返回管理中心</button>
  </view>
</template>
<script setup>
import { ref, watch } from 'vue'
import { onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { currentUser, getToken } from '@/utils/session.js'
import { getNotificationOverview } from '@/api/admin.js'
import { createAdminLoader } from '@/utils/admin-access.mjs'
import { validateNotificationOverview, notificationOverviewHint } from '@/utils/notification-overview.mjs'
const active = ref(false)
const state = ref({ data: null, busy: false, error: '', status: 0 })
const fields = [
  { key: 'pending', label: '待投递任务' }, { key: 'unconfirmed', label: '未确认发布' },
  { key: 'awaitingDelivery', label: '已确认待入库' }, { key: 'retrying', label: '重试中任务' },
  { key: 'leased', label: '领取中任务' }, { key: 'delivered', label: '已完成投递' },
  { key: 'oldestPendingSeconds', label: '最老待投递时长（秒）' }, { key: 'maxPendingAttempts', label: '待投递最多领取次数' }
]
const loader = createAdminLoader(getNotificationOverview,
  () => ({ userId: currentUser.value?.id, token: getToken() }),
  validateNotificationOverview, value => { state.value = value })
watch(() => [active.value, currentUser.value?.id, getToken()], () => loader.refresh(active.value), { flush: 'sync' })
onShow(() => { active.value = true })
onHide(() => { active.value = false })
onUnload(() => { active.value = false; loader.dispose() })
function openLogin() { uni.navigateTo({ url: '/pages/user/login' }) }
function goAdmin() { uni.redirectTo({ url: '/pages/admin/index' }) }
</script>
<style scoped>
.page { min-height: 100vh; padding: 32rpx; background: #f5f7fa; }
.title { display: block; font-size: 40rpx; font-weight: bold; margin-bottom: 24rpx; }
.panel { padding: 28rpx; margin-bottom: 20rpx; border-radius: 16rpx; background: white; }
.heading { display: block; font-size: 32rpx; font-weight: bold; }
.hint { display: block; padding: 16rpx 0; color: #64748b; line-height: 1.6; }
.stats { display: flex; flex-wrap: wrap; gap: 20rpx; }
.stats .panel { flex: 1 1 38%; }
.number { display: block; font-size: 44rpx; color: #2563eb; }
.error { display: block; color: #b42318; padding: 24rpx 0; }
button { margin-top: 20rpx; }
</style>

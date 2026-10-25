<template>
  <view class="page">
    <text class="title">有效登录明细</text>
    <text class="hint">这里只展示本账号仍有效的登录会话。同一设备可以多次登录，会话数量不等于设备数量。</text>
    <text v-if="!currentUser" class="hint">请先登录后查看明细。</text>
    <button v-if="!currentUser" @click="openLogin">前往登录</button>
    <text v-if="state.loading" class="hint">正在读取有效登录明细…</text>
    <text v-if="state.error" class="error">{{ state.error }}</text>
    <view v-if="state.page" class="panel">
      <text class="heading">共 {{ state.page.total }} 个有效登录</text>
      <text class="hint">服务器采样时间：{{ state.page.sampledAt }}</text>
      <view v-for="(item,index) in state.page.items" :key="index" class="session-row">
        <text class="badge">{{ item.current ? '当前登录' : '其他登录' }}</text>
        <text>创建时间：{{ item.createdAt }}</text>
        <text>到期时间：{{ item.expiresAt }}</text>
      </view>
      <view class="pager">
        <button :disabled="state.loading || state.page.page<=1" @click="loadPage(state.page.page-1)">上一页</button>
        <text>第 {{ state.page.page }} / {{ state.page.totalPages }} 页</text>
        <button :disabled="state.loading || state.page.page>=state.page.totalPages" @click="loadPage(state.page.page+1)">下一页</button>
      </view>
    </view>
    <button v-if="currentUser" :disabled="state.loading" @click="loadPage()">刷新有效登录明细</button>
    <text class="hint">每次翻页都会重新采样；新登录、退出或到期可能改变顺序和页数。需要退出其他登录时，请返回登录会话管理确认操作。</text>
    <button :disabled="state.loading" @click="goBack">返回登录会话管理</button>
  </view>
</template>
<script setup>
import { ref, watch } from 'vue'
import { onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { currentUser, getToken } from '@/utils/session.js'
import { getMyActiveSessions } from '@/api/user.js'
import { createSessionListManager } from '@/utils/session-list.mjs'
const state = ref({ page: null, loading: false, error: '', status: 0 })
const active = ref(false)
let requested = 1
const manager = createSessionListManager({ identity: () => ({ userId: currentUser.value?.id, token: getToken() }),
  load: getMyActiveSessions, notify: value => { state.value = value } })
async function loadPage(page = state.value.page?.page || requested) {
  if (state.value.loading || !active.value) return
  requested = page
  await manager.refresh({ page, size: 10 })
  if (state.value.page) requested = state.value.page.page
}
watch(() => [currentUser.value?.id, getToken()], () => {
  manager.reset(); requested = 1
  if (active.value) loadPage(1)
}, { flush: 'sync' })
onShow(() => { active.value = true; loadPage(1) })
onHide(() => { active.value = false; manager.reset() })
onUnload(() => { active.value = false; manager.dispose() })
function openLogin() { uni.navigateTo({ url: '/pages/user/login' }) }
function goBack() { uni.navigateBack({ fail: () => uni.redirectTo({ url: '/pages/user/sessions' }) }) }
</script>
<style scoped>
.page{padding:32rpx;min-height:100vh;background:#f5f7fa;box-sizing:border-box}.title{display:block;font-size:38rpx;font-weight:bold}.panel{padding:28rpx;margin:24rpx 0;background:white;border-radius:16rpx}.heading{display:block;font-size:32rpx;font-weight:bold}.hint{display:block;color:#64748b;line-height:1.7;margin:20rpx 0}.error{display:block;color:#b42318;margin:20rpx 0}.session-row{padding:24rpx 0;border-bottom:1px solid #e2e8f0}.session-row text{display:block;line-height:1.8}.badge{font-weight:bold;color:#2563eb}.pager{display:flex;align-items:center;justify-content:space-between;gap:16rpx;margin-top:24rpx}.pager button{margin:0;font-size:28rpx}button{margin-top:24rpx}
</style>

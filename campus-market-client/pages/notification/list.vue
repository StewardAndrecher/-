<template>
  <view class="page">
    <text class="title">站内通知</text><text class="hint">订单进度、举报结果和商品处理消息。通知异步送达，进入页面或手动刷新后更新。评价与本人反馈结果可直接打开，目标页面仍会验证你的权限。</text>
    <text v-if="!currentUser" class="hint">请先登录后查看通知</text><button v-if="!currentUser" @click="login">前往登录</button>
    <view v-if="currentUser">
      <text v-if="state.unreadCount !== null" class="hint">全部类型未读通知：{{ state.unreadCount }}（独立查询）</text>
      <view class="categories"><button v-for="item in categories" :key="item.value" :type="query.category===item.value?'primary':'default'" :disabled="busy" @click="changeCategory(item.value)">{{ item.label }}</button></view>
      <text class="hint">当前类型：{{ categoryLabel }}；列表仅统计当前类型与已读状态的交集。</text>
      <picker :range="filters" range-key="label" :value="filter" :disabled="busy" @change="change"><view class="filter">已读状态：{{ filters[filter].label }} ▾</view></picker>
      <button :disabled="busy" @click="controller.refresh(query, active)">刷新通知</button>
      <button :disabled="busy || pageUnread === 0" @click="markPageRead">{{ state.batchReading ? '正在确认本页已读…' : `本页未读标为已读（${pageUnread}条）` }}</button>
      <text v-if="state.page" class="hint">当前组合共{{ state.page.total }}条通知</text>
      <text class="hint">只处理当前页显示的未读通知，确认期间新到的通知仍保留未读。</text>
    </view>
    <text v-if="state.loading" class="hint">正在加载通知…</text><text v-if="state.error" class="error">{{ state.error }}</text>
    <view v-for="n in state.page?.records || []" :key="n.id" class="card">
      <text class="heading">{{ n.title }}</text><text class="hint">{{ notificationCategories[n.category] }} · {{ n.readAt ? '已读' : '未读' }}</text>
      <text class="body">{{ n.body }}</text><text class="hint">事件时间：{{ n.occurredAt }}</text>
      <button :disabled="busy" @click="controller.open(n.id)">{{ state.openingId === n.id ? '正在打开…' : notificationActionLabel(n) }}</button>
    </view>
    <text v-if="state.page && !state.page.records.length" class="hint">暂无符合条件的通知</text>
    <view v-if="state.page" class="row"><button size="mini" :disabled="busy || query.page <= 1" @click="page(-1)">上一页</button><text>第 {{ query.page }} / {{ pages }} 页</text><button size="mini" :disabled="busy || query.page >= pages" @click="page(1)">下一页</button></view>
  </view>
</template>
<script setup>
import { ref, computed, watch } from 'vue'
import { onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { currentUser, getToken } from '@/utils/session.js'
import { listNotifications, getNotificationUnread, readNotification, readNotificationBatch } from '@/api/notification.js'
import { createNotificationInbox, notificationCategories, notificationActionLabel } from '@/utils/notifications.mjs'
const active = ref(false), filter = ref(0), query = ref({ page: 1, size: 10, status: 'ALL', category: 'ALL' })
const filters = [{ value: 'ALL', label: '全部通知' }, { value: 'UNREAD', label: '仅未读' }, { value: 'READ', label: '仅已读' }]
const categories=[{value:'ALL',label:'全部类型'},{value:'ORDER',label:'订单消息'},{value:'REPORT',label:'举报结果'},{value:'MODERATION',label:'商品处理'}]
const categoryLabel=computed(()=>categories.find(item=>item.value===query.value.category)?.label||'类型无效')
const state = ref({ page: null, unreadCount: null, loading: false, openingId: null, batchReading: false, error: '', status: 0 })
const busy = computed(() => state.value.loading || state.value.openingId !== null || state.value.batchReading), pages = computed(() => Math.max(1,Math.ceil((state.value.page?.total || 0)/query.value.size)))
const pageUnread = computed(() => (state.value.page?.records || []).filter(n => n.readAt === null).length)
const controller = createNotificationInbox({ identity: () => ({ userId: currentUser.value?.id, token: getToken() }), load: listNotifications, unread: getNotificationUnread, read: readNotification, readBatch: readNotificationBatch,
  confirmBatch: count => new Promise((resolve,reject) => uni.showModal({ title: '确认本页已读', content: `将当前页显示的${count}条未读通知标为已读？其他页及新到的通知不受影响。`, confirmText: '标为已读', cancelText: '取消', success: result => resolve(result.confirm === true), fail: () => reject(new Error('暂时无法显示确认窗口，请重试')) })),
  navigate: url => new Promise((resolve,reject) => uni.navigateTo({ url, success: resolve, fail: () => reject(new Error('暂时无法打开相关页面，请刷新通知后重试')) })), notify: value => { state.value = value } })
watch(() => [active.value,currentUser.value?.id,getToken()], () => active.value ? controller.refresh(query.value,true) : controller.deactivate(), { flush: 'sync' })
onShow(() => { active.value = true }); onHide(() => { active.value = false }); onUnload(() => controller.dispose())
function change(e) { if(busy.value)return;const raw=e?.detail?.value;const n=typeof raw==='number'?raw:typeof raw==='string'&&/^[0-2]$/.test(raw)?Number(raw):NaN;if(Number.isInteger(n)&&filters[n]){filter.value=n;query.value={ ...query.value,page:1,status:filters[n].value };controller.refresh(query.value,active.value)} }
function changeCategory(category) { if(busy.value||!categories.some(item=>item.value===category))return;query.value={...query.value,page:1,category};controller.refresh(query.value,active.value) }
function page(delta) { if(busy.value||!state.value.page)return;query.value={...query.value,page:query.value.page+delta};controller.refresh(query.value,active.value) }
async function markPageRead() {
  const who = { userId: currentUser.value?.id, token: getToken() }
  if (!await controller.markPageRead() || !active.value || currentUser.value?.id !== who.userId || getToken() !== who.token) return
  query.value = { ...query.value, page: 1 }
  await controller.refresh(query.value, true)
}
function login() { uni.navigateTo({ url:'/pages/user/login' }) }
</script>
<style scoped>.page{padding:32rpx;min-height:100vh;background:#f5f7fa}.title{display:block;font-size:40rpx;font-weight:bold}.hint{display:block;color:#64748b;margin:16rpx 0}.filter,.card{background:white;padding:24rpx;border-radius:12rpx;margin:20rpx 0}.heading{display:block;font-size:32rpx;font-weight:bold}.body{display:block;white-space:pre-wrap}.error{display:block;color:#b42318}.categories{display:flex;flex-wrap:wrap;gap:12rpx}.categories button{flex:1;font-size:26rpx}.row{display:flex;align-items:center;gap:16rpx}button{margin:16rpx 0}</style>

<template>
  <view class="intent-list">
    <button :disabled="disabled || !active || loading || writing" @click="toggle">{{ expanded ? '收起购买意向' : '我的购买意向' }}</button>
    <view v-if="expanded">
      <text class="hint">意向不锁定商品，不代表成交或付款。以下价格是首次提交时的标价。卖家接受仅表示愿意沟通，可接受多位买家的意向。</text>
      <view class="roles">
        <button size="mini" :disabled="disabled || !active || loading || writing" @click="load('BUYER', 1)">{{ role === 'BUYER' ? '✓ ' : '' }}我发出的</button>
        <button size="mini" :disabled="disabled || !active || loading || writing" @click="load('SELLER', 1)">{{ role === 'SELLER' ? '✓ ' : '' }}我收到的</button>
        <button size="mini" :disabled="disabled || !active || loading || writing" @click="load(role, page)">{{ loading ? '查询中…' : '刷新意向' }}</button>
      </view>
      <button size="mini" :disabled="disabled || !active || loading || writing"
        @click="load(role, 1, statusFilter, !unreadOnly)">{{ unreadOnly ? '显示全部意向' : '只看有未读留言的意向' }}</button>
      <text class="hint">意向范围：{{ unreadOnly ? '仅有未读留言' : '全部意向' }}。筛选会查询全部页，刷新不会自动标记已读。</text>
      <view v-if="loaded && summary" class="summary" aria-label="当前身份全部意向统计">
        <text class="hint">{{ role === 'BUYER' ? '我发出的' : '我收到的' }} · 全部记录统计（不受当前状态、未读筛选和页码限制）</text>
        <view class="summary-counts">
          <text>全部 {{ summary.total }}</text>
          <text>待沟通 {{ summary.pending }}</text>
          <text>已接受 {{ summary.accepted }}</text>
          <text>已取消 {{ summary.cancelled }}</text>
        </view>
        <text v-if="overview" class="unread-summary">当前身份全部私密留言：未读 {{ overview.totalUnreadMessages }} 条 · {{ overview.unreadIntentCount }} 个意向有新留言（不受状态、未读筛选和页码限制，刷新时更新）</text>
      </view>
      <picker :range="statusOptions" range-key="label" :value="statusIndex"
        :disabled="disabled || !active || loading || writing" @change="chooseStatus">
        <view class="status-picker">意向状态：{{ statusOptions[statusIndex].label }} ▾</view>
      </picker>
      <picker :range="sortOptions" range-key="label" :value="sortIndex"
        :disabled="disabled || !active || loading || writing" @change="chooseSort">
        <view class="status-picker">意向排序：{{ sortOptions[sortIndex].label }} ▾</view>
      </picker>
      <text class="hint">最近收到留言：按对方未撤回留言的最近发送时间排序；没有这类留言的意向排在后面。同一时间按意向提交时间、编号倒序。标记已读不改变此顺序。</text>
      <text v-if="message" class="message">{{ message }}</text>
      <text v-if="stale" class="message">请刷新以确认当前状态，下面保留的是上次查询结果。</text>
      <text v-if="loaded" class="hint">{{ role === 'BUYER' ? '我发出的' : '我收到的' }} · {{ statusOptions[statusIndex].label }} · 共 {{ total }} 条 · 第 {{ page }}/{{ totalPages }} 页</text>
      <text v-if="loaded && unread" class="unread-summary">本页私密留言：未读 {{ unread.pageUnreadMessages }} 条 · {{ unread.pageUnreadIntents }} 个意向有新留言（刷新时更新）</text>
      <text v-if="loaded && !items.length" class="hint">暂无购买意向</text>
      <view v-for="item in items" :key="item.id" class="intent-row">
        <text class="row-title">{{ item.productTitleSnapshot }}</text>
        <text class="unread-badge">{{ unreadCount(item.id) > 0 ? '来自对方的未读留言 ' + unreadCount(item.id) + ' 条' : '暂无来自对方的未读留言' }}</text>
        <text class="hint">{{ receivedTimeLabel(item.id) }}</text>
        <button size="mini" :disabled="disabled || !active || loading || writing" @click="openDetail(item)">查看意向 #{{ item.id }}</button>
        <text class="hint">意向 #{{ item.id }} · 商品 #{{ item.productId }} · {{ statusLabel(item.status) }}</text>
        <text class="hint">提交时标价 ¥{{ money(item.priceInCentsSnapshot) }} · {{ role === 'BUYER' ? '卖家：' + item.seller.nickname : '买家：' + item.buyer.nickname }}</text>
        <text class="hint">意向提交：{{ item.createdAt.replace('T', ' ') }}</text>
        <text v-if="item.status === 'CANCELLED'" class="hint">取消时间：{{ item.cancelledAt.replace('T', ' ') }}。记录保留，暂不支持重新发起。</text>
        <text v-if="item.status === 'ACCEPTED'" class="hint">接受时间：{{ item.acceptedAt.replace('T', ' ') }}。仅表示愿意沟通，不锁定商品，不代表成交；当前暂不支持取消。</text>
        <button v-if="role === 'SELLER' && item.status === 'PENDING'" size="mini"
          :disabled="disabled || !active || loading || writing || stale" :loading="acceptId === item.id"
          @click="accept(item)">接受意向 #{{ item.id }}</button>
        <button v-if="role === 'BUYER' && item.status === 'PENDING'" size="mini"
          :disabled="disabled || !active || loading || writing || stale" :loading="cancelId === item.id"
          @click="cancel(item)">取消意向 #{{ item.id }}</button>
      </view>
      <view v-if="loaded" class="roles">
        <button size="mini" :disabled="disabled || !active || loading || writing || page <= 1" @click="load(role, page - 1)">上一页</button>
        <button size="mini" :disabled="disabled || !active || loading || writing || page >= totalPages" @click="load(role, page + 1)">下一页</button>
      </view>
    </view>
  </view>
</template>

<script setup>
import { getPurchaseIntentUnread, getPurchaseIntentUnreadSummary } from '@/api/intent-unread.js'
import { ref, computed, watch, onUnmounted } from 'vue'
import { intentDetailPath } from '@/utils/purchase-intent-route.js'
import { currentUser, getToken } from '@/utils/session.js'
import { getPurchaseIntents, cancelPurchaseIntent, acceptPurchaseIntent } from '@/api/purchase-intent.js'
const props = defineProps({ disabled: { type: Boolean, default: false }, active: { type: Boolean, default: true } })
const expanded = ref(false), loading = ref(false), loaded = ref(false), stale = ref(false)
const role = ref('BUYER'), page = ref(1), totalPages = ref(1), total = ref(0), items = ref([]), message = ref('')
const receivedMessageTimes = ref(null)
const statusFilter = ref(''), summary = ref(null), unread = ref(null), unreadOnly = ref(false), overview = ref(null)
function receivedTimeLabel(id) {
  const row = receivedMessageTimes.value?.find(item => item.intentId === id)
  if (!row) return '最近收到时间暂不可用，请稍后刷新'
  if (row.latestReceivedAt === null) return '最近收到：暂无来自对方的有效留言'
  const time = row.latestReceivedAt.replace('T', ' ').split('.')[0]
  return '最近收到：' + time + (time.length === 16 ? ':00' : '') + '（服务器记录时间）'
}
function unreadCount(id) { return unread.value?.items.find(item => item.intentId === id)?.unreadCount || 0 }
const statusOptions = [
  { value: '', label: '全部状态' }, { value: 'PENDING', label: '待沟通' },
  { value: 'ACCEPTED', label: '已接受' }, { value: 'CANCELLED', label: '已取消' }
]
const statusIndex = computed(() => Math.max(0, statusOptions.findIndex(option => option.value === statusFilter.value)))
const sort = ref('NEWEST')
const sortOptions = [{ value: 'NEWEST', label: '最新提交' }, { value: 'RECENT_MESSAGE', label: '最近收到留言' }]
const sortIndex = computed(() => Math.max(0, sortOptions.findIndex(option => option.value === sort.value)))
const writing = ref(false), cancelId = ref(0), acceptId = ref(0)
let generation = 0, closed = false
function statusLabel(status) { return { PENDING: '待沟通', CANCELLED: '已取消', ACCEPTED: '已接受' }[status] || '未知状态' }
function money(cents) { return (cents / 100).toFixed(2) }
function resetPrivateState() {
  generation++
  writing.value = false; cancelId.value = 0; acceptId.value = 0
  expanded.value = false; loading.value = false; loaded.value = false; stale.value = false
  receivedMessageTimes.value = null; sort.value = 'NEWEST'
  summary.value = null; unread.value = null; overview.value = null
  unreadOnly.value = false; statusFilter.value = ''; role.value = 'BUYER'
  page.value = 1; totalPages.value = 1; total.value = 0; items.value = []; message.value = ''
}
// saveSession replaces currentUser even when the account ID stays the same.
watch(() => [currentUser.value?.id, getToken()], (next, previous) => {
  if (previous && next[0] === previous[0] && next[1] === previous[1]) return
  resetPrivateState()
}, { flush: 'sync' })
watch(() => props.active, active => {
  if (!active) resetPrivateState()
}, { flush: 'sync' })

function toggle() {
  if (closed || props.active === false || props.disabled || loading.value || writing.value || !currentUser.value) return
  expanded.value = !expanded.value
  if (expanded.value) load(role.value, page.value)
}
function chooseStatus(event) {
  const value = event?.detail?.value
  if (typeof value !== 'number' && (typeof value !== 'string' || !/^\d+$/.test(value))) return
  const index = Number(value)
  if (!Number.isInteger(index) || index < 0 || index >= statusOptions.length) return
  return load(role.value, 1, statusOptions[index].value)
}
function chooseSort(event) {
  const value = event?.detail?.value
  if (typeof value !== 'number' && (typeof value !== 'string' || !/^\d+$/.test(value))) return
  const index = Number(value)
  if (!Number.isInteger(index) || index < 0 || index >= sortOptions.length) return
  return load(role.value, 1, statusFilter.value, unreadOnly.value, sortOptions[index].value)
}
function validatePage(data, expectedRole, expectedStatus, actor, expectedUnread, expectedSort) {
  if ((data.sort ?? 'NEWEST') !== expectedSort || (data.sort !== undefined && !sortOptions.some(option => option.value === data.sort))) throw new Error('购买意向与当前排序不一致')
  if (data.items.some(item => (expectedRole === 'BUYER' ? item.buyer.id : item.seller.id) !== actor)) throw new Error('购买意向与当前账号不一致')
  if ((data.unreadOnly === true) !== expectedUnread) throw new Error('购买意向与当前未读筛选不一致')
  if (data.role !== expectedRole || data.status !== expectedStatus || (expectedStatus && data.items.some(item => item.status !== expectedStatus))) throw new Error('购买意向与当前筛选不一致')
}
function commitPage(data, unreadData, overviewData) {
  receivedMessageTimes.value = data.receivedMessageTimes ?? null
  sort.value = data.sort ?? 'NEWEST'; overview.value = overviewData; unread.value = unreadData; unreadOnly.value = data.unreadOnly === true
  items.value = data.items; role.value = data.role; statusFilter.value = data.status; summary.value = data.summary
  page.value = data.page; totalPages.value = data.totalPages; total.value = data.total
  loaded.value = true; stale.value = false
}
async function load(nextRole = role.value, nextPage = page.value, nextStatus = statusFilter.value, nextUnread = unreadOnly.value, nextSort = sort.value) {
  if (closed || props.active === false || props.disabled || loading.value || writing.value || !expanded.value || !currentUser.value) return
  const actor = currentUser.value.id, token = getToken(), op = ++generation
  const stillCurrent = () => !closed && props.active !== false && expanded.value && op === generation && currentUser.value?.id === actor && getToken() === token
  loading.value = true; message.value = ''
  try {
    const data = await getPurchaseIntents({ role: nextRole, page: nextPage, size: 5, status: nextStatus, unreadOnly: nextUnread, sort: nextSort })
    if (!stillCurrent()) return
    validatePage(data, nextRole, nextStatus, actor, nextUnread, nextSort)
    const [unreadData, overviewData] = await Promise.all([
      getPurchaseIntentUnread({ role: nextRole, intentIds: data.items.map(item => item.id) }),
      getPurchaseIntentUnreadSummary(nextRole)
    ])
    if (!stillCurrent()) return
    commitPage(data, unreadData, overviewData)
  } catch (error) {
    if (stillCurrent()) { stale.value = loaded.value; message.value = error.message || '购买意向查询失败，请重试' }
  } finally { if (stillCurrent()) loading.value = false }
}
function openDetail(item) {
  if (closed || props.active === false || props.disabled || loading.value || writing.value || !currentUser.value ||
      !items.value.some(row => row.id === item.id && (row.buyer.id === currentUser.value.id || row.seller.id === currentUser.value.id))) return
  try {
    uni.navigateTo({ url: intentDetailPath(item.id), fail: () => uni.showToast({ title: '打开意向详情失败', icon: 'none' }) })
  } catch { uni.showToast({ title: '购买意向编号不正确', icon: 'none' }) }
}
function cancel(item) { return changeIntent(item, false) }
function accept(item) { return changeIntent(item, true) }
async function changeIntent(item, isAccept) {
  const requiredRole = isAccept ? 'SELLER' : 'BUYER', participant = isAccept ? 'seller' : 'buyer'
  if (closed || props.active === false || props.disabled || loading.value || writing.value || stale.value || !loaded.value || !expanded.value || role.value !== requiredRole || item.status !== 'PENDING' || item[participant].id !== currentUser.value?.id || !items.value.some(row => row.id === item.id && row.status === 'PENDING')) return
  const actor = currentUser.value.id, token = getToken(), op = ++generation
  const stillCurrent = () => !closed && props.active !== false && expanded.value && op === generation && currentUser.value?.id === actor && getToken() === token
  const snapshot = { id: item.id, productId: item.productId, title: item.productTitleSnapshot, buyerId: item.buyer.id, sellerId: item.seller.id }
  writing.value = true; cancelId.value = isAccept ? 0 : item.id; acceptId.value = isAccept ? item.id : 0; message.value = ''
  try {
    const content = isAccept
      ? `意向 #${snapshot.id}：${snapshot.title}，买家 ${item.buyer.nickname}，提交时标价 ¥${money(item.priceInCentsSnapshot)}。接受仅表示愿意沟通，不锁定商品，不代表成交或付款，可接受多位买家的意向。`
      : `意向 #${snapshot.id}：${snapshot.title}。取消后保留历史记录，暂不支持重新发起。商品状态不会改变。`
    const choice = await new Promise((resolve, reject) => uni.showModal({ title: isAccept ? '接受购买意向？' : '取消购买意向？', content, confirmText: isAccept ? '确认接受' : '确认取消', success: resolve, fail: reject }))
    if (!stillCurrent() || !choice.confirm) return
    if (props.disabled || role.value !== requiredRole || !items.value.some(row => row.id === snapshot.id && row.status === 'PENDING' && row[participant].id === actor)) return
    const response = await (isAccept ? acceptPurchaseIntent : cancelPurchaseIntent)(snapshot.id)
    if (!stillCurrent()) return
    const expectedStatus = isAccept ? 'ACCEPTED' : 'CANCELLED'
    if (response.intent.id !== snapshot.id || response.intent.productId !== snapshot.productId || response.intent.buyer.id !== snapshot.buyerId || response.intent.seller.id !== snapshot.sellerId || response.intent.status !== expectedStatus) throw new Error('操作结果与当前账号或商品不一致')
    const label = isAccept ? '接受' : '取消'
    {
      // Re-read after every successful write: summary changes even under the ALL filter.
      try {
        const data = await getPurchaseIntents({ role: role.value, page: page.value, size: 5, status: statusFilter.value, unreadOnly: unreadOnly.value, sort: sort.value })
        if (!stillCurrent()) return
        validatePage(data, role.value, statusFilter.value, actor, unreadOnly.value, sort.value)
        const [unreadData, overviewData] = await Promise.all([
          getPurchaseIntentUnread({ role: role.value, intentIds: data.items.map(item => item.id) }),
          getPurchaseIntentUnreadSummary(role.value)
        ])
        if (!stillCurrent()) return
        commitPage(data, unreadData, overviewData)
      } catch (error) {
        if (stillCurrent()) {
          stale.value = loaded.value
          message.value = `意向 #${snapshot.id} 已${label}，但列表与统计刷新失败（含未读总览与本页提示），请先刷新确认当前记录。`
        }
        return
      }
    }
    message.value = response.replayed ? `意向 #${snapshot.id} 此前已${label}，已确认原结果` : `意向 #${snapshot.id} 已${label}，历史记录已保留`
  } catch (error) {
    if (stillCurrent()) {
      stale.value = loaded.value
      if (error.statusCode === 409) message.value = `${error.message || '意向状态已变化'}；请先刷新意向列表确认。`
      else message.value = isAccept ? '未能确认接受结果，请先刷新意向列表；若仍显示待沟通，可以再次接受。' : '未能确认取消结果，请先刷新意向列表；若仍显示待沟通，可以再次取消。'
    }
  } finally { if (stillCurrent()) { writing.value = false; cancelId.value = 0; acceptId.value = 0 } }
}
onUnmounted(() => { closed = true; resetPrivateState() })
</script>

<style scoped>
.unread-summary, .unread-badge { display: block; margin-top: 12rpx; font-size: 26rpx; line-height: 1.6; color: #b54400; }
.summary { margin: 16rpx 0; padding: 16rpx; background: #f6f7f8; border-radius: 10rpx; }
.summary-counts { display: flex; flex-wrap: wrap; gap: 16rpx 32rpx; margin-top: 12rpx; font-size: 28rpx; }
.intent-list { margin: 24rpx 0; padding: 20rpx; background: #fff; border-radius: 16rpx; }
.roles { display: flex; flex-wrap: wrap; gap: 12rpx; margin-top: 16rpx; }
.status-picker { margin-top: 16rpx; padding: 16rpx; border: 1rpx solid #ddd; border-radius: 8rpx; font-size: 26rpx; }
.roles button { margin: 0; } .intent-row { padding: 20rpx 0; border-bottom: 1rpx solid #ddd; }
.row-title, .hint, .message { display: block; margin-top: 12rpx; font-size: 26rpx; line-height: 1.6; overflow-wrap: anywhere; }
.row-title { font-weight: bold; } .hint { color: #666; } .message { color: #b54400; }
</style>

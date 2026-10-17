<template>
  <view class="page">
    <text class="title">购买意向详情</text>
    <text v-if="intentId <= 0" class="error">意向编号不正确，请返回首页重新选择</text>
    <view v-else-if="!currentUser" class="card">
      <text class="message">请先登录后查看购买意向；只有该意向的买家和卖家可以查看。</text>
      <button @click="openLogin">去登录</button>
    </view>
    <text v-else-if="loading" class="message">正在加载购买意向…</text>
    <text v-else-if="loadError" class="error">{{ loadError }}</text>
    <view v-else-if="intent" class="card">
      <text class="message">意向编号：{{ intent.id }}</text>
      <text class="status">{{ statusLabel(intent.status) }}</text>
      <text class="message">提交时间：{{ time(intent.createdAt) }}</text>
      <text v-if="intent.acceptedAt" class="message">接受时间：{{ time(intent.acceptedAt) }}</text>
      <text v-if="intent.cancelledAt" class="message">取消时间：{{ time(intent.cancelledAt) }}</text>
      <text class="label">首次提交时的商品信息</text>
      <text class="snapshot-title">{{ intent.productTitleSnapshot }}</text>
      <text class="price">提交时标价：¥{{ (intent.priceInCentsSnapshot / 100).toFixed(2) }}</text>
      <text class="message">商品编号：{{ intent.productId }}</text>
      <text class="message">提交时商品版本：{{ intent.productVersionSnapshot }}</text>
      <text class="message">买家：{{ intent.buyer.nickname }}（{{ intent.buyer.id === currentUser.id ? '我' : '对方' }}）</text>
      <text class="message">卖家：{{ intent.seller.nickname }}（{{ intent.seller.id === currentUser.id ? '我' : '对方' }}）</text>
      <text class="hint">标题、标价和商品版本保留首次提交信息；双方昵称显示当前值。商品当前信息可能已变化。</text>
      <text class="hint">购买意向不锁定商品，不代表成交或付款。接受仅表示愿意沟通，可接受多位买家的意向。</text>
      <button :disabled="conversationBusy" @click="openProduct">查看当前商品</button>
      <text class="hint">当前商品可能已下架或售出；首次提交信息仍保留在这里。</text>
    </view>
    <IntentConversation v-if="intent && !loading && !loadError" :key="intent.id"
      :intent-id="intent.id" :buyer-id="intent.buyer.id" :seller-id="intent.seller.id" :status="intent.status" :active="conversationActive" @busy="conversationBusy = $event" />
    <button :disabled="conversationBusy || loading || intentId <= 0 || !currentUser" @click="loadIntent">刷新意向详情</button>
    <button :disabled="conversationBusy" @click="goBack">返回首页</button>
  </view>
</template>

<script setup>
import IntentConversation from '@/components/IntentConversation.vue'
import { ref, watch } from 'vue'
import { onLoad, onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { currentUser, getToken } from '@/utils/session.js'
import { getPurchaseIntent } from '@/api/purchase-intent.js'
import { parseIntentId, webHashIntentId } from '@/utils/purchase-intent-route.js'
import { detailPath } from '@/utils/product-share.js'
const intentId = ref(0), intent = ref(null), loading = ref(false), loadError = ref('')
const conversationBusy = ref(false), conversationActive = ref(false)
let active = false, closed = false, generation = 0
function invalidate() { generation++; loading.value = false; intent.value = null }
function statusLabel(status) { return { PENDING: '待沟通', ACCEPTED: '已接受', CANCELLED: '已取消' }[status] || '未知状态' }
function time(value) { return value.replace('T', ' ') }
watch(() => currentUser.value?.id, () => { invalidate(); loadError.value = ''; loadIntent() })
async function loadIntent() {
  if (closed || !active || conversationBusy.value || loading.value || intentId.value <= 0) return
  const actor = currentUser.value?.id, token = getToken(), id = intentId.value
  if (!actor || !token) { invalidate(); loadError.value = '请先登录后查看购买意向'; return }
  const op = ++generation
  const stillCurrent = () => !closed && active && op === generation && id === intentId.value && actor === currentUser.value?.id && token === getToken()
  loading.value = true; loadError.value = ''; intent.value = null
  try {
    const data = await getPurchaseIntent(id)
    if (!stillCurrent()) return
    if (data.id !== id || (data.buyer.id !== actor && data.seller.id !== actor)) throw new Error('购买意向与当前账号不一致')
    intent.value = data
  } catch (error) {
    if (stillCurrent()) loadError.value = error.statusCode === 404 ? '购买意向不存在或无权查看' : error.message || '读取购买意向失败，请重试'
  } finally { if (stillCurrent()) loading.value = false }
}
function setRoute(value) {
  invalidate(); intentId.value = parseIntentId(value)
  loadError.value = intentId.value ? '' : '意向编号不正确，请返回首页重新选择'
}
function syncWebRoute() {
  // #ifdef H5
  if (closed || !active) return
  const id = webHashIntentId(window.location.hash)
  if (id === null || id === intentId.value) return
  setRoute(id); loadIntent()
  // #endif
}
function openProduct() {
  if (closed || !active || conversationBusy.value || loading.value || !intent.value || !currentUser.value) return
  uni.navigateTo({ url: detailPath(intent.value.productId), fail: () => uni.showToast({ title: '打开商品失败', icon: 'none' }) })
}
function openLogin() { if (!closed && active) uni.navigateTo({ url: '/pages/user/login' }) }
function goBack() {
  if (conversationBusy.value) return
  const fail = () => uni.showToast({ title: '打开首页失败，请重试', icon: 'none' })
  if (getCurrentPages().length > 1) uni.navigateBack({ fail })
  else uni.reLaunch({ url: '/pages/index/index', fail })
}
onLoad(options => {
  setRoute(options?.id)
  // #ifdef H5
  window.addEventListener('hashchange', syncWebRoute)
  // #endif
})
onShow(() => { if (!closed) { conversationActive.value = true; active = true; syncWebRoute(); return loadIntent() } })
onHide(() => { conversationActive.value = false; conversationBusy.value = false; active = false; invalidate() })
onUnload(() => {
  conversationActive.value = false; conversationBusy.value = false; closed = true; active = false; invalidate()
  // #ifdef H5
  window.removeEventListener('hashchange', syncWebRoute)
  // #endif
})
</script>

<style scoped>
.page { padding: 32rpx; background: #f5f7fa; min-height: 100vh; box-sizing: border-box; }
.card { padding: 28rpx; margin: 24rpx 0; background: #fff; border-radius: 16rpx; }
.title, .status, .label, .snapshot-title, .price, .message, .hint, .error { display: block; margin-top: 18rpx; line-height: 1.6; overflow-wrap: anywhere; }
.title { font-size: 36rpx; font-weight: bold; } .label, .snapshot-title { font-weight: bold; }
.status { color: #176b44; } .price { color: #d35400; } .hint { font-size: 26rpx; color: #666; }
.error { color: #b54400; } button { margin-top: 24rpx; }
</style>

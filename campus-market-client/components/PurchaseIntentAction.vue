<template>
  <view class="intent-panel">
    <text class="title">购买意向</text>
    <text class="hint">仅表达购买意向，不锁定商品，也不代表成交或付款。每位买家对同一商品保留一条意向。</text>
    <text v-if="result" class="message">{{ result.intent.status === 'CANCELLED' ? '这条购买意向已取消' : result.intent.status === 'ACCEPTED' ? '这条购买意向已被卖家接受' : result.replayed ? '已找到之前提交的意向' : '购买意向已提交' }} · 编号 {{ result.intent.id }} · {{ statusLabel(result.intent.status) }}</text>
    <text v-if="result" class="hint">提交时：{{ result.intent.productTitleSnapshot }} · ¥{{ money(result.intent.priceInCentsSnapshot) }}。这是首次提交时的标价，请与卖家确认实际交易条件。</text>
    <text v-if="result?.intent.status === 'CANCELLED'" class="hint">取消时间：{{ result.intent.cancelledAt.replace('T', ' ') }}。已保留原记录，暂不支持重新发起这件商品的意向。</text>
    <text v-if="result?.intent.status === 'ACCEPTED'" class="hint">接受时间：{{ result.intent.acceptedAt.replace('T', ' ') }}。接受仅表示愿意沟通，不锁定商品，不代表成交或付款。</text>
    <template v-if="!result">
      <text v-if="!currentUser" class="hint">登录后可以发起购买意向。</text>
      <text v-else-if="product.seller?.id === currentUser.id" class="hint">这是你发布的商品，不能向自己提交购买意向。</text>
      <text v-else-if="!product.seller" class="hint">该历史商品尚未登记卖家，暂时不能提交购买意向。</text>
      <text v-else-if="reserved && !pending" class="hint">商品暂不能下单，当前不接受新的购买意向。</text>
      <text v-else-if="product.status !== 'ON_SALE' && !pending" class="hint">商品已下架或售出，不接受新的购买意向。</text>
      <button v-if="!currentUser" :disabled="disabled || !active" @click="openLogin">前往登录</button>
      <button v-else-if="eligible || pending" :disabled="disabled || !active || sending" :loading="sending" @click="submit">
        {{ sending ? '确认中…' : pending ? '重试确认这次意向' : '提交购买意向' }}
      </button>
    </template>
    <text v-if="message" class="message">{{ message }}</text>
    <text class="hint">在首页展开“我的购买意向”，可查看自己发出的意向和别人向你商品发来的意向。</text>
  </view>
</template>

<script setup>
import { ref, computed, watch, onUnmounted } from 'vue'
import { currentUser, getToken } from '@/utils/session.js'
import { createPurchaseIntent } from '@/api/purchase-intent.js'
const props = defineProps({ reserved: { type: Boolean, default: false }, product: { type: Object, required: true }, disabled: { type: Boolean, default: false }, active: { type: Boolean, default: true } })
const sending = ref(false), pending = ref(null), result = ref(null), message = ref('')
const eligible = computed(() => !props.reserved && currentUser.value && props.product.seller && props.product.seller.id !== currentUser.value.id && props.product.status === 'ON_SALE')
let generation = 0, closed = false
function statusLabel(status) { return { PENDING: '待沟通', CANCELLED: '已取消', ACCEPTED: '已接受' }[status] || '未知状态' }
function money(cents) { return (cents / 100).toFixed(2) }
function reset() { generation++; sending.value = false; pending.value = null; result.value = null; message.value = '' }
watch(() => [currentUser.value?.id, props.product.id], reset)
watch(() => props.active, active => { if (!active) { generation++; sending.value = false } })
function openLogin() { if (!closed && props.active && !props.disabled) uni.navigateTo({ url: '/pages/user/login' }) }
async function submit() {
  if (closed || !props.active || props.disabled || sending.value || result.value || !currentUser.value || (!eligible.value && !pending.value)) return
  const actor = currentUser.value.id, token = getToken(), op = ++generation
  const stillCurrent = () => !closed && props.active && op === generation && currentUser.value?.id === actor && getToken() === token
  const snapshot = pending.value || { productId: props.product.id, productVersion: props.product.version, title: props.product.title, priceInCents: props.product.priceInCents, buyerId: actor }
  sending.value = true; message.value = ''
  try {
    if (!pending.value) {
      const choice = await new Promise((resolve, reject) => uni.showModal({ title: '提交购买意向？', content: `${snapshot.title}，标价 ¥${money(snapshot.priceInCents)}。仅表达购买意向，不锁定商品，不代表付款。`, confirmText: '确认提交', success: resolve, fail: reject }))
      if (!stillCurrent() || !choice.confirm) return
      if (props.disabled || !eligible.value || props.product.id !== snapshot.productId || props.product.version !== snapshot.productVersion || props.product.title !== snapshot.title || props.product.priceInCents !== snapshot.priceInCents) {
        message.value = '商品或页面状态已变化，请刷新详情并重新确认'; return
      }
      pending.value = snapshot
    }
    if (!stillCurrent()) return
    const response = await createPurchaseIntent(snapshot.productId, snapshot.productVersion)
    if (!stillCurrent()) return
    if (response.intent.buyer.id !== actor || response.intent.productId !== snapshot.productId ||
        (!response.replayed && (response.intent.productVersionSnapshot !== snapshot.productVersion || response.intent.productTitleSnapshot !== snapshot.title || response.intent.priceInCentsSnapshot !== snapshot.priceInCents))) throw new Error('意向结果与提交内容不一致')
    result.value = response; pending.value = null
  } catch (error) {
    if (!stillCurrent()) return
    if ([400, 403, 404, 409].includes(error.statusCode)) {
      pending.value = null; message.value = error.message || '无法提交，请刷新详情后重试'
    } else if (error.statusCode === 401) {
      message.value = '登录已失效，请重新登录后到首页查询购买意向'
    } else {
      message.value = pending.value ? '未能确认是否成功，请先到首页查看我的购买意向；重试不会重复创建。' : '无法打开确认窗口，请重试'
    }
  } finally { if (stillCurrent()) sending.value = false }
}
onUnmounted(() => { closed = true; generation++ })
</script>

<style scoped>
.intent-panel { padding: 24rpx; margin-bottom: 24rpx; border-radius: 16rpx; background: #fff; }
.title { display: block; font-size: 30rpx; font-weight: bold; }
.hint, .message { display: block; margin-top: 16rpx; font-size: 26rpx; line-height: 1.6; overflow-wrap: anywhere; }
.hint { color: #666; } .message { color: #2863c7; }
button { margin-top: 20rpx; }
</style>

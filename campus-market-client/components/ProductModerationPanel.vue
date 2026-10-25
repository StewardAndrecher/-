<template>
  <view>
    <text v-if="!currentUser" class="hint">请先登录后查看处理记录</text>
    <button v-if="!currentUser" @click="openLogin">前往登录</button>
    <text v-if="state.loading" class="hint">正在读取商品处理记录…</text>
    <view v-if="state.detail">
      <view class="card">
        <text class="heading">{{ product.title }}</text>
        <text class="hint">商品编号：{{ product.id }} · ¥{{ (product.priceInCents / 100).toFixed(2) }}</text>
        <text class="hint">{{ product.status === 'SOLD' ? '已售出' : product.status === 'ON_SALE' ? '在售' : '已下架' }} · {{ reviewLabels[state.detail.item.moderation.state] }}</text>
        <text class="description">{{ product.description || '暂无商品描述' }}</text>
        <text v-if="state.detail.item.moderation.lastEventId" class="reason">平台处理说明：{{ state.detail.item.moderation.reason }}</text>
        <text v-if="state.detail.item.moderation.appealReason" class="reason">卖家复核申请：{{ state.detail.item.moderation.appealReason }}</text>
      </view>
      <view v-if="actions.length" class="card">
        <text class="hint">请填写{{ mode === 'seller' ? '整改情况和复核申请' : '处理原因' }}（2～200字）</text>
        <textarea v-model="reason" class="textarea" maxlength="200" :disabled="busy || !!state.pending" placeholder="说明具体原因，处理后保留记录" />
        <button v-for="action in actions" :key="action" :disabled="busy || !!state.pending" @click="confirmAction(action)">{{ actionLabels[action] }}</button>
        <text v-if="mode === 'admin'" class="hint">解除限制后商品仍下架，由卖家手动上架；有有效订单或已售出的商品不能处理。</text>
      </view>
      <button v-if="mode === 'seller' && product.status !== 'SOLD'" :disabled="busy" @click="editProduct">前往编辑整改</button>
      <view class="card">
        <text class="heading">近期处理记录（最多20条）</text>
        <text v-if="!state.detail.events.length" class="hint">暂无平台处理记录</text>
        <view v-for="event in state.detail.events" :key="event.id" class="event">
          <text class="reason">{{ actionLabels[event.action] }} · {{ event.actorRole === 'ADMIN' ? '平台管理员' : '商品卖家' }}</text>
          <text class="hint">{{ event.createdAt }}</text>
          <text class="description">{{ event.reason }}</text>
          <text class="hint">记录时商品：{{ event.snapshot.title }}</text>
          <text class="description">记录时描述：{{ event.snapshot.description || '无' }}</text>
        </view>
      </view>
    </view>
    <text v-if="state.status === 403" class="error">当前账号没有管理权限</text>
    <text v-else-if="state.error" class="error">{{ state.error }}</text>
    <view v-if="state.pending" class="card">
      <text class="error">上次处理结果尚未确认。重试会使用原请求和原说明，不会重复处理。</text>
      <button :disabled="busy" @click="retry">重试同一操作</button>
    </view>
    <button v-if="currentUser" :disabled="state.submitting || confirming" @click="controller.refresh()">刷新处理记录</button>
  </view>
</template>
<script setup>
import { ref, computed, watch, onUnmounted } from 'vue'
import { currentUser, getToken } from '@/utils/session.js'
import { getProductModeration, submitProductModeration } from '@/api/product-moderation.js'
import { newCommentRequestId } from '@/utils/comment-submissions.js'
import { createReviewController, reviewLabels, actionLabels, allowedReviewActions, normalizeReviewReason } from '@/utils/product-moderation.mjs'
const props = defineProps({ productId: { type: Number, required: true }, mode: { type: String, required: true }, active: { type: Boolean, default: false } })
const state = ref({ detail: null, loading: false, submitting: false, error: '', status: 0, pending: null })
const reason = ref(''), confirming = ref(false)
const product = computed(() => state.value.detail?.item.product)
const actions = computed(() => allowedReviewActions(state.value.detail?.item, props.mode))
const busy = computed(() => state.value.loading || state.value.submitting || confirming.value)
const controller = createReviewController({ productId: props.productId, mode: props.mode,
  identity: () => ({ userId: currentUser.value?.id, token: getToken() }),
  load: () => getProductModeration(props.productId, props.mode),
  submit: data => submitProductModeration(props.productId, props.mode, data), newRequestId: newCommentRequestId,
  notify: value => { state.value = value } })
watch(() => [props.active, currentUser.value?.id, getToken()], () => {
  reason.value = ''
  props.active ? controller.refresh() : controller.deactivate()
}, { immediate: true, flush: 'sync' })
onUnmounted(() => controller.dispose())
async function confirmAction(action) {
  if (busy.value || state.value.pending || !product.value) return
  confirming.value = true
  try {
    const text = normalizeReviewReason(reason.value), snapshot = product.value
    const token = getToken(), userId = currentUser.value?.id
    const confirmed = await new Promise(resolve => uni.showModal({ title: actionLabels[action], content: `确认对《${snapshot.title}》执行此操作？${action === 'RELEASE' ? '解除后仍保持下架。' : ''}`, success: result => resolve(result.confirm), fail: () => resolve(false) }))
    if (!confirmed) return
    if (!props.active || token !== getToken() || userId !== currentUser.value?.id || product.value?.id !== snapshot.id || product.value?.version !== snapshot.version) throw new Error('账号或商品状态已变化，请重新加载')
    if (await controller.act(action, text, snapshot.version)) { reason.value = ''; uni.showToast({ title: '处理结果已更新', icon: 'none' }) }
  } catch (error) { uni.showToast({ title: error.message || '操作失败', icon: 'none' }) }
  finally { confirming.value = false }
}
async function retry() { if (await controller.retry()) { reason.value = ''; uni.showToast({ title: '已确认处理结果', icon: 'none' }) } }
function editProduct() { uni.navigateTo({ url: `/pages/product/edit?id=${props.productId}` }) }
function openLogin() { uni.navigateTo({ url: '/pages/user/login' }) }
</script>
<style scoped>
.card { padding: 24rpx; background: white; border-radius: 14rpx; margin: 24rpx 0; }
.heading { display: block; font-size: 32rpx; font-weight: bold; }
.hint { display: block; color: #64748b; margin: 16rpx 0; }
.description { display: block; white-space: pre-wrap; margin: 16rpx 0; }
.reason { display: block; margin: 16rpx 0; }
.error { display: block; color: #b42318; margin: 20rpx 0; }
.textarea { width: 100%; min-height: 160rpx; padding: 16rpx 0; }
.event { border-top: 1px solid #e5e7eb; padding: 16rpx 0; }
button { margin: 16rpx 0; }
</style>

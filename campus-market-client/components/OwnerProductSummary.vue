<template>
  <view class="overview">
    <text class="heading">我的发布概览</text>
    <text class="hint">统计全部发布，不受下方搜索、状态和排序影响。</text>
    <view v-if="summary" class="counts">
      <text>全部 {{ summary.total }} 件</text>
      <text>在售 {{ summary.onSale }} 件</text>
      <text>已下架 {{ summary.offShelf }} 件</text>
      <text>已售出 {{ summary.sold }} 件</text>
      <text class="amount">已售出商品标价合计：¥{{ formatCents(summary.soldPriceInCents) }}</text>
    </view>
    <text v-else class="hint">{{ loading ? '正在加载概览…' : '概览尚未加载' }}</text>
    <text class="hint">金额按商品标价计算，仅供参考。</text>
    <text v-if="stale && summary" class="warning">概览待刷新，当前显示上次查询结果。</text>
    <text v-if="error" class="warning">{{ error }}</text>
    <button size="mini" :disabled="loading || busy" @click="load">{{ loading ? '加载中…' : '刷新概览' }}</button>
  </view>
</template>

<script setup>
import { ref, watch, onMounted, onUnmounted } from 'vue'
import { getMyProductSummary } from '@/api/product.js'
import { currentUser, getToken } from '@/utils/session.js'

const props = defineProps({ revision: { type: Number, default: 0 }, busy: { type: Boolean, default: false } })
const summary = ref(null)
const loading = ref(false)
const stale = ref(false)
const error = ref('')
let requestId = 0, disposed = false, pending = false

function formatCents(value) {
  return Math.floor(value / 100) + '.' + String(value % 100).padStart(2, '0')
}
function invalidate() {
  requestId++; loading.value = false; stale.value = !!summary.value
}
async function load() {
  if (disposed) return false
  if (props.busy) { pending = true; return false }
  if (loading.value) return false
  const token = getToken(), userId = currentUser.value?.id
  if (!token || !userId) { summary.value = null; error.value = ''; stale.value = false; return false }
  pending = false
  const id = ++requestId
  loading.value = true; error.value = ''
  try {
    const data = await getMyProductSummary()
    if (disposed || id !== requestId || token !== getToken() || userId !== currentUser.value?.id) return false
    summary.value = data; stale.value = false
    return true
  } catch (cause) {
    if (!disposed && id === requestId && (token === getToken() || !getToken())) {
      if (cause.statusCode === 401) summary.value = null
      stale.value = !!summary.value
      error.value = cause.statusCode === 401 ? '登录已失效，请重新登录' : cause.message || '加载概览失败，请重试'
    }
    return false
  } finally {
    if (id === requestId) loading.value = false
  }
}
watch(() => props.revision, () => { invalidate(); load() })
watch(() => props.busy, busy => {
  if (busy) { invalidate(); pending = true }
  else if (pending) load()
})
watch(currentUser, () => {
  invalidate(); summary.value = null; stale.value = false; error.value = ''; load()
})
onMounted(load)
onUnmounted(() => { disposed = true; invalidate() })
</script>

<style scoped>
.overview { padding: 24rpx; margin: 24rpx 0; border-radius: 16rpx; background: #fff; }
.heading { display: block; font-size: 32rpx; font-weight: bold; }
.hint { display: block; color: #666; font-size: 24rpx; margin: 16rpx 0; }
.counts { display: flex; flex-wrap: wrap; gap: 20rpx; margin: 20rpx 0; }
.amount { width: 100%; }
.warning { display: block; color: #b42318; margin: 16rpx 0; }
</style>

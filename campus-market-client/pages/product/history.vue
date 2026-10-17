<template>
  <view class="page">
    <text class="title">最近浏览</text>
    <text class="summary">{{ currentUser ? currentUser.nickname + '的账号记录' : '游客浏览记录' }}</text>
    <text class="summary">仅保存在当前设备，游客与账号分别保存；最多20条，保留30天。</text>
    <text class="summary">标题、价格和状态来自本次服务器查询。</text>
    <button :disabled="loading" @click="fetchHistory()">{{ loading ? '正在查询…' : '刷新记录' }}</button>
    <text v-if="message" class="error">{{ message }}</text>
    <view v-for="row in rows" :key="row.productId" class="panel">
      <text class="summary">浏览时间：{{ formatTime(row.viewedAt) }}</text>
      <view v-if="row.product" @click="openDetail(row.productId)">
        <image v-if="imageUrl(row.product.coverImageId)" class="cover" :src="imageUrl(row.product.coverImageId)" mode="aspectFit" />
        <text class="item-title">{{ row.product.title }}</text>
        <text class="price">¥{{ (row.product.priceInCents / 100).toFixed(2) }}</text>
        <FavoriteCount :value="row.product.favoriteCount" />
        <button size="mini">查看详情</button>
      </view>
      <text v-else class="summary">{{ row.unavailable ? '商品已下架或不可用' : '商品读取失败，请刷新重试' }}（编号：{{ row.productId }}）</text>
      <button size="mini" :disabled="loading" @click="removeOne(row.productId)">移除这条记录</button>
    </view>
    <text v-if="!loading && !message && total === 0" class="summary">还没有浏览记录，打开在售商品详情后会自动记录。</text>
    <view class="pagination">
      <button size="mini" :disabled="loading || page <= 1" @click="fetchHistory(page - 1)">上一页</button>
      <text>共 {{ total }} 条，第 {{ page }} / {{ totalPages }} 页</text>
      <button size="mini" :disabled="loading || page >= totalPages" @click="fetchHistory(page + 1)">下一页</button>
    </view>
    <button :disabled="loading || total === 0" @click="clearAll">清空当前身份的浏览记录</button>
    <button @click="goHome">返回首页</button>
  </view>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { getProduct } from '@/api/product.js'
import FavoriteCount from '@/components/FavoriteCount.vue'
import { imageUrl } from '@/api/image.js'
import { currentUser, getToken } from '@/utils/session.js'
import { loadHistory, removeHistory, clearHistory } from '@/utils/browsing-history.js'
import { detailPath } from '@/utils/product-share.js'

const SIZE = 5
const rows = ref([]), total = ref(0), page = ref(1)
const loading = ref(false), message = ref('')
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / SIZE)))
let active = false, closed = false, operation = 0
function ownerId() { return getToken() ? currentUser.value?.id || 0 : 0 }

watch(() => currentUser.value?.id, () => {
  operation++; rows.value = []; total.value = 0; page.value = 1
  loading.value = false; message.value = ''
  if (active) fetchHistory(1)
})

function validProduct(product, id) {
  return product && product.id === id && product.status === 'ON_SALE' &&
    typeof product.title === 'string' && !!product.title.trim() &&
    Number.isSafeInteger(product.priceInCents) && product.priceInCents > 0
}

async function fetchHistory(targetPage = page.value) {
  if (closed || !active || loading.value) return
  const owner = ownerId(), token = getToken(), number = ++operation
  loading.value = true; message.value = ''; rows.value = []
  try {
    const all = loadHistory(owner)
    total.value = all.length
    page.value = Math.min(Math.max(1, targetPage), Math.max(1, Math.ceil(all.length / SIZE)))
    const selected = all.slice((page.value - 1) * SIZE, page.value * SIZE)
    const results = await Promise.allSettled(selected.map(row => getProduct(row.productId)))
    if (closed || !active || number !== operation || token !== getToken() || owner !== ownerId()) return
    rows.value = selected.map((row, index) => {
      const result = results[index]
      if (result.status === 'fulfilled') {
        if (validProduct(result.value, row.productId)) return { ...row, product: result.value }
        return { ...row, product: null, unavailable: result.value?.id === row.productId && result.value?.status === 'OFF_SHELF' }
      }
      return { ...row, product: null, unavailable: result.reason?.statusCode === 404 }
    })
  } catch (error) {
    if (!closed && number === operation && token === getToken()) message.value = '本机浏览记录读取失败，请重试'
  } finally {
    if (number === operation) loading.value = false
  }
}

function removeOne(id) {
  if (closed || loading.value) return
  try { removeHistory(ownerId(), id); fetchHistory() }
  catch { message.value = '移除失败，请重试' }
}

function clearAll() {
  if (closed || loading.value || !total.value) return
  const owner = ownerId(), token = getToken(), number = operation
  uni.showModal({ title: '清空浏览记录', content: '只清空当前身份在这台设备的记录，此操作无法撤销。',
    success(result) {
      if (!result.confirm || closed || !active || loading.value || number !== operation ||
          token !== getToken() || owner !== ownerId()) return
      try { clearHistory(owner); fetchHistory(1) }
      catch { message.value = '清空失败，请重试' }
    }
  })
}

function openDetail(id) {
  uni.navigateTo({ url: detailPath(id), fail: () => uni.showToast({ title: '打开详情失败', icon: 'none' }) })
}
function formatTime(value) {
  const d = new Date(value), pad = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}
function goHome() { uni.reLaunch({ url: '/pages/index/index' }) }
onShow(() => { active = true; return fetchHistory() })
onHide(() => { active = false; operation++; loading.value = false; rows.value = [] })
onUnload(() => { closed = true; active = false; operation++ })
</script>

<style scoped>
.page { padding: 32rpx; min-height: 100vh; box-sizing: border-box; background: #f5f7fa; }
.panel { margin: 24rpx 0; padding: 28rpx; background: white; border-radius: 16rpx; }
.title, .item-title { display: block; font-size: 36rpx; font-weight: bold; }
.item-title { font-size: 30rpx; }
.summary, .error, .price { display: block; margin: 20rpx 0; }
.summary { color: #666; }
.error { color: #d93025; }
.price { color: #e65b35; }
.cover { width: 100%; height: 240rpx; }
.pagination { display: flex; align-items: center; justify-content: space-between; gap: 16rpx; }
button { margin-top: 20rpx; }
</style>

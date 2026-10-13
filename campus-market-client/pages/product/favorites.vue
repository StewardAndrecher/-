<template>
  <view class="page">
    <text class="title">我的收藏</text>
    <view v-if="!currentUser" class="panel">
      <text>登录后查看账号收藏，换设备登录同一账号也能读取。</text>
      <button type="primary" @click="openLogin">去登录</button>
    </view>
    <view v-else>
      <text class="summary">{{ currentUser.nickname }}，共 {{ total }} 件收藏</text>
      <button :disabled="loading || removingId > 0 || importing" @click="fetchFavorites()">刷新收藏</button>
      <view v-if="legacyIds.length" class="panel">
        <text>本机还有 {{ legacyIds.length }} 件旧收藏。点击后合并到当前账号：{{ currentUser.username }}。</text>
        <button :disabled="loading || removingId > 0 || importing" @click="importLegacy">
          {{ importing ? '导入中…' : '导入本机旧收藏（每次最多20件）' }}
        </button>
      </view>
      <text v-if="message" class="error">{{ message }}</text>
      <view v-for="item in records" :key="item.productId" class="panel">
        <view v-if="item.product" @click="openDetail(item.productId)">
          <image v-if="imageUrl(item.product.coverImageId)" class="cover" :src="imageUrl(item.product.coverImageId)" mode="aspectFit" />
          <text class="item-title">{{ item.product.title }}</text>
          <text class="summary">¥{{ (item.product.priceInCents / 100).toFixed(2) }}</text>
          <button size="mini">查看详情</button>
        </view>
        <text v-else class="summary">商品已下架或不可用（编号：{{ item.productId }}）</text>
        <button :disabled="loading || removingId > 0 || importing" @click="removeFavorite(item.productId)">
          {{ removingId === item.productId ? '取消中…' : '取消收藏' }}
        </button>
      </view>
      <text v-if="!loading && !message && records.length === 0" class="summary">还没有收藏商品</text>
      <view class="pagination">
        <button size="mini" :disabled="loading || removingId > 0 || importing || page <= 1" @click="fetchFavorites(page - 1)">上一页</button>
        <text>第 {{ page }} / {{ totalPages }} 页</text>
        <button size="mini" :disabled="loading || removingId > 0 || importing || page >= totalPages" @click="fetchFavorites(page + 1)">下一页</button>
      </view>
    </view>
    <button :disabled="loading || removingId > 0 || importing" @click="goHome">返回首页</button>
  </view>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { onShow, onUnload } from '@dcloudio/uni-app'
import { getFavorites, setProductFavorite } from '@/api/favorite.js'
import { imageUrl } from '@/api/image.js'
import { currentUser, getToken } from '@/utils/session.js'
import { loadLegacyFavoriteIds, markFavoriteImported } from '@/utils/favorites.js'

const SIZE = 5
const records = ref([])
const total = ref(0)
const page = ref(1)
const loading = ref(false)
const removingId = ref(0)
const importing = ref(false)
const message = ref('')
const legacyIds = ref([])
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / SIZE)))
let pageClosed = false
let operation = 0

watch(() => currentUser.value?.id, () => {
  operation++
  records.value = []; total.value = 0; page.value = 1; legacyIds.value = []
  loading.value = false; removingId.value = 0; importing.value = false; message.value = ''
})

function isValidItem(item) {
  if (!item || !Number.isSafeInteger(item.productId) || item.productId <= 0) return false
  if (item.product === null) return true
  const p = item.product
  return !!p && p.id === item.productId && p.status === 'ON_SALE' &&
    typeof p.title === 'string' && typeof p.description === 'string' &&
    Number.isSafeInteger(p.priceInCents) && p.priceInCents > 0 &&
    Number.isSafeInteger(p.version) && p.version >= 0
}

function readLegacy() {
  if (!currentUser.value) return
  try { legacyIds.value = loadLegacyFavoriteIds(currentUser.value.id) }
  catch { message.value = '旧本机收藏读取失败，账号收藏仍可使用'; legacyIds.value = [] }
}

async function fetchFavorites(targetPage = page.value) {
  if (pageClosed || loading.value || removingId.value > 0 || importing.value) return
  const token = getToken()
  if (!token || !currentUser.value) return
  const number = ++operation
  loading.value = true; message.value = ''
  let smallerPage = 0
  try {
    const data = await getFavorites({ page: targetPage, size: SIZE })
    if (pageClosed || number !== operation || token !== getToken()) return
    if (!data || !Array.isArray(data.records) || !data.records.every(isValidItem) ||
        !Number.isSafeInteger(data.total) || data.total < data.records.length ||
        data.page !== targetPage || data.size !== SIZE || data.records.length > SIZE) {
      throw new Error('收藏列表格式不正确')
    }
    records.value = data.records; total.value = data.total; page.value = targetPage
    const lastPage = Math.max(1, Math.ceil(data.total / SIZE))
    if (targetPage > lastPage) smallerPage = lastPage
  } catch (error) {
    if (!pageClosed && number === operation && (token === getToken() || !getToken())) {
      message.value = error.statusCode === 401 ? '登录已失效，请重新登录' : error.message || '加载收藏失败'
    }
  } finally {
    if (number === operation) loading.value = false
  }
  if (smallerPage && !pageClosed && token === getToken()) await fetchFavorites(smallerPage)
}

async function removeFavorite(id) {
  if (pageClosed || loading.value || removingId.value > 0 || importing.value) return
  const token = getToken()
  if (!token) { openLogin(); return }
  const number = ++operation
  removingId.value = id; message.value = ''
  let removed = false
  try {
    const result = await setProductFavorite(id, false)
    if (pageClosed || number !== operation || token !== getToken()) return
    if (result?.productId !== id || result.favorite !== false) throw new Error('取消结果异常，请刷新确认')
    removed = true
  } catch (error) {
    if (!pageClosed && number === operation && (token === getToken() || !getToken())) {
      message.value = error.statusCode === 0 ? '未收到取消结果，请刷新确认后再操作' : error.message || '取消失败'
    }
  } finally {
    if (number === operation) removingId.value = 0
  }
  if (removed && !pageClosed && token === getToken()) await fetchFavorites()
}

async function importLegacy() {
  if (pageClosed || loading.value || removingId.value > 0 || importing.value) return
  const token = getToken(), userId = currentUser.value?.id
  if (!token || !userId) return
  const number = ++operation
  importing.value = true; message.value = ''
  let imported = 0, unavailable = 0, failure = ''
  try {
    for (const id of legacyIds.value.slice(0, 20)) {
      if (pageClosed || number !== operation || token !== getToken()) return
      try {
        const result = await setProductFavorite(id, true)
        if (pageClosed || number !== operation || token !== getToken()) return
        if (result?.productId !== id || result.favorite !== true) throw new Error('导入结果异常')
        imported++
        legacyIds.value = legacyIds.value.filter(value => value !== id)
        markFavoriteImported(userId, id)
      } catch (error) {
        if (error.statusCode === 404) { unavailable++; continue }
        failure = error.statusCode === 0 ? '网络结果未确认，可重新导入，重复请求不会新增记录' : error.message || '导入中断'
        break
      }
    }
  } finally {
    if (number === operation) importing.value = false
  }
  if (!pageClosed && number === operation && token === getToken()) {
    await fetchFavorites(1)
    if (!pageClosed && token === getToken()) {
      const refreshError = message.value
      message.value = `已合并${imported}件，${unavailable}件不可用。` + (failure || refreshError || '原本机收藏保留。')
    }
  }
}

function openLogin() { uni.navigateTo({ url: '/pages/user/login' }) }
function openDetail(id) {
  uni.navigateTo({ url: `/pages/product/detail?id=${id}`, fail: () => uni.showToast({ title: '打开详情失败', icon: 'none' }) })
}
function goHome() {
  if (getCurrentPages().length > 1) uni.navigateBack()
  else uni.reLaunch({ url: '/pages/index/index' })
}
onShow(async () => { await fetchFavorites(); readLegacy() })
onUnload(() => { pageClosed = true; operation++ })
</script>

<style scoped>
.page { padding: 32rpx; min-height: 100vh; box-sizing: border-box; background: #f5f7fa; }
.panel { padding: 28rpx; margin: 24rpx 0; background: white; border-radius: 16rpx; }
.title, .item-title { display: block; font-size: 36rpx; font-weight: bold; }
.item-title { font-size: 30rpx; }
.summary, .error { display: block; margin: 20rpx 0; }
.summary { color: #666; }
.error { color: #d93025; }
.cover { width: 100%; height: 240rpx; }
.pagination { display: flex; align-items: center; justify-content: space-between; gap: 16rpx; }
button { margin-top: 20rpx; }
</style>

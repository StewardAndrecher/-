<template>
  <view class="page">
    <text class="title">我的发布</text>
    <view v-if="!currentUser" class="card">
      <text>请先登录后查看自己的商品</text>
      <button type="primary" @click="openLogin">去登录</button>
    </view>
    <view v-else>
      <text class="summary">{{ currentUser.nickname }}，共 {{ total }} 件，包含在售和下架商品</text>
      <button size="mini" :disabled="loading || busyId > 0" @click="fetchMine()">
        {{ loading ? '加载中…' : '刷新我的发布' }}
      </button>
      <text v-if="message" class="error">{{ message }}</text>
      <view v-for="item in records" :key="item.id" class="card">
        <image v-if="imageUrl(item.coverImageId)" class="cover" :src="imageUrl(item.coverImageId)" mode="aspectFit" />
        <text class="item-title">{{ item.title }}</text>
        <text class="summary">¥{{ (item.priceInCents / 100).toFixed(2) }} · {{ item.status === 'ON_SALE' ? '在售' : '已下架' }}</text>
        <button size="mini" :disabled="loading || busyId > 0" @click="openPage('detail', item.id)">查看详情</button>
        <button size="mini" :disabled="loading || busyId > 0" @click="openPage('edit', item.id)">编辑</button>
        <button type="primary" :disabled="loading || busyId > 0" @click="changeStatus(item)">
          {{ busyId === item.id ? '处理中…' : (item.status === 'ON_SALE' ? '下架' : '上架') }}
        </button>
      </view>
      <text v-if="!loading && !message && records.length === 0" class="summary">还没有发布商品</text>
      <view class="pagination">
        <button size="mini" :disabled="loading || busyId > 0 || page <= 1" @click="fetchMine(page - 1)">上一页</button>
        <text>第 {{ page }} / {{ totalPages }} 页</text>
        <button size="mini" :disabled="loading || busyId > 0 || page >= totalPages" @click="fetchMine(page + 1)">下一页</button>
      </view>
    </view>
    <button :disabled="loading || busyId > 0" @click="goHome">返回首页</button>
  </view>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { onShow, onUnload } from '@dcloudio/uni-app'
import { getMyProducts, updateProductStatus } from '@/api/product.js'
import { currentUser, getToken } from '@/utils/session.js'
import { imageUrl } from '@/api/image.js'

const SIZE = 5
const records = ref([])
const total = ref(0)
const page = ref(1)
const loading = ref(false)
const busyId = ref(0)
const message = ref('')
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / SIZE)))
let pageClosed = false

function isValidItem(item, userId) {
  return item && Number.isSafeInteger(item.id) && item.id > 0 &&
    typeof item.title === 'string' && typeof item.description === 'string' &&
    Number.isSafeInteger(item.priceInCents) && item.priceInCents > 0 &&
    item.seller?.id === userId && typeof item.seller.nickname === 'string' &&
    ['ON_SALE', 'OFF_SHELF'].includes(item.status) &&
    Number.isSafeInteger(item.version) && item.version >= 0
}

watch(currentUser, (user, previous) => {
  if (user?.id !== previous?.id) {
    records.value = []; total.value = 0; page.value = 1
  }
})

async function fetchMine(targetPage = page.value) {
  if (pageClosed || loading.value || busyId.value > 0 || targetPage < 1) return
  message.value = ''
  const token = getToken()
  const userId = currentUser.value?.id
  if (!token || !userId) { records.value = []; total.value = 0; return }
  loading.value = true
  try {
    const data = await getMyProducts({ page: targetPage, size: SIZE })
    if (pageClosed || token !== getToken()) return
    if (!data || !Array.isArray(data.records) || !data.records.every(item => isValidItem(item, userId)) ||
        !Number.isSafeInteger(data.total) || data.total < data.records.length ||
        data.page !== targetPage || data.size !== SIZE || data.records.length > SIZE) {
      throw new Error('我的发布响应格式不正确')
    }
    records.value = data.records; total.value = data.total; page.value = data.page
  } catch (error) {
    if (!pageClosed && (token === getToken() || !getToken())) {
      if (error.statusCode === 401) { records.value = []; total.value = 0 }
      message.value = error.statusCode === 401 ? '登录已失效，请重新登录' : error.message || '加载失败'
    }
  } finally {
    loading.value = false
    if (!pageClosed && token !== getToken() && getToken()) await fetchMine(1)
  }
}

async function changeStatus(item) {
  if (pageClosed || loading.value || busyId.value > 0) return
  const token = getToken()
  const userId = currentUser.value?.id
  if (!token || !userId) { openLogin(); return }
  if (!isValidItem(item, userId)) { message.value = '商品信息不正确，请刷新'; return }
  const target = item.status === 'ON_SALE' ? 'OFF_SHELF' : 'ON_SALE'
  busyId.value = item.id; message.value = ''
  let refreshAfterConflict = false
  try {
    const result = await updateProductStatus(item.id, target, item.version)
    if (pageClosed || token !== getToken()) return
    if (!isValidItem(result, userId) || result.id !== item.id || result.status !== target) {
      throw new Error('状态响应异常，请刷新确认')
    }
    records.value = records.value.map(row => row.id === item.id ? result : row)
    uni.showToast({ title: target === 'ON_SALE' ? '已上架' : '已下架', icon: 'success' })
  } catch (error) {
    if (!pageClosed && (token === getToken() || !getToken())) {
      if (error.statusCode === 409 && error.code === 'PRODUCT_CONFLICT') refreshAfterConflict = true
      if (error.statusCode === 401) { records.value = []; total.value = 0 }
      message.value = error.statusCode === 0 ? '未收到操作结果，请刷新确认状态后再重试'
        : error.statusCode === 401 ? '登录已失效，请重新登录' : error.message || '操作失败'
    }
  } finally {
    busyId.value = 0
    if (refreshAfterConflict && !pageClosed && token === getToken()) {
      await fetchMine()
      if (!pageClosed && token === getToken()) {
        message.value = message.value ? '商品已被更新；刷新失败：' + message.value
          : '商品已被更新，列表已刷新，请确认最新内容后再操作'
      }
    }
    if (!pageClosed && token !== getToken() && getToken()) await fetchMine(1)
  }
}

function openLogin() {
  uni.navigateTo({ url: '/pages/user/login', fail: () => uni.showToast({ title: '打开登录页失败', icon: 'none' }) })
}
function openPage(type, id) {
  if (loading.value || busyId.value > 0) return
  uni.navigateTo({ url: `/pages/product/${type}?id=${id}`, fail: () => uni.showToast({ title: '打开商品页面失败', icon: 'none' }) })
}
function goHome() {
  if (loading.value || busyId.value > 0) return
  if (getCurrentPages().length > 1) uni.navigateBack()
  else uni.reLaunch({ url: '/pages/index/index' })
}
onShow(() => fetchMine())
onUnload(() => { pageClosed = true })
</script>

<style scoped>
.cover { width: 100%; height: 240rpx; }
.page { padding: 32rpx; min-height: 100vh; box-sizing: border-box; background: #f5f7fa; }
.title, .item-title { display: block; font-size: 36rpx; font-weight: bold; }
.item-title { font-size: 30rpx; }
.card { padding: 28rpx; background: white; border-radius: 16rpx; margin: 24rpx 0; }
.summary, .error { display: block; margin: 24rpx 0; }
.summary { color: #666666; }
.error { color: #d93025; }
.pagination { display: flex; align-items: center; justify-content: space-between; gap: 16rpx; }
button { margin-top: 20rpx; }
</style>

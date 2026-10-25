<template>
  <view class="page">
    <text class="title">我的发布</text>
    <view v-if="!currentUser" class="card">
      <text>请先登录后查看自己的商品</text>
      <button type="primary" @click="openLogin">去登录</button>
    </view>
    <view v-else>
      <OwnerProductSummary v-if="pageVisible" :revision="summaryRevision" :busy="busyId > 0" />
      <view class="search-bar">
        <input v-model="draftKeyword" :disabled="loading || busyId > 0" maxlength="50" placeholder="搜索自己的商品标题" confirm-type="search" @confirm="searchMine" />
        <button size="mini" :disabled="loading || busyId > 0" @click="searchMine">搜索</button>
        <button size="mini" :disabled="loading || busyId > 0" @click="clearSearch">清空搜索</button>
      </view>
      <picker :range="STATUS_OPTIONS" range-key="label" :value="selectedStatusIndex" :disabled="loading || busyId > 0" @change="changeFilter">
        <view class="status-filter">查看状态：{{ STATUS_OPTIONS[selectedStatusIndex].label }} ▾</view>
      </picker>
      <picker :range="SORT_OPTIONS" range-key="label" :value="selectedSortIndex" :disabled="loading || busyId > 0" @change="changeSort">
        <view class="status-filter">我的发布排序：{{ SORT_OPTIONS[selectedSortIndex].label }} ▾</view>
      </picker>
      <text class="summary">{{ currentUser.nickname }}，当前筛选共 {{ total }} 件（关键词：{{ activeKeyword || '全部' }}）</text>
      <text v-if="listNeedsRefresh" class="error">列表待刷新，请先刷新确认最新结果再管理商品。</text>
      <button size="mini" :disabled="loading || busyId > 0" @click="refreshAll">
        {{ loading ? '加载中…' : '刷新我的发布' }}
      </button>
      <text v-if="message" class="error">{{ message }}</text>
      <view v-for="item in records" :key="item.id" class="card">
        <image v-if="imageUrl(item.coverImageId)" class="cover" :src="imageUrl(item.coverImageId)" mode="aspectFit" />
        <text class="item-title">{{ item.title }}</text>
        <text class="summary">¥{{ (item.priceInCents / 100).toFixed(2) }} · {{ item.status === 'SOLD' ? '已售出' : item.status === 'ON_SALE' ? '在售' : '已下架' }}</text>
        <button size="mini" :disabled="loading || busyId > 0" @click="openPage('detail', item.id)">查看详情</button>
        <button v-if="item.status !== 'SOLD'" size="mini" :disabled="loading || busyId > 0 || listNeedsRefresh" @click="openPage('edit', item.id)">编辑</button>
        <button size="mini" :disabled="loading || busyId > 0" @click="openPage('moderation', item.id)">平台处理与复核</button>
        <button v-if="item.status !== 'SOLD'" type="primary" :disabled="loading || busyId > 0 || listNeedsRefresh" @click="changeStatus(item)">
          {{ busyId === item.id ? '处理中…' : (item.status === 'ON_SALE' ? '下架' : '上架') }}
        </button>
        <button v-if="item.status !== 'SOLD'" :disabled="loading || busyId > 0 || listNeedsRefresh" @click="markSold(item)">标记已售出</button>
        <text v-else class="summary">已售出商品保留记录，不能再编辑或重新上架。</text>
      </view>
      <text v-if="!loading && !message && records.length === 0" class="summary">{{ activeStatus || activeKeyword ? '当前条件下没有商品' : '还没有发布商品' }}</text>
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
import { onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { getMyProducts, updateProductStatus } from '@/api/product.js'
import { currentUser, getToken } from '@/utils/session.js'
import { imageUrl } from '@/api/image.js'
import OwnerProductSummary from '@/components/OwnerProductSummary.vue'
import { createPageTaskScope } from '@/utils/page-task-scope.mjs'

const summaryRevision = ref(0)
const SIZE = 5
const STATUS_OPTIONS = Object.freeze([
  { value: '', label: '全部状态' },
  { value: 'ON_SALE', label: '在售' },
  { value: 'OFF_SHELF', label: '已下架' },
  { value: 'SOLD', label: '已售出' }
])
const SORT_OPTIONS = Object.freeze([
  { value: 'NEWEST', label: '最新发布' },
  { value: 'PRICE_ASC', label: '价格从低到高' },
  { value: 'PRICE_DESC', label: '价格从高到低' }
])
const activeSort = ref('NEWEST')
const selectedSortIndex = computed(() => SORT_OPTIONS.findIndex(option => option.value === activeSort.value))
const activeStatus = ref('')
const draftKeyword = ref('')
const activeKeyword = ref('')
const selectedStatusIndex = computed(() => STATUS_OPTIONS.findIndex(option => option.value === activeStatus.value))
const listNeedsRefresh = ref(false)
const records = ref([])
const total = ref(0)
const page = ref(1)
const loading = ref(false)
const busyId = ref(0)
const message = ref('')
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / SIZE)))
const pageVisible = ref(false)
const tasks = createPageTaskScope(() => ({ userId: currentUser.value?.id, token: getToken() }))

function isValidItem(item, userId) {
  return item && Number.isSafeInteger(item.id) && item.id > 0 &&
    typeof item.title === 'string' && typeof item.description === 'string' &&
    Number.isSafeInteger(item.priceInCents) && item.priceInCents > 0 &&
    item.seller?.id === userId && typeof item.seller.nickname === 'string' &&
    ['ON_SALE', 'OFF_SHELF', 'SOLD'].includes(item.status) &&
    Number.isSafeInteger(item.version) && item.version >= 0
}

function clearPage() {
  records.value = []; total.value = 0; page.value = 1
  activeSort.value = 'NEWEST'; activeStatus.value = ''; draftKeyword.value = ''; activeKeyword.value = ''
  listNeedsRefresh.value = false; loading.value = false; busyId.value = 0; message.value = ''
}
watch(() => [currentUser.value?.id, getToken()], () => {
  tasks.invalidate(); clearPage()
  if (pageVisible.value && currentUser.value && getToken()) refreshAll()
}, { flush: 'sync' })

function changeFilter(event) {
  const raw = event?.detail?.value
  if (!['string', 'number'].includes(typeof raw) || !/^[0-3]$/.test(String(raw))) return
  fetchMine(1, STATUS_OPTIONS[Number(raw)].value)
}

function changeSort(event) {
  const raw = event?.detail?.value
  if (!['string', 'number'].includes(typeof raw) || !/^[0-2]$/.test(String(raw))) return
  fetchMine(1, activeStatus.value, activeKeyword.value, SORT_OPTIONS[Number(raw)].value)
}

async function searchMine() {
  const ticket = tasks.capture()
  if (!ticket) return false
  const keyword = draftKeyword.value.trim()
  if (keyword.length > 50) { message.value = '搜索关键词不能超过50个字符'; return }
  if (await fetchMine(1, activeStatus.value, keyword) && tasks.valid(ticket)) draftKeyword.value = activeKeyword.value
}

async function clearSearch() {
  const ticket = tasks.capture()
  if (!ticket) return false
  if (await fetchMine(1, activeStatus.value, '') && tasks.valid(ticket)) draftKeyword.value = ''
}

async function fetchMine(targetPage = page.value, status = activeStatus.value, keyword = activeKeyword.value, sort = activeSort.value) {
  const ticket = tasks.capture()
  if (!ticket || loading.value || busyId.value > 0 || !Number.isSafeInteger(targetPage) || targetPage < 1 || targetPage > 1000000 ||
      !STATUS_OPTIONS.some(option => option.value === status) || typeof keyword !== 'string' || keyword.length > 50 ||
      !SORT_OPTIONS.some(option => option.value === sort)) return false
  loading.value = true; message.value = ''
  try {
    let requested = targetPage
    for (let attempt = 0; attempt < 2; attempt++) {
      const data = await getMyProducts({ page: requested, size: SIZE, status, keyword, sort })
      if (!tasks.valid(ticket)) return false
      if (!data || !Array.isArray(data.records) || !data.records.every(item => isValidItem(item, ticket.userId) && (!status || item.status === status)) ||
          new Set(data.records.map(item => item.id)).size !== data.records.length ||
          !Number.isSafeInteger(data.total) || data.total < 0 ||
          data.page !== requested || data.size !== SIZE ||
          data.records.length !== Math.min(SIZE, Math.max(0, data.total - (requested - 1) * SIZE))) {
        throw new Error('我的发布响应格式不正确')
      }
      const lastPage = Math.max(1, Math.ceil(data.total / SIZE))
      if (requested > lastPage) { requested = lastPage; continue }
      records.value = data.records; total.value = data.total; page.value = data.page
      activeSort.value = sort; activeStatus.value = status; activeKeyword.value = keyword; listNeedsRefresh.value = false
      return true
    }
    throw new Error('商品数量正在变化，请重新刷新')
  } catch (error) {
    if (tasks.valid(ticket)) {
      listNeedsRefresh.value = true
      if (error?.statusCode === 401) { records.value = []; total.value = 0 }
      message.value = error?.statusCode === 401 ? '登录已失效，请重新登录' : error?.message || '加载失败'
    }
    return false
  } finally {
    // An old request must not unlock a newer session's request.
    if (tasks.valid(ticket)) loading.value = false
  }
}

async function changeStatus(item, requestedStatus = '') {
  const ticket = tasks.capture()
  if (!ticket || loading.value || busyId.value > 0 || listNeedsRefresh.value) return false
  if (!isValidItem(item, ticket.userId)) { message.value = '商品信息不正确，请刷新'; return false }
  const latest = records.value.find(row => row.id === item.id)
  if (!latest || latest.version !== item.version || latest.status !== item.status) {
    message.value = '商品信息已变化，请刷新后确认'; return false
  }
  if (item.status === 'SOLD') { message.value = '商品已售出，请查看保留记录'; return false }
  const target = requestedStatus === 'SOLD' ? 'SOLD' : item.status === 'ON_SALE' ? 'OFF_SHELF' : 'ON_SALE'
  busyId.value = item.id; message.value = ''
  let refreshAfterConflict = false, refreshAfterChange = false, changed = false
  try {
    const result = await updateProductStatus(item.id, target, item.version)
    if (!tasks.valid(ticket)) return false
    if (!isValidItem(result, ticket.userId) || result.id !== item.id || result.status !== target ||
        result.version !== item.version + 1) {
      throw new Error('状态响应异常，请刷新确认')
    }
    changed = true
    if (activeStatus.value && result.status !== activeStatus.value) {
      records.value = records.value.filter(row => row.id !== item.id)
      total.value = Math.max(0, total.value - 1)
      page.value = Math.min(page.value, Math.max(1, Math.ceil(total.value / SIZE)))
      listNeedsRefresh.value = true; refreshAfterChange = true
    } else records.value = records.value.map(row => row.id === item.id ? result : row)
    uni.showToast({ title: target === 'SOLD' ? '已标记售出' : target === 'ON_SALE' ? '已上架' : '已下架', icon: 'success' })
  } catch (error) {
    if (tasks.valid(ticket)) {
      refreshAfterConflict = error?.statusCode === 409 && error?.code === 'PRODUCT_CONFLICT'
      // Keep the last snapshot visible, but require a fresh read before editing again.
      listNeedsRefresh.value = true
      if (error?.statusCode === 401) { records.value = []; total.value = 0 }
      message.value = error?.statusCode === 0 ? '未收到操作结果，请刷新确认状态后再重试'
        : error?.statusCode === 401 ? '登录已失效，请重新登录' : error?.message || '操作失败，请刷新确认'
    }
  } finally {
    if (tasks.valid(ticket)) {
      busyId.value = 0
      if (refreshAfterChange) {
        const refreshed = await fetchMine()
        if (!refreshed && tasks.valid(ticket)) message.value = '状态已更新，但列表刷新失败：' + (message.value || '请手动刷新')
      }
      if (refreshAfterConflict) {
        await fetchMine()
        if (tasks.valid(ticket)) {
          message.value = message.value ? '商品已被更新；刷新失败：' + message.value
            : '商品已被更新，列表已刷新，请确认最新内容后再操作'
        }
      }
    }
  }
  return changed
}

async function markSold(item) {
  const ticket = tasks.capture()
  if (!ticket || loading.value || busyId.value > 0 || listNeedsRefresh.value || item?.status === 'SOLD') return false
  if (!isValidItem(item, ticket.userId)) { message.value = '商品信息不正确，请刷新'; return false }
  const id = item.id, version = item.version, status = item.status
  busyId.value = id
  const confirmed = await new Promise(resolve => {
    try {
      uni.showModal({ title: '确认商品已售出？', content: '确认后将保留记录，并停止编辑和重新上架。请仅在商品确实售出后确认。',
        confirmText: '确认售出', success: result => resolve(result.confirm === true), fail: () => resolve(false) })
    } catch { resolve(false) }
  })
  // Check before clearing busyId: this callback may belong to a hidden page or old session.
  if (!tasks.valid(ticket)) return false
  busyId.value = 0
  if (!confirmed) return false
  const latest = records.value.find(row => row.id === id)
  if (!latest || latest.version !== version || latest.status !== status) {
    message.value = '商品信息已变化，请刷新后确认'; return false
  }
  return changeStatus(latest, 'SOLD')
}

function openLogin() {
  uni.navigateTo({ url: '/pages/user/login', fail: () => uni.showToast({ title: '打开登录页失败', icon: 'none' }) })
}
function openPage(type, id) {
  if (!tasks.capture() || loading.value || busyId.value > 0 || (type === 'edit' && listNeedsRefresh.value)) return
  uni.navigateTo({ url: `/pages/product/${type}?id=${id}`, fail: () => uni.showToast({ title: '打开商品页面失败', icon: 'none' }) })
}
function goHome() {
  if (loading.value || busyId.value > 0) return
  if (getCurrentPages().length > 1) uni.navigateBack()
  else uni.reLaunch({ url: '/pages/index/index' })
}
function refreshAll() {
  summaryRevision.value++
  return fetchMine()
}
onShow(() => {
  if (!tasks.show()) return
  clearPage(); pageVisible.value = true
  return refreshAll()
})
onHide(() => { pageVisible.value = false; tasks.hide(); clearPage() })
onUnload(() => { pageVisible.value = false; tasks.dispose(); clearPage() })
</script>

<style scoped>
.search-bar { margin-top: 20rpx; }
.search-bar input { background: white; padding: 20rpx; border-radius: 12rpx; }
.status-filter { padding: 20rpx; margin-top: 20rpx; border-radius: 12rpx; background: white; }
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

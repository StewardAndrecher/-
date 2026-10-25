<template>
  <view class="page">
    <text class="title">我的收藏</text>
    <view v-if="!currentUser" class="panel">
      <text>登录后查看账号收藏，换设备登录同一账号也能读取。</text>
      <button type="primary" @click="openLogin">去登录</button>
    </view>
    <view v-else>
      <text v-if="state.page" class="summary">{{ currentUser.nickname }}，当前筛选共 {{ total }} 件收藏</text>
      <text v-else class="summary">{{ loading ? '正在读取账号收藏…' : '请刷新读取账号收藏' }}</text>
      <view class="search-bar">
        <input v-model="draftKeyword" :disabled="busy" maxlength="50" placeholder="搜索收藏中的商品标题" confirm-type="search" @confirm="searchFavorites" />
        <button size="mini" :disabled="busy" @click="searchFavorites">搜索收藏</button>
        <button size="mini" :disabled="busy" @click="clearSearch">清空搜索</button>
      </view>
      <text class="summary">当前关键词：{{ state.keyword || '全部' }}。标题搜索只匹配可查看商品，已失效商品不参与标题搜索。</text>
      <view class="filters">
        <button v-for="option in FAVORITE_AVAILABILITY" :key="option.value" size="mini"
          :type="state.availability === option.value ? 'primary' : 'default'"
          :disabled="busy" @click="inbox.filter(option.value)">{{ option.label }}</button>
      </view>
      <text class="summary">当前筛选：{{ filterLabel }}。已失效商品仅保留编号；可查看不代表商品仍未被他人下单。</text>
      <button :disabled="busy" @click="fetchFavorites()">刷新收藏</button>
      <button :disabled="writesBlocked" @click="openBatch">批量管理全部收藏</button>
      <view v-if="legacyIds.length" class="panel">
        <text>本机还有 {{ legacyIds.length }} 件旧收藏（导入不受当前筛选影响）。点击后合并到当前账号：{{ currentUser.username }}。</text>
        <button :disabled="writesBlocked" @click="importLegacy">
          {{ importing ? '导入中…' : '导入本机旧收藏（每次最多20件）' }}
        </button>
      </view>
      <text v-if="state.notice" class="summary">{{ state.notice }}</text>
      <text v-if="message" class="error">{{ message }}</text>
      <view v-for="item in records" :key="item.productId" class="panel">
        <view v-if="item.product" @click="openDetail(item.productId)">
          <image v-if="imageUrl(item.product.coverImageId)" class="cover" :src="imageUrl(item.product.coverImageId)" mode="aspectFit" />
          <text class="item-title">{{ item.product.title }}</text>
          <text class="summary">¥{{ (item.product.priceInCents / 100).toFixed(2) }}</text>
          <FavoriteCount :value="item.product.favoriteCount" />
          <button size="mini">查看详情</button>
        </view>
        <text v-else class="summary">商品已下架或不可用（编号：{{ item.productId }}）</text>
        <button :disabled="writesBlocked" @click="removeFavorite(item.productId)">
          {{ removingId === item.productId ? '取消中…' : '取消收藏' }}
        </button>
      </view>
      <text v-if="!loading && !message && state.page && records.length === 0" class="summary">{{ state.availability === 'ALL' && !state.keyword ? '还没有收藏商品' : '当前筛选下没有收藏商品' }}</text>
      <view v-if="state.page" class="pagination">
        <button size="mini" :disabled="busy || page <= 1" @click="fetchFavorites(page - 1)">上一页</button>
        <text>第 {{ page }} / {{ totalPages }} 页</text>
        <button size="mini" :disabled="busy || page >= totalPages" @click="fetchFavorites(page + 1)">下一页</button>
      </view>
    </view>
    <button :disabled="busy" @click="goHome">返回首页</button>
  </view>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { getFavorites, setProductFavorite } from '@/api/favorite.js'
import FavoriteCount from '@/components/FavoriteCount.vue'
import { imageUrl } from '@/api/image.js'
import { currentUser, getToken } from '@/utils/session.js'
import { loadLegacyFavoriteIds, markFavoriteImported } from '@/utils/favorites.js'
import { createFavoriteInbox, FAVORITE_PAGE_SIZE, FAVORITE_AVAILABILITY } from '@/utils/favorite-inbox.mjs'

const draftKeyword = ref('')
const state = ref({ availability: 'ALL', keyword: '', page: null, legacyIds: [], loading: false, removingId: 0,
  importing: false, error: '', notice: '', uncertain: false })
const inbox = createFavoriteInbox({
  identity: () => ({ userId: currentUser.value?.id, token: getToken() }),
  load: getFavorites, setFavorite: setProductFavorite,
  loadLegacy: loadLegacyFavoriteIds, markImported: markFavoriteImported,
  notify: value => { state.value = value }
})
const filterLabel = computed(() => FAVORITE_AVAILABILITY.find(option => option.value === state.value.availability)?.label || '全部收藏')
const records = computed(() => state.value.page?.records || [])
const total = computed(() => state.value.page?.total || 0)
const page = computed(() => state.value.page?.page || 1)
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / FAVORITE_PAGE_SIZE)))
const loading = computed(() => state.value.loading)
const removingId = computed(() => state.value.removingId)
const importing = computed(() => state.value.importing)
const legacyIds = computed(() => state.value.legacyIds)
const message = computed(() => state.value.error)
const busy = computed(() => loading.value || removingId.value > 0 || importing.value)
const writesBlocked = computed(() => busy.value || state.value.uncertain || !state.value.page)

// saveSession replaces currentUser even for the same user, making token rotation observable.
watch(() => [currentUser.value?.id, getToken()], () => { draftKeyword.value = ''; inbox.identityChanged() }, { flush: 'sync' })
async function searchFavorites() {
  if (busy.value) return
  if (await inbox.search(draftKeyword.value)) draftKeyword.value = state.value.keyword
}
async function clearSearch() {
  if (busy.value) return
  draftKeyword.value = ''
  return inbox.search('')
}
function fetchFavorites(target) { return inbox.refresh(target) }
function removeFavorite(id) { return inbox.remove(id) }
function importLegacy() { return inbox.importLegacy() }
function openBatch() {
  if (!writesBlocked.value && currentUser.value) uni.navigateTo({ url: '/pages/product/favorite-manage',
    fail: () => uni.showToast({ title: '打开批量收藏管理失败', icon: 'none' }) })
}
function openLogin() { uni.navigateTo({ url: '/pages/user/login' }) }
function openDetail(id) {
  if (busy.value) return
  uni.navigateTo({ url: `/pages/product/detail?id=${id}`, fail: () => uni.showToast({ title: '打开详情失败', icon: 'none' }) })
}
function goHome() {
  if (getCurrentPages().length > 1) uni.navigateBack()
  else uni.reLaunch({ url: '/pages/index/index' })
}
onShow(() => { draftKeyword.value = ''; return inbox.show() })
onHide(() => { draftKeyword.value = ''; inbox.hide() })
onUnload(() => { draftKeyword.value = ''; inbox.dispose() })
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
.search-bar { display: flex; align-items: center; flex-wrap: wrap; gap: 12rpx; margin-top: 20rpx; }
.search-bar input { flex: 1; min-width: 220rpx; height: 76rpx; padding: 0 16rpx; background: #fff; border: 1px solid #d9dfe8; border-radius: 12rpx; }
.search-bar button { margin-left: 0; margin-right: 0; }
.filters { display: flex; flex-wrap: wrap; gap: 12rpx; margin-top: 20rpx; }
.filters button { margin-left: 0; margin-right: 0; }
.pagination { display: flex; align-items: center; justify-content: space-between; gap: 16rpx; }
button { margin-top: 20rpx; }
</style>

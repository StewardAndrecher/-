<template>
  <view class="page">
    <text class="title">卖家主页</text>
    <text v-if="loading" class="summary">正在查询卖家在售商品…</text>
    <text v-if="message" class="error">{{ message }}</text>
    <view v-if="store" class="panel">
      <image v-if="imageUrl(store.seller.avatarImageId)" :src="imageUrl(store.seller.avatarImageId)" class="store-avatar" mode="aspectFill" />
      <text v-if="store.seller.bio" class="summary">{{ store.seller.bio }}</text>
      <text v-if="store.seller.campusCode && store.seller.campusCode !== 'UNSPECIFIED'" class="summary">{{ campusName(store.seller.campusCode) }}</text>
      <text class="seller-name">{{ store.seller.nickname }}</text>
      <text class="summary">卖家编号：{{ store.seller.id }}，当前分类共 {{ store.products.total }} 件在售商品</text>
      <text class="summary">商品价格、状态和收藏人数来自本次服务器查询。</text>
    </view>
    <CategoryPicker :model-value="category" include-all label="筛选卖家商品分类"
      :disabled="loading || sellerId <= 0" @update:model-value="changeCategory" />
    <SortPicker :model-value="sort" :disabled="loading || sellerId <= 0" @update:model-value="changeSort" />
    <button :disabled="loading || sellerId <= 0" @click="fetchStore()">刷新卖家商品</button>
    <ProductCard v-for="item in store?.products.records || []" :key="item.id" :product="item"
      :show-favorite-button="false" :show-seller-button="false" @open-detail="openDetail" />
    <text v-if="store && store.products.total === 0" class="summary">{{ category ? '这位卖家在当前分类下暂无在售商品。' : '这位卖家暂时没有在售商品。' }}</text>
    <view v-if="store" class="pagination">
      <button size="mini" :disabled="loading || page <= 1" @click="fetchStore(page - 1)">上一页</button>
      <text>第 {{ page }} / {{ totalPages }} 页</text>
      <button size="mini" :disabled="loading || page >= totalPages" @click="fetchStore(page + 1)">下一页</button>
    </view>
    <button @click="goBack">返回上一页</button>
  </view>
</template>

<script setup>
import { ref, computed } from 'vue'
import { onLoad, onShow, onHide, onUnload } from '@dcloudio/uni-app'
import ProductCard from '@/components/ProductCard.vue'
import SortPicker from '@/components/SortPicker.vue'
import CategoryPicker from '@/components/CategoryPicker.vue'
import { imageUrl } from '@/api/image.js'
import { campusName } from '@/utils/product-details.mjs'
import { getSellerStore } from '@/api/seller.js'
import { parseSellerId, webHashSellerId } from '@/utils/seller-store.js'
import { detailPath } from '@/utils/product-share.js'

const SIZE = 5
const sellerId = ref(0), store = ref(null), page = ref(1), sort = ref('NEWEST'), category = ref('')
const loading = ref(false), message = ref('')
const totalPages = computed(() => Math.max(1, Math.ceil((store.value?.products.total || 0) / SIZE)))
let active = false, closed = false, operation = 0

async function fetchStore(targetPage = page.value, targetSort = sort.value, targetCategory = category.value) {
  if (closed || !active || loading.value || sellerId.value <= 0) return
  const id = sellerId.value, number = ++operation
  loading.value = true; message.value = ''; store.value = null
  try {
    let requested = targetPage
    for (let attempt = 0; attempt < 2; attempt++) {
      const result = await getSellerStore(id, { page: requested, size: SIZE, sort: targetSort, category: targetCategory })
      if (closed || !active || number !== operation || id !== sellerId.value) return
      const lastPage = Math.max(1, Math.ceil(result.products.total / SIZE))
      if (requested > lastPage) { requested = lastPage; continue }
      store.value = result; page.value = requested; sort.value = targetSort; category.value = targetCategory
      return
    }
    throw new Error('商品数量正在变化，请刷新后重试')
  } catch (error) {
    if (!closed && active && number === operation && id === sellerId.value)
      message.value = error?.statusCode === 404 ? '卖家不存在，请返回重新选择' : error?.message || '读取卖家主页失败'
  } finally {
    if (number === operation) loading.value = false
  }
}

function changeSort(value) { return fetchStore(1, value) }
function changeCategory(value) { return fetchStore(1, sort.value, value) }

function openDetail(id) {
  if (loading.value) return
  uni.navigateTo({ url: detailPath(id), fail: () => uni.showToast({ title: '打开详情失败', icon: 'none' }) })
}
function goBack() {
  if (getCurrentPages().length > 1) uni.navigateBack()
  else uni.reLaunch({ url: '/pages/index/index' })
}
function syncWebRoute() {
  // #ifdef H5
  if (closed || !active) return
  const id = webHashSellerId(window.location.hash)
  if (id === null || id === sellerId.value) return
  operation++; loading.value = false; store.value = null; sellerId.value = id
  page.value = 1; sort.value = 'NEWEST'; category.value = ''
  message.value = id ? '' : '卖家ID不正确，请返回重新选择'
  if (id) return fetchStore(1)
  // #endif
}
onLoad(options => {
  // #ifdef H5
  window.addEventListener('hashchange', syncWebRoute)
  // #endif
  sellerId.value = parseSellerId(options?.id)
  if (!sellerId.value) message.value = '卖家ID不正确，请返回重新选择'
})
onShow(() => { if (closed) return; active = true; syncWebRoute(); return fetchStore() })
onHide(() => { active = false; operation++; loading.value = false; store.value = null })
onUnload(() => {
  closed = true; active = false; operation++
  loading.value = false; store.value = null; message.value = ''
  // #ifdef H5
  window.removeEventListener('hashchange', syncWebRoute)
  // #endif
})
</script>

<style scoped>
.store-avatar{width:64px;height:64px;border-radius:50%;margin-bottom:14px}
.page { padding: 32rpx; min-height: 100vh; background: #f5f7fa; box-sizing: border-box; }
.panel { padding: 28rpx; margin: 24rpx 0; background: white; border-radius: 16rpx; }
.title, .seller-name { display: block; font-size: 36rpx; font-weight: bold; }
.seller-name { font-size: 32rpx; }
.summary, .error { display: block; margin: 20rpx 0; }
.summary { color: #666; }
.error { color: #d93025; }
.pagination { display: flex; align-items: center; justify-content: space-between; gap: 16rpx; }
button { margin: 20rpx 0; }
</style>

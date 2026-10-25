<template>
  <view class="page">
    <text class="title">连续浏览</text>
    <text class="hint">按最新发布浏览，刷新可查看新发布的商品。</text>
    <NetworkStatusBanner :busy="state.loading" allow-refresh @retry="retry" />
    <input v-model="keyword" class="search" :maxlength="50" placeholder="搜索商品" @confirm="refresh" />
    <CategoryPicker :model-value="category" include-all label="筛选分类" @update:modelValue="changeCategory" />
    <view class="actions">
      <button size="mini" @click="refresh">搜索／刷新列表</button>
    </view>
    <text class="hint">已显示 {{ state.items.length }} 件（关键词：{{ state.keyword || '全部' }}）</text>
    <ProductCard v-for="item in state.items" :key="item.id" :product="item"
      :show-favorite-button="false" @open-detail="openDetail" />
    <text v-if="state.error" class="error">{{ state.error }}</text>
    <text v-if="!state.loading && !state.error && state.items.length === 0" class="hint">没有符合条件的商品</text>
    <button v-if="state.hasMore" :disabled="state.loading" @click="controller.loadMore()">
      {{ state.loading ? '正在加载…' : (state.error ? '重试加载' : '继续加载') }}
    </button>
    <text v-else class="hint">已经到底了，刷新可查看新发布商品</text>
  </view>
</template>

<script setup>
import { ref } from 'vue'
import { onLoad, onReachBottom, onPullDownRefresh, onUnload } from '@dcloudio/uni-app'
import ProductCard from '@/components/ProductCard.vue'
import CategoryPicker from '@/components/CategoryPicker.vue'
import NetworkStatusBanner from '@/components/NetworkStatusBanner.vue'
import { getProductFeed } from '@/api/product.js'
import { createProductFeed } from '@/utils/product-feed.mjs'

const keyword = ref('')
const category = ref('')
const state = ref({ items: [], cursor: '', hasMore: true, loading: false, error: '', keyword: '', category: '' })
const controller = createProductFeed(getProductFeed, value => { state.value = value })
async function refresh() {
  try { await controller.refresh({ keyword: keyword.value, category: category.value }) }
  catch (error) { uni.showToast({ title: error.message || '搜索条件无效', icon: 'none' }) }
}
function changeCategory(value) { category.value = value; refresh() }
function retry() { state.value.error ? controller.loadMore() : refresh() }
function openDetail(id) {
  uni.navigateTo({ url: `/pages/product/detail?id=${id}`, fail: () => uni.showToast({ title: '打开商品详情失败', icon: 'none' }) })
}
onLoad(() => refresh())
onReachBottom(() => controller.loadMore())
onPullDownRefresh(async () => { try { await refresh() } finally { uni.stopPullDownRefresh() } })
onUnload(() => controller.dispose())
</script>

<style scoped>
.page { padding: 32rpx; background: #f5f7fa; min-height: 100vh; }
.title { display: block; font-size: 40rpx; font-weight: bold; margin-bottom: 16rpx; }
.hint { display: block; color: #64748b; padding: 20rpx 0; }
.search { background: white; padding: 24rpx; border-radius: 12rpx; margin: 16rpx 0; }
.actions { display: flex; margin: 20rpx 0; }
.error { display: block; color: #b42318; padding: 20rpx 0; }
</style>

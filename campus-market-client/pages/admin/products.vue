<template>
  <view class="page">
    <text class="title">商品审核</text>
    <text class="hint">发布后巡查。平台下架后，卖家需整改并申请复核。</text>
    <input v-model="keyword" class="search" maxlength="50" placeholder="搜索商品标题" @confirm="search" />
    <picker :range="statuses" range-key="label" :value="statusIndex" @change="changeStatus"><view class="filter">商品状态：{{ statuses[statusIndex].label }} ▾</view></picker>
    <picker :range="reviews" range-key="label" :value="reviewIndex" @change="changeReview"><view class="filter">审核状态：{{ reviews[reviewIndex].label }} ▾</view></picker>
    <button :disabled="state.busy" @click="search">搜索／刷新</button>
    <text v-if="state.busy" class="hint">正在加载…</text>
    <text v-if="state.error" class="error">{{ state.error }}</text>
    <text v-if="state.data" class="hint">共 {{ state.data.total }} 件，第 {{ query.page }} / {{ totalPages }} 页</text>
    <view v-for="item in state.data?.records || []" :key="item.product.id" class="card">
      <text class="heading">{{ item.product.title }}</text>
      <text class="hint">编号 {{ item.product.id }} · {{ item.product.seller?.nickname || '历史商品未登记卖家' }} · ¥{{ (item.product.priceInCents / 100).toFixed(2) }}</text>
      <text class="hint">{{ item.product.status === 'SOLD' ? '已售出' : item.product.status === 'ON_SALE' ? '在售' : '已下架' }} · {{ reviewLabels[item.moderation.state] }}</text>
      <text v-if="item.moderation.reason" class="hint">处理说明：{{ item.moderation.reason }}</text>
      <button size="mini" @click="openReview(item.product.id)">查看与处理</button>
    </view>
    <text v-if="state.data && !state.data.records.length" class="hint">没有符合条件的商品</text>
    <view class="pagination"><button size="mini" :disabled="state.busy || query.page <= 1" @click="page(-1)">上一页</button><button size="mini" :disabled="state.busy || !state.data || query.page >= totalPages" @click="page(1)">下一页</button></view>
  </view>
</template>
<script setup>
import { ref, computed, watch } from 'vue'
import { onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { currentUser, getToken } from '@/utils/session.js'
import { createAdminLoader } from '@/utils/admin-access.mjs'
import { getModerationProducts } from '@/api/product-moderation.js'
import { validateModerationPage, reviewLabels } from '@/utils/product-moderation.mjs'
const statuses = [{ value: '', label: '全部' }, { value: 'ON_SALE', label: '在售' }, { value: 'OFF_SHELF', label: '已下架' }, { value: 'SOLD', label: '已售出' }]
const reviews = [{ value: '', label: '全部' }, { value: 'CLEAR', label: '无平台限制' }, { value: 'BLOCKED', label: '平台限制中' }, { value: 'APPEALED', label: '等待复核' }]
const keyword = ref(''), statusIndex = ref(0), reviewIndex = ref(0), active = ref(false)
const query = ref({ page: 1, size: 10, keyword: '', status: '', moderationState: '' })
const state = ref({ data: null, busy: false, error: '', status: 0 })
const totalPages = computed(() => Math.max(1, Math.ceil((state.value.data?.total || 0) / query.value.size)))
const loader = createAdminLoader(() => getModerationProducts({ ...query.value }), () => ({ userId: currentUser.value?.id, token: getToken() }), data => validateModerationPage(data, query.value), value => { state.value = value })
watch(() => [active.value, currentUser.value?.id, getToken()], () => loader.refresh(active.value), { flush: 'sync' })
onShow(() => { active.value = true })
onHide(() => { active.value = false })
onUnload(() => { active.value = false; loader.dispose() })
function search() { query.value = { page: 1, size: 10, keyword: keyword.value.trim(), status: statuses[statusIndex.value].value, moderationState: reviews[reviewIndex.value].value }; loader.refresh(active.value) }
function changeStatus(event) { const n = Number(event.detail.value); if (Number.isInteger(n) && statuses[n]) { statusIndex.value = n; search() } }
function changeReview(event) { const n = Number(event.detail.value); if (Number.isInteger(n) && reviews[n]) { reviewIndex.value = n; search() } }
function page(delta) { if (!state.value.data || state.value.busy) return; query.value = { ...query.value, page: query.value.page + delta }; loader.refresh(active.value) }
function openReview(id) { uni.navigateTo({ url: `/pages/admin/product-review?id=${id}` }) }
</script>
<style scoped>
.page { padding: 32rpx; min-height: 100vh; background: #f5f7fa; }.title { display: block; font-size: 40rpx; font-weight: bold; }.heading { display: block; font-size: 32rpx; font-weight: bold; }.hint { display: block; color: #64748b; margin: 16rpx 0; }.search,.filter { background: white; padding: 24rpx; margin: 16rpx 0; border-radius: 12rpx; }.card { padding: 24rpx; background: white; margin: 24rpx 0; border-radius: 14rpx; }.error { color: #b42318; }.pagination { display: flex; gap: 20rpx; }
</style>

<template>
  <view class="page">
    <text class="title">最近浏览</text>
    <text class="summary">{{ currentUser ? currentUser.nickname + '的账号记录' : '游客浏览记录' }}</text>
    <text class="summary">仅保存在当前设备，游客与账号分别保存；最多20条，保留30天。</text>
    <text class="summary">标题、价格和状态来自本次服务器查询；读取失败不会删除本机记录。</text>
    <button :disabled="busy" @click="fetchHistory()">{{ loading ? '正在查询…' : '刷新记录' }}</button>
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
      <button size="mini" :disabled="busy" @click="removeOne(row.productId)">移除这条记录</button>
    </view>
    <text v-if="!loading && !message && total === 0" class="summary">还没有浏览记录，打开在售商品详情后会自动记录。</text>
    <view class="pagination">
      <button size="mini" :disabled="busy || page <= 1" @click="fetchHistory(page - 1)">上一页</button>
      <text>共 {{ total }} 条，第 {{ page }} / {{ totalPages }} 页</text>
      <button size="mini" :disabled="busy || page >= totalPages" @click="fetchHistory(page + 1)">下一页</button>
    </view>
    <button :disabled="busy || total === 0" @click="clearAll">清空当前身份的浏览记录</button>
    <button :disabled="busy" @click="goHome">返回首页</button>
  </view>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { getPublicProducts } from '@/api/product-lookup.js'
import FavoriteCount from '@/components/FavoriteCount.vue'
import { imageUrl } from '@/api/image.js'
import { currentUser, getToken } from '@/utils/session.js'
import { loadHistory, removeHistory, clearHistory } from '@/utils/browsing-history.js'
import { detailPath } from '@/utils/product-share.js'

const SIZE = 5
const rows = ref([]), total = ref(0), page = ref(1)
const loading = ref(false), confirming = ref(false), message = ref('')
const busy = computed(() => loading.value || confirming.value)
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / SIZE)))
let active = false, closed = false, operation = 0
function ownerId() { return getToken() ? currentUser.value?.id || 0 : 0 }

function resetPage() {
  operation++; rows.value = []; total.value = 0; page.value = 1
  loading.value = false; confirming.value = false; message.value = ''
}
function alive(number, token, owner) {
  return !closed && active && number === operation && token === getToken() && owner === ownerId()
}
watch(() => [currentUser.value?.id, getToken()], (next, previous) => {
  if (previous && next[0] === previous[0] && next[1] === previous[1]) return
  resetPage()
  if (active && !closed) fetchHistory(1)
}, { flush: 'sync' })

async function fetchHistory(targetPage = page.value) {
  if (closed || !active || busy.value) return
  if (!Number.isSafeInteger(targetPage) || targetPage < 1 || targetPage > 1000000) {
    message.value = '浏览页码不正确'; return
  }
  const owner = ownerId(), token = getToken(), number = ++operation
  let selected = [], localLoaded = false
  loading.value = true; message.value = ''; rows.value = []
  try {
    const all = loadHistory(owner)
    total.value = all.length
    page.value = Math.min(targetPage, Math.max(1, Math.ceil(all.length / SIZE)))
    selected = all.slice((page.value - 1) * SIZE, page.value * SIZE)
    localLoaded = true
    const results = selected.length ? await getPublicProducts(selected.map(row => row.productId)) : []
    if (!alive(number, token, owner)) return
    rows.value = selected.map((row, index) => ({ ...row, product: results[index].product,
      unavailable: results[index].product === null }))
  } catch (error) {
    if (alive(number, token, owner)) {
      rows.value = selected.map(row => ({ ...row, product: null, unavailable: false }))
      message.value = localLoaded ? '本次商品信息读取失败，请刷新重试；本机浏览记录仍保留' : '本机浏览记录读取失败，请重试'
    }
  } finally {
    if (alive(number, token, owner)) loading.value = false
  }
}

function removeOne(id) {
  if (closed || !active || busy.value || !rows.value.some(row => row.productId === id)) return
  try { removeHistory(ownerId(), id); return fetchHistory() }
  catch { message.value = '移除失败，请重试' }
}

function clearAll() {
  if (closed || !active || busy.value || !total.value) return
  const owner = ownerId(), token = getToken(), number = ++operation
  confirming.value = true
  const valid = () => alive(number, token, owner) && confirming.value
  const fail = () => { if (valid()) { confirming.value = false; message.value = '无法打开清空确认，请重试' } }
  try {
    uni.showModal({ title: '清空浏览记录', content: '只清空当前身份在这台设备的记录，此操作无法撤销。',
      success(result) {
        if (!valid()) return
        confirming.value = false
        if (!result.confirm) return
        try { clearHistory(owner); fetchHistory(1) }
        catch { message.value = '清空失败，请重试' }
      }, fail
    })
  } catch { fail() }
}

function openDetail(id) {
  if (closed || !active || busy.value || !rows.value.some(row => row.productId === id && row.product)) return
  uni.navigateTo({ url: detailPath(id), fail: () => uni.showToast({ title: '打开详情失败', icon: 'none' }) })
}

function formatTime(value) {
  const d = new Date(value), pad = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}
function goHome() { if (!closed && active && !busy.value) uni.reLaunch({ url: '/pages/index/index' }) }
onShow(() => { if (closed) return; active = true; return fetchHistory() })
onHide(() => { active = false; resetPage() })
onUnload(() => { closed = true; active = false; resetPage() })
</script>

<style scoped>
.page { padding: 30px; max-width: 850px; min-height: 100vh; box-sizing: border-box; background: #f5f7fc; color: #20395f; font-size: 14px; }
.panel { margin: 18px 0; padding: 24px; background: #fff; border: 1px solid #e5ebf5; border-radius: 16px; box-shadow: 0 4px 18px #243d6b04; }
.title { display: block; font-size: 28px; font-weight: 750; margin-bottom: 16px; }
.item-title { display: block; font-size: 18px; font-weight: 650; color: #243f6b; }
.summary, .error, .price { display: block; margin: 12px 0; font-size: 13px; line-height: 1.8; }
.summary { color: #8c9bb1; }
.error { color: #bd3e40; background: #fff2f0; padding: 12px; border-radius: 9px; }
.price { color: #df7c45; font-size: 22px; font-weight: 600; }
.cover { width: 100%; height: 200px; border-radius: 11px; margin-bottom: 14px; background: #f1f5fc; }
.pagination { display: flex; align-items: center; justify-content: space-between; gap: 12px; font-size: 12px; color: #8294b0; margin: 24px 0; }
button { margin: 12px 0; }
.panel :deep(.count) { color: #95a4b8; font-size: 12px; margin: 12px 0; }
@media (max-width: 600px) { .page { padding: 24px 16px; } .title { font-size: 25px; } .panel { padding: 20px; } }
</style>

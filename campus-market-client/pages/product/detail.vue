<template>
  <view class="page">
    <text v-if="loading" class="message">
      正在加载商品…
    </text>

    <text v-else-if="errorMessage" class="error">
      {{ errorMessage }}
    </text>

    <view v-else-if="product" class="card detail-card">
      <view class="detail-visual"><ProductGallery :image-ids="product.details?.imageIds || []" :cover-image-id="product.coverImageId || ''" :category-code="product.category?.code || ''" :alt="product.title" /></view>
      <view class="detail-copy"><text class="detail-eyebrow">CAMPUS FINDS · 校园好物</text>
      <text class="title">{{ product.title }}</text>
      <view class="detail-tags"><text>{{ product.category?.name || '未分类' }}</text><text>{{ conditionName(product.details?.conditionCode) }}</text><text>{{ campusName(product.details?.campusCode) }}</text></view>
      <text v-if="product.details?.handoverLocation" class="message">交接地点：{{ product.details.handoverLocation }}</text>

      <text class="price">
        ¥{{ (product.priceInCents / 100).toFixed(2) }}
      </text>

      <text class="description">
        {{ product.description || '暂未填写描述' }}
      </text>

      <text class="message">
        商品编号：{{ product.id }}
      </text>
      <FavoriteCount :value="product.favoriteCount" />
      <text class="message">状态：{{ product.status === 'SOLD' ? '已售出' : product.status === 'ON_SALE' ? '在售' : '已下架' }}</text>
      <text class="message">
        {{ product.seller ? '卖家：' + product.seller.nickname : '历史商品，卖家信息未登记' }}
      </text>
      <view v-if="product.seller" class="seller-profile"><image v-if="imageUrl(product.seller.avatarImageId)" :src="imageUrl(product.seller.avatarImageId)" class="seller-avatar" mode="aspectFill" /><text v-if="product.seller.bio" class="message">{{ product.seller.bio }}</text></view>
      <button v-if="product.seller" size="mini" :disabled="commentsBusy" @click="openSeller">查看卖家主页</button>
      <text v-if="product.seller && currentUser?.id === product.seller.id" class="owner">
        这是你发布的商品
      </text>
      <button v-if="product.status !== 'SOLD' && product.seller && currentUser?.id === product.seller.id"
        type="primary" :disabled="commentsBusy" @click="openEdit">编辑商品</button>
      </view>
    </view>

    <text v-if="product && historyMessage" class="message">{{ historyMessage }}</text>

    <button v-if="product && !loading && !errorMessage && product.status === 'ON_SALE' && product.seller?.id !== currentUser?.id" :disabled="commentsBusy" @click="openReport">举报商品</button>
    <ProductShare v-if="product && !loading && !errorMessage" :product="product" :disabled="commentsBusy" />

    <ProductOrderAction v-if="product && !loading && !errorMessage" :key="'order-'+product.id" :product="product" :disabled="commentsBusy" :active="purchaseActive" @refresh="fetchProduct" @availability="orderAvailable = $event" />

    <PurchaseIntentAction :reserved="orderAvailable === false" v-if="product && !loading && !errorMessage" :key="product.id" :product="product" :disabled="commentsBusy" :active="purchaseActive" />
    <ProductComments v-if="product" :key="product.id" :product-id="product.id" :status="product.status" @busy="commentsBusy = $event" />

    <button
      :disabled="loading || commentsBusy || productId <= 0"
      @click="fetchProduct"
    >
      {{ loading ? '加载中…' : '重新加载' }}
    </button>

    <button :disabled="commentsBusy" @click="goBack">返回列表</button>
  </view>
</template>

<script setup>
import { ref, watch } from 'vue'
import { onLoad, onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { getProduct } from '@/api/product.js'
import { sellerStorePath } from '@/utils/seller-store.js'
import { recordHistory } from '@/utils/browsing-history.js'
import { currentUser, getToken } from '@/utils/session.js'
import ProductGallery from '@/components/ProductGallery.vue'
import { campusName, conditionName } from '@/utils/product-details.mjs'
import { imageUrl } from '@/api/image.js'
import FavoriteCount from '@/components/FavoriteCount.vue'
import { isValidFavoriteCount } from '@/utils/product-popularity.js'
import ProductComments from '@/components/ProductComments.vue'
import ProductShare from '@/components/ProductShare.vue'
import ProductOrderAction from '@/components/ProductOrderAction.vue'
import PurchaseIntentAction from '@/components/PurchaseIntentAction.vue'
import { parseProductId, canShareProduct, wechatProductShare, webHashProductId } from '@/utils/product-share.js'
// #ifdef MP-WEIXIN
import { onShareAppMessage } from '@dcloudio/uni-app'
// #endif



const productId = ref(0)
const product = ref(null)
const loading = ref(false)
const errorMessage = ref('')
const commentsBusy = ref(false)
const historyMessage = ref('')
const purchaseActive = ref(false)
const orderAvailable = ref(null)
let detailActive = false
let detailClosed = false
let detailRequest = 0

watch(() => currentUser.value?.id, () => {
  detailRequest++; loading.value = false; commentsBusy.value = false; product.value = null
  fetchProduct()
})

async function fetchProduct() {
  if (detailClosed || !detailActive || loading.value || commentsBusy.value || productId.value <= 0) return

  const token = getToken(), number = ++detailRequest
  const historyOwner = token ? currentUser.value?.id || 0 : 0
  historyMessage.value = ''
  loading.value = true
  errorMessage.value = ''
  product.value = null

  try {
    const item = await getProduct(productId.value)
    if (detailClosed || !detailActive || number !== detailRequest || token !== getToken() ||
        historyOwner !== (getToken() ? currentUser.value?.id || 0 : 0)) return

    if (
      item === null ||
      typeof item !== 'object' ||
      item.id !== productId.value ||
      typeof item.title !== 'string' ||
      typeof item.description !== 'string' ||
      !['ON_SALE', 'OFF_SHELF', 'SOLD'].includes(item.status) ||
      !isValidFavoriteCount(item.favoriteCount) ||
      !Number.isSafeInteger(item.version) || item.version < 0 ||
      (item.seller != null && (
        !Number.isSafeInteger(item.seller.id) || item.seller.id <= 0 ||
        typeof item.seller.nickname !== 'string' || !item.seller.nickname.trim()
      )) ||
      !Number.isSafeInteger(item.priceInCents) ||
      item.priceInCents <= 0
    ) {
      throw new Error('接口返回的商品格式不正确')
    }

    product.value = item
    try { recordHistory(historyOwner, item) }
    catch { historyMessage.value = '浏览记录未保存，商品详情仍可正常查看' }
  } catch (error) {
    if (detailClosed || number !== detailRequest || (token !== getToken() && getToken())) return
    errorMessage.value = error.statusCode === 404
      ? '商品不存在或不在售'
      : error.message

    console.error('请求商品详情失败：', error)
  } finally {
    if (number === detailRequest) {
      loading.value = false
      if (!detailClosed && token !== getToken()) await fetchProduct()
    }
  }
}

function goBack() {
  if (commentsBusy.value) return
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
  } else {
    uni.reLaunch({
      url: '/pages/index/index'
    })
  }
}

function openSeller() {
  if (commentsBusy.value || !product.value?.seller) return
  uni.navigateTo({ url: sellerStorePath(product.value.seller.id),
    fail: () => uni.showToast({ title: '打开卖家主页失败', icon: 'none' }) })
}

function openReport() { if (product.value) uni.navigateTo({ url: `/pages/report/create?id=${product.value.id}` }) }
function openEdit() {
  if (commentsBusy.value || product.value?.status === 'SOLD') return
  if (!product.value?.seller || product.value.seller.id !== currentUser.value?.id) return
  uni.navigateTo({
    url: `/pages/product/edit?id=${productId.value}`,
    fail: () => uni.showToast({ title: '打开编辑页失败', icon: 'none' })
  })
}

function syncWebRoute() {
  // #ifdef H5
  if (detailClosed || !detailActive) return
  const id = webHashProductId(window.location.hash)
  if (id === null || id === productId.value) return
  detailRequest++; loading.value = false; commentsBusy.value = false
  product.value = null; productId.value = id
  errorMessage.value = id ? '' : '商品ID不正确，请返回首页重新选择'
  if (id) fetchProduct()
  // #endif
}

function shareReady() {
  return detailActive && !detailClosed && !loading.value && !errorMessage.value && !commentsBusy.value && canShareProduct(product.value)
}

function syncShareMenu() {
  // #ifdef MP-WEIXIN
  try {
    if (shareReady()) uni.showShareMenu({ menus: ['shareAppMessage'], fail: () => {} })
    else uni.hideShareMenu({ fail: () => {} })
  } catch (error) { console.warn('分享菜单暂不可用') }
  // #endif
}
watch([product, loading, errorMessage, commentsBusy], syncShareMenu)
// #ifdef MP-WEIXIN
onShareAppMessage(() => wechatProductShare(shareReady() ? product.value : null))
// #endif

onLoad((options) => {
  // #ifdef H5
  window.addEventListener('hashchange', syncWebRoute)
  // #endif
  productId.value = 0
  product.value = null
  syncShareMenu()
  const id = parseProductId(options?.id)

  if (!Number.isSafeInteger(id) || id <= 0) {
    errorMessage.value = '商品ID不正确，请返回首页重新选择'
    return
  }

  productId.value = id
})

// 从编辑页返回时重新读取，显示已保存的商品。
onShow(() => { purchaseActive.value = true; detailActive = true; syncWebRoute(); fetchProduct() })
onHide(() => { purchaseActive.value = false; detailActive = false; syncShareMenu() })
onUnload(() => {
  purchaseActive.value = false;
  detailClosed = true; detailActive = false; detailRequest++
  // #ifdef H5
  window.removeEventListener('hashchange', syncWebRoute)
  // #endif
})
</script>

<style scoped>
.detail-tags{display:flex;flex-wrap:wrap;gap:8px;margin:15px 0}.detail-tags text{padding:5px 10px;background:#edf3ff;color:#5b76a0;font-size:12px;border-radius:20px}.seller-profile{display:flex;gap:12px;align-items:center}.seller-avatar{width:38px;height:38px;border-radius:50%}
.page { max-width: 1000px; padding: 28px; min-height: 100vh; background: #f5f7fc; box-sizing: border-box; font-size: 14px; }
.card { background: #fff; border: 1px solid #e5ebf5; border-radius: 20px; overflow: hidden; margin-bottom: 24px; box-shadow: 0 6px 24px #243c6505; }
.detail-card { display: grid; grid-template-columns: 44% 56%; }
.detail-visual { min-height: 370px; }
.detail-copy { padding: 32px; min-width: 0; }
.detail-eyebrow { display: block; color: #90a3c1; font-size: 10px; letter-spacing: 1.5px; margin-bottom: 14px; }
.title { display: block; font-size: 26px; line-height: 1.45; font-weight: 750; color: #1c3358; overflow-wrap: anywhere; }
.price { display: block; margin: 20px 0; color: #de7944; font-size: 32px; line-height: 1.3; font-weight: 600; }
.description { display: block; white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.9; color: #687c97; font-size: 13px; padding: 14px 0; border-top: 1px solid #edf1f7; border-bottom: 1px solid #edf1f7; }
.message, .error { display: block; margin: 12px 0; font-size: 12px; line-height: 1.8; }
.message { color: #8b9bb1; }
.owner { display: block; margin: 14px 0; color: #3c8b6d; font-size: 12px; }
.error { color: #bd3e40; background: #fff1ef; padding: 14px; border-radius: 10px; }
button { margin-top: 14px; }
.detail-copy :deep(.count) { margin: 10px 0; font-size: 12px; color: #8b9bb1; }
.page :deep(.heading) { font-size: 18px; color: #2b456c; }
.page :deep(.summary), .page :deep(.hint) { font-size: 13px; line-height: 1.8; }
@media (max-width: 650px) { .page { padding: 20px 16px; } .detail-card { display: block; } .detail-visual { height: 250px; min-height: 0; } .detail-copy { padding: 24px; } .title { font-size: 23px; } }
</style>

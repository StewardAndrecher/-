<template>
  <view class="page">
    <text v-if="loading" class="message">
      正在加载商品…
    </text>

    <text v-else-if="errorMessage" class="error">
      {{ errorMessage }}
    </text>

    <view v-else-if="product" class="card">
      <image v-if="imageUrl(product.coverImageId)" class="cover" :src="imageUrl(product.coverImageId)" mode="aspectFit" />
      <text class="title">{{ product.title }}</text>
      <text class="message">分类：{{ product.category?.name || '未分类' }}</text>

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
      <button v-if="product.seller" size="mini" :disabled="commentsBusy" @click="openSeller">查看卖家主页</button>
      <text v-if="product.seller && currentUser?.id === product.seller.id" class="owner">
        这是你发布的商品
      </text>
      <button v-if="product.status !== 'SOLD' && product.seller && currentUser?.id === product.seller.id"
        type="primary" :disabled="commentsBusy" @click="openEdit">编辑商品</button>
    </view>

    <text v-if="product && historyMessage" class="message">{{ historyMessage }}</text>

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
.cover { width: 100%; height: 360rpx; margin-bottom: 24rpx; }
.page {
  padding: 32rpx;
  min-height: 100vh;
  background: #f5f7fa;
  box-sizing: border-box;
}

.card {
  padding: 32rpx;
  background: #ffffff;
  border-radius: 20rpx;
  margin-bottom: 24rpx;
}

.title {
  display: block;
  font-size: 38rpx;
  font-weight: bold;
}

.price {
  display: block;
  margin: 24rpx 0;
  color: #e65b35;
  font-size: 40rpx;
}

.description {
  display: block;
  white-space: pre-wrap;
  overflow-wrap: break-word;
  line-height: 1.7;
  color: #444444;
}

.message,
.error {
  display: block;
  margin: 24rpx 0;
}

.message {
  color: #666666;
}

.owner { display: block; margin: 24rpx 0; color: #16834a; }

.error {
  color: #d93025;
}

button {
  margin-top: 24rpx;
}
</style>

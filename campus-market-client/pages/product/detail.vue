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

      <text class="price">
        ¥{{ (product.priceInCents / 100).toFixed(2) }}
      </text>

      <text class="description">
        {{ product.description || '暂未填写描述' }}
      </text>

      <text class="message">
        商品编号：{{ product.id }}
      </text>
      <text class="message">状态：{{ product.status === 'ON_SALE' ? '在售' : '已下架' }}</text>
      <text class="message">
        {{ product.seller ? '卖家：' + product.seller.nickname : '历史商品，卖家信息未登记' }}
      </text>
      <text v-if="product.seller && currentUser?.id === product.seller.id" class="owner">
        这是你发布的商品
      </text>
      <button v-if="product.seller && currentUser?.id === product.seller.id"
        type="primary" :disabled="commentsBusy" @click="openEdit">编辑商品</button>
    </view>

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
import { onLoad, onShow, onUnload } from '@dcloudio/uni-app'
import { getProduct } from '@/api/product.js'
import { currentUser, getToken } from '@/utils/session.js'
import { imageUrl } from '@/api/image.js'
import ProductComments from '@/components/ProductComments.vue'



const productId = ref(0)
const product = ref(null)
const loading = ref(false)
const errorMessage = ref('')
const commentsBusy = ref(false)
let detailClosed = false
let detailRequest = 0

watch(() => currentUser.value?.id, () => {
  detailRequest++; loading.value = false; commentsBusy.value = false; product.value = null
  fetchProduct()
})

async function fetchProduct() {
  if (detailClosed || loading.value || commentsBusy.value || productId.value <= 0) return

  const token = getToken(), number = ++detailRequest
  loading.value = true
  errorMessage.value = ''
  product.value = null

  try {
    const item = await getProduct(productId.value)
    if (detailClosed || number !== detailRequest || token !== getToken()) return

    if (
      item === null ||
      typeof item !== 'object' ||
      item.id !== productId.value ||
      typeof item.title !== 'string' ||
      typeof item.description !== 'string' ||
      !['ON_SALE', 'OFF_SHELF'].includes(item.status) ||
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
  } catch (error) {
    if (detailClosed || number !== detailRequest || (token !== getToken() && getToken())) return
    errorMessage.value = error.statusCode === 404
      ? '商品不存在或已下架'
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

function openEdit() {
  if (commentsBusy.value) return
  if (!product.value?.seller || product.value.seller.id !== currentUser.value?.id) return
  uni.navigateTo({
    url: `/pages/product/edit?id=${productId.value}`,
    fail: () => uni.showToast({ title: '打开编辑页失败', icon: 'none' })
  })
}

onLoad((options) => {
  const id = Number(options.id)

  if (!Number.isSafeInteger(id) || id <= 0) {
    errorMessage.value = '商品ID不正确，请返回首页重新选择'
    return
  }

  productId.value = id
})

// 从编辑页返回时重新读取，显示已保存的商品。
onShow(fetchProduct)
onUnload(() => { detailClosed = true; detailRequest++ })
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

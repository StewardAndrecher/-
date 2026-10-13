<template>
  <view class="card" @click="requestOpenDetail">
    <image v-if="imageUrl(product.coverImageId)" class="cover" :src="imageUrl(product.coverImageId)" mode="aspectFill" />
    <text class="title">{{ product.title }}</text>
    <text class="description">{{ product.description }}</text>
    <text class="seller">{{ product.seller ? '卖家：' + product.seller.nickname : '历史商品，卖家信息未登记' }}</text>
    <text class="price">
      ¥{{ (product.priceInCents / 100).toFixed(2) }}
    </text>

    <button :disabled="favoriteDisabled" @click.stop="requestToggleFavorite">
      {{ favoritePending ? '同步中…' : (isFavorite ? '取消收藏' : '收藏商品') }}
    </button>

    <text v-if="isFavorite" class="tip">已加入收藏</text>
  </view>
</template>

<script setup>
import { imageUrl } from '@/api/image.js'
const props = defineProps({
  product: {
    type: Object,
    required: true
  },
  favoriteDisabled: { type: Boolean, default: false },
  favoritePending: { type: Boolean, default: false },
  isFavorite: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits([
  'toggle-favorite',
  'open-detail'
])

function requestOpenDetail() {
  emit('open-detail', props.product.id)
}

function requestToggleFavorite() {
  if (!props.favoriteDisabled) emit('toggle-favorite', props.product.id)
}
</script>

<style scoped>
.cover { width: 100%; height: 300rpx; border-radius: 12rpx; margin-bottom: 20rpx; }
.card {
  padding: 32rpx;
  margin-bottom: 24rpx;
  border-radius: 20rpx;
  background: #ffffff;
}

.title {
  display: block;
  font-size: 34rpx;
  font-weight: bold;
}

.description {
  display: block;
  margin-top: 16rpx;
  color: #666666;
  font-size: 28rpx;
}

.price {
  display: block;
  margin: 24rpx 0;
  color: #e65b35;
  font-size: 38rpx;
}

.seller { display: block; margin-top: 16rpx; color: #666666; font-size: 26rpx; }

.tip {
  display: block;
  margin-top: 20rpx;
  color: #16834a;
  font-size: 26rpx;
}
</style>

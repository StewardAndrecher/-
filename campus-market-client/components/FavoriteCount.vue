<template>
  <text class="count">{{ favoriteCountText(value, stale) }}</text>
</template>

<script setup>
import { favoriteCountText } from '@/utils/product-popularity.js'
defineProps({
  value: { type: Number, default: null },
  stale: { type: Boolean, default: false }
})
</script>

<style scoped>
.count { display: block; margin: 20rpx 0; color: #666; font-size: 26rpx; }
</style>

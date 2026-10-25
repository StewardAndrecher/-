<template>
  <view class="page">
    <text class="title">平台处理与复核</text>
    <text v-if="!productId" class="error">商品编号无效，请返回列表重新打开</text>
    <ProductModerationPanel v-else :key="productId" :product-id="productId" mode="seller" :active="active" />
  </view>
</template>
<script setup>
import { ref } from 'vue'
import { onLoad, onShow, onHide, onUnload } from '@dcloudio/uni-app'
import ProductModerationPanel from '@/components/ProductModerationPanel.vue'
const productId = ref(0), active = ref(false)
onLoad(options => { const raw = options?.id; if (typeof raw === 'string' && /^[1-9][0-9]*$/.test(raw) && Number.isSafeInteger(Number(raw))) productId.value = Number(raw) })
onShow(() => { active.value = true })
onHide(() => { active.value = false })
onUnload(() => { active.value = false })
</script>
<style scoped>.page { padding: 32rpx; min-height: 100vh; background: #f5f7fa; }.title { display: block; font-size: 40rpx; font-weight: bold; }.error { color: #b42318; }</style>

<template>
  <view v-if="(webEnabled || wechatEnabled) && canShareProduct(product)" class="share-panel">
    <text class="title">分享商品</text>
    <!-- #ifdef H5 -->
    <button :disabled="disabled || copying" :loading="copying" @click="copyLink">
      {{ copying ? '复制中…' : '复制商品链接' }}
    </button>
    <text v-if="message" class="message">{{ message }}</text>
    <text v-if="link" class="link" selectable>{{ link }}</text>
    <!-- #endif -->
    <!-- #ifdef MP-WEIXIN -->
    <button open-type="share" :disabled="disabled">分享给微信好友</button>
    <!-- #endif -->
  </view>
</template>

<script setup>
import { ref, watch, onUnmounted } from 'vue'
import { canShareProduct, webProductLink } from '@/utils/product-share.js'
const props = defineProps({ product: { type: Object, required: true }, disabled: { type: Boolean, default: false } })
let webEnabled = false, wechatEnabled = false
// #ifdef H5
webEnabled = true
// #endif
// #ifdef MP-WEIXIN
wechatEnabled = true
// #endif
const copying = ref(false)
const link = ref('')
const message = ref('')
let closed = false, generation = 0
watch(() => [props.product?.id, props.product?.status], () => {
  generation++; copying.value = false; link.value = ''; message.value = ''
})
async function copyLink() {
  if (!webEnabled || closed || props.disabled || copying.value || !canShareProduct(props.product)) return
  const request = ++generation
  copying.value = true
  message.value = ''
  try {
    // #ifdef H5
    const url = webProductLink(props.product.id, window.location.origin, window.location.pathname)
    link.value = url
    await new Promise((resolve, reject) => uni.setClipboardData({
      data: url, showToast: false, success: resolve, fail: reject
    }))
    if (!closed && request === generation) message.value = '商品链接已复制'
    // #endif
  } catch (error) {
    if (!closed && request === generation) message.value = link.value
      ? '复制失败，可长按或选中下方链接手动复制'
      : '当前页面无法生成商品链接，请重新打开网页'
  } finally { if (!closed && request === generation) copying.value = false }
}
onUnmounted(() => { closed = true; generation++ })
</script>

<style scoped>
.share-panel { padding: 24rpx; margin-bottom: 24rpx; background: #fff; border-radius: 16rpx; }
.title { display: block; font-size: 30rpx; font-weight: bold; }
.message, .link { display: block; margin-top: 16rpx; font-size: 26rpx; }
.link { color: #2863c7; overflow-wrap: anywhere; }
button { margin-top: 20rpx; }
</style>

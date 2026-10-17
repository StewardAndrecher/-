<template>
  <view v-if="networkStatus.state === 'offline' || networkStatus.recovered" class="network-banner">
    <text class="message">{{ networkStatus.state === 'offline'
      ? '设备当前未连接网络，页面中已有的商品可能不是最新数据。'
      : '设备已恢复网络连接，可手动刷新商品。' }}</text>
    <text class="hint">网络连接状态不代表服务器可用；操作结果以接口响应为准。</text>
    <button size="mini" :disabled="busy" @click="refreshNetworkStatus">重新检查网络</button>
    <button v-if="allowRefresh && networkStatus.recovered" size="mini" :disabled="busy" @click="retry">刷新商品</button>
  </view>
</template>

<script setup>
import { onMounted, onUnmounted } from 'vue'
import { networkStatus, acquireNetworkStatus, refreshNetworkStatus, acknowledgeNetworkRecovery } from '@/utils/network-status.js'

const props = defineProps({ busy: { type: Boolean, default: false }, allowRefresh: { type: Boolean, default: false } })
const emit = defineEmits(['retry'])
let release, closed = false
onMounted(() => { if (!closed) release = acquireNetworkStatus() })
onUnmounted(() => { closed = true; release?.() })
function retry() {
  if (closed || props.busy || !props.allowRefresh || !networkStatus.value.recovered) return
  acknowledgeNetworkRecovery()
  emit('retry')
}
</script>

<style scoped>
.network-banner { padding: 20rpx; margin: 20rpx 0; border-radius: 12rpx; background: #fff3db; }
.message { display: block; color: #704a00; line-height: 1.6; }
.hint { display: block; color: #666; font-size: 24rpx; line-height: 1.6; margin: 12rpx 0; }
.network-banner button { margin: 8rpx 12rpx 0 0; }
</style>

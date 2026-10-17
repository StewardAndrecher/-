<template>
  <view class="page">
    <text class="title">开发连接诊断</text>
    <NetworkStatusBanner />
    <text v-if="!enabled">此页面只用于开发调试。</text>
    <view v-else>
      <text class="line">平台：{{ configuration.platform }} / {{ configuration.envVersion || 'Web' }}</text>
      <text class="line">设备：{{ configuration.devicePlatform || '浏览器' }}</text>
      <text class="line">设备网络：{{ networkStatus.state }} / {{ networkStatus.networkType }}（不代表后端可用）</text>
      <text class="line">服务器：{{ configuration.baseUrl || '未配置' }}</text>
      <text v-if="configuration.error" class="error">{{ configuration.error }}</text>
      <button type="primary" :disabled="working" @click="runCheck">{{ working ? '检查中…' : '开始检查' }}</button>
      <view v-if="report" class="result">
        <text class="title">{{ report.summary.title }}</text>
        <text class="line">{{ report.summary.hint }}</text>
        <view v-for="item in [report.liveness, report.readiness]" :key="item.path" class="probe">
          <text class="line">{{ item.path }}</text>
          <text class="line">结果：{{ item.state }}；HTTP：{{ item.httpStatus || '未收到' }}；耗时：{{ item.elapsedMs }}ms</text>
        </view>
      </view>
      <text class="hint">liveness 检查应用是否存活；readiness 检查应用及数据库是否就绪。</text>
      <text v-if="configuration.platform === 'app'" class="hint">App开发调试使用电脑局域网地址；App资源文件需要手机或模拟器的运行基座，不能当作网页直接打开。</text>
      <text class="hint">手机里的 localhost 指向手机自身。开发版手机需与电脑处于可互通网络，并使用电脑当前的局域网地址。</text>
      <text class="hint">本页只发送两次匿名GET，不携带登录Token，也不修改商品、收藏或留言。</text>
    </view>
    <button :disabled="working" @click="back">返回</button>
  </view>
</template>

<script setup>
import { ref } from 'vue'
import NetworkStatusBanner from '@/components/NetworkStatusBanner.vue'
import { networkStatus } from '@/utils/network-status.js'
import { onUnload } from '@dcloudio/uni-app'
import { SERVER_CONFIG, DIAGNOSTICS_ENABLED } from '@/config/server.js'
import { startConnectionCheck } from '@/utils/connection-check.js'

const configuration = SERVER_CONFIG
const enabled = DIAGNOSTICS_ENABLED
const working = ref(false)
const report = ref(null)
let closed = false, operation = 0, currentCheck

async function runCheck() {
  if (closed || working.value || !enabled) return
  const number = ++operation
  working.value = true
  report.value = null
  currentCheck = startConnectionCheck()
  try {
    const result = await currentCheck.promise
    if (!closed && number === operation) report.value = result
  } finally {
    if (!closed && number === operation) { working.value = false; currentCheck = null }
  }
}

function back() {
  if (working.value) return
  uni.navigateBack({ fail: () => uni.reLaunch({ url: '/pages/index/index' }) })
}
onUnload(() => { closed = true; operation++; currentCheck?.abort() })
</script>

<style scoped>
.page { padding: 28rpx; }
.title { display: block; font-size: 34rpx; font-weight: bold; margin-bottom: 24rpx; }
.line { display: block; margin: 16rpx 0; overflow-wrap: anywhere; }
.result { margin: 24rpx 0; padding: 24rpx; background: #eef7f0; border-radius: 16rpx; }
.probe { border-top: 1rpx solid #ccc; margin-top: 20rpx; padding-top: 10rpx; }
.hint { display: block; margin: 24rpx 0; color: #666; line-height: 1.7; }
.error { display: block; color: #c0392b; margin: 20rpx 0; }
button { margin: 24rpx 0; }
</style>

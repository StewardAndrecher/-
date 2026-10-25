<template>
  <view class="page">
    <text class="title">{{ admin ? '举报处理' : '举报进度' }}</text>
    <text v-if="!currentUser" class="hint">请先登录后查看举报</text><button v-if="!currentUser" @click="login">前往登录</button>
    <text v-if="state.loading" class="hint">正在读取举报…</text>
    <view v-if="ticket" class="card">
      <text class="heading">{{ ticket.snapshot.title }}</text><text class="hint">工单 {{ ticket.id }} · {{ reportCategoryLabel(ticket.category) }} · {{ reportStates[ticket.status] }}</text>
      <text class="body">举报说明：{{ ticket.reason }}</text><text class="hint">提交时间：{{ ticket.createdAt }}</text>
      <text class="body">举报时描述：{{ ticket.snapshot.description || '无' }}</text><text class="hint">举报时标价：¥{{ (ticket.snapshot.priceInCents / 100).toFixed(2) }}</text>
      <text v-if="ticket.resolution" class="body">处理结果：{{ ticket.resolution }}</text><text v-if="ticket.resolvedAt" class="hint">处理时间：{{ ticket.resolvedAt }}</text>
    </view>
    <view v-if="admin && state.data" class="card">
      <text class="heading">当前商品内容</text><text class="hint">举报人：{{ state.data.reporter.nickname }}</text>
      <text class="body">{{ state.data.currentProduct.product.title }}</text><text class="body">{{ state.data.currentProduct.product.description }}</text>
      <text class="hint">{{ state.data.currentProduct.product.status === 'ON_SALE' ? '在售' : state.data.currentProduct.product.status === 'OFF_SHELF' ? '已下架' : '已售出' }} · {{ reviewLabels[state.data.currentProduct.moderation.state] }}</text>
      <button @click="review">查看平台处理记录</button>
      <view v-if="ticket.status === 'OPEN'">
        <textarea v-model="reason" maxlength="300" :disabled="busy || !!state.pending" class="textarea" placeholder="填写处理原因（驳回2～300字，下架2～200字）" />
        <button :disabled="busy || !!state.pending" @click="resolve('DISMISS')">驳回并结案</button>
        <button :disabled="busy || !!state.pending || !canBlock" @click="resolve('BLOCK')">下架商品并结案</button>
        <text class="hint">商品已受限或已售出时不能重复下架；已有有效订单时由后端拒绝。驳回不会改变商品。</text>
      </view>
    </view>
    <text v-if="state.error" class="error">{{ state.error }}</text><text v-if="state.pending" class="hint">上次处理结果尚未确认。重试使用原操作和说明。</text>
    <button v-if="state.pending" :disabled="busy" @click="retry">重试同一处理</button><button v-if="currentUser" :disabled="busy" @click="task?.refresh()">刷新举报记录</button>
  </view>
</template>
<script setup>
import { ref, computed, watch } from 'vue'
import { onLoad, onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { currentUser, getToken } from '@/utils/session.js'
import { getReport, resolveReport } from '@/api/report.js'
import { reviewLabels } from '@/utils/product-moderation.mjs'
import { newCommentRequestId } from '@/utils/comment-submissions.js'
import { reportStates, reportCategoryLabel, reportText, createReportTask, validateReportDetail, validateReportReceipt } from '@/utils/reports.mjs'
const id = ref(0), admin = ref(false), active = ref(false), reason = ref(''), confirming = ref(false)
const state = ref({ data: null, loading: false, submitting: false, error: '', pending: null })
const ticket = computed(() => admin.value ? state.value.data?.ticket : state.value.data), busy = computed(() => state.value.loading || state.value.submitting || confirming.value)
const canBlock = computed(() => state.value.data?.currentProduct?.product.status !== 'SOLD' && state.value.data?.currentProduct?.moderation.state === 'CLEAR')
let task
onLoad(options => {
  const n = Number(options?.id); if (!/^[1-9][0-9]*$/.test(String(options?.id || '')) || !Number.isSafeInteger(n)) { state.value.error = '举报编号不正确'; return }; id.value = n; admin.value = options?.mode === 'admin'
  let productId
  task = createReportTask({ identity: () => ({ userId: currentUser.value?.id, token: getToken() }), load: () => getReport(n, admin.value), send: data => resolveReport(n, data),
    validateLoad: data => { const r = validateReportDetail(data, n, admin.value); productId = admin.value ? r.ticket.productId : r.productId; return r },
    validateReceipt: (r,p) => validateReportReceipt(r, { id: n, productId, action: p.action, reason: p.reason }), notify: value => { state.value = value } })
})
watch(() => [active.value, currentUser.value?.id, getToken()], () => { reason.value = ''; active.value ? task?.refresh() : task?.deactivate() }, { flush: 'sync' })
onShow(() => { active.value = true }); onHide(() => { active.value = false }); onUnload(() => task?.dispose())
async function resolve(action) {
  if (!admin.value || busy.value || state.value.pending || ticket.value?.status !== 'OPEN') return
  confirming.value = true
  try {
    const text = reportText(reason.value, action === 'BLOCK' ? 200 : 300), original = ticket.value, product = state.value.data.currentProduct.product, user = currentUser.value?.id, token = getToken()
    const ok = await new Promise(resolve => uni.showModal({ title: action === 'BLOCK' ? '下架并结案' : '驳回并结案', content: '处理结果将记录并向举报人展示，确认提交？', success: r => resolve(r.confirm), fail: () => resolve(false) }))
    if (!ok || !active.value || user !== currentUser.value?.id || token !== getToken() || ticket.value?.id !== original.id || ticket.value?.version !== original.version || state.value.data?.currentProduct.product.version !== product.version) return
    const r = await task.execute({ action, reason: text, version: original.version, ...(action === 'BLOCK' ? { productVersion: product.version } : {}), requestId: newCommentRequestId() })
    if (r) { reason.value = ''; await task.refresh() }
  } catch(e) { uni.showToast({ title: e.message, icon: 'none' }) } finally { confirming.value = false }
}
async function retry() { const r = await task.retry(); if (r) { reason.value = ''; await task.refresh() } }
function review() { if (ticket.value) uni.navigateTo({ url: `/pages/admin/product-review?id=${ticket.value.productId}` }) }
function login() { uni.navigateTo({ url: '/pages/user/login' }) }
</script>
<style scoped>.page{padding:32rpx;background:#f5f7fa;min-height:100vh}.title{display:block;font-size:40rpx;font-weight:bold}.card{padding:24rpx;margin:24rpx 0;background:white;border-radius:12rpx}.heading{display:block;font-size:32rpx;font-weight:bold}.hint{display:block;color:#64748b;margin:20rpx 0}.body{display:block;white-space:pre-wrap;margin:16rpx 0}.textarea{width:100%;min-height:200rpx}.error{display:block;color:#b42318}button{margin:16rpx 0}</style>

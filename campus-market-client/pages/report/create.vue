<template>
  <view class="page">
    <text class="title">举报商品</text><text class="hint">请如实说明问题，不要填写手机号、住址等私人信息。提交后在“我的举报”查看结果。</text>
    <text v-if="!currentUser" class="hint">请先登录后提交举报</text><button v-if="!currentUser" @click="login">前往登录</button>
    <text v-if="state.loading" class="hint">正在读取商品…</text>
    <view v-if="state.data" class="card"><text class="heading">{{ state.data.title }}</text><text class="hint">{{ state.data.description || '暂无描述' }}</text>
      <picker :range="reportCategories" range-key="label" :value="category" :disabled="busy || !!state.pending" @change="changeCategory"><view class="filter">举报类型：{{ reportCategories[category].label }} ▾</view></picker>
      <textarea v-model="reason" maxlength="300" :disabled="busy || !!state.pending" placeholder="说明商品存在的问题（2～300字）" class="textarea" />
      <button :disabled="busy || !!state.pending" @click="submit">提交举报</button>
    </view>
    <text v-if="state.error" class="error">{{ state.error }}</text><text v-if="state.pending" class="hint">上次提交结果尚未确认。重试使用原内容，不会重复创建工单。</text>
    <button v-if="state.pending" :disabled="busy" @click="retry">重试同一举报</button>
    <button v-if="currentUser" :disabled="busy" @click="task?.refresh()">重新读取商品</button><button @click="mine">我的举报</button>
  </view>
</template>
<script setup>
import { ref, computed, watch } from 'vue'
import { onLoad, onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { currentUser, getToken } from '@/utils/session.js'
import { getProduct } from '@/api/product.js'
import { createReport } from '@/api/report.js'
import { newCommentRequestId } from '@/utils/comment-submissions.js'
import { reportCategories, reportText, createReportTask, validateReportReceipt } from '@/utils/reports.mjs'
const id = ref(0), active = ref(false), category = ref(0), reason = ref(''), confirming = ref(false)
const state = ref({ data: null, loading: false, submitting: false, error: '', pending: null })
const busy = computed(() => state.value.loading || state.value.submitting || confirming.value)
let task
onLoad(options => {
  const n = Number(options?.id); if (!/^[1-9][0-9]*$/.test(String(options?.id || '')) || !Number.isSafeInteger(n)) { state.value.error = '商品编号不正确'; return }; id.value = n
  task = createReportTask({ identity: () => ({ userId: currentUser.value?.id, token: getToken() }), load: () => getProduct(n), send: data => createReport(n, data),
    validateLoad: p => { if (p?.id !== n || p.status !== 'ON_SALE' || p.seller?.id === currentUser.value?.id || typeof p.title !== 'string') throw new Error('当前商品不可举报'); return p },
    validateReceipt: (r,p) => validateReportReceipt(r, { productId: n, category: p.category, reason: p.reason }), notify: value => { state.value = value } })
})
watch(() => [active.value, currentUser.value?.id, getToken()], () => { reason.value = ''; active.value ? task?.refresh() : task?.deactivate() }, { flush: 'sync' })
onShow(() => { active.value = true }); onHide(() => { active.value = false }); onUnload(() => task?.dispose())
function changeCategory(e) { const n = Number(e.detail.value); if (Number.isInteger(n) && reportCategories[n]) category.value = n }
async function submit() {
  if (busy.value || !state.value.data || state.value.pending) return
  confirming.value = true
  try {
    const text = reportText(reason.value), type = reportCategories[category.value].value, user = currentUser.value?.id, token = getToken()
    const ok = await new Promise(resolve => uni.showModal({ title: '提交举报', content: '确认提交此商品的问题说明？请确保内容真实。', success: r => resolve(r.confirm), fail: () => resolve(false) }))
    if (!ok || !active.value || user !== currentUser.value?.id || token !== getToken()) return
    const r = await task.execute({ category: type, reason: text, requestId: newCommentRequestId() }); if (r) done(r)
  } catch(e) { uni.showToast({ title: e.message, icon: 'none' }) } finally { confirming.value = false }
}
async function retry() { const r = await task.retry(); if (r) done(r) }
function done(r) { uni.redirectTo({ url: `/pages/report/detail?id=${r.ticket.id}` }) }
function mine() { uni.navigateTo({ url: '/pages/report/list' }) }
function login() { uni.navigateTo({ url: '/pages/user/login' }) }
</script>
<style scoped>.page{padding:32rpx;background:#f5f7fa;min-height:100vh}.title{display:block;font-size:40rpx;font-weight:bold}.heading{font-weight:bold}.hint{display:block;color:#64748b;margin:20rpx 0}.card{background:white;border-radius:12rpx;padding:24rpx;margin:24rpx 0}.filter{padding:24rpx 0}.textarea{width:100%;min-height:200rpx}.error{display:block;color:#b42318}button{margin:20rpx 0}</style>

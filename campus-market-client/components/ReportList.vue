<template>
  <view>
    <text v-if="!currentUser" class="hint">请先登录后查看举报工单</text><button v-if="!currentUser" @click="login">前往登录</button>
    <picker :range="filters" range-key="label" :value="filter" @change="change"><view class="card">处理状态：{{ filters[filter].label }} ▾</view></picker>
    <button v-if="currentUser" :disabled="state.busy" @click="loader.refresh(active)">刷新举报列表</button>
    <text v-if="state.busy" class="hint">正在加载…</text><text v-if="state.error" class="error">{{ state.error }}</text>
    <view v-for="t in state.data?.records || []" :key="t.id" class="card">
      <text class="heading">{{ t.snapshot.title }}</text><text class="hint">工单 {{ t.id }} · {{ reportCategoryLabel(t.category) }} · {{ reportStates[t.status] }}</text>
      <text class="body">{{ t.reason }}</text><text v-if="t.resolution" class="body">处理结果：{{ t.resolution }}</text>
      <button @click="detail(t.id)">{{ admin ? '查看与处理' : '查看进度' }}</button>
    </view>
    <text v-if="state.data && !state.data.records.length" class="hint">暂无符合条件的举报</text>
    <view v-if="state.data" class="row"><button size="mini" :disabled="state.busy || query.page <= 1" @click="page(-1)">上一页</button><text>第 {{ query.page }} / {{ pages }} 页</text><button size="mini" :disabled="state.busy || query.page >= pages" @click="page(1)">下一页</button></view>
  </view>
</template>
<script setup>
import { ref, computed, watch, onUnmounted } from 'vue'
import { currentUser, getToken } from '@/utils/session.js'
import { createAdminLoader } from '@/utils/admin-access.mjs'
import { listReports } from '@/api/report.js'
import { reportStates, reportCategoryLabel, validateReportPage } from '@/utils/reports.mjs'
const props = defineProps({ admin: Boolean, active: Boolean })
const filters = [{ value: '', label: '全部' }, ...Object.entries(reportStates).map(([value,label]) => ({ value,label }))]
const filter = ref(props.admin ? 1 : 0), query = ref({ page: 1, size: 10, status: props.admin ? 'OPEN' : '' })
const state = ref({ data: null, busy: false, error: '', status: 0 }), pages = computed(() => Math.max(1, Math.ceil((state.value.data?.total || 0) / query.value.size)))
const loader = createAdminLoader(() => listReports(props.admin, { ...query.value }), () => ({ userId: currentUser.value?.id, token: getToken() }), data => validateReportPage(data, query.value), value => { state.value = value })
watch(() => [props.active, currentUser.value?.id, getToken()], () => loader.refresh(props.active), { immediate: true, flush: 'sync' })
onUnmounted(() => loader.dispose())
function change(e) { const n = Number(e.detail.value); if (Number.isInteger(n) && filters[n]) { filter.value = n; query.value = { page: 1, size: 10, status: filters[n].value }; loader.refresh(props.active) } }
function page(delta) { if (state.value.busy || !state.value.data) return; query.value = { ...query.value, page: query.value.page + delta }; loader.refresh(props.active) }
function detail(id) { uni.navigateTo({ url: `/pages/report/detail?id=${id}${props.admin ? '&mode=admin' : ''}` }) }
function login() { uni.navigateTo({ url: '/pages/user/login' }) }
</script>
<style scoped>
.card{background:#fff;padding:24rpx;margin:20rpx 0;border-radius:12rpx}.hint{display:block;color:#64748b;margin:16rpx 0}.heading{display:block;font-size:32rpx;font-weight:bold}.body{display:block;white-space:pre-wrap}.error{display:block;color:#b42318}.row{display:flex;align-items:center;gap:16rpx}button{margin-top:16rpx}
</style>

<template>
  <view>
    <button size="mini" @click="open">站内通知{{ state.data ? `（${state.data.unreadCount}条未读）` : '' }}</button>
    <text v-if="state.error && active" class="hint">未读数暂不可用</text>
    <button v-if="active" size="mini" :disabled="state.busy" @click="loader.refresh(true)">刷新通知数</button>
  </view>
</template>
<script setup>
import { ref, watch, onUnmounted } from 'vue'
import { currentUser, getToken } from '@/utils/session.js'
import { getNotificationUnread } from '@/api/notification.js'
import { createAdminLoader } from '@/utils/admin-access.mjs'
import { validateNotificationUnread } from '@/utils/notifications.mjs'
const props = defineProps({ active: Boolean })
const state = ref({ data: null, busy: false, error: '', status: 0 })
const loader = createAdminLoader(getNotificationUnread, () => ({ userId: currentUser.value?.id, token: getToken() }), validateNotificationUnread, value => { state.value = value })
watch(() => [props.active, currentUser.value?.id, getToken()], () => loader.refresh(props.active), { immediate: true, flush: 'sync' })
onUnmounted(() => loader.dispose())
function open() { uni.navigateTo({ url: '/pages/notification/list', fail: () => uni.showToast({ title: '打开通知失败', icon: 'none' }) }) }
</script>
<style scoped>.hint{display:block;color:#64748b;font-size:24rpx}</style>

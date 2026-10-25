<template>
  <button v-if="state.data?.permissions.includes('ADMIN_ACCESS')" size="mini" @click="openAdmin">管理中心</button>
  <view v-else-if="state.error && active">
    <text class="error">管理入口暂不可用</text>
    <button size="mini" :disabled="state.busy" @click="loader.refresh(true)">重试读取权限</button>
  </view>
</template>
<script setup>
import { ref, watch, onUnmounted } from 'vue'
import { getMyPermissions } from '@/api/admin.js'
import { createAdminLoader, validatePermissions } from '@/utils/admin-access.mjs'
import { currentUser, getToken } from '@/utils/session.js'
const props = defineProps({ active: { type: Boolean, default: false } })
const state = ref({ data: null, busy: false, error: '', status: 0 })
const loader = createAdminLoader(getMyPermissions, () => ({ userId: currentUser.value?.id, token: getToken() }), validatePermissions, value => { state.value = value })
watch(() => [props.active, currentUser.value?.id, getToken()], () => loader.refresh(props.active), { immediate: true, flush: 'sync' })
onUnmounted(() => loader.dispose())
function openAdmin() {
  uni.navigateTo({ url: '/pages/admin/index', fail: () => uni.showToast({ title: '打开管理中心失败', icon: 'none' }) })
}
</script>
<style scoped>.error { color: #b42318; font-size: 24rpx; }</style>

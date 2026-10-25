<template><view class="page"><text class="title">我的举报</text><ReportList :admin="false" :active="active" /></view></template>
<script setup>
import { ref } from 'vue'
import { onShow, onHide, onUnload } from '@dcloudio/uni-app'
import ReportList from '@/components/ReportList.vue'
const active = ref(false)
onShow(() => { active.value = true }); onHide(() => { active.value = false }); onUnload(() => { active.value = false })
</script>
<style scoped>.page{padding:32rpx;min-height:100vh;background:#f5f7fa}.title{display:block;font-size:40rpx;font-weight:bold}</style>

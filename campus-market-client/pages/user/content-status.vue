<template>
 <view class="service-page"><view class="page-heading"><text class="eyebrow">TRUST & SAFETY</text><text class="page-title">内容与账号状态</text><text class="page-subtitle">了解发布规则，查看处理记录与复核进度。</text></view>
 <view class="notice"><text>请如实描述商品，避免欺诈、刷单引流或交易违禁物品。平台会校验文本、限制高频发布；商品审核和举报工单继续独立处理。</text></view>
 <button v-if="!currentUser" class="primary" @click="login">登录查看账号状态</button><text v-if="state.error" class="error">{{ state.error }}</text><text v-if="state.message" class="success">{{ state.message }}</text><text v-if="state.loading" class="muted">正在读取…</text>
 <view v-if="state.data" class="surface"><text class="section-title">{{ state.data.restricted ? '公开内容发布受限' : '当前可正常发布' }}</text><text class="muted">{{ state.data.username }} · 登录、账号安全及交易售后不受此限制影响。</text><template v-if="state.data.restricted"><text class="body-copy">处理原因：{{ state.data.reason }}</text><text class="muted">截止时间：{{ state.data.until }}</text><text class="label">申请平台复核</text><textarea v-model="reason" class="textarea" :maxlength="200" :disabled="busy" placeholder="5～200字，说明需要复核的情况"/><button class="primary spaced" :disabled="busy || reason.trim().length<5" @click="appeal">提交复核申请</button></template></view>
 <view v-if="state.data?.events.length" class="surface"><text class="section-title">处理记录</text><view v-for="event in state.data.events" :key="event.id" class="record"><text class="label">{{ ({RESTRICT:'限制发布',RELEASE:'解除限制',APPEAL:'申请复核'})[event.action] }}</text><text class="body-copy">{{ event.reason }}</text><text class="muted">{{ event.createdAt }}</text></view></view>
 <view class="page-actions"><button v-if="currentUser" class="secondary" :disabled="busy" @click="manager.refresh()">刷新状态</button><button class="text-button" @click="home">返回首页</button></view></view>
</template>
<script setup>
import {ref} from 'vue'
import {usePrivatePage,ask,login,home} from '@/composables/usePrivatePage.js'
import {contentStatus,appealContent} from '@/api/platform.js'
import {validateContentState} from '@/utils/platform.mjs'
const reason=ref('')
const {state,manager,currentUser,busy}=usePrivatePage({load:contentStatus,validate:validateContentState,clear:()=>reason.value=''})
async function appeal(){const text=reason.value.trim();await manager.mutate({confirm:()=>ask('申请复核','每次处理记录可提交一次复核，请确认说明完整。'),write:snapshot=>appealContent({version:snapshot.version,reason:text}),validateReceipt:(result,snapshot,owner)=>validateContentState(result,owner),message:'复核申请已保存，请等待平台答复。'});reason.value=''}
</script>
<style scoped src="@/styles/service-pages.css"></style>
<style scoped>.spaced{margin-top:18px}.record{border-top:1px solid #e6edf7;margin-top:20px;padding-top:4px}</style>

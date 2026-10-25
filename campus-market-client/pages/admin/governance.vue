<template>
 <view class="service-page"><view class="page-heading"><text class="eyebrow">PLATFORM GOVERNANCE</text><text class="page-title">内容与滥用治理</text><text class="page-subtitle">查明原因、限定范围，为处理留下可复核记录。</text></view>
 <view class="notice"><text>本页仅管理公开内容发布限制。不会修改密码、管理员权限或交易结果；已有商品下架和举报继续从原审核入口处理。</text></view>
 <view class="surface"><text class="label">准确用户名</text><input v-model="username" class="field" :maxlength="32" :disabled="busy" placeholder="例如：student_01"/><button class="primary spaced" :disabled="busy || !currentUser" @click="search">查询账号</button></view><button v-if="!currentUser" class="secondary" @click="login">管理员登录</button>
 <text v-if="state.error" class="error">{{ state.error }}</text><text v-if="state.message" class="success">{{ state.message }}</text><text v-if="state.loading" class="muted">正在核验管理权限…</text>
 <view v-if="state.data" class="surface"><text class="section-title">{{ state.data.username }} · {{ state.data.restricted?'发布受限':'正常' }}</text><text class="muted">账号 #{{ state.data.userId }} · 版本 {{ state.data.version }}</text><text v-if="state.data.until" class="muted">限制截止：{{ state.data.until }}</text><text class="body-copy">{{ state.data.reason }}</text><text class="label">处理时长（小时）</text><input v-model="hours" type="number" class="field" :disabled="busy" placeholder="1～720"/><text class="label">处理原因或复核答复</text><textarea v-model="reason" class="textarea" :maxlength="200" :disabled="busy" placeholder="5～200字，说明证据与处理依据"/><view class="page-actions"><button class="primary" :disabled="busy || reason.trim().length<5" @click="change('RESTRICT')">限制公开内容发布</button><button class="secondary" :disabled="busy || !state.data.restricted || reason.trim().length<5" @click="change('RELEASE')">解除限制</button></view></view>
 <view v-if="state.data?.events.length" class="surface"><text class="section-title">处理与复核记录</text><view v-for="event in state.data.events" :key="event.id" class="record"><text class="label">{{ ({RESTRICT:'限制发布',RELEASE:'解除限制',APPEAL:'用户申请复核'})[event.action] }}</text><text class="body-copy">{{ event.reason }}</text><text class="muted">{{ event.createdAt }}</text></view></view><button class="text-button" @click="home">返回首页</button></view>
</template>
<script setup>
import {ref} from 'vue'
import {usePrivatePage,ask,login,home} from '@/composables/usePrivatePage.js'
import {governanceLookup,changeRestriction} from '@/api/platform.js'
import {validateContentState} from '@/utils/platform.mjs'
const username=ref(''),selected=ref(''),reason=ref(''),hours=ref('24')
const {state,manager,currentUser,busy}=usePrivatePage({load:async()=>selected.value?governanceLookup(selected.value):null,validate:(value,owner)=>value===null?null:validateContentState(value,owner,selected.value),clear:()=>{username.value='';selected.value='';reason.value='';hours.value='24'}})
function search(){if(busy.value)return;selected.value=username.value.trim();reason.value='';manager.refresh()}
async function change(action){const text=reason.value.trim(),duration=Number(hours.value);await manager.mutate({confirm:()=>ask(action==='RESTRICT'?'确认限制发布':'确认解除限制',`目标账号：${state.value.data.username}。${action==='RESTRICT'?`限制${duration}小时；` : ''}处理原因将对账号本人可见。`),write:snapshot=>changeRestriction(snapshot.userId,{action,hours:duration,reason:text,version:snapshot.version}),validateReceipt:(value,snapshot,owner)=>{validateContentState(value,owner,snapshot.username);if(value.version!==snapshot.version+1)throw new Error('处理版本不匹配')},message:'处理已保存，请核对记录。'});reason.value=''}
</script>
<style scoped src="@/styles/service-pages.css"></style>
<style scoped>.spaced{margin-top:18px}.record{padding-top:4px;margin-top:20px;border-top:1px solid #e6edf7}</style>

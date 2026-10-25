<template>
 <view class="service-page"><view class="page-heading"><text class="eyebrow">YOUR MEDIA LIBRARY</text><text class="page-title">图片空间</text><text class="page-subtitle">整理上传的图片，让每张好物照片都有去处。</text></view>
 <view class="notice"><text>被商品、头像、发布回执或云端草稿引用的图片会受到保护。回收站仍占用空间，至少保留30天后才可由管理员永久清理。未同步的本地草稿不计入服务端引用，误移后请及时恢复。</text></view>
 <button v-if="!currentUser" class="primary" @click="login">登录查看图片库</button><text v-if="state.error" class="error">{{ state.error }}</text><text v-if="state.message" class="success">{{ state.message }}</text>
 <view class="surface"><view class="segmented"><button :disabled="busy" :class="{active:scope==='ACTIVE'}" @click="select('ACTIVE')">图片库</button><button :disabled="busy" :class="{active:scope==='TRASH'}" @click="select('TRASH')">回收站</button></view><text v-if="state.data" class="muted">已计费 {{ displayBytes(state.data.chargedBytes) }} / {{ displayBytes(state.data.quotaBytes) }} · {{ state.data.count }} / {{ state.data.maxCount }} 张</text><text v-if="state.loading" class="muted">正在加载图片…</text></view>
 <view v-if="state.data" class="media-grid"><view v-for="image in state.data.items" :key="image.id" class="surface media-card"><image v-if="scope==='ACTIVE'" class="media-picture" :src="API_BASE_URL+'/api/images/'+image.id" mode="aspectFill"/><view v-else class="media-picture trash-placeholder">已移入回收站</view><text class="muted">{{ image.width }} × {{ image.height }} · {{ image.sizeKnown ? displayBytes(image.bytes) : '历史图片，大小待核验' }}</text><text class="muted">{{ image.referenced?'正在使用，已保护':'未发现服务端引用' }}</text><button class="secondary" :disabled="busy || (scope==='ACTIVE' && image.referenced)" @click="change(image)">{{ scope==='TRASH'?'恢复图片':'移入回收站' }}</button></view></view>
 <view v-if="state.data && !state.data.items.length" class="surface empty">{{ scope==='TRASH'?'回收站为空':'还没有上传图片' }}</view>
 <view v-if="state.data" class="pagination"><button class="secondary" :disabled="busy || state.data.page<=1" @click="move(-1)">上一页</button><text>{{ state.data.page }} / {{ state.data.totalPages }}</text><button class="secondary" :disabled="busy || state.data.page>=state.data.totalPages" @click="move(1)">下一页</button></view>
 <view class="page-actions"><button v-if="currentUser" class="secondary" :disabled="busy" @click="manager.refresh()">刷新清单</button><button class="text-button" @click="home">返回首页</button></view></view>
</template>
<script setup>
import {ref} from 'vue'
import {usePrivatePage,ask,login,home} from '@/composables/usePrivatePage.js'
import {mediaLibrary,changeMedia} from '@/api/platform.js'
import {validateLibrary,displayBytes} from '@/utils/platform.mjs'
import {API_BASE_URL} from '@/api/request.js'
const scope=ref('ACTIVE'),page=ref(1)
const {state,manager,currentUser,busy}=usePrivatePage({load:()=>mediaLibrary(scope.value,page.value),validate:(value,owner)=>validateLibrary(value,owner,scope.value),clear:()=>{scope.value='ACTIVE';page.value=1}})
function select(value){if(busy.value)return;scope.value=value;page.value=1;manager.refresh()}
function move(step){if(!busy.value&&state.value.data){page.value=state.value.data.page+step;manager.refresh()}}
async function change(image){const restore=scope.value==='TRASH';await manager.mutate({confirm:()=>ask(restore?'恢复图片':'移入回收站',restore?'恢复后可以继续在自己的商品或头像中使用。':'图片暂时不可访问，可从回收站恢复。请确认没有未同步的本机草稿需要这张图片。'),write:()=>changeMedia(image.id,restore,image.version),validateReceipt:result=>{if(result!==''&&result!==null)throw new Error('操作回执不完整')},message:restore?'图片已恢复。':'图片已移入回收站。'})}
</script>
<style scoped src="@/styles/service-pages.css"></style>
<style scoped>.media-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:16px}.media-card{padding:14px;margin:0}.media-picture{width:100%;height:165px;border-radius:10px;background:#eef3fc}.media-card button{margin-top:14px;padding:0 8px;font-size:12px}.trash-placeholder{display:flex;align-items:center;justify-content:center;color:#8b9cb6;font-size:13px}@media(max-width:650px){.media-grid{grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}.media-card{padding:10px}.media-picture{height:125px}}</style>

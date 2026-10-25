<template>
 <view class="page">
  <text class="title">收藏批量管理</text>
  <text class="hint">仅处理本页明确选中的商品，翻页或刷新会清空选择。失效商品仍可取消收藏。</text>
  <button v-if="!currentUser" @click="login">前往登录</button>
  <text v-if="state.loading" class="hint">正在读取收藏…</text>
  <text v-if="state.error" class="error">{{ state.error }}</text>
  <text v-if="state.notice" class="notice">{{ state.notice }}</text>
  <view v-if="state.page">
   <text class="hint">共{{ state.page.total }}件收藏，本页{{ state.page.records.length }}件，已选{{ state.selected.length }}件。</text>
   <view class="actions">
    <button :disabled="blocked" @click="manager.select('ALL')">选择本页全部</button>
    <button :disabled="blocked" @click="manager.select('UNAVAILABLE')">选择本页失效商品</button>
    <button :disabled="blocked||!state.selected.length" @click="manager.select('NONE')">清空选择</button>
   </view>
   <view v-for="item in state.page.records" :key="item.productId" class="card">
    <text v-if="item.product" class="name">{{ item.product.title }}</text>
    <text v-else class="name">商品已下架或不可用</text>
    <text class="hint">商品编号：{{ item.productId }}</text>
    <text v-if="item.product">¥{{ (item.product.priceInCents/100).toFixed(2) }}</text>
    <button :type="state.selected.includes(item.productId)?'primary':'default'" :disabled="blocked" @click="manager.toggle(item.productId)">{{ state.selected.includes(item.productId)?'已选择':'选择该商品' }}</button>
   </view>
   <text v-if="!state.page.records.length" class="hint">暂无收藏，可返回首页浏览商品。</text>
   <view class="actions"><button :disabled="blocked||state.page.page<=1" @click="load(state.page.page-1)">上一页</button><text>第{{ state.page.page }}/{{ Math.max(1,Math.ceil(state.page.total/SIZE)) }}页</text><button :disabled="blocked||state.page.page>=Math.max(1,Math.ceil(state.page.total/SIZE))" @click="load(state.page.page+1)">下一页</button></view>
  </view>
  <button type="warn" :loading="state.saving" :disabled="blocked||!state.selected.length" @click="manager.submit()">{{ state.saving?'正在确认或取消…':`取消所选收藏（${state.selected.length}件）` }}</button>
  <button :disabled="state.loading||state.saving" @click="load()">刷新收藏并清空选择</button>
  <button :disabled="state.saving" @click="back">返回我的收藏</button>
 </view>
</template>
<script setup>
import {ref,computed,watch} from 'vue'
import {onShow,onHide,onUnload} from '@dcloudio/uni-app'
import {currentUser,getToken} from '@/utils/session.js'
import {getFavorites} from '@/api/favorite.js'
import {removeFavoriteBatch} from '@/api/favorite-batch.js'
import {FAVORITE_BATCH_SIZE as SIZE,createFavoriteBatchManager} from '@/utils/favorite-batch.mjs'
const state=ref({page:null,selected:[],loading:false,saving:false,error:'',notice:'',uncertain:false}),currentPage=ref(1)
let active=false
const blocked=computed(()=>state.value.loading||state.value.saving||state.value.uncertain||!state.value.page)
const manager=createFavoriteBatchManager({identity:()=>({userId:currentUser.value?.id,token:getToken()}),load:getFavorites,remove:removeFavoriteBatch,
 confirm:ids=>new Promise((resolve,reject)=>uni.showModal({title:'确认批量取消收藏',content:`将取消选中的${ids.length}件收藏（商品编号：${ids.join('、')}）。仅处理这些编号，不会删除商品。`,confirmText:'确认取消',success:r=>resolve(r.confirm===true),fail:reject})),
 notify:value=>{state.value=value;if(value.page)currentPage.value=value.page.page}})
function load(page=currentPage.value){if(active)return manager.refresh(page)}
function login(){uni.navigateTo({url:'/pages/user/login'})}
function back(){uni.navigateBack({fail:()=>uni.redirectTo({url:'/pages/product/favorites'})})}
watch(()=>[currentUser.value?.id,getToken()],()=>{manager.reset();currentPage.value=1;if(active)load()},{flush:'sync'})
onShow(()=>{active=true;load()})
onHide(()=>{active=false;manager.reset()})
onUnload(()=>{active=false;manager.dispose()})
</script>
<style scoped>
.page{padding:30rpx;min-height:100vh;box-sizing:border-box;background:#f5f7fa}.title,.name{display:block;font-weight:bold;font-size:36rpx}.name{font-size:30rpx}.hint,.error,.notice{display:block;line-height:1.6;margin:20rpx 0}.hint{color:#64748b}.error{color:#b42318}.notice{color:#166534}.card{padding:24rpx;margin:20rpx 0;background:white;border-radius:16rpx}.actions{display:flex;align-items:center;gap:16rpx;margin:20rpx 0}.actions button{flex:1;font-size:26rpx}button{margin-top:20rpx}
</style>

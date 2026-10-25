<template>
 <view class="page">
  <button @click="openRefunds">我的退款与售后</button>
  <button :disabled="busy" @click="tradeTasks">我的交易待办</button>
  <view class="roles">
   <button :type="role === 'BUYER' ? 'primary' : 'default'" :disabled="busy" @click="load('BUYER',1,status)">我买到的</button>
   <button :type="role === 'SELLER' ? 'primary' : 'default'" :disabled="busy" @click="load('SELLER',1,status)">我卖出的</button>
  </view>
  <button :disabled="busy" @click="reviewTasks">待评价与评价记录</button>
  <button :disabled="busy" @click="receivedReviews">我收到的交易评价</button>
  <button :disabled="busy" @click="issueList">我的交易反馈</button>
  <button :disabled="busy||!!routeError||!currentUser" @click="exportOrders">导出当前条件订单</button>
  <button v-if="!currentUser" @click="login">前往登录</button>
  <view class="search">
   <text>按完整订单编号查找</text><input v-model="draftOrder" type="text" :maxlength="-1" :disabled="busy||!!routeError" placeholder="订单编号；留空查询全部" @confirm="search" />
   <button :disabled="busy||!!routeError" @click="search">查询订单</button><button :disabled="busy||!!routeError" @click="clearSearch">清除订单编号</button>
   <text v-if="draftOrder!==filterOrder" class="note">输入的订单编号尚未查询。</text><text v-if="searchError" class="error">{{ searchError }}</text>
  </view>
  <view v-if="summary" class="summary">
   <text class="heading">{{ role === 'BUYER' ? '买家' : '卖家' }}交易统计</text>
   <text>当前身份全部订单，共{{ summary.total }}笔；不随下方筛选或分页改变。</text>
   <view class="stats">
    <view v-for="filter in orderFilters.slice(1)" :key="filter.value" class="stat"><text>{{ filter.label }}</text><text>{{ orderSummaryCount(summary,filter.value) }}笔</text></view>
   </view>
   <text class="amount">已完成模拟交易金额：¥{{ formatOrderMoney(summary.completedAmountInCents) }}</text>
   <text class="note">按下单金额快照统计；仅模拟交易，不代表真实收入或资金结算。</text>
  </view>
  <picker :range="filterLabels" :value="filterIndex" :disabled="busy" @change="chooseStatus">
   <view class="filter">订单状态：{{ filterLabels[filterIndex] }} ▾</view>
  </picker>
  <text v-if="summary">当前订单范围：{{ filterOrder===''?'全部订单':'订单'+filterOrder }}</text>
  <text v-if="summary">{{ role === 'BUYER' ? '我买到的订单' : '我卖出的订单' }}，筛选结果共{{ total }}笔</text>
  <text v-if="routeError||error" class="error">{{ routeError||error }}</text>
  <text v-else-if="summary && !busy && !items.length">当前条件下暂无订单</text>
  <text v-if="busy" class="note">正在加载订单与统计…</text>
  <view v-for="item in items" :key="item.id" class="card" @click="open(item.id)">
   <text>{{ item.productTitleSnapshot }} · {{ orderStatus(item.status) }}</text><text>¥{{ (item.priceInCentsSnapshot / 100).toFixed(2) }} · 订单{{ item.id }}</text><text>{{ item.createdAt.replace('T',' ') }}</text><button size="mini" :disabled="busy" @click.stop="open(item.id)">查看订单</button>
  </view>
  <button :disabled="busy || !summary || page <= 1" @click="load(role,page-1,status)">上一页</button><text v-if="summary">第{{ page }}/{{ totalPages }}页</text><button :disabled="busy || !summary || page >= totalPages" @click="load(role,page+1,status)">下一页</button>
  <button :disabled="busy" @click="load(role,page,status)">刷新订单</button><button :disabled="busy" @click="home">返回首页</button>
 </view>
</template>
<script setup>
function openRefunds(){uni.navigateTo({url:'/pages/order/refunds'})}
import {orderExportPath} from '@/utils/order-export.mjs'
import { ref, computed, watch } from 'vue'
import { onLoad, onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { currentUser, getToken } from '@/utils/session.js'
import {orderSearchQuery,readOrderSearchRoute,orderSearchDetailPath} from '@/utils/order-search.mjs'
import {issueListPath} from '@/utils/order-issue-list.mjs'
import {receivedReviewPath} from '@/utils/received-reviews.mjs'
import {reviewTaskPath} from '@/utils/order-review-tasks.mjs'
import { getOrders } from '@/api/order.js'
import { orderStatus, orderFilters, orderSummaryCount, formatOrderMoney } from '@/utils/order.js'
const routeError=ref(''),filterOrder=ref(''),draftOrder=ref(''),searchError=ref('')
const items=ref([]),role=ref('BUYER'),status=ref('ALL'),page=ref(1),total=ref(0),totalPages=ref(1),summary=ref(null),busy=ref(false),error=ref('')
const filterLabels=orderFilters.map(item=>item.label)
const filterIndex=computed(()=>orderFilters.findIndex(item=>item.value===status.value))
let generation=0,active=false,closed=false
const alive=(n,token,user)=>active&&!closed&&n===generation&&token===getToken()&&user===currentUser.value?.id
function clear(){items.value=[];summary.value=null;total.value=0;totalPages.value=1;error.value=''}
async function load(nextRole=role.value,nextPage=page.value,nextStatus=status.value,nextOrder=filterOrder.value){
 if(!active||closed||busy.value||routeError.value)return
 const n=++generation,token=getToken(),user=currentUser.value?.id;busy.value=true;error.value=''
 try{
  if(!token||!user)throw new Error('请先登录后查看我的订单')
  const result=await getOrders(nextRole,nextPage,nextStatus,nextOrder)
  if(!alive(n,token,user))return
  if(!result.items.every(o=>(nextRole==='BUYER'?o.buyer.id:o.seller.id)===user))throw new Error('订单列表参与者不匹配')
  // 列表、筛选与全量统计只在同一响应成功后一起提交。
  items.value=result.items;role.value=result.role;status.value=result.status;page.value=result.page;total.value=result.total;totalPages.value=result.totalPages;summary.value=result.summary;filterOrder.value=nextOrder;searchError.value=''
 }catch(e){if(alive(n,token,user))error.value=(e.message||'加载订单列表失败')+(summary.value?'；仍显示上次成功结果':'')}
 finally{if(n===generation)busy.value=false}
}
function search(){
 if(busy.value||routeError.value||!active||closed)return
 try{const q=orderSearchQuery({role:role.value,status:status.value,orderId:draftOrder.value,page:1});searchError.value='';load(q.role,q.page,q.status,q.orderId)}catch(e){searchError.value=e.message}
}
function clearSearch(){if(busy.value||routeError.value)return;draftOrder.value='';search()}
function login(){uni.navigateTo({url:'/pages/user/login'})}
function chooseStatus(event){
 if(busy.value||!active||closed)return
 const raw=event?.detail?.value
 const index=(typeof raw==='number'||(typeof raw==='string'&&/^[0-6]$/.test(raw)))?Number(raw):NaN
 if(!Number.isInteger(index)||index<0||index>=orderFilters.length){error.value='订单筛选选项不正确';return}
 load(role.value,1,orderFilters[index].value)
}
function open(id){if(!busy.value&&active&&!closed&&items.value.some(item=>item.id===id))uni.navigateTo({url:orderSearchDetailPath(id,{role:role.value,status:status.value,orderId:filterOrder.value,page:page.value}),fail:()=>{error.value='打开订单详情失败'}})}
function exportOrders(){if(!busy.value&&active&&!closed&&currentUser.value&&!routeError.value)uni.navigateTo({url:orderExportPath({role:role.value,status:status.value,orderId:filterOrder.value,page:page.value}),fail:()=>{error.value='打开订单导出失败'}})}
function tradeTasks(){if(!busy.value&&active&&!closed)uni.navigateTo({url:'/pages/order/tasks',fail:()=>{error.value='打开交易待办失败'}})}
function receivedReviews(){if(!busy.value&&active&&!closed)uni.navigateTo({url:receivedReviewPath(role.value),fail:()=>{error.value='打开收到评价失败'}})}
function reviewTasks(){if(!busy.value&&active&&!closed)uni.navigateTo({url:reviewTaskPath(role.value),fail:()=>{error.value='打开评价清单失败'}})}
function issueList(){if(!busy.value&&active&&!closed)uni.navigateTo({url:issueListPath(role.value),fail:()=>{error.value='打开交易反馈清单失败'}})}
function home(){if(!busy.value&&active&&!closed)uni.reLaunch({url:'/pages/index/index',fail:()=>{error.value='返回首页失败'}})}
watch(()=>[currentUser.value?.id,getToken()],(next,before)=>{generation++;clear();page.value=1;busy.value=false;if(before?.[0]&&next[0]!==before[0]){filterOrder.value='';draftOrder.value='';searchError.value=''}if(active)load()},{flush:'sync'})
onLoad(options=>{try{const q=readOrderSearchRoute(options);role.value=q.role;status.value=q.status;page.value=q.page;filterOrder.value=q.orderId;draftOrder.value=q.orderId}catch(e){routeError.value=e.message}})
onShow(()=>{active=true;load()})
onHide(()=>{active=false;generation++;clear();busy.value=false})
onUnload(()=>{closed=true;active=false;generation++;clear()})
</script>
<style scoped>
.page{padding:30rpx}.search{padding:24rpx;background:#fff;margin:24rpx 0}.search text{display:block;margin:12rpx 0}.search input{border:1px solid #d9dfe8;padding:20rpx}.roles{display:flex;gap:20rpx}.roles button{flex:1}.summary,.card{background:white;padding:24rpx;margin:24rpx 0;border-radius:20rpx}.summary>text,.card text{display:block;margin:12rpx 0}.heading,.amount{font-weight:bold}.stats{display:flex;flex-wrap:wrap;margin:18rpx 0}.stat{width:33.33%;padding:12rpx 0}.stat text{display:block}.note{color:#666;font-size:25rpx}.filter{padding:24rpx;background:#fff;border-radius:12rpx;margin:20rpx 0}.error{display:block;color:#d93025;margin:16rpx 0}button{margin:18rpx 0}
</style>

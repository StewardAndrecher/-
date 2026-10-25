<template>
 <view class="page">
  <text class="title">交易问题工单</text><text class="hint">查看双方各自提交的反馈，逐条记录处理结果。处理不改变交易状态，不执行退款。</text>
  <button v-if="!currentUser" @click="login">前往登录</button>
  <view class="panel"><text>按订单编号查找反馈</text><input v-model="draftOrder" type="text" :maxlength="-1" :disabled="state.busy||!!routeError" placeholder="完整订单编号；留空查询全部" @confirm="search" /><button :disabled="state.busy||!!routeError" @click="search">查询订单反馈</button><button :disabled="state.busy||!!routeError" @click="clearSearch">清除订单编号</button><text v-if="draftOrder!==query.orderId" class="hint">输入的订单编号尚未查询。</text><text v-if="searchError" class="error">{{ searchError }}</text></view>
  <view class="tabs"><button v-for="item in filters" :key="item.value" :type="query.status===item.value?'primary':'default'" :disabled="state.busy||!!routeError" @click="choose(item.value)">{{ item.label }}</button></view>
  <view v-if="query.status==='OPEN'" class="tabs"><button v-for="item in ages" :key="item.value" :type="query.age===item.value?'primary':'default'" :disabled="state.busy||!!routeError" @click="chooseAge(item.value)">{{ item.label }}</button></view>
  <text v-if="routeError" class="error">{{ routeError }}</text>
  <text v-if="state.busy" class="hint">正在读取工单…</text>
  <text v-if="state.status===403" class="error">当前账号没有管理权限。</text><text v-else-if="state.error" class="error">{{ state.error }}</text>
  <view v-if="state.data">
   <text>当前订单范围：{{ state.data.orderId===null?'全部订单':'订单'+state.data.orderId }}</text><text>当前筛选共{{ state.data.total }}条反馈</text><text class="hint">采样时间（服务器时间）：{{ state.data.sampledAt.replace('T',' ') }}。{{ query.age==='ALL'?'按最新提交排序':'等待较久的工单排在前面' }}</text><text v-if="!state.data.items.length" class="hint">当前条件下暂无工单</text>
   <view v-for="item in state.data.items" :key="`${item.orderId}:${item.applicantId}`" class="panel">
    <text>订单{{ item.orderId }} · {{ item.applicantRole==='BUYER'?'买家':'卖家' }}反馈</text><text>提交人编号：{{ item.applicantId }}</text><text>{{ reasonLabel(item.reason) }} · {{ item.status==='OPEN'?'待处理':'已处理' }}</text>
    <text class="hint">{{ item.createdAt.replace('T',' ') }}</text><button :disabled="state.busy" @click="open(item)">查看工单</button>
   </view>
   <view class="tabs"><button :disabled="state.busy||state.data.page<=1" @click="load(state.data.page-1)">上一页</button><text>第{{ state.data.page }}/{{ state.data.totalPages }}页</text><button :disabled="state.busy||state.data.page>=state.data.totalPages" @click="load(state.data.page+1)">下一页</button></view>
  </view>
  <button :disabled="state.busy||!!routeError" @click="loader.refresh(active)">刷新工单</button><button @click="back">返回管理中心</button>
 </view>
</template>
<script setup>
import {ref,watch} from 'vue'
import {onLoad,onShow,onHide,onUnload} from '@dcloudio/uni-app'
import {currentUser,getToken} from '@/utils/session.js'
import {listAdminOrderIssues} from '@/api/admin.js'
import {createAdminLoader} from '@/utils/admin-access.mjs'
import {adminIssueQuery,readAdminIssueListRoute,adminIssuePath,validateAdminIssuePage} from '@/utils/admin-order-issues.mjs'
import {issueReasons} from '@/utils/order-issues.mjs'
const draftOrder=ref(''),searchError=ref(''),routeError=ref(''),active=ref(false),query=ref(adminIssueQuery()),state=ref({data:null,busy:false,error:'',status:0})
const filters=[{value:'OPEN',label:'待处理'},{value:'RESOLVED',label:'已处理'},{value:'ALL',label:'全部'}]
const ages=[{value:'ALL',label:'全部待处理'},{value:'OVER_24H',label:'已等待满24小时'},{value:'OVER_72H',label:'已等待满72小时'}]
const loader=createAdminLoader(()=>listAdminOrderIssues(query.value),()=>({userId:currentUser.value?.id,token:getToken()}),data=>validateAdminIssuePage(data,query.value),value=>{state.value=value})
function load(page){if(state.value.busy||routeError.value)return;query.value=adminIssueQuery({...query.value,page});loader.refresh(active.value)}
function choose(status){if(state.value.busy||routeError.value)return;query.value=adminIssueQuery({status,orderId:query.value.orderId});searchError.value='';loader.refresh(active.value)}
function chooseAge(age){if(state.value.busy||routeError.value)return;query.value=adminIssueQuery({status:'OPEN',age,orderId:query.value.orderId});searchError.value='';loader.refresh(active.value)}
function search(){
 if(state.value.busy||routeError.value||!active.value)return
 try{const next=adminIssueQuery({...query.value,orderId:draftOrder.value,page:1});query.value=next;searchError.value='';loader.refresh(active.value)}catch(error){searchError.value=error.message}
}
function clearSearch(){if(state.value.busy||routeError.value)return;draftOrder.value='';search()}
function reasonLabel(reason){return issueReasons.find(item=>item.value===reason)?.label||'类型未知'}
function open(item){if(!state.value.busy&&active.value&&state.value.data?.items.some(row=>row.orderId===item.orderId&&row.applicantId===item.applicantId))uni.navigateTo({url:adminIssuePath(item.orderId,item.applicantId,{...query.value,page:state.value.data.page})})}
function login(){uni.navigateTo({url:'/pages/user/login'})}
function back(){uni.redirectTo({url:'/pages/admin/index'})}
watch(()=>[active.value,currentUser.value?.id,getToken()],()=>{loader.refresh(active.value&&!routeError.value)},{flush:'sync'})
onLoad(options=>{try{query.value=readAdminIssueListRoute(options);draftOrder.value=query.value.orderId}catch(error){routeError.value=error.message}})
onShow(()=>{active.value=true})
onHide(()=>{active.value=false})
onUnload(()=>{active.value=false;loader.dispose()})
</script>
<style scoped>
.page{padding:30rpx;min-height:100vh;background:#f5f7fa;box-sizing:border-box}.title{display:block;font-size:36rpx;font-weight:bold}.hint{display:block;color:#64748b;line-height:1.6;margin:20rpx 0}.error{display:block;color:#b42318;margin:20rpx 0}.tabs{display:flex;align-items:center;gap:16rpx;margin:20rpx 0}.tabs button{flex:1}.panel{background:white;border-radius:16rpx;padding:24rpx;margin:24rpx 0}.panel text{display:block;margin:12rpx 0}.panel input{padding:20rpx;border:1px solid #d9dfe8}button{margin-top:20rpx}
</style>

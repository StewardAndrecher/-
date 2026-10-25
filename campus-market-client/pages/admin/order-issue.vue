<template>
 <view class="page">
  <text class="title">反馈工单详情</text><text class="hint">每条反馈仅保存一次处理结果。请核对原始说明后答复；处理不改变订单、商品或付款状态。</text>
  <text v-if="routeError||state.error" class="error">{{ routeError||state.error }}</text><text v-if="state.status===403" class="error">当前账号无权处理此工单。</text>
  <button v-if="!currentUser" @click="login">前往登录</button><text v-if="state.loading" class="hint">正在读取工单…</text>
  <text v-if="state.result" class="success">{{ state.result.replayed?'保留首次处理结果，不会重复提醒。':'处理结果已保存，已安排站内提醒提交人。' }}</text>
  <view v-if="state.detail" class="panel">
   <text class="heading">{{ state.detail.productTitleSnapshot }}</text><text>订单{{ state.detail.orderId }} · {{ orderStatus(state.detail.orderStatus) }}</text><text>下单金额快照：¥{{ formatOrderMoney(state.detail.priceInCentsSnapshot) }}（模拟交易）</text>
   <text>提交方：{{ state.detail.applicantRole==='BUYER'?'买家':'卖家' }} · 编号{{ state.detail.applicantId }}</text><text>问题类型：{{ reasonLabel(state.detail.reason) }}</text>
   <text class="description">{{ state.detail.description }}</text><text class="hint">提交时间：{{ state.detail.createdAt.replace('T',' ') }}</text>
   <view v-if="state.detail.resolution"><text class="heading">处理结果：{{ issueDecisionLabel(state.detail.resolution.decision) }}</text><text class="description">{{ state.detail.resolution.explanation }}</text><text class="hint">{{ state.detail.resolution.createdAt.replace('T',' ') }}</text></view>
   <text v-else class="hint">处理状态：待处理</text>
  </view>
  <view v-if="state.detail?.status==='OPEN'" class="panel">
   <text>处理结果</text><picker :range="decisionLabels" :value="decisionIndex" :disabled="busy" @change="choose"><view class="picker">{{ decisionLabels[decisionIndex] }} ▾</view></picker>
   <textarea v-model="explanation" :maxlength="-1" :disabled="busy" class="field" placeholder="填写向提交人展示的处理说明，10至1000个字符；勿包含另一方的私密反馈" />
   <text class="hint">{{ Array.from(explanation).length }} / 1000 个字符</text><button type="primary" :disabled="busy" @click="submit">{{ state.saving?'正在确认或提交…':'保存处理结果' }}</button>
  </view>
  <button v-if="currentUser&&!routeError" :disabled="busy" @click="manager.refresh()">重新加载工单</button><button :disabled="busy" @click="back">返回工单列表</button>
 </view>
</template>
<script setup>
import {ref,computed,watch} from 'vue'
import {onLoad,onShow,onHide,onUnload} from '@dcloudio/uni-app'
import {currentUser,getToken} from '@/utils/session.js'
import {getAdminOrderIssue,resolveAdminOrderIssue} from '@/api/admin.js'
import {orderId,orderStatus,formatOrderMoney} from '@/utils/order.js'
import {issueReasons,issueDecisionLabel} from '@/utils/order-issues.mjs'
import {issueDecisions,createAdminIssueManager,adminIssueListPath,adminIssueBackPath} from '@/utils/admin-order-issues.mjs'
const backUrl=ref(adminIssueListPath()),order=ref(0),applicant=ref(0),routeError=ref(''),explanation=ref(''),decisionIndex=ref(0),decisionLabels=issueDecisions.map(item=>item.label)
const state=ref({detail:null,loading:false,saving:false,error:'',status:0,result:null,uncertain:false}),busy=computed(()=>state.value.loading||state.value.saving)
let active=false
const manager=createAdminIssueManager({identity:()=>({orderId:order.value,applicantId:applicant.value,userId:currentUser.value?.id,token:getToken()}),load:getAdminOrderIssue,resolve:resolveAdminOrderIssue,notify:value=>{state.value=value},
 confirm:(payload,before)=>new Promise((resolve,reject)=>uni.showModal({title:'确认保存处理结果',content:`订单 #${before.orderId}，提交人编号${before.applicantId}；结果：${issueDecisionLabel(payload.decision)}。保存后不可覆盖，将提醒该提交人；不会改变交易或执行退款。确认提交？`,confirmText:'确认处理',cancelText:'取消',success:r=>resolve(r.confirm===true),fail:()=>reject(new Error('无法显示确认窗口'))}))})
function reasonLabel(reason){return issueReasons.find(item=>item.value===reason)?.label||'类型未知'}
function choose(event){const n=Number(event?.detail?.value);if(Number.isInteger(n)&&n>=0&&n<issueDecisions.length)decisionIndex.value=n}
async function submit(){if(await manager.submit({decision:issueDecisions[decisionIndex.value].value,explanation:explanation.value})){explanation.value='';decisionIndex.value=0}}
function reset(){manager.reset();explanation.value='';decisionIndex.value=0}
function login(){uni.navigateTo({url:'/pages/user/login'})}
function back(){uni.redirectTo({url:backUrl.value})}
watch(()=>[currentUser.value?.id,getToken()],()=>{reset();if(active&&!routeError.value&&order.value&&applicant.value)manager.refresh()},{flush:'sync'})
onLoad(options=>{try{const url=adminIssueBackPath(options),id=orderId(options?.orderId),who=orderId(options?.applicantId);backUrl.value=url;order.value=id;applicant.value=who}catch(error){routeError.value=error.message}})
onShow(()=>{active=true;if(!routeError.value&&order.value&&applicant.value)manager.refresh()})
onHide(()=>{active=false;reset()})
onUnload(()=>{active=false;explanation.value='';manager.dispose()})
</script>
<style scoped>
.page{padding:30rpx;min-height:100vh;background:#f5f7fa;box-sizing:border-box}.title{display:block;font-size:36rpx;font-weight:bold}.hint{display:block;color:#64748b;line-height:1.6;margin:20rpx 0}.error{display:block;color:#b42318;margin:20rpx 0}.success{display:block;color:#137333;margin:20rpx 0}.panel{padding:24rpx;background:white;border-radius:16rpx;margin:24rpx 0}.panel text{display:block;margin:14rpx 0}.heading{font-weight:bold}.description{white-space:pre-wrap;overflow-wrap:anywhere}.picker{padding:24rpx;border:1px solid #d9dfe8}.field{width:100%;min-height:220rpx;padding:16rpx;border:1px solid #d9dfe8;box-sizing:border-box;margin-top:20rpx}button{margin-top:20rpx}
</style>

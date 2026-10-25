<template>
 <view class="service-page">
  <view class="page-heading"><text class="eyebrow">AFTER SALES · 售后服务</text><text class="page-title">退款与售后</text><text class="page-subtitle">把问题说清楚，让每一次沟通都有记录。</text></view>
  <view class="notice"><text class="notice-title">当前为演示订单售后</text><text>订单没有真实扣款。此处记录申请、卖家答复和撤回进度；同意申请不会执行资金退款，也不代表已经到账。真实支付接通后才能处理原路退款。</text></view>
  <text v-if="routeError || state.error" class="error">{{ routeError || state.error }}</text>
  <text v-if="state.message" class="success">{{ state.message }}</text>
  <view v-if="!currentUser" class="surface empty"><text>登录后查看自己的售后记录</text><button class="primary" @click="login">前往登录</button></view>
  <view v-if="state.loading" class="surface empty"><text>正在读取订单和售后进度…</text></view>
  <template v-if="state.data">
   <view class="surface order-summary"><view><text class="section-title">{{ state.data.productTitle }}</text><text class="muted">订单 #{{ id }} · {{ state.data.viewerRole === 'BUYER' ? '我买到的' : '我卖出的' }}</text></view><view class="amount"><text class="muted">申请金额上限</text><text>¥{{ money(state.data.amountInCents) }}</text></view></view>
   <view v-if="state.data.canApply" class="surface">
    <text class="section-title">提交退款申请</text><text class="muted">当前支持整单申请，金额以订单快照为准。说明至少 10 个字符，每笔订单最多申请 5 次。</text>
    <text class="label">退款原因</text><picker :range="refundReasons" range-key="name" :value="reasonIndex" :disabled="busy" @change="chooseReason"><view class="select">{{ refundReasons[reasonIndex].name }} <text>⌄</text></view></picker>
    <text class="label">问题说明</text><textarea v-model="description" class="textarea" :maxlength="1000" :disabled="busy" placeholder="描述商品状况、交接情况以及你的诉求，请勿填写密码或证件号码。" />
    <text class="counter">{{ Array.from(description).length }} / 1000</text><button class="primary" :disabled="busy" @click="submit">{{ state.saving ? '正在提交…' : '提交申请 · ¥' + money(state.data.amountInCents) }}</button>
   </view>
   <view v-else-if="!state.data.requests.length" class="surface empty"><text class="section-title">暂无售后申请</text><text class="muted">{{ state.data.viewerRole === 'SELLER' ? '买家提交申请后，可以在这里查看并答复。' : '完成演示付款后可申请售后，未付款订单请直接取消。' }}</text></view>
   <view v-for="(item,index) in state.data.requests" :key="item.id" class="surface case-card">
    <view class="case-heading"><text class="section-title">{{ index === 0 ? '最新申请' : '历史申请' }} #{{ item.id }}</text><text class="status" :class="item.status.toLowerCase()">{{ refundStateName(item.status) }}</text></view>
    <text class="case-amount">¥{{ money(item.amountInCents) }} <text>演示订单金额</text></text><text class="label">{{ refundReasonName(item.reason) }}</text><text class="body-copy">{{ item.description }}</text>
    <view v-if="item.status === 'APPROVED'" class="approval-note"><text>卖家已同意申请，未执行资金退款。</text><text>该订单为演示订单，不存在真实扣款或到账。原订单履约操作暂停，售后状态单独保存。</text></view>
    <view class="timeline"><view v-for="event in item.events" :key="event.id" class="timeline-row"><view class="timeline-dot"></view><view><text class="timeline-title">{{ eventName(event.action) }}</text><text class="muted">{{ time(event.createdAt) }} · {{ event.actorRole === 'BUYER' ? '买家' : '卖家' }}</text><text v-if="event.action !== 'REQUESTED' && event.explanation" class="body-copy">{{ event.explanation }}</text></view></view></view>
    <button v-if="item.status === 'REQUESTED' && state.data.viewerRole === 'BUYER'" class="secondary" :disabled="busy" @click="withdraw(item)">撤回本次申请</button>
    <view v-if="item.status === 'REQUESTED' && state.data.viewerRole === 'SELLER'" class="decision-panel"><text class="section-title">处理买家的申请</text><text class="muted">请先核对商品和沟通情况。答复会保留，提交后不可直接修改。</text><textarea v-model="explanation" class="textarea" :disabled="busy" :maxlength="1000" placeholder="说明处理依据及下一步安排，至少10个字符" /><view class="decision-actions"><button class="secondary" :disabled="busy" @click="decide(item,'REJECT')">拒绝申请</button><button class="primary" :disabled="busy" @click="decide(item,'APPROVE')">同意申请</button></view></view>
   </view>
  </template>
  <view class="page-actions"><button v-if="currentUser && !routeError" class="secondary" :disabled="busy" @click="manager.refresh()">刷新进度</button><button class="secondary" :disabled="busy" @click="openList">全部退款售后</button><button v-if="id" class="secondary" :disabled="busy" @click="openIssue">交易问题反馈</button><button class="text-button" :disabled="busy" @click="back">返回订单详情</button></view>
 </view>
</template>
<script setup>
import { ref,computed,watch } from 'vue'
import { onLoad,onShow,onHide,onUnload } from '@dcloudio/uni-app'
import { currentUser,getToken } from '@/utils/session.js'
import { createOwnedResource } from '@/utils/owned-resource.mjs'
import { refundReasons,refundStateName,refundReasonName,refundText,validateRefundSummary,validateRefund } from '@/utils/refunds.mjs'
import { getOrderRefunds,createRefund,decideRefund,withdrawRefund } from '@/api/refund.js'
import { newPublicationRequestId } from '@/utils/product-publications.js'
import { orderId } from '@/utils/order.js'
const id=ref(0),routeError=ref(''),description=ref(''),explanation=ref(''),reasonIndex=ref(0),state=ref({data:null,loading:false,saving:false,error:'',message:'',uncertain:false})
const busy=computed(()=>state.value.loading||state.value.saving)
let active=false,pending=null
const manager=createOwnedResource({identity:()=>({userId:currentUser.value?.id,token:getToken(),orderId:id.value}),load:owner=>getOrderRefunds(owner.orderId),validate:validateRefundSummary,notify:value=>{state.value=value}})
const money=value=>(value/100).toFixed(2),time=value=>value.replace('T',' ')
const eventName=action=>({REQUESTED:'买家提交申请',APPROVED:'卖家同意申请 · 未执行资金退款',REJECTED:'卖家拒绝申请',WITHDRAWN:'买家撤回申请'}[action]||'进度更新')
function confirm(title,content){return new Promise(resolve=>uni.showModal({title,content,confirmText:'确认',success:r=>resolve(r.confirm===true),fail:()=>resolve(false)}))}
function chooseReason(event){const index=Number(event.detail.value);if(!busy.value&&refundReasons[index])reasonIndex.value=index}
function inputError(error){state.value={...state.value,error:error.message}}
async function submit(){
 if(!state.value.data?.canApply||busy.value)return
 let payload;try{payload={reason:refundReasons[reasonIndex.value].code,description:refundText(description.value)};if(!pending||pending.reason!==payload.reason||pending.description!==payload.description)pending={...payload,requestId:newPublicationRequestId()}}catch(error){inputError(error);return}
 const body={...pending}
 if(await manager.mutate({confirm:()=>confirm('提交退款申请',`申请金额 ¥${money(state.value.data.amountInCents)}。这是演示订单，提交不会执行真实退款。`),write:()=>createRefund(id.value,body),validateReceipt:value=>{const item=validateRefund(value?.refund,id.value);if(typeof value.replayed!=='boolean'||item.requestId!==body.requestId||item.description!==body.description||item.reason!==body.reason)throw new Error('申请回执不匹配')},message:'申请已保存，请等待卖家答复。'})){description.value='';pending=null}
}
async function decide(item,decision){
 if(busy.value||state.value.data?.viewerRole!=='SELLER'||item.status!=='REQUESTED')return
 let text;try{text=refundText(explanation.value)}catch(error){inputError(error);return}
 await manager.mutate({confirm:()=>confirm(decision==='APPROVE'?'同意退款申请':'拒绝退款申请',decision==='APPROVE'?'同意后保存处理记录并暂停履约操作。当前不会执行资金退款，也不会标记到账。':'确认保存拒绝原因？买家可以查看该答复。'),write:()=>decideRefund(id.value,item.id,{version:item.version,decision,explanation:text}),validateReceipt:value=>{const result=validateRefund(value?.refund,id.value);if(result.id!==item.id||result.status!==(decision==='APPROVE'?'APPROVED':'REJECTED')||result.explanation!==text)throw new Error('处理回执不匹配')},message:'处理记录已保存。'});explanation.value=''
}
async function withdraw(item){if(state.value.data?.viewerRole!=='BUYER'||item.status!=='REQUESTED')return;await manager.mutate({confirm:()=>confirm('撤回申请','确认撤回这次申请？撤回记录会保留，订单可继续正常履约。'),write:()=>withdrawRefund(id.value,item.id,{version:item.version}),validateReceipt:value=>{const result=validateRefund(value?.refund,id.value);if(result.id!==item.id||result.status!=='WITHDRAWN')throw new Error('撤回回执不匹配')},message:'申请已撤回。'})}
function resetDrafts(){description.value='';explanation.value='';reasonIndex.value=0;pending=null}
function login(){uni.navigateTo({url:'/pages/user/login'})}
function openList(){uni.navigateTo({url:'/pages/order/refunds'})}
function openIssue(){uni.navigateTo({url:`/pages/order/issue?id=${id.value}`})}
function back(){uni.navigateBack({fail:()=>uni.redirectTo({url:id.value?`/pages/order/detail?id=${id.value}`:'/pages/order/list'})})}
watch(()=>[currentUser.value?.id,getToken()],()=>{manager.hide();resetDrafts();if(active&&id.value)manager.show()},{flush:'sync'})
onLoad(options=>{try{id.value=orderId(options?.id)}catch(error){routeError.value=error.message}})
onShow(()=>{active=true;if(id.value)manager.show()})
onHide(()=>{active=false;manager.hide();resetDrafts()})
onUnload(()=>{active=false;manager.dispose();resetDrafts()})
</script>
<style scoped src="@/styles/service-pages.css"></style>

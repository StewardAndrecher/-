<template>
 <view v-if="state.loading || state.error || state.data?.hasActiveRefund" class="refund-notice">
  <text v-if="state.loading">正在核对售后状态…</text>
  <template v-else-if="state.data?.hasActiveRefund"><text class="notice-title">此订单正在处理退款申请</text><text>交付与收货操作已暂停。请查看申请进度；同意申请不代表资金已经退回。</text><button @click="open">查看退款与售后进度 →</button></template>
  <template v-else-if="state.error"><text>{{ state.error }}；暂不能确认可继续履约，请重新读取售后状态。</text><button @click="manager.refresh()">重试读取售后状态</button></template>
 </view>
</template>
<script setup>
import { ref,watch,onUnmounted } from 'vue'
import { getOrderRefunds } from '@/api/refund.js'
import { createOwnedResource } from '@/utils/owned-resource.mjs'
import { validateRefundSummary,refundPath } from '@/utils/refunds.mjs'
import { currentUser,getToken } from '@/utils/session.js'
const props=defineProps({orderId:{type:Number,required:true},active:Boolean}),emit=defineEmits(['state'])
const state=ref({data:null,loading:false,error:''})
const manager=createOwnedResource({identity:()=>({userId:currentUser.value?.id,token:getToken(),orderId:props.orderId}),load:owner=>getOrderRefunds(owner.orderId),validate:validateRefundSummary,notify:value=>{state.value=value;emit('state',{loaded:!!value.data,blocked:value.data?.hasActiveRefund===true})}})
watch(()=>[props.active,props.orderId,currentUser.value?.id,getToken()],()=>{manager.hide();if(props.active&&props.orderId)manager.show()},{immediate:true,flush:'sync'})
function open(){if(props.active)uni.navigateTo({url:refundPath(props.orderId)})}
onUnmounted(()=>manager.dispose())
</script>
<style scoped>
.refund-notice{border:1px solid #d8e6fc;background:#edf4ff;color:#5d7fa9;padding:18px 22px;border-radius:14px;font-size:12px;line-height:1.9;margin:20px 0}.refund-notice text{display:block}.notice-title{font-size:14px;font-weight:600;color:#315d98;margin-bottom:5px}.refund-notice button{margin:12px 0 0;padding:0;min-height:28px;line-height:28px;background:transparent;color:#2463f5;text-align:left;border:0;font-size:12px}.refund-notice button:after{border:0}
</style>

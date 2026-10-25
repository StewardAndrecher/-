<template>
 <view class="page">
  <text v-if="error" class="error">{{ error }}</text>
  <view v-if="order" class="card">
   <text class="title">{{ order.productTitleSnapshot }}</text>
   <text>订单编号：{{ order.id }}</text><text>状态：{{ orderStatus(order.status) }}</text>
   <text>下单金额：¥{{ (order.priceInCentsSnapshot / 100).toFixed(2) }}</text>
   <text>买家：{{ order.buyer.nickname }}；卖家：{{ order.seller.nickname }}</text>
   <text>创建时间：{{ order.createdAt.replace('T',' ') }}</text>
   <text>付款截止时间：{{ order.paymentDueAt.replace('T',' ') }}</text>
   <text v-if="order.paidAt">模拟付款时间：{{ order.paidAt.replace('T',' ') }}</text>
   <text v-if="order.deliveredAt">卖家确认交付时间：{{ order.deliveredAt.replace('T',' ') }}</text>
   <text v-if="order.completedAt">买家确认收货时间：{{ order.completedAt.replace('T',' ') }}</text>
   <text v-if="order.closedAt">超时关闭时间：{{ order.closedAt.replace('T',' ') }}</text>
   <text v-if="order.cancelledAt">取消时间：{{ order.cancelledAt.replace('T',' ') }}</text>
   <text>以上标题、金额及双方昵称为下单时快照。</text>
   <text v-if="order.status === 'PENDING_PAYMENT'">{{ capabilities?.simulatedPaymentEnabled ? '尚未付款。请在创建后15分钟内完成模拟付款；超时由服务器关闭。' : '尚未付款。当前支付通道未开通，请勿转账；可取消订单或等待服务器超时关闭。' }}</text>
   <text v-if="order.status === 'PAID'">模拟付款已完成，没有真实扣款。请等待卖家交付商品，卖家确认交付后买家再确认收货。</text>
   <text v-if="order.status === 'CLOSED_TIMEOUT'">订单已超时关闭，商品占用已释放，没有发生付款。</text>
   <text v-if="order.status === 'DELIVERED'">卖家已确认交付，请买家核对商品；确认收到商品后再确认收货。本订单使用模拟付款。</text>
   <text v-if="order.status === 'COMPLETED'">买家已确认收货，交易流程完成。付款为教学模拟，没有真实资金结算。</text>
   <OrderRefundNotice :order-id="order.id" :active="active" @state="refundState=$event" />
   <button v-if="order.status === 'PAID' && order.seller.id === currentUser?.id" type="primary" :disabled="busy || !refundState.loaded || refundState.blocked" @click="deliver">确认已交付</button>
   <button v-if="order.status === 'DELIVERED' && order.buyer.id === currentUser?.id" type="primary" :disabled="busy || !refundState.loaded || refundState.blocked" @click="complete">确认收货</button>
   <button v-if="order.status === 'PENDING_PAYMENT' && order.buyer.id === currentUser?.id && capabilities?.simulatedPaymentEnabled" type="primary" :disabled="busy" @click="pay">模拟付款（不扣款）</button>
   <button v-if="order.status === 'PENDING_PAYMENT' && order.buyer.id === currentUser?.id" :disabled="busy" @click="cancel">取消订单</button>
  </view>
  <button v-if="order" :disabled="busy" @click="openReviews">交易评价（仅订单双方可见）</button>
  <button v-if="order" :disabled="busy" @click="openRefund">退款与售后申请 / 处理进度</button>
  <button v-if="order" :disabled="busy" @click="openIssue">交易问题反馈与本人记录</button>
  <view v-if="order" class="timeline">
   <text class="title">订单进度时间线</text>
   <text v-if="timelineError" class="error">{{ timelineError }}</text>
   <view v-for="event in events" :key="event.sequence" class="event">
    <text>{{ event.sequence }}. {{ orderEventLabel(event.action) }}</text>
    <text>操作方：{{ event.actorRole === 'BUYER' ? '买家' : event.actorRole === 'SELLER' ? '卖家' : '系统' }} · {{ event.actorNickname }}</text>
    <text>{{ event.occurredAt.replace('T',' ') }} · {{ orderStatus(event.toStatus) }}</text>
    <text v-if="event.action === 'HISTORY_SNAPSHOT'" class="note">仅表示迁移时观察到的状态，无法还原此前实际操作人及完整历史。</text>
   </view>
  </view>
  <button :disabled="busy" @click="load">{{ busy ? '处理中…' : '重新加载订单' }}</button>
  <button :disabled="busy" @click="list">返回订单列表</button>
 </view>
</template>
<script setup>
import { ref, watch } from 'vue'
import OrderRefundNotice from '@/components/OrderRefundNotice.vue'
import { getTradeCapabilities } from '@/api/trade-capabilities.js'
import { onLoad, onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { currentUser, getToken } from '@/utils/session.js'
import { getOrderTimeline, cancelOrder, simulateOrderPayment, deliverOrder, completeOrder } from '@/api/order.js'
import {orderSearchBackPath,orderSearchListPath} from '@/utils/order-search.mjs'
import { orderId, orderStatus } from '@/utils/order.js'
import {orderIssuePath} from '@/utils/order-issues.mjs'
import { reviewPath } from '@/utils/order-reviews.mjs'
import { orderEventLabel, compatibleOrderProgress } from '@/utils/order-timeline.js'
const capabilities = ref(null)
const refundState=ref({loaded:false,blocked:false})
function openRefund(){if(order.value && !busy.value)uni.navigateTo({url:`/pages/order/refund?id=${order.value.id}`})}
const order = ref(null), error = ref(''), busy = ref(false), events = ref([]), timelineError = ref('')
let backPath=null
let id = 0, generation = 0, active = false, closed = false
const alive = (n, token) => active && !closed && n === generation && token === getToken()
async function load() {
 if (!active || closed || busy.value || !id) return
 const n = ++generation, token = getToken(); order.value = null; capabilities.value = null; refundState.value={loaded:false,blocked:false}; events.value = []; timelineError.value = ''; error.value = ''; busy.value = true
 try { if (!token || !currentUser.value) throw new Error('请先登录后查看订单'); const [timeline, capability] = await Promise.all([getOrderTimeline(id), getTradeCapabilities()]); const result = timeline.order; if (!alive(n,token)) return; if (![result.buyer.id,result.seller.id].includes(currentUser.value?.id)) throw new Error('订单参与者不匹配'); capabilities.value = capability; order.value = result; events.value = timeline.events }
 catch(e) { if (alive(n,token)) error.value = e.message || '获取订单失败' }
 finally { if(n === generation) busy.value = false }
}
async function applyReceipt(confirmed,n,token) {
 order.value=confirmed;events.value=[];timelineError.value=''
 try{
  const fresh=await getOrderTimeline(id)
  if(!alive(n,token))return
  if(!compatibleOrderProgress(fresh.order,confirmed))throw new Error('订单时间线与已确认结果不一致')
  order.value=fresh.order;events.value=fresh.events
 }catch(e){if(alive(n,token))timelineError.value='操作已成功，但进度记录读取失败；请重新加载订单确认，不要重复提交：'+(e.message||'网络异常')}
}
async function cancel() {
 if (!order.value || order.value.status !== 'PENDING_PAYMENT' || order.value.buyer.id !== currentUser.value?.id || busy.value || !active) return
 const n = ++generation, token = getToken(), buyer = currentUser.value.id; busy.value = true; error.value = ''
 try {
  const confirmed = await new Promise(resolve => uni.showModal({title:'取消订单',content:'确认取消这笔未付款订单？商品随后可被重新下单。',success:r=>resolve(r.confirm),fail:()=>resolve(false)}))
  if (!confirmed || !alive(n,token) || currentUser.value?.id !== buyer) return
  const result = await cancelOrder(id); if (!alive(n,token)) return
  if (result.order.buyer.id !== buyer) throw new Error('订单买家不匹配'); await applyReceipt(result.order,n,token)
 } catch(e) { if(alive(n,token)) { order.value = null; events.value=[]; timelineError.value=''; error.value = (e.message || '取消结果暂不确定') + '；请重新加载订单确认' } }
 finally { if(n === generation) busy.value = false }
}
async function pay() {
 if (!order.value || order.value.status !== 'PENDING_PAYMENT' || order.value.buyer.id !== currentUser.value?.id || busy.value || !active || !getToken() || !capabilities.value?.simulatedPaymentEnabled) return
 const n = ++generation, token = getToken(), buyer = currentUser.value.id, snapshot = order.value
 busy.value = true; error.value = ''
 try {
  const confirmed = await new Promise(resolve => uni.showModal({ title:'模拟付款确认', content:`商品：${snapshot.productTitleSnapshot}；金额：¥${(snapshot.priceInCentsSnapshot / 100).toFixed(2)}。这是教学模拟，不会扣款。确认后商品标记已售出；可记录售后申请，不会发生真实资金流转。`,success:r=>resolve(r.confirm),fail:()=>resolve(false) }))
  if (!confirmed || !alive(n,token) || currentUser.value?.id !== buyer || order.value !== snapshot) return
  const result = await simulateOrderPayment(id)
  if (!alive(n,token)) return
  if (result.order.buyer.id !== buyer || result.order.priceInCentsSnapshot !== snapshot.priceInCentsSnapshot || result.order.productId !== snapshot.productId) throw new Error('模拟付款结果与订单不匹配')
  await applyReceipt(result.order,n,token)
 } catch(e) {
  if(alive(n,token)) { order.value=null; events.value=[]; timelineError.value=''; error.value=(e.message || '模拟付款结果暂不确定')+'；请重新加载订单确认，不要重新创建订单' }
 } finally { if(n===generation)busy.value=false }
}
async function progress(delivery) {
 const snapshot = order.value, expected = delivery ? 'PAID' : 'DELIVERED'
 if (!snapshot || snapshot.status !== expected || busy.value || !active || closed || !getToken() || !refundState.value.loaded || refundState.value.blocked) return
 const actor = delivery ? snapshot.seller.id : snapshot.buyer.id
 if (actor !== currentUser.value?.id) return
 const n = ++generation, token = getToken()
 busy.value = true; error.value = ''
 try {
  const title = delivery ? '确认已交付' : '确认收货'
  const content = delivery ? `确认已将“${snapshot.productTitleSnapshot}”交给买家？确认后将等待买家收货。此订单为模拟交易。` : `确认已收到并核对“${snapshot.productTitleSnapshot}”？确认后交易完成。此订单为模拟交易，没有真实资金结算。`
  const confirmed = await new Promise(resolve => uni.showModal({title,content,success:r=>resolve(r.confirm),fail:()=>resolve(false)}))
  if (!confirmed || !alive(n,token) || currentUser.value?.id !== actor || order.value !== snapshot) return
  const result = await (delivery ? deliverOrder(id) : completeOrder(id))
  if (!alive(n,token)) return
  const item = result.order
  if (item.productId !== snapshot.productId || item.buyer.id !== snapshot.buyer.id || item.seller.id !== snapshot.seller.id || item.priceInCentsSnapshot !== snapshot.priceInCentsSnapshot || item.productTitleSnapshot !== snapshot.productTitleSnapshot) throw new Error('订单进度结果与原订单不匹配')
  await applyReceipt(item,n,token)
 } catch(e) {
  if(alive(n,token)) {order.value=null;events.value=[];timelineError.value='';error.value=(e.message || '操作结果暂不确定')+'；请重新加载订单确认，不要重新下单'}
 } finally {if(n===generation)busy.value=false}
}
async function deliver(){await progress(true)}
async function complete(){await progress(false)}
function openReviews(){if(order.value&&!busy.value)uni.navigateTo({url:reviewPath(order.value.id),fail:()=>{error.value='打开评价页面失败'}})}
function openIssue(){if(order.value&&!busy.value)uni.navigateTo({url:orderIssuePath(order.value.id),fail:()=>{error.value='打开交易问题反馈失败'}})}
function list(){
 if(busy.value||!active||closed)return
 const pages=getCurrentPages(),failed=()=>{error.value='打开订单列表失败'}
 if(pages.length>1&&pages[pages.length-2].route==='pages/order/list'){uni.navigateBack({fail:failed});return}
 const role=order.value&&order.value.seller.id===currentUser.value?.id?'SELLER':'BUYER'
 uni.redirectTo({url:backPath||orderSearchListPath({role}),fail:failed})
}
watch(() => [currentUser.value?.id,getToken()],(next,before)=>{if(before?.[0]&&next[0]!==before[0])backPath=null;generation++;order.value=null;events.value=[];timelineError.value='';busy.value=false;if(active)load()})
onLoad(options=>{try{const parsed=orderId(options?.id);backPath=orderSearchBackPath(options);id=parsed}catch(e){error.value=e.message}})
onShow(()=>{active=true;load()})
onHide(()=>{active=false;generation++;order.value=null;events.value=[];timelineError.value='';busy.value=false})
onUnload(()=>{closed=true;active=false;generation++;order.value=null;events.value=[];timelineError.value=''})
</script>
<style scoped>.page{padding:30rpx}.card{padding:28rpx;background:#fff;border-radius:20rpx}.card text{display:block;margin:18rpx 0}.title{font-weight:bold;font-size:36rpx}.error{color:#d93025}button{margin-top:24rpx}.timeline{background:#fff;border-radius:20rpx;padding:28rpx;margin-top:24rpx}.event{padding:20rpx 0;border-bottom:1px solid #eee}.event text{display:block;margin:10rpx 0}.note{font-size:25rpx;color:#666}</style>

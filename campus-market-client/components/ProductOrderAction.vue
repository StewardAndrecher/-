<template>
  <view class="panel">
    <text class="title">{{ capabilities?.paymentMode === 'SIMULATED' ? '演示订单' : '在线交易' }}</text>
    <text v-if="capabilities?.simulatedPaymentEnabled" class="simulation-note">当前为演示流程，不发生真实扣款。</text>
    <text class="message">{{ message || '正在检查是否可以下单…' }}</text>
    <text v-if="error" class="error">{{ error }}</text>
    <button v-if="error" :disabled="busy || disabled" @click="emit('refresh')">重新加载商品详情</button>
    <button v-if="!currentUser && capabilities?.orderCreationEnabled" :disabled="busy" @click="login">登录后下单</button>
    <button v-else-if="state?.myOrderId" :disabled="busy" @click="open">查看此商品订单</button>
    <button v-else-if="state?.available && capabilities?.orderCreationEnabled" type="primary" :disabled="busy || disabled" @click="submit">{{ busy ? '正在确认…' : '确认下单（未付款）' }}</button>
    <button size="mini" :disabled="busy || disabled" @click="load">刷新订单状态</button>
  </view>
</template>
<script setup>
import { ref, watch, onUnmounted } from 'vue'
import { currentUser, getToken } from '@/utils/session.js'
import { createOrder, getOrderState } from '@/api/order.js'
import { getTradeCapabilities } from '@/api/trade-capabilities.js'
import { orderPath, orderRequestId } from '@/utils/order.js'
const emit = defineEmits(['refresh', 'availability'])
const props = defineProps({ product: { type: Object, required: true }, active: Boolean, disabled: Boolean })
const capabilities = ref(null)
const state = ref(null), busy = ref(false), error = ref(''), message = ref('')
let generation = 0, closed = false, pending = null
const alive = (n, token) => !closed && props.active && n === generation && token === getToken()
function reset() { emit('availability', null); generation++; state.value = null; capabilities.value = null; busy.value = false; error.value = ''; message.value = ''; pending = null; if (props.active) load() }
async function load() {
  if (closed || !props.active || busy.value || props.disabled) return
  const n = ++generation, token = getToken(); busy.value = true; error.value = ''
  try { const [result, capability] = await Promise.all([getOrderState(props.product.id, !!token), getTradeCapabilities()]); if (!alive(n, token)) return; state.value = result; capabilities.value = capability; message.value = capability.orderCreationEnabled ? result.message : capability.message; emit('availability', result.available) }
  catch (e) { if (alive(n, token)) { state.value = null; capabilities.value = null; error.value = e.message || '检查订单状态失败' } }
  finally { if (n === generation) busy.value = false }
}
async function submit() {
  if (closed || !props.active || props.disabled || busy.value || !state.value?.available || !capabilities.value?.orderCreationEnabled || !currentUser.value || !getToken()) return
  const token = getToken(), userId = currentUser.value.id, productId = props.product.id, version = props.product.version, title = props.product.title, price = props.product.priceInCents, n = ++generation
  busy.value = true; error.value = ''
  try {
    const confirmed = await new Promise(resolve => uni.showModal({ title: '确认下单', content: `商品：${props.product.title}；金额：¥${(props.product.priceInCents / 100).toFixed(2)}。下单不会付款；商品将保留15分钟，你可在订单详情模拟付款或取消。`, success: r => resolve(r.confirm), fail: () => resolve(false) }))
    if (!confirmed || !alive(n, token) || props.disabled || currentUser.value?.id !== userId || props.product.id !== productId || props.product.version !== version || props.product.title !== title || props.product.priceInCents !== price) return
    if (!pending || pending.productId !== productId || pending.version !== version || pending.userId !== userId) pending = { productId, version, userId, requestId: orderRequestId() }
    const result = await createOrder(productId, version, pending.requestId)
    if (!alive(n, token)) return
    if (result.order.buyer.id !== userId || result.order.productTitleSnapshot !== title || result.order.priceInCentsSnapshot !== price) throw new Error('订单结果与确认信息不匹配')
    state.value = { available: false, myOrderId: result.order.id }; emit('availability', false); message.value = ['CANCELLED', 'CLOSED_TIMEOUT'].includes(result.order.status) ? '已找到这次提交的已结束订单，请刷新商品后重新下单' : ['PAID', 'DELIVERED', 'COMPLETED'].includes(result.order.status) ? '已找到这次提交的已模拟付款订单，可查看当前交易进度' : '订单已创建，尚未付款'; open()
  } catch (e) { if (alive(n, token)) { state.value = null; error.value = (e.message || '下单结果暂不确定') + '；请刷新订单状态或查看我的订单确认，避免重复操作' } }
  finally { if (n === generation) busy.value = false }
}
function open() { if (state.value?.myOrderId) uni.navigateTo({ url: orderPath(state.value.myOrderId), fail: () => { error.value = '打开订单失败，请从首页我的订单查看' } }) }
function login() { if (!busy.value) uni.navigateTo({ url: '/pages/user/login', fail: () => { error.value = '打开登录页失败' } }) }
watch(() => [props.active, props.product.id, props.product.version, currentUser.value?.id], reset, { immediate: true })
watch(() => props.disabled, value => { if (!value && props.active && !state.value && !busy.value) load() })
onUnmounted(() => { closed = true; generation++; pending = null })
</script>
<style scoped>
.panel{padding:28rpx;margin:24rpx 0;background:white;border-radius:20rpx}.title{display:block;font-weight:bold}.message,.error{display:block;margin:18rpx 0}.error{color:#d93025}.simulation-note{display:block;color:#657b9c;font-size:12px;margin-top:10px}button{margin-top:18rpx}
</style>

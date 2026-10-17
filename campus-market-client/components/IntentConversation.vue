<template>
  <view class="conversation">
    <text class="title">意向内私密留言</text>
    <text class="hint">仅这条意向的买家和卖家可见。卖家接受后可发送；沟通记录不代表成交或付款。</text>
    <text v-if="status !== 'ACCEPTED'" class="hint">{{ status === 'CANCELLED' ? '意向已取消，仅可查看已有记录。' : '等待卖家接受意向后再沟通。' }}</text>
    <text v-if="notice" class="notice">{{ notice }}</text>
    <text v-if="loadError" class="notice">{{ loadError }}</text>
    <text v-if="stale && loaded" class="notice">保留上次查询记录，请刷新确认。</text>
    <text v-if="loaded" class="hint">共 {{ total }} 条 · 第 {{ page }}/{{ totalPages }} 页</text>
    <view v-if="loaded && readState" class="read-state">
      <text class="hint">来自对方的未读留言：{{ readState.unreadCount }} 条（刷新时更新）</text>
      <text class="hint">全部标记已读会保存本次查询时已有留言的阅读进度；后来收到的留言仍计为未读。撤回留言不计入未读。</text>
      <button size="mini" :disabled="busy || stale || readState.unreadCount === 0" :loading="markingRead" @click="markAllRead">全部标记已读</button>
    </view>
    <text v-if="loaded && !items.length" class="hint">暂无私密留言</text>
    <view v-for="item in items" :key="item.id" class="message-row">
      <text class="hint">{{ item.sender.nickname }}{{ item.sender.id === currentUser?.id ? '（我）' : '' }} · {{ item.createdAt.replace('T', ' ') }}</text>
      <text v-if="item.withdrawn" class="hint">留言已撤回 · {{ item.withdrawnAt.replace('T', ' ') }}</text>
      <text v-else class="content">{{ item.content }}</text>
      <button v-if="!item.withdrawn && item.sender.id === currentUser?.id" size="mini"
        :disabled="busy || stale" :loading="withdrawingId === item.id" @click="withdraw(item)">撤回留言 #{{ item.id }}</button>
    </view>
    <view class="actions">
      <button size="mini" :disabled="busy || page <= 1" @click="loadPage(page - 1)">上一页私密留言</button>
      <button size="mini" :disabled="busy || page >= totalPages" @click="loadPage(page + 1)">下一页私密留言</button>
      <button size="mini" :disabled="busy" @click="loadPage(page)">{{ loading ? '查询中…' : '刷新私密留言' }}</button>
    </view>
    <view v-if="status === 'ACCEPTED'">
      <textarea v-model="draft" :maxlength="-1" :disabled="busy || !!pending" placeholder="约定交接时间、地点等，最多500个字符" />
      <text class="hint">{{ Array.from(draft).length }} / 500 个字符</text>
      <text v-if="pending" class="notice">上次发送结果未确认，重试会确认同一条留言；离开页面前可先刷新记录查看是否已送达。</text>
      <button :disabled="busy || !loaded || stale" :loading="sending" @click="send">{{ pending ? '重试这条留言' : '发送私密留言' }}</button>
    </view>
  </view>
</template>

<script setup>
import { ref, computed, watch, onUnmounted } from 'vue'
import { currentUser, getToken } from '@/utils/session.js'
import { getIntentMessages, sendIntentMessage, withdrawIntentMessage, getIntentMessageReadState, markIntentMessagesRead } from '@/api/intent-message.js'
// Reuse only the UUID generator; private drafts never call the public-comment storage helpers.
import { newCommentRequestId as newMessageRequestId } from '@/utils/comment-submissions.js'
const props = defineProps({ intentId: Number, buyerId: Number, sellerId: Number, status: String, active: { type: Boolean, default: true }, disabled: Boolean })
const emit = defineEmits(['busy'])
const items = ref([]), page = ref(1), totalPages = ref(1), total = ref(0), loaded = ref(false), stale = ref(false)
const withdrawingId = ref(0), markingRead = ref(false), readState = ref(null)
const loading = ref(false), sending = ref(false), draft = ref(''), pending = ref(null), notice = ref(''), loadError = ref('')
const busy = computed(() => props.disabled || props.active === false || loading.value || sending.value || withdrawingId.value > 0 || markingRead.value)
let closed = false, generation = 0, writeGeneration = 0
function clear() { generation++; writeGeneration++; items.value = []; draft.value = ''; pending.value = null; loaded.value = false; stale.value = false; loading.value = false; sending.value = false; withdrawingId.value = 0; markingRead.value = false; readState.value = null; page.value = 1; totalPages.value = 1; total.value = 0; notice.value = ''; loadError.value = ''; emit('busy', false) }
function participant() { return currentUser.value && [props.buyerId, props.sellerId].includes(currentUser.value.id) }
watch(() => `${props.intentId}:${currentUser.value?.id || 0}`, () => { clear(); loadPage(1) }, { immediate: true })
watch(() => props.active, active => { if (!active) clear(); else loadPage(1) })
async function loadPage(next = page.value, internal = false) {
  if (closed || props.active === false || props.disabled || loading.value || ((sending.value || withdrawingId.value > 0 || markingRead.value) && !internal) || !participant()) return false
  const actor = currentUser.value.id, token = getToken(), id = props.intentId, op = ++generation
  const valid = () => !closed && props.active !== false && op === generation && actor === currentUser.value?.id && token === getToken() && id === props.intentId
  loading.value = true; loadError.value = ''; if (!internal && !pending.value) notice.value = ''
  try {
    const [data, state] = await Promise.all([getIntentMessages(id, { page: next, size: 10 }), getIntentMessageReadState(id)])
    if (!valid()) return false
    if (data.items.some(item => ![props.buyerId, props.sellerId].includes(item.sender.id))) throw new Error('留言发送者与当前意向不一致')
    readState.value = state; items.value = data.items; page.value = data.page; totalPages.value = data.totalPages; total.value = data.total; loaded.value = true
    stale.value = data.intentStatus !== props.status
    if (stale.value) loadError.value = '意向状态已变化，请先刷新上方意向详情'
    return !stale.value
  } catch (error) {
    if (valid()) { stale.value = loaded.value; loadError.value = error.message || '私密留言查询失败，请重试' }
    return false
  } finally { if (valid()) loading.value = false }
}
async function send() {
  if (closed || busy.value || !loaded.value || stale.value || !participant() || props.status !== 'ACCEPTED') return
  const text = pending.value?.content || draft.value.trim()
  if (!text || Array.from(text).length > 500 || /[\u0000-\u0008\u000b-\u001f\u007f-\u009f]/.test(text)) { notice.value = '留言须为1～500个字符，不能全为空白或包含非法控制字符'; return }
  const actor = currentUser.value.id, token = getToken(), id = props.intentId, op = ++generation
  const writeOp = ++writeGeneration
  const valid = () => !closed && props.active !== false && actor === currentUser.value?.id && token === getToken() && id === props.intentId && writeOp === writeGeneration
  if (!pending.value) pending.value = Object.freeze({ content: text, requestId: newMessageRequestId() })
  const submission = pending.value
  sending.value = true; emit('busy', true); notice.value = ''
  try {
    const data = await sendIntentMessage(id, submission)
    if (!valid() || op !== generation) return
    if (data.message.sender.id !== actor || (!data.message.withdrawn && data.message.content !== submission.content)) throw new Error('留言发送结果与当前账号或内容不一致')
    if (data.message.withdrawn) items.value = items.value.map(row => row.id === data.message.id ? data.message : row)
    pending.value = null; draft.value = ''
    const refreshed = await loadPage(1000000, true)
    if (!valid()) return
    notice.value = refreshed ? (data.message.withdrawn ? '已确认原留言已撤回，不会重新发送' : data.replayed ? '已确认原留言，没有重复发送' : '私密留言已发送') : data.message.withdrawn ? '原留言已撤回，但记录刷新失败，请刷新确认' : '留言已发送，但记录刷新失败，请刷新确认'
  } catch (error) {
    if (!valid() || op !== generation) return
    if (error.statusCode >= 400 && error.statusCode < 500) { pending.value = null; stale.value = true; notice.value = `${error.message || '留言未发送'}；请先刷新记录确认状态` }
    else notice.value = '未能确认发送结果，可刷新记录查看，或重试这条留言；不会自动重复发送'
  } finally { if (valid()) { sending.value = false; emit('busy', false) } }
}
async function withdraw(item) {
  if (closed || busy.value || !loaded.value || stale.value || !participant() || item.withdrawn || item.sender.id !== currentUser.value.id || !items.value.some(row => row.id === item.id && !row.withdrawn && row.sender.id === currentUser.value.id)) return
  const actor = currentUser.value.id, token = getToken(), id = props.intentId, messageId = item.id, createdAt = item.createdAt
  const op = ++generation, writeOp = ++writeGeneration
  const valid = () => !closed && props.active !== false && actor === currentUser.value?.id && token === getToken() && id === props.intentId && writeOp === writeGeneration
  withdrawingId.value = messageId; emit('busy', true); notice.value = ''
  try {
    const choice = await new Promise((resolve, reject) => uni.showModal({ title: '撤回这条私密留言？', content: '撤回后保留记录编号，对方刷新后将看到“留言已撤回”。当前不支持恢复显示。', confirmText: '确认撤回', success: resolve, fail: reject }))
    if (!valid() || op !== generation || !choice.confirm || props.disabled || !items.value.some(row => row.id === messageId && !row.withdrawn && row.sender.id === actor)) return
    const data = await withdrawIntentMessage(id, messageId)
    if (!valid() || op !== generation) return
    if (data.message.id !== messageId || data.message.intentId !== id || data.message.sender.id !== actor || data.message.createdAt !== createdAt || data.message.withdrawn !== true || data.message.content !== null) throw new Error('撤回结果与当前留言不一致')
    // Hide acknowledged withdrawn content immediately, even if the following refresh fails.
    items.value = items.value.map(row => row.id === messageId ? data.message : row)
    const refreshed = await loadPage(page.value, true)
    if (!valid()) return
    notice.value = refreshed ? (data.replayed ? '这条留言此前已撤回，已确认原结果' : '私密留言已撤回') : '留言已撤回，但记录刷新失败，请刷新确认'
  } catch (error) {
    if (valid()) { stale.value = loaded.value; notice.value = error.statusCode === 403 || error.statusCode === 404 ? `${error.message}；请刷新记录确认` : '未能确认撤回结果，请刷新私密留言后确认，不会自动重试' }
  } finally { if (valid()) { withdrawingId.value = 0; emit('busy', false) } }
}
async function markAllRead() {
  if (closed || busy.value || !loaded.value || stale.value || !participant() || !readState.value?.unreadCount) return
  const actor = currentUser.value.id, token = getToken(), id = props.intentId
  const through = readState.value.latestMessageId, before = readState.value.lastReadMessageId
  const op = ++generation, writeOp = ++writeGeneration
  const valid = () => !closed && props.active !== false && actor === currentUser.value?.id && token === getToken() && id === props.intentId && writeOp === writeGeneration
  markingRead.value = true; emit('busy', true); notice.value = ''
  try {
    const state = await markIntentMessagesRead(id, through)
    if (!valid() || op !== generation) return
    if (state.intentId !== id || state.lastReadMessageId < through || state.lastReadMessageId < before) throw new Error('阅读进度与当前意向不一致')
    readState.value = state
    notice.value = state.unreadCount ? '已保存阅读进度，还有新留言未读，请刷新查看' : '已将本次查询时已有的留言标记已读'
  } catch (error) {
    if (valid()) { stale.value = loaded.value; notice.value = '未能确认阅读进度，请刷新私密留言后确认，不会自动重试' }
  } finally { if (valid()) { markingRead.value = false; emit('busy', false) } }
}
onUnmounted(() => { closed = true; clear() })
</script>

<style scoped>
.conversation { margin: 24rpx 0; padding: 28rpx; background: #fff; border-radius: 16rpx; }
.title, .hint, .notice, .content { display: block; margin-top: 14rpx; line-height: 1.6; overflow-wrap: anywhere; }
.title { font-weight: bold; font-size: 32rpx; } .hint { font-size: 26rpx; color: #666; } .notice { color: #b54400; }
.message-row { padding: 16rpx 0; border-bottom: 1rpx solid #eee; } .content { white-space: pre-wrap; }
.actions { display: flex; flex-wrap: wrap; gap: 12rpx; margin: 20rpx 0; } .actions button { margin: 0; }
textarea { width: 100%; min-height: 180rpx; padding: 16rpx; border: 1rpx solid #ccc; border-radius: 8rpx; box-sizing: border-box; }
</style>

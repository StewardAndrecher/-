<template>
  <view class="comments-panel">
    <text class="comments-title">商品留言（{{ total }}）</text>
    <text class="hint">留言公开可见，请勿填写手机号、住址等私人信息。</text>
    <button size="mini" :disabled="loading || sending || withdrawingId > 0" @click="loadComments()">
      {{ loading ? '加载中…' : '刷新留言' }}
    </button>
    <text v-if="message" class="notice">{{ message }}</text>
    <view v-for="item in records" :key="item.id" class="comment-row">
      <text class="author">{{ item.author.nickname }}{{ item.seller ? ' · 卖家' : '' }}</text>
      <text class="comment-content">{{ item.content }}</text>
      <text class="hint">{{ formatTime(item.createdAt) }}</text>
      <button v-if="item.author.id === currentUser?.id" size="mini"
        :disabled="loading || sending || withdrawingId > 0" @click="withdraw(item.id)">
        {{ withdrawingId === item.id ? '撤回中…' : '撤回我的留言' }}
      </button>
    </view>
    <text v-if="!loading && records.length === 0 && !message" class="hint">还没有留言</text>
    <view class="pagination">
      <button size="mini" :disabled="loading || sending || withdrawingId > 0 || page <= 1" @click="loadComments(page - 1)">上一页留言</button>
      <text>第 {{ page }} / {{ totalPages }} 页</text>
      <button size="mini" :disabled="loading || sending || withdrawingId > 0 || page >= totalPages" @click="loadComments(page + 1)">下一页留言</button>
    </view>
    <view v-if="currentUser && (pendingSubmission || pendingStorageError)" class="pending-panel">
      <text class="hint">本机有 {{ pendingCount }} 条待确认草稿。确认时继续使用原提交，内容保持不变。</text>
      <text v-if="pendingSubmission" class="comment-content">{{ pendingSubmission.content }}</text>
      <button v-if="pendingSubmission" type="primary" :disabled="loading || sending || withdrawingId > 0" @click="sendComment">
        {{ sending ? '确认中…' : '重试确认这次发表' }}
      </button>
      <text class="hint">放弃本机草稿只清除这份待确认内容，不会撤回已经发表的留言。</text>
      <button :disabled="loading || sending || withdrawingId > 0" @click="discardPendingDraft">放弃本机待确认草稿</button>
    </view>
    <text v-if="status !== 'ON_SALE'" class="hint">商品已下架，暂不支持新留言。</text>
    <button v-else-if="!currentUser" @click="openLogin">登录后留言</button>
    <view v-else-if="!pendingSubmission && !pendingStorageError">
      <text class="author">{{ currentUser.nickname }}，发表留言</text>
      <textarea v-model="draftContent" class="comment-input" :maxlength="1000"
        :disabled="loading || sending || withdrawingId > 0" placeholder="询问成色、交接地点等，最多500个字符" />
      <text class="hint">{{ Array.from(draftContent).length }} / 500</text>
      <button type="primary" :disabled="loading || sending || withdrawingId > 0" @click="sendComment">
        {{ sending ? '发送中…' : '发表留言' }}
      </button>
    </view>
  </view>
</template>

<script setup>
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { getComments, createComment, withdrawComment } from '@/api/comment.js'
import { currentUser, getToken } from '@/utils/session.js'
import { newCommentRequestId, loadPendingComments, savePendingComment, clearPendingComment, clearPendingComments } from '@/utils/comment-submissions.js'

const props = defineProps({ productId: { type: Number, required: true }, status: { type: String, required: true } })
const emit = defineEmits(['busy'])
const SIZE = 5
const records = ref([])
const total = ref(0)
const page = ref(1)
const loading = ref(false)
const sending = ref(false)
const withdrawingId = ref(0)
const message = ref('')
const draftContent = ref('')
const pendingSubmission = ref(null)
const pendingCount = ref(0)
const pendingStorageError = ref(false)
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / SIZE)))
let closed = false
let operation = 0

watch(() => sending.value || withdrawingId.value > 0, value => emit('busy', value), { immediate: true })
watch([() => props.productId, () => currentUser.value?.id], () => {
  operation++
  records.value = []; total.value = 0; page.value = 1; draftContent.value = ''
  loading.value = false; sending.value = false; withdrawingId.value = 0; pendingSubmission.value = null; pendingCount.value = 0; pendingStorageError.value = false; message.value = ''
  restorePendingDraft()
  loadComments(1)
})

function isValidComment(item) {
  return !!item && Number.isSafeInteger(item.id) && item.id > 0 && item.productId === props.productId &&
    typeof item.content === 'string' && Array.from(item.content).length > 0 && Array.from(item.content).length <= 500 &&
    Number.isSafeInteger(item.author?.id) && item.author.id > 0 && typeof item.author.nickname === 'string' &&
    typeof item.seller === 'boolean' && typeof item.createdAt === 'string' && !Number.isNaN(Date.parse(item.createdAt))
}

function formatTime(value) {
  const time = new Date(value)
  const pad = number => String(number).padStart(2, '0')
  return `${time.getFullYear()}-${pad(time.getMonth() + 1)}-${pad(time.getDate())} ${pad(time.getHours())}:${pad(time.getMinutes())}`
}

async function loadComments(targetPage = page.value) {
  if (closed || loading.value || sending.value || withdrawingId.value > 0 || targetPage < 1) return
  const token = getToken(), id = props.productId, number = ++operation
  loading.value = true; message.value = ''
  let smallerPage = 0
  try {
    const data = await getComments(id, { page: targetPage, size: SIZE })
    if (closed || number !== operation || token !== getToken() || id !== props.productId) return
    if (!data || !Array.isArray(data.records) || !data.records.every(isValidComment) ||
        !Number.isSafeInteger(data.total) || data.total < data.records.length || data.page !== targetPage ||
        data.size !== SIZE || data.records.length > SIZE) throw new Error('留言列表格式不正确')
    records.value = data.records; total.value = data.total; page.value = targetPage
    const lastPage = Math.max(1, Math.ceil(data.total / SIZE))
    if (targetPage > lastPage) smallerPage = lastPage

  } catch (error) {
    if (!closed && number === operation && (token === getToken() || !getToken())) {
      records.value = []; total.value = 0
      message.value = error.statusCode === 404 ? '商品不存在或已下架，留言暂不可见'
        : error.statusCode === 401 ? '登录已失效，请重新登录' : error.message || '加载留言失败'
    }
  } finally {
    if (number === operation) loading.value = false
  }
  if (smallerPage && !closed && token === getToken()) await loadComments(smallerPage)
}

function restorePendingDraft() {
  const userId = currentUser.value?.id
  if (!userId || !getToken()) return
  try {
    const entries = loadPendingComments(userId, props.productId)
    pendingSubmission.value = entries[0] || null
    pendingCount.value = entries.length
    pendingStorageError.value = false
    if (pendingSubmission.value) draftContent.value = pendingSubmission.value.content
  } catch (error) {
    pendingStorageError.value = true
    message.value = error.message || '待确认草稿读取失败'
  }
}

async function sendComment() {
  if (closed || loading.value || sending.value || withdrawingId.value > 0 || pendingStorageError.value) return
  const token = getToken(), authorId = currentUser.value?.id
  if (!token || !authorId) { openLogin(); return }
  if (!pendingSubmission.value && props.status !== 'ON_SALE') { message.value = '商品已下架，暂不支持新留言'; return }
  const content = pendingSubmission.value?.content || draftContent.value.trim()
  if (Array.from(content).length < 1 || Array.from(content).length > 500) {
    message.value = '留言需为1～500个字符，不能全为空白'; return
  }
  // 先保存再发送；每个requestId单独存储，多个页面的草稿互不覆盖。
  if (!pendingSubmission.value) {
    try {
      pendingSubmission.value = savePendingComment(authorId, props.productId, {
        requestId: newCommentRequestId(), content, createdAt: Date.now()
      })
      pendingCount.value++
      draftContent.value = content
    } catch (error) {
      message.value = '草稿未保存，尚未发送：' + (error.message || '本机存储不可用')
      return
    }
  }
  const submission = pendingSubmission.value
  const id = props.productId, number = ++operation
  sending.value = true; message.value = ''
  let confirmed = false, withdrawn = false, replayed = false
  try {
    const result = await createComment(id, { content: submission.content, requestId: submission.requestId })
    if (closed || number !== operation || token !== getToken() || id !== props.productId) return
    if (result?.requestId !== submission.requestId || typeof result.replayed !== 'boolean' ||
        typeof result.withdrawn !== 'boolean' || !isValidComment(result.comment) ||
        result.comment.author.id !== authorId || result.comment.content !== submission.content) {
      throw new Error('提交回执异常，请使用原草稿重试确认')
    }
    confirmed = true; withdrawn = result.withdrawn; replayed = result.replayed
    // 只清理已确认的那条草稿，不删除其他页面的待确认提交。
    clearPendingComment(authorId, id, submission.requestId)
    pendingSubmission.value = null; draftContent.value = ''
    restorePendingDraft()
  } catch (error) {
    if (!closed && number === operation && (token === getToken() || !getToken())) {
      message.value = confirmed ? '发表结果已确认，但本机草稿清理失败，可再次确认'
        : error.statusCode === 0 ? '未收到结果，原草稿已保存；重试确认不会重复发表'
        : error.statusCode === 401 ? '登录已失效，重新登录后可继续确认原草稿'
        : error.message || '发表失败，原草稿保留'
    }
  } finally {
    if (number === operation) sending.value = false
  }
  if (confirmed && !closed && token === getToken()) {
    const cleanupError = message.value
    await loadComments(1)
    if (!closed && token === getToken()) {
      const resultText = withdrawn ? '原留言已撤回，未重新创建' : replayed ? '已确认原留言，未重复发表' : '留言已发表'
      message.value = resultText + (cleanupError ? '；' + cleanupError : message.value ? '；' + message.value : '')
    }
  }
}

function discardPendingDraft() {
  if (closed || loading.value || sending.value || withdrawingId.value > 0) return
  const userId = currentUser.value?.id
  if (!userId || !getToken()) return
  try {
    if (pendingStorageError.value) clearPendingComments(userId, props.productId)
    else if (pendingSubmission.value) clearPendingComment(userId, props.productId, pendingSubmission.value.requestId)
    pendingSubmission.value = null; draftContent.value = ''
    restorePendingDraft()
    message.value = '已放弃本机草稿；已经发表的留言不会被撤回。'
  } catch (error) { message.value = error.message || '清理草稿失败' }
}

async function withdraw(commentId) {
  if (closed || loading.value || sending.value || withdrawingId.value > 0) return
  const token = getToken()
  if (!token || !currentUser.value) { openLogin(); return }
  const item = records.value.find(row => row.id === commentId)
  if (!item || item.author.id !== currentUser.value.id) { message.value = '只能撤回自己的留言'; return }
  const id = props.productId, number = ++operation
  withdrawingId.value = commentId; message.value = ''
  let removed = false
  try {
    await withdrawComment(id, commentId)
    if (closed || number !== operation || token !== getToken() || id !== props.productId) return
    removed = true
  } catch (error) {
    if (!closed && number === operation && (token === getToken() || !getToken())) {
      message.value = error.statusCode === 0 ? '未收到撤回结果，请刷新确认' : error.message || '撤回失败'
    }
  } finally {
    if (number === operation) withdrawingId.value = 0
  }
  if (removed && !closed && token === getToken()) await loadComments()
}

function openLogin() {
  uni.navigateTo({ url: '/pages/user/login', fail: () => uni.showToast({ title: '打开登录页失败', icon: 'none' }) })
}
onMounted(() => { restorePendingDraft(); loadComments(1) })
onUnmounted(() => { closed = true; operation++; emit('busy', false) })
</script>

<style scoped>
.pending-panel { padding: 20rpx; background: #fff8e8; border-radius: 12rpx; margin-top: 20rpx; }
.comments-panel { padding: 28rpx; margin: 24rpx 0; background: white; border-radius: 20rpx; }
.comments-title { display: block; font-size: 34rpx; font-weight: bold; }
.comment-row { border-bottom: 1rpx solid #e5e7eb; padding: 24rpx 0; }
.author { display: block; margin: 16rpx 0; font-weight: bold; }
.comment-content { display: block; white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.7; }
.hint, .notice { display: block; margin: 16rpx 0; color: #666; }
.notice { color: #c0392b; }
.comment-input { width: 100%; height: 180rpx; padding: 16rpx; box-sizing: border-box; background: #f5f7fa; border-radius: 12rpx; }
.pagination { display: flex; align-items: center; justify-content: space-between; gap: 12rpx; }
button { margin-top: 16rpx; }
</style>

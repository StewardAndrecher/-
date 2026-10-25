<template>
  <view class="page">
    <text class="title">账号资料</text>
    <text class="message">离开此页会清除未保存的昵称草稿，返回后重新读取资料。</text>
    <view v-if="!currentUser" class="panel">
      <text class="message">请先登录后查看账号资料</text>
      <button type="primary" @click="openLogin">去登录</button>
    </view>
    <text v-else-if="loading" class="message">正在加载账号资料…</text>
    <view v-else-if="loadError" class="panel">
      <text class="error">{{ loadError }}</text>
      <button @click="loadProfile()">重试加载</button>
    </view>
    <view v-else-if="profile && profile.id === currentUser.id" class="panel">
      <text class="message">账号编号：{{ profile.id }}</text>
      <text class="message">用户名：{{ profile.username }}</text>
      <text class="message">用户名用于登录，昵称用于商品和留言展示。</text>
      <text class="label">昵称</text>
      <input v-model="draftNickname" class="field" :maxlength="-1" :disabled="saving"
        placeholder="1～30个字符" @confirm="saveNickname" />
      <text class="message">{{ Array.from(draftNickname).length }} / 30 个字符</text>
      <text v-if="message" :class="message === '昵称已保存' ? 'message' : 'error'">{{ message }}</text>
      <button v-if="conflict || uncertain" :disabled="saving" @click="loadProfile(true)">
        重新加载最新资料（替换草稿）
      </button>
      <button type="primary" :loading="saving" :disabled="saving || conflict || uncertain" @click="saveNickname">
        {{ saving ? '保存中…' : '保存昵称' }}
      </button>
      <button :disabled="saving" @click="loadProfile()">刷新资料（保留草稿）</button>
    </view>
    <AccountProfileCard v-if="currentUser" :active="pageVisible" />
    <button @click="openPage('/pages/user/email')">邮箱绑定与邮件找回</button>
    <button @click="openPage('/pages/user/content-status')">内容与账号状态</button>
    <button @click="openPage('/pages/user/images')">图片空间与回收站</button>
    <button v-if="currentUser" :disabled="loading || saving" @click="openRecovery">恢复码与找回密码设置</button>
    <button v-if="currentUser" :disabled="loading || saving" @click="openPassword">修改密码</button>
    <button v-if="currentUser" :disabled="loading || saving" @click="openSessions">登录会话管理</button>
    <button :disabled="loading || saving" @click="goHome">返回首页</button>
  </view>
</template>

<script setup>
import { openPage } from '@/composables/usePrivatePage.js'
import { ref, watch } from 'vue'
import AccountProfileCard from '@/components/AccountProfileCard.vue'
import { onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { getCurrentUser, updateMyNickname } from '@/api/user.js'
import { currentUser, getToken, updateCurrentUser } from '@/utils/session.js'
import { createPageTaskScope } from '@/utils/page-task-scope.mjs'
const profile = ref(null)
const draftNickname = ref('')
const expectedNickname = ref('')
const loading = ref(false)
const saving = ref(false)
const conflict = ref(false)
const uncertain = ref(false)
const loadError = ref('')
const message = ref('')
const pageVisible = ref(false)
const tasks = createPageTaskScope(() => ({ userId: currentUser.value?.id, token: getToken() }))

function resetPrivateState() {
  profile.value = null
  draftNickname.value = ''; expectedNickname.value = ''
  loading.value = false; saving.value = false
  conflict.value = false; uncertain.value = false
  loadError.value = ''; message.value = ''
}
watch(() => [currentUser.value?.id, getToken()], (next, previous) => {
  // Updating the nickname in the same session must not invalidate its own save.
  if (previous && next[0] === previous[0] && next[1] === previous[1]) return
  tasks.invalidate(); resetPrivateState()
  if (pageVisible.value && currentUser.value && getToken()) loadProfile()
}, { flush: 'sync' })

function matchesUser(user, owner) {
  return user && owner && user.id === owner.id && user.username === owner.username &&
    typeof user.nickname === 'string' && !!user.nickname.trim() &&
    Array.from(user.nickname).length <= 30 && !/[\u0000-\u001f\u007f-\u009f]/.test(user.nickname)
}

async function loadProfile(discardDraft = false) {
  const ticket = tasks.capture()
  if (!ticket || loading.value || saving.value) return
  const owner = currentUser.value
  loading.value = true
  loadError.value = ''
  try {
    const user = await getCurrentUser()
    if (!tasks.valid(ticket)) return
    if (!matchesUser(user, owner)) throw new Error('账号资料响应不匹配')
    updateCurrentUser(user, ticket.token)
    const keepDraft = !discardDraft && profile.value?.id === user.id
    profile.value = user
    if (!keepDraft) {
      draftNickname.value = user.nickname
      expectedNickname.value = user.nickname
      conflict.value = false
      uncertain.value = false
      message.value = ''
    } else if (expectedNickname.value !== user.nickname) {
      conflict.value = true
      message.value = '昵称已在另一处更新，草稿仍保留。重新加载将替换草稿。'
    }
  } catch (error) {
    if (tasks.valid(ticket))
      loadError.value = error?.statusCode === 401 ? '登录已失效，请重新登录' : error?.message || '加载资料失败'
  } finally {
    if (tasks.valid(ticket)) loading.value = false
  }
}

async function saveNickname() {
  const ticket = tasks.capture()
  if (!ticket || loading.value || saving.value || conflict.value || uncertain.value || !profile.value || loadError.value) return
  const owner = currentUser.value
  if (profile.value.id !== owner.id) {
    loadError.value = '请先登录后修改昵称'; return
  }
  const nickname = draftNickname.value.trim()
  const length = Array.from(nickname).length
  message.value = ''
  if (length < 1 || length > 30) { message.value = '昵称需要1～30个字符'; return }
  if (/[\u0000-\u001f\u007f-\u009f]/.test(nickname)) { message.value = '昵称不能包含换行或控制字符'; return }
  saving.value = true
  try {
    const result = await updateMyNickname({ nickname, expectedNickname: expectedNickname.value })
    if (!tasks.valid(ticket)) return
    if (!matchesUser(result, owner) || result.nickname !== nickname) {
      uncertain.value = true
      message.value = '修改结果格式异常，请重新加载资料确认'; return
    }
    updateCurrentUser(result, ticket.token)
    profile.value = result
    draftNickname.value = result.nickname
    expectedNickname.value = result.nickname
    message.value = '昵称已保存'
  } catch (error) {
    if (tasks.valid(ticket)) {
      if (error?.statusCode === 409 && error?.code === 'USER_PROFILE_CONFLICT') {
        conflict.value = true
        message.value = '昵称已在另一处更新，本次未保存。草稿仍保留，请重新加载最新资料。'
      } else if (error?.statusCode === 401) loadError.value = '登录已失效，请重新登录'
      else if (error?.statusCode === 0 || error?.statusCode >= 500) {
        uncertain.value = true
        message.value = '未收到可靠的修改结果，请重新加载资料确认后再操作。'
      } else message.value = error?.message || '保存昵称失败'
    }
  } finally { if (tasks.valid(ticket)) saving.value = false }
}

function openRecovery(){if(tasks.capture() && !loading.value && !saving.value)openPage('/pages/user/recovery')}
function openPassword() {
  if (tasks.capture() && !loading.value && !saving.value) uni.navigateTo({ url: '/pages/user/password' })
}
function openSessions() {
  if (tasks.capture() && !loading.value && !saving.value) uni.navigateTo({ url: '/pages/user/sessions' })
}
function openLogin() {
  if (pageVisible.value && !currentUser.value && !loading.value && !saving.value)
    uni.navigateTo({ url: '/pages/user/login' })
}
function goHome() {
  if (!pageVisible.value || loading.value || saving.value) return
  const fail = () => uni.showToast({ title: '打开首页失败，请重试', icon: 'none' })
  if (getCurrentPages().length > 1) uni.navigateBack({ fail })
  else uni.reLaunch({ url: '/pages/index/index', fail })
}
onShow(() => {
  if (!tasks.show()) return
  pageVisible.value = true
  resetPrivateState()
  return loadProfile()
})
onHide(() => {
  pageVisible.value = false
  tasks.hide(); resetPrivateState()
})
onUnload(() => {
  pageVisible.value = false
  tasks.dispose(); resetPrivateState()
})
</script>

<style scoped>
.page { max-width:900px;margin:0 auto;padding:36px 24px 50px;min-height:100vh;background:#f5f8fd;color:#203c61;box-sizing:border-box; }
.panel { padding:26px;margin:24px 0;background:#fff;border:1px solid #e4ecf7;border-radius:18px; }
.title { display: block; font-size: 36rpx; font-weight: bold; }
.label { display: block; margin: 28rpx 0 12rpx; }
.field { height: 88rpx; padding: 0 20rpx; border: 1px solid #d9dfe8; border-radius: 12rpx; }
.message, .error { display: block; margin-top: 20rpx; font-size: 28rpx; }
.error { color: #d93025; }
button { margin-top: 24rpx; }
</style>

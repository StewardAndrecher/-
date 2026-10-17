<template>
  <view class="page">
    <text class="title">账号资料</text>
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
    <button :disabled="loading || saving" @click="goHome">返回首页</button>
  </view>
</template>

<script setup>
import { ref } from 'vue'
import { onShow, onUnload } from '@dcloudio/uni-app'
import { getCurrentUser, updateMyNickname } from '@/api/user.js'
import { currentUser, getToken, updateCurrentUser } from '@/utils/session.js'
const profile = ref(null)
const draftNickname = ref('')
const expectedNickname = ref('')
const loading = ref(false)
const saving = ref(false)
const conflict = ref(false)
const uncertain = ref(false)
const loadError = ref('')
const message = ref('')
let pageClosed = false

function matchesUser(user, owner) {
  return user && owner && user.id === owner.id && user.username === owner.username &&
    typeof user.nickname === 'string' && !!user.nickname.trim() &&
    Array.from(user.nickname).length <= 30
}

async function loadProfile(discardDraft = false) {
  if (pageClosed || loading.value || saving.value) return
  const token = getToken()
  const owner = currentUser.value
  if (!token || !owner) { loadError.value = '请先登录后查看账号资料'; return }
  loading.value = true
  loadError.value = ''
  try {
    const user = await getCurrentUser()
    if (pageClosed || token !== getToken()) return
    if (!matchesUser(user, owner)) throw new Error('账号资料响应不匹配')
    updateCurrentUser(user, token)
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
    if (!pageClosed && (token === getToken() || !getToken()))
      loadError.value = error.statusCode === 401 ? '登录已失效，请重新登录' : error.message || '加载资料失败'
  } finally {
    loading.value = false
    if (!pageClosed && token !== getToken() && getToken()) await loadProfile()
  }
}

async function saveNickname() {
  if (pageClosed || loading.value || saving.value || conflict.value || uncertain.value || !profile.value || loadError.value) return
  const token = getToken()
  const owner = currentUser.value
  if (!token || !owner || profile.value.id !== owner.id) {
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
    if (pageClosed || token !== getToken()) return
    if (!matchesUser(result, owner) || result.nickname !== nickname) {
      uncertain.value = true
      message.value = '修改结果格式异常，请重新加载资料确认'; return
    }
    updateCurrentUser(result, token)
    profile.value = result
    draftNickname.value = result.nickname
    expectedNickname.value = result.nickname
    message.value = '昵称已保存'
  } catch (error) {
    if (!pageClosed && (token === getToken() || !getToken())) {
      if (error.statusCode === 409 && error.code === 'USER_PROFILE_CONFLICT') {
        conflict.value = true
        message.value = '昵称已在另一处更新，本次未保存。草稿仍保留，请重新加载最新资料。'
      } else if (error.statusCode === 401) loadError.value = '登录已失效，请重新登录'
      else if (error.statusCode === 0 || error.statusCode >= 500) {
        uncertain.value = true
        message.value = '未收到可靠的修改结果，请重新加载资料确认后再操作。'
      } else message.value = error.message || '保存昵称失败'
    }
  } finally { saving.value = false }
}

function openLogin() { uni.navigateTo({ url: '/pages/user/login' }) }
function goHome() {
  if (loading.value || saving.value) return
  const fail = () => uni.showToast({ title: '打开首页失败，请重试', icon: 'none' })
  if (getCurrentPages().length > 1) uni.navigateBack({ fail })
  else uni.reLaunch({ url: '/pages/index/index', fail })
}
onShow(() => loadProfile())
onUnload(() => { pageClosed = true })
</script>

<style scoped>
.page { padding: 32rpx; min-height: 100vh; background: #f5f7fa; box-sizing: border-box; }
.panel { padding: 32rpx; margin: 24rpx 0; background: #fff; border-radius: 20rpx; }
.title { display: block; font-size: 36rpx; font-weight: bold; }
.label { display: block; margin: 28rpx 0 12rpx; }
.field { height: 88rpx; padding: 0 20rpx; border: 1px solid #d9dfe8; border-radius: 12rpx; }
.message, .error { display: block; margin-top: 20rpx; font-size: 28rpx; }
.error { color: #d93025; }
button { margin-top: 24rpx; }
</style>

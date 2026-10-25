<template>
  <view class="page auth-page">
    <view class="auth-brand"><image src="/static/ui/brand-mark.png" mode="aspectFit" /><text>校园集市</text><text class="auth-caption">好物相遇，从这里开始</text></view>
    <view class="panel" v-if="loginDone && currentUser">
      <text class="title">登录成功</text>
      <text class="message">你好，{{ currentUser.nickname }}！</text>
    </view>
    <view class="panel" v-else>
      <text class="auth-eyebrow">WELCOME BACK</text>
      <text class="title">登录校园集市</text>
      <text class="auth-subtitle">欢迎回来，继续发现校园里的小美好。</text>
      <text class="label">用户名</text>
      <input v-model="username" class="field" :maxlength="-1"
        placeholder="填写已注册的用户名" :disabled="submitting" />
      <text class="label">密码</text>
      <input v-model="password" class="field" :password="true" :maxlength="-1"
        placeholder="填写密码" :disabled="submitting" @confirm="submitLogin" />
      <text v-if="errorMessage" class="error">{{ errorMessage }}</text>
      <text v-if="retryIn > 0" class="hint">请等待{{ retryIn }}秒后再登录；用户名仍保留，不会自动提交。</text>
      <button type="primary" :loading="submitting" :disabled="submitting || retryIn > 0" @click="submitLogin">
        {{ submitting ? '登录中…' : retryIn > 0 ? `等待${retryIn}秒后重试` : '登录' }}
      </button>
    </view>
    <button class="back-home" :disabled="submitting" @click="goHome">返回首页</button>
  <button class="recovery-link" :disabled="submitting" @click="openRecovery">忘记密码？使用恢复码找回</button>
  </view>
</template>

<script setup>
function openRecovery(){if(!submitting.value && !pageClosed)uni.navigateTo({url:'/pages/user/recovery'})}
import { ref } from 'vue'
import { onLoad, onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { loginUser } from '@/api/user.js'
import { saveSession, currentUser } from '@/utils/session.js'
import { createLoginCooldown } from '@/utils/login-retry.mjs'

const username = ref('')
const password = ref('')
const submitting = ref(false)
const errorMessage = ref('')
const loginDone = ref(false)
let pageClosed = false
let pageActive = true
const retryIn = ref(0)
const cooldown = createLoginCooldown()
let retryTimer = null
function stopRetry() {
  if (retryTimer !== null) { clearInterval(retryTimer); retryTimer = null }
}
function updateRetry() {
  retryIn.value = cooldown.remaining()
  if (retryIn.value === 0) stopRetry()
}
function startRetry() {
  stopRetry()
  updateRetry()
  if (pageActive && retryIn.value > 0) retryTimer = setInterval(updateRetry, 250)
}

async function submitLogin() {
  if (submitting.value || loginDone.value || pageClosed) return
  updateRetry()
  if (retryIn.value > 0) return
  errorMessage.value = ''
  const loginName = username.value.trim()
  const rawPassword = password.value
  if (!/^[a-z0-9_]{4,32}$/.test(loginName)) {
    errorMessage.value = '请填写符合格式的用户名'
    return
  }
  if (!rawPassword || Array.from(rawPassword).length > 64) {
    errorMessage.value = '请填写密码，最多64个字符'
    return
  }
  submitting.value = true
  try {
    const result = await loginUser({ username: loginName, password: rawPassword })
    if (pageClosed) return
    if (result?.user?.username !== loginName) throw new Error('登录响应用户不匹配')
    saveSession(result)
    cooldown.clear(); stopRetry(); retryIn.value = 0
    password.value = ''
    loginDone.value = true
  } catch (error) {
    if (pageClosed) return
    const waiting = cooldown.start(error)
    if (waiting) startRetry()
    errorMessage.value = waiting && error.statusCode === 429
      ? '登录尝试过于频繁，请等待倒计时结束后再提交'
      : (error.message || '登录请求失败')
  } finally {
    submitting.value = false
  }
  if (loginDone.value && !pageClosed && pageActive) goHome()
}

function goHome() {
  if (submitting.value) return
  const fail = () => uni.showToast({ title: '打开首页失败，请再次点击返回', icon: 'none' })
  if (getCurrentPages().length > 1) {
    uni.navigateBack({ fail })
  } else {
    uni.reLaunch({ url: '/pages/index/index', fail })
  }
}

onLoad((options) => {
  if (typeof options.username === 'string' && /^[a-z0-9_]{4,32}$/.test(options.username)) {
    username.value = options.username
  }
})
onShow(() => {
  pageActive = true
  startRetry()
  if (loginDone.value && currentUser.value) goHome()
})
onHide(() => { pageActive = false; stopRetry(); password.value = '' })
onUnload(() => {
  pageClosed = true; pageActive = false
  stopRetry(); cooldown.clear(); retryIn.value = 0; password.value = ''
})
</script>

<style scoped>
.recovery-link{background:transparent;border:0;color:#6686b5;font-size:12px;margin-top:18px}.recovery-link:after{border:0}
.auth-page { max-width: none; min-height: calc(100vh - 44px); padding: 38px 20px 48px; box-sizing: border-box; background: #f5f7fc; }
.auth-brand { display: flex; flex-wrap: wrap; align-items: center; justify-content: center; width: 100%; max-width: 440px; margin: 0 auto 26px; gap: 10px; font-size: 22px; font-weight: 750; color: #173f89; }
.auth-brand image { width: 31px; height: 35px; }
.auth-caption { display: block; width: 100%; text-align: center; margin-top: 2px; font-size: 12px; font-weight: 400; letter-spacing: 2px; color: #98a6bc; }
.panel { max-width: 440px; padding: 34px 36px; margin: 0 auto 20px; background: #fff; border: 1px solid #e4eaf4; border-radius: 20px; box-shadow: 0 12px 40px #273e6806; }
.auth-eyebrow { display: block; color: #93a7c7; font-size: 9px; letter-spacing: 2px; margin-bottom: 10px; }
.auth-page .panel .title { display: block; font-size: 24px; font-weight: 750; color: #1d355b; }
.auth-subtitle { display: block; font-size: 12px; line-height: 1.8; color: #94a0b2; margin-top: 10px; margin-bottom: 25px; }
.label { display: block; font-size: 13px; color: #5d708e; margin: 22px 0 9px; }
.field { height: 45px; padding: 0 13px; border: 1px solid #e0e7f2; border-radius: 9px; background: #f9fafd; font-size: 13px; }
.hint, .message, .error { display: block; margin-top: 15px; font-size: 12px; line-height: 1.8; }
.hint { color: #94a0b2; }
.error { color: #bd3e40; background: #fff2f0; padding: 10px 12px; border-radius: 8px; }
.panel button { margin-top: 28px; height: 44px; line-height: 42px; font-size: 14px; border-radius: 10px; }
.back-home { max-width: 440px; margin: 0 auto; background: transparent; border-color: transparent; color: #8394ae; font-size: 12px; }
@media (max-width: 500px) { .auth-page { padding: 28px 20px; } .panel { padding: 28px 24px; border-radius: 17px; } .auth-page .panel .title { font-size: 22px; } }
</style>

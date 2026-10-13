<template>
  <view class="page">
    <view class="panel" v-if="loginDone && currentUser">
      <text class="title">登录成功</text>
      <text class="message">你好，{{ currentUser.nickname }}！</text>
    </view>
    <view class="panel" v-else>
      <text class="title">登录校园集市</text>
      <text class="label">用户名</text>
      <input v-model="username" class="field" :maxlength="-1"
        placeholder="填写已注册的用户名" :disabled="submitting" />
      <text class="label">密码</text>
      <input v-model="password" class="field" :password="true" :maxlength="-1"
        placeholder="填写密码" :disabled="submitting" @confirm="submitLogin" />
      <text v-if="errorMessage" class="error">{{ errorMessage }}</text>
      <button type="primary" :loading="submitting" :disabled="submitting" @click="submitLogin">
        {{ submitting ? '登录中…' : '登录' }}
      </button>
    </view>
    <button :disabled="submitting" @click="goHome">返回首页</button>
  </view>
</template>

<script setup>
import { ref } from 'vue'
import { onLoad, onUnload } from '@dcloudio/uni-app'
import { loginUser } from '@/api/user.js'
import { saveSession, currentUser } from '@/utils/session.js'

const username = ref('')
const password = ref('')
const submitting = ref(false)
const errorMessage = ref('')
const loginDone = ref(false)
let pageClosed = false

async function submitLogin() {
  if (submitting.value || loginDone.value || pageClosed) return
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
    password.value = ''
    loginDone.value = true
  } catch (error) {
    if (!pageClosed) errorMessage.value = error.message || '登录请求失败'
  } finally {
    submitting.value = false
  }
  if (loginDone.value && !pageClosed) goHome()
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
onUnload(() => { pageClosed = true; password.value = '' })
</script>

<style scoped>
.page { padding: 32rpx; min-height: 100vh; background: #f5f7fa; box-sizing: border-box; }
.panel { padding: 32rpx; margin-bottom: 24rpx; background: #ffffff; border-radius: 20rpx; }
.title { display: block; font-size: 36rpx; font-weight: bold; }
.label { display: block; margin: 28rpx 0 12rpx; }
.field { height: 88rpx; padding: 0 20rpx; border: 1px solid #d9dfe8; border-radius: 12rpx; }
.message, .error { display: block; margin-top: 24rpx; }
.error { color: #d93025; }
button { margin-top: 28rpx; }
</style>

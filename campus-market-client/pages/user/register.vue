<template>
  <view class="page">
    <view v-if="registeredUser" class="panel">
      <text class="title">注册成功</text>
      <text class="message">你好，{{ registeredUser.nickname }}！</text>
      <text class="message">用户名：{{ registeredUser.username }}</text>
      <text class="hint">账号已创建，可以继续登录。</text>
      <button type="primary" @click="goLogin">去登录</button>
    </view>

    <view v-else class="panel">
      <text class="title">创建校园集市账号</text>

      <text class="label">用户名</text>
      <input v-model="username" class="field" :maxlength="-1"
        placeholder="4～32位小写字母、数字或下划线"
        :disabled="submitting" />

      <text class="label">昵称</text>
      <input v-model="nickname" class="field" :maxlength="-1"
        placeholder="1～30个字符，可以使用中文"
        :disabled="submitting" />

      <text class="label">密码</text>
      <input v-model="password" class="field" :password="true"
        :maxlength="-1" placeholder="8～64个字符"
        :disabled="submitting" />
      <text class="hint">较长的中文或表情密码可能超过长度上限。</text>

      <text class="label">确认密码</text>
      <input v-model="confirmPassword" class="field" :password="true"
        :maxlength="-1" placeholder="再次输入密码"
        :disabled="submitting" confirm-type="done"
        @confirm="submitRegistration" />

      <text v-if="errorMessage" class="error">{{ errorMessage }}</text>
      <button type="primary" :loading="submitting"
        :disabled="submitting" @click="submitRegistration">
        {{ submitting ? '注册中…' : '注册账号' }}
      </button>
    </view>

    <button :disabled="submitting" @click="goHome">返回首页</button>
  </view>
</template>

<script setup>
import { ref } from 'vue'
import { onUnload } from '@dcloudio/uni-app'
import { registerUser } from '@/api/user.js'

const username = ref('')
const nickname = ref('')
const password = ref('')
const confirmPassword = ref('')
const submitting = ref(false)
const errorMessage = ref('')
const registeredUser = ref(null)
let pageClosed = false

async function submitRegistration() {
  if (submitting.value || registeredUser.value || pageClosed) return
  errorMessage.value = ''

  const loginName = username.value.trim()
  const displayName = nickname.value.trim()
  const rawPassword = password.value

  if (!/^[a-z0-9_]{4,32}$/.test(loginName)) {
    errorMessage.value = '用户名需要4～32位小写字母、数字或下划线'
    return
  }
  if (Array.from(displayName).length < 1 || Array.from(displayName).length > 30) {
    errorMessage.value = '昵称需要1～30个字符'
    return
  }
  if (!rawPassword.trim() || Array.from(rawPassword).length < 8 ||
      Array.from(rawPassword).length > 64) {
    errorMessage.value = '密码需要8～64个字符，不能全为空白'
    return
  }
  if (rawPassword !== confirmPassword.value) {
    errorMessage.value = '两次输入的密码不一致'
    return
  }

  submitting.value = true
  try {
    const user = await registerUser({
      username: loginName,
      password: rawPassword,
      nickname: displayName
    })
    if (pageClosed) return
    if (!user || !Number.isSafeInteger(user.id) || user.id <= 0 ||
        user.username !== loginName || typeof user.nickname !== 'string' ||
        !user.nickname.trim()) {
      throw new Error('注册响应格式不正确，请确认注册结果后再试')
    }
    registeredUser.value = {
      id: user.id,
      username: user.username,
      nickname: user.nickname
    }
    password.value = ''
    confirmPassword.value = ''
  } catch (error) {
    if (pageClosed) return
    errorMessage.value = error.statusCode === 0
      ? '网络中断，暂时无法确认注册结果，请稍后重试'
      : (error.message || '注册请求失败，请稍后重试')
  } finally {
    submitting.value = false
  }
}

function goLogin() {
  if (!registeredUser.value) return
  uni.redirectTo({
    url: '/pages/user/login?username=' + encodeURIComponent(registeredUser.value.username),
    fail: () => uni.showToast({ title: '打开登录页失败', icon: 'none' })
  })
}

function goHome() {
  if (submitting.value) return
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
  } else {
    uni.reLaunch({ url: '/pages/index/index' })
  }
}

onUnload(() => {
  pageClosed = true
  password.value = ''
  confirmPassword.value = ''
})
</script>

<style scoped>
.page {
  padding: 32rpx;
  min-height: 100vh;
  background: #f5f7fa;
  box-sizing: border-box;
}
.panel {
  padding: 32rpx;
  margin-bottom: 24rpx;
  background: #ffffff;
  border-radius: 20rpx;
}
.title { display: block; font-size: 36rpx; font-weight: bold; }
.label { display: block; margin-top: 28rpx; margin-bottom: 12rpx; }
.field {
  height: 88rpx;
  padding: 0 20rpx;
  border: 1px solid #d9dfe8;
  border-radius: 12rpx;
  font-size: 28rpx;
}
.hint, .message, .error { display: block; margin-top: 20rpx; line-height: 1.6; }
.hint { color: #666666; font-size: 24rpx; }
.error { color: #d93025; }
button { margin-top: 28rpx; }
</style>

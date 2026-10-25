<template>
  <view class="page auth-page">
    <view class="auth-brand"><image src="/static/ui/brand-mark.png" mode="aspectFit" /><text>校园集市</text><text class="auth-caption">好物相遇，从这里开始</text></view>
    <view v-if="registeredUser" class="panel">
      <text class="title">注册成功</text>
      <text class="message">你好，{{ registeredUser.nickname }}！</text>
      <text class="message">用户名：{{ registeredUser.username }}</text>
      <text class="hint">账号已创建，可以继续登录。</text>
      <button type="primary" @click="goLogin">去登录</button>
    </view>

    <view v-else class="panel">
      <text class="auth-eyebrow">JOIN THE CAMPUS</text>
      <text class="title">创建校园集市账号</text>
      <text class="auth-subtitle">加入校园集市，让闲置的价值继续流转。</text>

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
      <text v-if="retryIn>0" class="hint">请等待{{ retryIn }}秒后再注册；表单仍保留，不会自动提交。</text>
      <button type="primary" :loading="submitting"
        :disabled="submitting||retryIn>0" @click="submitRegistration">
        {{ submitting ? '注册中…' : retryIn>0 ? `等待${retryIn}秒后重试` : '注册账号' }}
      </button>
    </view>

    <button class="back-home" :disabled="submitting" @click="goHome">返回首页</button>
  </view>
</template>

<script setup>
import { ref } from 'vue'
import { onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { registerUser } from '@/api/user.js'
import {createRegistrationCooldown} from '@/utils/registration-retry.mjs'

const username = ref('')
const nickname = ref('')
const password = ref('')
const confirmPassword = ref('')
const submitting = ref(false)
const errorMessage = ref('')
const registeredUser = ref(null)
let pageClosed = false
const retryIn=ref(0),cooldown=createRegistrationCooldown()
let retryTimer=null,pageActive=true
function updateRetry(){retryIn.value=cooldown.remaining();if(retryIn.value===0)stopRetry()}
function stopRetry(){if(retryTimer!==null){clearInterval(retryTimer);retryTimer=null}}
function startRetry(){stopRetry();updateRetry();if(pageActive&&retryIn.value>0)retryTimer=setInterval(updateRetry,250)}

async function submitRegistration() {
  if (submitting.value || registeredUser.value || pageClosed) return
  updateRetry();if(retryIn.value>0)return
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
    cooldown.clear();stopRetry();retryIn.value=0
    registeredUser.value = {
      id: user.id,
      username: user.username,
      nickname: user.nickname
    }
    password.value = ''
    confirmPassword.value = ''
  } catch (error) {
    if (pageClosed) return
    const waiting=cooldown.start(error);if(waiting)startRetry()
    errorMessage.value = waiting&&error.statusCode===429
      ? '注册请求过于频繁，请等待倒计时结束后再提交'
      : error.statusCode === 0
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

onShow(()=>{pageActive=true;startRetry()})
onHide(()=>{pageActive=false;stopRetry();password.value='';confirmPassword.value=''})
onUnload(() => {
  stopRetry();cooldown.clear()
  pageClosed = true
  password.value = ''
  confirmPassword.value = ''
})
</script>

<style scoped>
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

<template>
  <view class="page">
    <text v-if="loading" class="message">正在核验身份并加载商品…</text>
    <view v-else-if="loadError" class="panel">
      <text class="error">{{ loadError }}</text>
      <button v-if="!currentUser" type="primary" @click="openLogin">去登录</button>
      <button v-else @click="loadForEdit()">重新加载</button>
    </view>
    <view v-else-if="saveDone" class="panel">
      <text class="title">保存成功</text>
      <text class="message">商品编号与卖家归属保持不变。</text>
      <button type="primary" @click="goDetail">查看商品</button>
    </view>
    <view v-else-if="ready" class="panel">
      <text class="title">编辑商品</text>
      <text class="message">商品编号：{{ productId }} · 卖家：{{ product.seller.nickname }}</text>
      <text class="message">当前状态：{{ product.status === 'ON_SALE' ? '在售' : '已下架' }}</text>
      <text class="label">商品标题</text>
      <input v-model="draftTitle" class="field" :maxlength="50" :disabled="saving || uploadingCover"
        placeholder="2～50个字符" />
      <text class="label">商品描述</text>
      <textarea v-model="draftDescription" class="field description" :maxlength="500"
        :disabled="saving || uploadingCover" placeholder="成色、使用情况、交接地点，可不填" />
      <text class="label">价格（元）</text>
      <input v-model="draftPrice" class="field" type="digit" :maxlength="9"
        :disabled="saving || uploadingCover" placeholder="例如：35.50" />
      <CategoryPicker v-model="draftCategory" :disabled="saving || uploadingCover" @ready="categoryReady = $event" />
      <ImagePicker v-model="draftCoverId" :disabled="saving" @busy="uploadingCover = $event" />
      <text v-if="errorMessage" class="error">{{ errorMessage }}</text>
      <button v-if="conflict" :disabled="saving || uploadingCover" @click="loadForEdit(true)">重新加载最新商品（替换草稿）</button>
      <button type="primary" :disabled="saving || uploadingCover || conflict || !categoryReady" :loading="saving" @click="saveProduct">
        {{ saving ? '保存中…' : '保存修改' }}
      </button>
    </view>
    <button :disabled="saving || loading || uploadingCover" @click="goDetail">返回商品详情</button>
  </view>
</template>

<script setup>
import { ref } from 'vue'
import { onLoad, onShow, onUnload } from '@dcloudio/uni-app'
import { getProduct, updateProduct } from '@/api/product.js'
import ImagePicker from '@/components/ImagePicker.vue'
import CategoryPicker from '@/components/CategoryPicker.vue'
import { getCurrentUser } from '@/api/user.js'
import { currentUser, getToken, updateCurrentUser } from '@/utils/session.js'

const productId = ref(0)
const product = ref(null)
const draftTitle = ref('')
const draftDescription = ref('')
const draftPrice = ref('')
const draftCoverId = ref('')
const draftCategory = ref('UNCLASSIFIED')
const categoryReady = ref(false)
const draftVersion = ref(null)
const conflict = ref(false)
const uploadingCover = ref(false)
const loading = ref(false)
const saving = ref(false)
const ready = ref(false)
const saveDone = ref(false)
const loadError = ref('')
const errorMessage = ref('')
let pageClosed = false

function isValidProduct(item) {
  return item && item.id === productId.value &&
    typeof item.title === 'string' && typeof item.description === 'string' &&
    ['ON_SALE', 'OFF_SHELF', 'SOLD'].includes(item.status) &&
    Number.isSafeInteger(item.version) && item.version >= 0 &&
    Number.isSafeInteger(item.priceInCents) && item.priceInCents > 0 &&
    (item.seller == null || (Number.isSafeInteger(item.seller.id) && item.seller.id > 0 &&
      typeof item.seller.nickname === 'string' && !!item.seller.nickname.trim()))
}

async function loadForEdit(discardDraft = false) {
  if (pageClosed || productId.value <= 0 || loading.value || saving.value || saveDone.value || uploadingCover.value) return
  ready.value = false
  loadError.value = ''
  const token = getToken()
  if (!token || !currentUser.value) {
    loadError.value = '请先登录后编辑商品'
    return
  }
  loading.value = true
  try {
    const user = await getCurrentUser()
    if (pageClosed || getToken() !== token) return
    updateCurrentUser(user, token)
    const item = await getProduct(productId.value)
    if (pageClosed || getToken() !== token) return
    if (!isValidProduct(item)) throw new Error('接口返回的商品格式不正确')
    if (!item.seller || item.seller.id !== user.id) {
      loadError.value = item.seller ? '只能修改自己发布的商品' : '历史商品未登记卖家，暂不支持修改'
      return
    }
    if (item.status === 'SOLD') { loadError.value = '商品已售出，不能再编辑'; return }
    // 同一用户重新登录时保留尚未保存的草稿。
    const keepDraft = !discardDraft && product.value?.id === item.id && product.value?.seller?.id === user.id
    product.value = item
    if (!keepDraft) {
      draftTitle.value = item.title
      draftDescription.value = item.description
      draftPrice.value = (item.priceInCents / 100).toFixed(2)
      draftCoverId.value = item.coverImageId || ''
      draftCategory.value = item.category?.code || 'UNCLASSIFIED'
      draftVersion.value = item.version
      conflict.value = false
    }
    if (keepDraft && draftVersion.value !== item.version) conflict.value = true
    errorMessage.value = conflict.value ? '商品已被更新，草稿仍保留。重新加载将用服务器内容替换当前草稿。' : ''
    ready.value = true
  } catch (error) {
    if (!pageClosed && (getToken() === token || !getToken())) {
      loadError.value = error.statusCode === 401 ? '登录已失效，请重新登录后编辑'
        : error.statusCode === 404 ? '商品不存在' : error.message || '加载商品失败'
    }
  } finally {
    loading.value = false
    if (!pageClosed && getToken() !== token) {
      if (getToken()) await loadForEdit()
      else if (!loadError.value) loadError.value = '请先登录后编辑商品'
    }
  }
}

async function saveProduct() {
  if (pageClosed || loading.value || saving.value || !ready.value || saveDone.value || uploadingCover.value || conflict.value || !categoryReady.value) return
  errorMessage.value = ''
  const token = getToken()
  if (!token || !currentUser.value) {
    ready.value = false
    loadError.value = '请先登录后编辑商品'
    return
  }
  const editorId = currentUser.value.id
  const title = draftTitle.value.trim()
  const description = draftDescription.value.trim()
  const priceText = draftPrice.value.trim()
  if (title.length < 2 || title.length > 50) { errorMessage.value = '商品标题需要2～50个字符'; return }
  if (description.length > 500) { errorMessage.value = '商品描述不能超过500个字符'; return }
  if (!/^\d{1,6}(\.\d{1,2})?$/.test(priceText)) {
    errorMessage.value = '价格需为数字，整数最多6位，小数最多2位'
    return
  }
  const priceInCents = Math.round(Number(priceText) * 100)
  if (priceInCents <= 0) { errorMessage.value = '价格必须大于0'; return }
  saving.value = true
  try {
    const result = await updateProduct(productId.value, { title, description, priceInCents, coverImageId: draftCoverId.value, version: draftVersion.value, categoryCode: draftCategory.value })
    if (pageClosed || getToken() !== token) return
    if (!isValidProduct(result) || result.seller?.id !== editorId) {
      throw new Error('修改结果格式异常，请返回详情重新加载确认')
    }
    product.value = result
    saveDone.value = true
  } catch (error) {
    if (!pageClosed && (getToken() === token || !getToken())) {
      if (error.statusCode === 409 && error.code === 'PRODUCT_CONFLICT') {
        conflict.value = true
        errorMessage.value = '商品已被更新，本次未保存。草稿仍保留；重新加载将替换当前草稿，请先复制需要保留的内容。'
      } else if ([401, 403, 404].includes(error.statusCode)) {
        ready.value = false
        loadError.value = error.statusCode === 401 ? '登录已失效，请重新登录后编辑'
          : error.statusCode === 403 ? '没有权限修改此商品' : '商品不存在'
      } else {
        errorMessage.value = error.statusCode === 0
          ? '未收到保存结果，请返回详情重新加载确认后再重试' : error.message || '保存失败'
      }
    }
  } finally {
    saving.value = false
    if (!pageClosed && getToken() !== token && getToken()) {
      ready.value = false
      loadError.value = '登录身份已变化，请重新加载商品'
    }
  }
  if (saveDone.value && !pageClosed) goDetail()
}

function openLogin() {
  uni.navigateTo({ url: '/pages/user/login',
    fail: () => uni.showToast({ title: '打开登录页失败', icon: 'none' }) })
}

function goDetail() {
  if (saving.value || loading.value) return
  const fail = () => uni.showToast({ title: saveDone.value ? '已保存，返回失败，请再次点击查看商品' : '返回失败，请重试', icon: 'none' })
  const pages = getCurrentPages()
  if (pages.length > 1 && pages[pages.length - 2].route === 'pages/product/detail') {
    uni.navigateBack({ fail })
  } else {
    uni.redirectTo({ url: productId.value > 0 ? `/pages/product/detail?id=${productId.value}` : '/pages/index/index', fail })
  }
}

onLoad((options) => {
  const id = Number(options.id)
  if (!Number.isSafeInteger(id) || id <= 0) { loadError.value = '商品ID不正确'; return }
  productId.value = id
})
onShow(() => loadForEdit())
onUnload(() => { pageClosed = true })
</script>

<style scoped>
.page { padding: 32rpx; min-height: 100vh; box-sizing: border-box; background: #f5f7fa; }
.panel { padding: 32rpx; margin-bottom: 24rpx; background: #ffffff; border-radius: 20rpx; }
.title { display: block; font-size: 36rpx; font-weight: bold; }
.message, .error { display: block; margin: 24rpx 0; }
.message { color: #666666; }
.error { color: #d93025; }
.label { display: block; margin: 24rpx 0 12rpx; }
.field { width: 100%; height: 88rpx; padding: 16rpx; box-sizing: border-box; background: #f5f7fa; border-radius: 12rpx; }
.description { height: 180rpx; }
button { margin-top: 24rpx; }
</style>

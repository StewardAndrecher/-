<template>
  <view class="page">
    <text class="page-title">校园集市</text>
    <view class="account-panel">
      <text>{{ currentUser ? '你好，' + currentUser.nickname : '未登录' }}</text>
      <button v-if="!currentUser" size="mini" :disabled="checkingAccount || exiting || publishing || uploadingCover"
        @click="openLogin">登录</button>
      <button v-else size="mini" :disabled="checkingAccount || exiting || publishing || uploadingCover"
        @click="exitAccount">{{ exiting ? '退出中…' : '退出登录' }}</button>
      <text v-if="accountMessage" class="request-error">{{ accountMessage }}</text>
      <button v-if="currentUser" size="mini" :disabled="checkingAccount || exiting || publishing || uploadingCover"
        @click="openMyProducts">我的发布</button>
      <button v-if="currentUser" size="mini" @click="openMyFavorites">我的收藏</button>
    </view>
	<button
	  size="mini"
	  :disabled="loading || publishing || uploadingCover"
	  @click="openRegister"
	>
	  注册账号
	</button>
	<view class="request-panel">
	  <button
	    size="mini"
	    :disabled="loading || publishing || uploadingCover"
	    @click="fetchProducts()"
	  >
	    {{ loading ? '加载中…' : '刷新商品' }}
	  </button>

	  <text class="summary">
	    数据来源：{{ dataSource }}
	  </text>

	  <text v-if="requestError" class="request-error">
	    {{ requestError }}
	  </text>
	</view>

  <text v-if="storageMessage" class="storage-message">
  {{ storageMessage }}
  </text>

<view class="publish-form">
  <text class="form-title">发布闲置</text>
  <text v-if="!currentUser" class="summary">登录后可以发布商品，填写的内容会在前往登录时保留。</text>

  <text class="field-label">商品标题</text>
  <input
    v-model="draftTitle"
    class="form-field"
    placeholder="2～50个字符，例如：闲置台灯"
    :maxlength="50"
	:disabled="publishing || uploadingCover"
  />

  <text class="field-label">商品描述</text>
  <textarea
    v-model="draftDescription"
    class="form-field description-input"
    placeholder="成色、使用情况、交接地点，可不填"
    :maxlength="500"
	:disabled="publishing || uploadingCover"
  />

  <text class="field-label">价格（元）</text>
  <input
    v-model="draftPrice"
    class="form-field"
    type="digit"
    placeholder="例如：35.50"
    :maxlength="9"
	:disabled="publishing || uploadingCover"
  />

  <ImagePicker v-model="draftCoverId" :disabled="publishing" @busy="uploadingCover = $event" />

  <text v-if="publishError" class="form-error">
    {{ publishError }}
  </text>

  <button
    type="primary"
    :disabled="publishing || loading || checkingAccount || exiting || uploadingCover"
    @click="publishProduct"
  >
    {{ publishing ? '发布中…' : (currentUser ? '发布商品' : '登录后发布') }}
  </button>
</view>


    <input
      v-model="keyword"
      class="search-input"
      placeholder="输入关键词后点击搜索"
      :maxlength="50"
      :disabled="loading || publishing || uploadingCover"
      @confirm="searchProducts"
    />

    <button size="mini" :disabled="loading || publishing || uploadingCover" @click="searchProducts">搜索</button>

    <button size="mini" :disabled="!currentUser || favoriteLoading || favoriteBusyId > 0" @click="toggleFavoritesFilter">
      {{ onlyFavorites ? '显示本页全部' : '仅看本页收藏' }}
    </button>

    <text class="summary">
      匹配 {{ total }} 件，本页显示 {{ filteredProducts.length }} 件
      （关键词：{{ activeKeyword || '全部' }}）
    </text>

    <text v-if="favoriteLoading" class="summary">正在同步账号收藏…</text>
    <text v-if="favoriteMessage" class="request-error">{{ favoriteMessage }}</text>

    <ProductCard
      v-for="item in filteredProducts"
      :key="item.id"
      :product="item"
      :is-favorite="favoriteIds.includes(item.id)"
      :favorite-disabled="loading || favoriteLoading || favoriteBusyId > 0 || !!favoriteMessage"
      :favorite-pending="favoriteBusyId === item.id"
      @toggle-favorite="toggleFavorite($event)"
	  @open-detail="openProductDetail($event)"
    />

    <text
      v-if="!loading && !requestError && filteredProducts.length === 0"
      class="empty"
    >
      没有符合条件的商品
    </text>
    <view class="pagination">
      <button size="mini" :disabled="loading || publishing || page <= 1"
        @click="fetchProducts(page - 1)">上一页</button>
      <text>第 {{ page }} / {{ totalPages }} 页</text>
      <button size="mini" :disabled="loading || publishing || page >= totalPages"
        @click="fetchProducts(page + 1)">下一页</button>
    </view>
  </view>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
import ProductCard from '@/components/ProductCard.vue'
import ImagePicker from '@/components/ImagePicker.vue'
import { getProducts, createProduct } from '@/api/product.js'
import { getCurrentUser, logoutUser } from '@/api/user.js'
import { currentUser, getToken, updateCurrentUser, clearSession } from '@/utils/session.js'
import { favoriteIds, favoriteLoading, favoriteBusyId, favoriteMessage, loadAccountFavorites, changeAccountFavorite } from '@/utils/account-favorites.js'

const checkingAccount = ref(false)
const exiting = ref(false)
const accountMessage = ref('')

function openLogin() {
  uni.navigateTo({
    url: '/pages/user/login',
    fail: () => uni.showToast({ title: '打开登录页失败', icon: 'none' })
  })
}

function openMyProducts() {
  uni.navigateTo({ url: '/pages/product/mine',
    fail: () => uni.showToast({ title: '打开我的发布失败', icon: 'none' }) })
}

async function refreshAccount() {
  if (checkingAccount.value || exiting.value) return
  accountMessage.value = ''
  const token = getToken()
  if (!token) return
  checkingAccount.value = true
  try {
    const user = await getCurrentUser()
    updateCurrentUser(user, token)
  } catch (error) {
    if (getToken() === token || !getToken()) {
      accountMessage.value = error.statusCode === 401
        ? '登录已失效，请重新登录'
        : '暂时无法核验登录状态，请稍后重试'
    }
  } finally {
    checkingAccount.value = false
  }
}

async function exitAccount() {
  if (exiting.value || checkingAccount.value || publishing.value || uploadingCover.value) return
  const token = getToken()
  if (!token) return
  exiting.value = true
  let message = ''
  try {
    await logoutUser()
  } catch (error) {
    message = '本机已退出，服务器退出请求暂未完成'
  } finally {
    clearSession(token)
    if (!getToken()) accountMessage.value = message
    exiting.value = false
  }
}

let hasShown = false
onShow(() => {
  refreshAccount()
  if (hasShown) fetchProducts()
  hasShown = true
})

function openRegister() {
  uni.navigateTo({
    url: '/pages/user/register',
    fail: () => uni.showToast({
      title: '打开注册页失败',
      icon: 'none'
    })
  })
}

const PAGE_SIZE = 5
const PAGE_CACHE_KEY = 'campus-market-page-cache-v4'

const products = ref([])
const page = ref(1)
const total = ref(0)
const keyword = ref('')
const activeKeyword = ref('')
const onlyFavorites = ref(false)
const loading = ref(false)
const publishing = ref(false)
const requestError = ref('')
const publishError = ref('')
const storageMessage = ref('')
const dataSource = ref('待加载')
const draftTitle = ref('')
const draftDescription = ref('')
const draftPrice = ref('')
const draftCoverId = ref('')
const uploadingCover = ref(false)

watch(currentUser, (user, previous) => {
  if (user?.id !== previous?.id) onlyFavorites.value = false
  if (user && ['请先登录后发布商品', '登录已失效，请重新登录后发布'].includes(publishError.value)) {
    publishError.value = ''
  }
})

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / PAGE_SIZE)))
const filteredProducts = computed(() => products.value.filter(item =>
  !onlyFavorites.value || favoriteIds.value.includes(item.id)
))

function isValidProduct(item) {
  return item !== null && typeof item === 'object' &&
    Number.isSafeInteger(item.id) && item.id > 0 &&
    typeof item.title === 'string' &&
    typeof item.description === 'string' &&
    item.status === 'ON_SALE' &&
    Number.isSafeInteger(item.version) && item.version >= 0 &&
    (item.seller == null || (
      Number.isSafeInteger(item.seller.id) && item.seller.id > 0 &&
      typeof item.seller.nickname === 'string' && !!item.seller.nickname.trim()
    )) &&
    Number.isSafeInteger(item.priceInCents) && item.priceInCents > 0
}

function isValidPage(data) {
  return data !== null && typeof data === 'object' &&
    Array.isArray(data.records) && data.records.every(isValidProduct) &&
    Number.isSafeInteger(data.total) && data.total >= 0 &&
    Number.isSafeInteger(data.page) && data.page >= 1 &&
    data.size === PAGE_SIZE && data.records.length <= PAGE_SIZE &&
    data.total >= data.records.length
}

function loadLocalData() {
  try {
    const savedText = uni.getStorageSync(PAGE_CACHE_KEY)
    if (!savedText) return
    const saved = JSON.parse(savedText)
    if (!isValidPage(saved) || typeof saved.keyword !== 'string') {
      throw new Error('分页缓存格式不正确')
    }
    products.value = saved.records
    page.value = saved.page
    total.value = saved.total
    activeKeyword.value = saved.keyword
    keyword.value = saved.keyword
    dataSource.value = '本地缓存'
  } catch (error) {
    storageMessage.value = '读取商品缓存失败，将尝试请求后端'
    console.error('读取分页缓存失败：', error)
  }
}

function savePageCache(data, searchText) {
  try {
    uni.setStorageSync(PAGE_CACHE_KEY, JSON.stringify({
      ...data,
      keyword: searchText
    }))
  } catch (error) {
    storageMessage.value = '商品已加载，但本地缓存保存失败'
    console.error('保存分页缓存失败：', error)
  }
}

async function fetchProducts(targetPage = page.value, searchText = activeKeyword.value) {
  if (loading.value || publishing.value || targetPage < 1) return
  loading.value = true
  requestError.value = ''

  try {
    const data = await getProducts({
      page: targetPage,
      size: PAGE_SIZE,
      keyword: searchText
    })
    if (!isValidPage(data) || data.page !== targetPage) {
      throw new Error('接口返回的分页数据格式不正确')
    }
    products.value = data.records
    total.value = data.total
    page.value = data.page
    activeKeyword.value = searchText
    dataSource.value = '后端接口'
    savePageCache(data, searchText)
  } catch (error) {
    requestError.value = error.message
    console.error('请求商品失败：', error)
  } finally {
    loading.value = false
    await loadAccountFavorites(products.value.map(item => item.id))
  }
}

function searchProducts() {
  fetchProducts(1, keyword.value.trim())
}

function toggleFavoritesFilter() {
  onlyFavorites.value = !onlyFavorites.value
}

async function toggleFavorite(productId) {
  if (!getToken() || !currentUser.value) { openLogin(); return }
  await changeAccountFavorite(productId, !favoriteIds.value.includes(productId))
}

function openMyFavorites() {
  uni.navigateTo({ url: '/pages/product/favorites',
    fail: () => uni.showToast({ title: '打开我的收藏失败', icon: 'none' }) })
}

async function publishProduct() {
  if (loading.value || publishing.value || checkingAccount.value || exiting.value || uploadingCover.value) return
  publishError.value = ''
  if (!getToken() || !currentUser.value) {
    publishError.value = '请先登录后发布商品'
    openLogin()
    return
  }
  const publisherId = currentUser.value.id
  const title = draftTitle.value.trim()
  const description = draftDescription.value.trim()
  const priceText = draftPrice.value.trim()

  if (title.length < 2 || title.length > 50) {
    publishError.value = '商品标题需要2～50个字符'
    return
  }
  if (description.length > 500) {
    publishError.value = '商品描述不能超过500个字符'
    return
  }
  if (!/^\d{1,6}(\.\d{1,2})?$/.test(priceText)) {
    publishError.value = '价格需为数字，整数最多6位，小数最多2位'
    return
  }
  const priceInCents = Math.round(Number(priceText) * 100)
  if (priceInCents <= 0) {
    publishError.value = '价格必须大于0'
    return
  }

  publishing.value = true
  let published = false
  try {
    const data = await createProduct({ title, description, priceInCents, coverImageId: draftCoverId.value })
    if (!isValidProduct(data) || data.seller?.id !== publisherId) {
      throw new Error('商品可能已发布，但响应格式异常，请刷新列表确认')
    }
    published = true
    draftTitle.value = ''
    draftDescription.value = ''
    draftPrice.value = ''
    draftCoverId.value = ''
    keyword.value = ''
    onlyFavorites.value = false
    uni.showToast({ title: '发布成功', icon: 'success' })
  } catch (error) {
    publishError.value = error.statusCode === 401
      ? '登录已失效，请重新登录后发布'
      : error.statusCode === 0
        ? '未收到发布结果，请刷新列表确认后再重试'
        : error.message
    console.error('发布商品失败：', error)
  } finally {
    publishing.value = false
  }

  // 发布已确认成功；列表刷新失败由 requestError 单独提示
  if (published) await fetchProducts(1, '')
}

function openProductDetail(productId) {
  uni.navigateTo({
    url: `/pages/product/detail?id=${productId}`,
    fail: (error) => {
      uni.showToast({ title: '打开详情失败', icon: 'none' })
      console.error('打开详情失败：', error)
    }
  })
}

onLoad(() => {
  loadLocalData()
  fetchProducts()
})
</script>

<style scoped>
.pagination { display: flex; align-items: center; justify-content: space-between; gap: 16rpx; margin-top: 24rpx; font-size: 26rpx; }
.storage-message {
  display: block;
  margin-top: 16rpx;
  color: #c0392b;
  font-size: 26rpx;
}
.page {
  padding: 32rpx;
  min-height: 100vh;
  background: #f5f7fa;
  box-sizing: border-box;
}

.page-title {
  display: block;
  font-size: 40rpx;
  font-weight: bold;
}

.summary {
  display: block;
  margin: 16rpx 0 28rpx;
  color: #666666;
  font-size: 26rpx;
}

.search-input {
  height: 88rpx;
  padding: 0 24rpx;
  margin: 24rpx 0;
  border-radius: 12rpx;
  background: #ffffff;
}

.empty {
  display: block;
  padding: 48rpx 0;
  text-align: center;
  color: #666666;
}

.publish-form {
  padding: 28rpx;
  margin-top: 24rpx;
  border-radius: 20rpx;
  background: #ffffff;
}

.form-title {
  display: block;
  font-size: 32rpx;
  font-weight: bold;
}

.field-label {
  display: block;
  margin: 24rpx 0 12rpx;
  font-size: 28rpx;
}

.form-field {
  width: 100%;
  height: 80rpx;
  padding: 16rpx;
  border-radius: 10rpx;
  background: #f5f7fa;
  box-sizing: border-box;
}

.description-input {
  height: 180rpx;
}

.form-error {
  display: block;
  margin: 20rpx 0;
  color: #c0392b;
}

.publish-form button {
  margin-top: 24rpx;
}

.request-panel {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 16rpx;
  margin-bottom: 24rpx;
}

.request-error {
  color: #d93025;
  font-size: 26rpx;
}
</style>

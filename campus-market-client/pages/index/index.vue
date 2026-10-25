<template>
  <view class="page market-page">
<view class="site-header"><view class="header-inner">
  <view class="brand-group">
    <view class="site-brand"><image class="brand-mark" src="/static/ui/brand-mark.png" mode="aspectFit" /><view><text class="brand-name">校园集市</text><text class="brand-caption">CAMPUS MARKET</text></view></view>
    <picker :range="campusFilters" range-key="name" :value="campusFilterIndex" :disabled="loading || publishing || uploadingCover" @change="chooseCampus"><view class="campus-select">{{ campusFilters[campusFilterIndex].name }} <MarketIcon name="chevron-down" /></view></picker>
  </view>
  <view class="header-actions">
    <button class="nav-button desktop-link" @click="openHistory">最近浏览</button>
    <button class="nav-button desktop-link" @click="openMyFavorites">我的收藏</button>
    <button v-if="!currentUser" class="login-button" :disabled="checkingAccount || exiting || publishing || uploadingCover" @click="openLogin">登录</button>
    <button v-else class="nav-button account-button" @click="openProfile">{{ currentUser.nickname }}</button>
    <button v-if="!currentUser" class="register-button desktop-link" :disabled="loading || publishing || uploadingCover" @click="openRegister">注册</button>
    <button v-else class="nav-button desktop-link" :disabled="checkingAccount || exiting || publishing || uploadingCover" @click="exitAccount">{{ exiting ? '退出中…' : '退出登录' }}</button>
    <button class="publish-link" role="button" :class="{ 'is-active': publishExpanded }" :disabled="publishing || uploadingCover" :aria-expanded="publishExpanded" aria-controls="publish-form" @click="togglePublish"><MarketIcon :name="publishExpanded ? 'close-white' : 'plus-white'" />{{ publishExpanded ? '收起发布' : '发布闲置' }}</button>
  </view>
</view></view>

    <view class="market-shell">
      <NetworkStatusBanner :busy="loading || publishing || uploadingCover" allow-refresh @retry="fetchProducts()" />
      <view v-if="currentUser" class="member-panel surface">
        <view class="member-links">
          <button size="mini" :disabled="checkingAccount || exiting || publishing || uploadingCover" @click="openMyProducts">我的发布</button>
          <button size="mini" :disabled="checkingAccount || exiting || publishing || uploadingCover" @click="openOrders">我的订单</button>
          <button size="mini" :disabled="publishing || uploadingCover" @click="openRefunds">退款售后</button>
          <button size="mini" :disabled="checkingAccount || exiting || publishing || uploadingCover" @click="openTradeTasks">交易待办</button>
          <button size="mini" @click="openMyFavorites">我的收藏</button>
          <button size="mini" :disabled="checkingAccount || exiting || publishing || uploadingCover" @click="openProfile">账号资料</button>
          <button size="mini" @click="openReports">我的举报</button>
          <AdminEntry :active="intentionPageActive" />
        </view>
        <NotificationEntry :active="intentionPageActive" />
      </view>
      <text v-if="accountMessage" class="request-error account-error">{{ accountMessage }}</text>
<view class="welcome-banner">
  <image class="hero-photo" src="/static/ui/campus-hero.jpg" mode="aspectFill" aria-label="阳光下的课本、耳机和帆布包，校园生活氛围图" />
  <view class="hero-shade"></view>
  <view class="welcome-copy">
    <text class="welcome-eyebrow">校园生活 · 循环有光</text>
    <text class="welcome-title">闲置好物，<text class="welcome-accent">校园再相遇。</text></text>
    <text class="welcome-description">让用过的好东西，继续陪伴下一位同学。</text>
    <view class="hero-benefits"><view><MarketIcon name="people" /><text>校内面交</text></view><view><MarketIcon name="pin" /><text>近在身边</text></view><view><MarketIcon name="leaf" /><text>循环利用</text></view></view>
  </view>
</view>
<view class="welcome-notes">
  <button class="welcome-action" role="button" @click="focusCatalogue">发现所需 <text>↓</text></button><text class="note-divider">/</text>
  <button class="welcome-action" role="button" :class="{ 'is-active': publishExpanded }" :disabled="publishing || uploadingCover" :aria-expanded="publishExpanded" aria-controls="publish-form" @click="togglePublish">分享闲置</button><text class="note-divider">/</text>
  <button class="welcome-action" role="button" :class="{ 'is-active': publishExpanded }" :disabled="publishing || uploadingCover" :aria-expanded="publishExpanded" aria-controls="publish-form" @click="togglePublish">延续美好</button>
</view>

      <view class="market-layout">
        <view class="publish-reveal" :class="{ 'is-open': publishExpanded }" :aria-hidden="!publishExpanded" :inert="!publishExpanded">
          <view class="publish-clip">
            <view class="publish-column">
              <view id="publish-form" class="publish-form surface">
                <view class="publish-heading">
                  <view>
                    <view class="section-eyebrow"><text class="tiny-dot"></text><text>给闲置一个新去处</text></view>
                    <text class="form-title">发布闲置</text>
                    <text class="form-subtitle">分享一件好物，开启一段新故事。</text>
                  </view>
                  <button class="collapse-publish" size="mini" :disabled="publishing || uploadingCover"
                    :aria-expanded="publishExpanded" aria-controls="publish-form" @click="togglePublish">收起 <text>↑</text></button>
                </view>
                <text v-if="!currentUser" class="publish-note">登录后可以发布商品，填写的内容会在前往登录时保留。</text>
                <view class="publish-fields">
                  <view class="publish-main">
                    <text class="field-label">商品标题</text>
                    <input v-model="draftTitle" class="form-field" placeholder="2～50个字符，例如：闲置台灯" :maxlength="50"
                      :disabled="publishing || uploadingCover || !!pendingPublication || publicationStorageBlocked" />
                    <text class="field-label">商品描述</text>
                    <textarea v-model="draftDescription" class="form-field description-input"
                      placeholder="成色、使用情况、交接地点，可不填" :maxlength="500"
                      :disabled="publishing || uploadingCover || !!pendingPublication || publicationStorageBlocked" />
                  </view>
                  <view class="publish-aside">
                    <view class="publish-facts">
                      <view>
                        <text class="field-label">价格（元）</text>
                        <input v-model="draftPrice" class="form-field" type="digit" placeholder="例如：35.50" :maxlength="9"
                          :disabled="publishing || uploadingCover || !!pendingPublication || publicationStorageBlocked" />
                      </view>
                      <view class="publish-category">
                        <CategoryPicker v-model="draftCategory"
                          :disabled="publishing || uploadingCover || !!pendingPublication || publicationStorageBlocked"
                          @ready="publishCategoryReady = $event" />
                      </view>
                    </view>
                    <ProductDetailsFields v-model="draftDetails" :active="intentionPageActive && publishExpanded" :disabled="publishing || !!pendingPublication || publicationStorageBlocked"
                      @cover="draftCoverId = $event" @busy="uploadingCover = $event" />
                  </view>
                </view>
      <PublishQualityChecklist :draft="{title:draftTitle,description:draftDescription,priceText:draftPrice,coverImageId:draftCoverId,categoryCode:draftCategory,details:draftDetails}" :active="intentionPageActive && publishExpanded" :disabled="publishing || uploadingCover || !!pendingPublication" />
                <ProductDraftBox v-if="currentUser"
                  :draft="{ title: draftTitle, description: draftDescription, priceText: draftPrice, coverImageId: draftCoverId, categoryCode: draftCategory, details: draftDetails }"
                  :active-draft="activeSavedDraft"
                  :disabled="publishing || uploadingCover || checkingAccount || exiting || !!pendingPublication || publicationStorageBlocked"
                  @saved="rememberSavedDraft" @restore="restoreSavedDraft" @removed="detachSavedDraft" @restore-cloud="restoreCloudDraft" />
                <text v-if="pendingPublication" class="summary">这次发布待确认，内容已锁定。超时后点击“确认这次发布”，会继续使用原提交标识。</text>
                <text v-if="publicationMessage" class="summary">{{ publicationMessage }}</text>
                <text v-if="publishError" class="form-error">{{ publishError }}</text>
                <view class="publish-footer">
                  <button class="publish-submit" type="primary"
                    :disabled="publishing || loading || checkingAccount || exiting || uploadingCover || (!pendingPublication && !publishCategoryReady) || publicationStorageBlocked"
                    @click="publishProduct">
                    {{ publishing ? '发布中…' : (pendingPublication ? '确认这次发布' : (currentUser ? '发布商品' : '登录后发布')) }}
                  </button>
                </view>
              </view>
              <view class="campus-note"><text class="campus-note-title">好物流转，也让信任发生</text><text>聊清成色与价格，当面确认商品。让每一次校园交易都多一份安心。</text></view>
            </view>
          </view>
        </view>
<view id="catalogue" class="catalogue">
  <view class="discovery-controls">
    <view class="search-box"><MarketIcon name="search" /><input v-model="keyword" class="search-input" placeholder="搜索教材、数码、宿舍好物…" :maxlength="50" :disabled="loading || publishing || uploadingCover" confirm-type="search" @confirm="searchProducts" /><button class="search-button" type="primary" :disabled="loading || publishing || uploadingCover" @click="searchProducts">搜索</button></view>
    <view class="hot-searches"><text>大家都在搜：</text><button v-for="word in hotKeywords" :key="word" :disabled="loading || publishing || uploadingCover" @click="searchHotKeyword(word)">{{ word }}</button><button v-if="activeKeyword" class="clear-search" :disabled="loading || publishing || uploadingCover" @click="searchHotKeyword('')">清除搜索 ×</button></view>
  </view>
  <CategoryTabs :model-value="activeCategory" :disabled="loading || publishing || uploadingCover" @update:modelValue="changeCategory" />
  <view class="catalogue-heading"><view class="catalogue-heading-copy"><text class="catalogue-title">发现校园好物</text><text class="catalogue-subtitle">让每一件闲置，都值得下一次心动。</text></view><button class="refresh-button" :disabled="loading || publishing || uploadingCover" @click="fetchProducts()"><MarketIcon name="refresh" />{{ loading ? '加载中' : '刷新' }}</button></view>
  <view class="catalogue-toolbar">
    <button class="latest-sort" :class="{ selected: activeSort === 'NEWEST' }" :disabled="loading || publishing || uploadingCover" @click="changeSort('NEWEST')">最新发布</button>
    <SortPicker compact :model-value="activeSort" :disabled="loading || publishing || uploadingCover" @update:modelValue="changeSort" />
    <button class="favorites-filter" role="checkbox" :aria-checked="onlyFavorites" :disabled="favoriteLoading || favoriteBusyId > 0" @click="filterAccountFavorites"><view class="checkbox-mark" :class="{ checked: onlyFavorites }"><text v-if="onlyFavorites">✓</text></view><text>本页收藏</text></button>
    <text class="result-total">共 {{ total }} 件<text class="total-suffix">好物</text></text>
  </view>
  <view v-if="activeKeyword || onlyFavorites" class="filter-summary"><text>{{ activeKeyword ? '搜索：' + activeKeyword + ' · ' : '' }}本页显示 {{ filteredProducts.length }} 件{{ onlyFavorites ? '收藏商品' : '商品' }}</text><button v-if="onlyFavorites" @click="openMyFavorites">查看全部收藏 →</button></view>

          <text v-if="requestError" class="request-error inline-message">{{ requestError }}</text>
          <text v-if="storageMessage" class="storage-message inline-message">{{ storageMessage }}</text>
          <text v-if="favoriteLoading" class="summary">正在同步账号收藏…</text>
          <text v-if="favoriteMessage" class="request-error inline-message">{{ favoriteMessage }}</text>
          <view class="product-grid">
            <ProductCard compact v-for="item in filteredProducts" :key="item.id" :product="item" :is-favorite="favoriteIds.includes(item.id)" :favorite-disabled="loading || favoriteLoading || favoriteBusyId > 0 || !!favoriteMessage" :favorite-pending="favoriteBusyId === item.id" :stats-stale="dataSource !== '后端接口'" :show-seller-button="true" @open-seller="openSeller" @toggle-favorite="toggleFavorite($event)" @open-detail="openProductDetail($event)" />
          </view>
          <view v-if="!loading && !requestError && filteredProducts.length === 0" class="empty surface"><image src="/static/ui/brand-mark.png" mode="aspectFit" /><text class="empty-title">没有符合条件的商品</text><text>换个关键词或分类，继续发现校园好物。</text></view>
          <view class="pagination">
            <button class="page-arrow" aria-label="上一页" :disabled="loading || publishing || page <= 1" @click="goToPage(page - 1)"><MarketIcon name="chevron-left" /></button>
            <view class="desktop-pages"><template v-for="(number, index) in pageButtons" :key="index"><text v-if="number === '…'" class="page-ellipsis">…</text><button v-else :class="{ 'current-page': page === number }" :aria-label="'第' + number + '页'" :disabled="loading || publishing" @click="goToPage(number)">{{ number }}</button></template></view>
            <text class="mobile-page-count">{{ page }} / {{ totalPages }}</text>
            <button class="page-arrow" aria-label="下一页" :disabled="loading || publishing || page >= totalPages" @click="goToPage(page + 1)"><MarketIcon name="chevron-right" /></button>
          </view>
          <text class="data-source">数据来源：{{ dataSource }}</text>
        </view>
      </view>
      <view v-if="currentUser" class="intents-section"><PurchaseIntentList :active="intentionPageActive" :disabled="checkingAccount || exiting || publishing || uploadingCover" /></view>
<view class="site-footer"><view class="footer-identity"><image src="/static/ui/brand-mark.png" mode="aspectFit" /><view><text class="footer-brand">校园集市</text><text class="footer-brand-en">CAMPUS MARKET</text></view><text class="footer-motto">每一件闲置，都值得下一次心动。</text></view><view class="footer-links"><button @click="showAbout">关于集市</button><button @click="showTradeGuide">交易指南</button><button @click="openFeed">连续浏览</button><button v-if="DIAGNOSTICS_ENABLED" :disabled="publishing || uploadingCover" @click="openConnection">连接诊断</button></view></view>
</view>
<view class="mobile-navigation">
  <button class="mobile-nav-item active" aria-label="回到首页顶部" @click="backToTop"><MarketIcon name="home-filled" /><text>首页</text></button>
  <button class="mobile-nav-item" @click="openMyFavorites"><MarketIcon name="heart" /><text>收藏</text></button>
  <button class="mobile-nav-item mobile-publish" :class="{ active: publishExpanded }" :disabled="publishing || uploadingCover" :aria-expanded="publishExpanded" aria-controls="publish-form" @click="togglePublish"><view class="mobile-plus"><MarketIcon :name="publishExpanded ? 'close-white' : 'plus-white'" /></view><text>{{ publishExpanded ? '收起' : '发布' }}</text></button>
  <button class="mobile-nav-item" @click="openHistory"><MarketIcon name="clock" /><text>最近</text></button>
  <button class="mobile-nav-item" @click="openAccount"><MarketIcon name="user" /><text>我的</text></button>
</view>
</view>

</template>

<script setup>
import PublishQualityChecklist from '@/components/PublishQualityChecklist.vue'
import { submitPublication } from '@/api/publication.js'
import { newPublicationRequestId, loadPendingPublication, savePendingPublication, clearPendingPublication } from '@/utils/product-publications.js'
import { ref, computed, watch, nextTick } from 'vue'
import { onLoad, onShow, onHide, onUnload } from '@dcloudio/uni-app'
import ProductCard from '@/components/ProductCard.vue'
import MarketIcon from '@/components/MarketIcon.vue'
import CategoryTabs from '@/components/CategoryTabs.vue'
import AdminEntry from '@/components/AdminEntry.vue'
import NotificationEntry from '@/components/NotificationEntry.vue'
import NetworkStatusBanner from '@/components/NetworkStatusBanner.vue'
import ProductDetailsFields from '@/components/ProductDetailsFields.vue'
import { legacyDetails, productDetails, campuses } from '@/utils/product-details.mjs'
import PurchaseIntentList from '@/components/PurchaseIntentList.vue'
import CategoryPicker from '@/components/CategoryPicker.vue'
import SortPicker from '@/components/SortPicker.vue'
import ProductDraftBox from '@/components/ProductDraftBox.vue'
import { DIAGNOSTICS_ENABLED } from '@/config/server.js'
import { getProducts } from '@/api/product.js'
import { getCurrentUser, logoutUser } from '@/api/user.js'
import { sellerStorePath } from '@/utils/seller-store.js'
import { isValidFavoriteCount } from '@/utils/product-popularity.js'
import { currentUser, getToken, updateCurrentUser, clearSession } from '@/utils/session.js'
import { favoriteIds, favoriteLoading, favoriteBusyId, favoriteMessage, loadAccountFavorites, changeAccountFavorite } from '@/utils/account-favorites.js'

const publishExpanded = ref(false)
let sectionNavigation = 0
let sectionScrollTimer = null
let revealSettlesAt = 0
const checkingAccount = ref(false)
const exiting = ref(false)
const accountMessage = ref('')

function openFeed() {
  uni.navigateTo({ url: '/pages/product/feed', fail: () => uni.showToast({ title: '打开连续浏览失败', icon: 'none' }) })
}

function openConnection() {
  uni.navigateTo({ url: '/pages/dev/connection',
    fail: () => uni.showToast({ title: '打开诊断页失败', icon: 'none' }) })
}

function openLogin() {
  uni.navigateTo({
    url: '/pages/user/login',
    fail: () => uni.showToast({ title: '打开登录页失败', icon: 'none' })
  })
}

function openSeller(id) {
  uni.navigateTo({ url: sellerStorePath(id),
    fail: () => uni.showToast({ title: '打开卖家主页失败', icon: 'none' }) })
}

function openReports() { uni.navigateTo({ url: '/pages/report/list' }) }
function openTradeTasks() { uni.navigateTo({ url: '/pages/order/tasks', fail: () => uni.showToast({ title: '打开交易待办失败', icon: 'none' }) }) }
function openRefunds(){uni.navigateTo({url:'/pages/order/refunds'})}
function openOrders() { uni.navigateTo({ url: '/pages/order/list', fail: () => uni.showToast({ title: '打开订单列表失败', icon: 'none' }) }) }

function openHistory() {
  uni.navigateTo({ url: '/pages/product/history',
    fail: () => uni.showToast({ title: '打开浏览记录失败', icon: 'none' }) })
}

function openProfile() {
  uni.navigateTo({ url: '/pages/user/profile',
    fail: () => uni.showToast({ title: '打开账号资料失败', icon: 'none' }) })
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

const intentionPageActive = ref(false)
let hasShown = false
onShow(() => {
  intentionPageActive.value = true
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

const PAGE_SIZE = 8
const PAGE_CACHE_KEY = 'campus-market-page-cache-v9'

const products = ref([])
const page = ref(1)
const total = ref(0)
const keyword = ref('')
const activeKeyword = ref('')
const activeCategory = ref('')
const activeSort = ref('NEWEST')
const draftCategory = ref('OTHER')
const publishCategoryReady = ref(false)
const onlyFavorites = ref(false)
const loading = ref(false)
const publishing = ref(false)
const requestError = ref('')
const publishError = ref('')
const storageMessage = ref('')
const dataSource = ref('待加载')
const activeSavedDraft = ref(null)
const draftTitle = ref('')
const draftDescription = ref('')
const draftPrice = ref('')
const draftCoverId = ref('')
const draftDetails = ref(legacyDetails())
const activeCampus = ref('')
const campusFilters = [{code:'',name:'全部校区'},...campuses]
const campusFilterIndex = computed(() => Math.max(0,campusFilters.findIndex(item => item.code === activeCampus.value)))
function chooseCampus(event) { const option=campusFilters[Number(event.detail.value)];if(option)fetchProducts(1,keyword.value.trim(),activeCategory.value,activeSort.value,option.code) }
const uploadingCover = ref(false)
const pendingPublication = ref(null)
const publicationMessage = ref('')
const publicationStorageBlocked = ref(false)
let homeClosed = false
onHide(() => { intentionPageActive.value = false; cancelSectionNavigation() })
onUnload(() => { intentionPageActive.value = false; homeClosed = true; cancelSectionNavigation() })

function rememberSavedDraft(item) {
  if (item.userId === currentUser.value?.id) activeSavedDraft.value = { id: item.id, revision: item.revision }
}
function restoreSavedDraft(item) {
  if (homeClosed || publishing.value || uploadingCover.value || checkingAccount.value || exiting.value ||
      pendingPublication.value || publicationStorageBlocked.value || !getToken() || item.userId !== currentUser.value?.id) return
  draftTitle.value = item.content.title; draftDescription.value = item.content.description
  draftPrice.value = item.content.priceText; draftCoverId.value = item.content.coverImageId; draftCategory.value = item.content.categoryCode
  draftDetails.value = productDetails(item.content.details,item.content.coverImageId)
  rememberSavedDraft(item); publishError.value = ''; return true
}
function restoreCloudDraft(item) {
  if (restoreSavedDraft({ userId: item.userId, id: item.draftId, revision: item.sourceRevision, content: item.content })) activeSavedDraft.value = null
}
function detachSavedDraft(id) {
  if (activeSavedDraft.value?.id === id) activeSavedDraft.value = null
}
function clearPublicationDraft() {
  activeSavedDraft.value = null
  draftTitle.value = ''; draftDescription.value = ''; draftPrice.value = ''
  draftCoverId.value = ''; draftCategory.value = 'OTHER'; draftDetails.value = legacyDetails()
}
function restorePublication(userId) {
  pendingPublication.value = null
  publicationStorageBlocked.value = false
  try {
    const pending = loadPendingPublication(userId)
    pendingPublication.value = pending
    if (pending) {
      draftTitle.value = pending.product.title; draftDescription.value = pending.product.description
      draftPrice.value = (pending.product.priceInCents / 100).toFixed(2)
      draftCoverId.value = pending.product.coverImageId; draftCategory.value = pending.product.categoryCode
      draftDetails.value = productDetails(pending.product.details,pending.product.coverImageId)
    }
  } catch (error) {
    publicationStorageBlocked.value = true
    publishError.value = error.message
  }
}

watch(currentUser, (user, previous) => {
  if (user?.id !== previous?.id) {
    onlyFavorites.value = false
    pendingPublication.value = null; publicationMessage.value = ''; publicationStorageBlocked.value = false
    if (previous?.id) clearPublicationDraft()
    if (user?.id) restorePublication(user.id)
  }
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
    isValidFavoriteCount(item.favoriteCount) &&
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
    if (!isValidPage(saved) || typeof saved.keyword !== 'string' || typeof saved.category !== 'string' || typeof saved.campus !== 'string' || !campusFilters.some(item => item.code === saved.campus) ||
        !['NEWEST', 'PRICE_ASC', 'PRICE_DESC', 'MOST_FAVORITED'].includes(saved.sort)) {
      throw new Error('分页缓存格式不正确')
    }
    products.value = saved.records
    page.value = saved.page
    total.value = saved.total
    activeKeyword.value = saved.keyword
    keyword.value = saved.keyword
    activeCategory.value = saved.category
    activeSort.value = saved.sort
    activeCampus.value = saved.campus || ''
    dataSource.value = '本地缓存'
  } catch (error) {
    storageMessage.value = '读取商品缓存失败，将尝试请求后端'
    console.error('读取分页缓存失败：', error)
  }
}

function savePageCache(data, searchText, categoryCode, sortCode, campusCode) {
  try {
    uni.setStorageSync(PAGE_CACHE_KEY, JSON.stringify({
      ...data,
      keyword: searchText,
      category: categoryCode,
      sort: sortCode,
      campus: campusCode
    }))
  } catch (error) {
    storageMessage.value = '商品已加载，但本地缓存保存失败'
    console.error('保存分页缓存失败：', error)
  }
}

async function fetchProducts(targetPage = page.value, searchText = activeKeyword.value, categoryCode = activeCategory.value, sortCode = activeSort.value, campusCode = activeCampus.value) {
  if (loading.value || publishing.value || targetPage < 1) return
  loading.value = true
  requestError.value = ''

  try {
    const data = await getProducts({
      page: targetPage,
      size: PAGE_SIZE,
      keyword: searchText,
      category: categoryCode,
      sort: sortCode,
      campus: campusCode
    })
    if (!isValidPage(data) || data.page !== targetPage) {
      throw new Error('接口返回的分页数据格式不正确')
    }
    products.value = data.records
    total.value = data.total
    page.value = data.page
    activeKeyword.value = searchText
    activeCategory.value = categoryCode
    activeSort.value = sortCode
    activeCampus.value = campusCode
    dataSource.value = '后端接口'
    savePageCache(data, searchText, categoryCode, sortCode, campusCode)
  } catch (error) {
    requestError.value = error.message
    console.error('请求商品失败：', error)
  } finally {
    loading.value = false
    await loadAccountFavorites(products.value.map(item => item.id))
  }
}

function changeCategory(code) {
  fetchProducts(1, keyword.value.trim(), code)
}

function changeSort(code) {
  fetchProducts(1, keyword.value.trim(), activeCategory.value, code)
}

function searchProducts() {
  fetchProducts(1, keyword.value.trim())
}

function toggleFavoritesFilter() {
  onlyFavorites.value = !onlyFavorites.value
}

async function toggleFavorite(productId) {
  if (!getToken() || !currentUser.value) { openLogin(); return }
  const token = getToken()
  const changed = await changeAccountFavorite(productId, !favoriteIds.value.includes(productId))
  if (!changed || token !== getToken()) return
  // 收藏已成功，但人数需要服务器重新统计；失败时保留上次结果并标记。
  dataSource.value = '待刷新'
  await fetchProducts()
}

function openMyFavorites() {
  uni.navigateTo({ url: '/pages/product/favorites',
    fail: () => uni.showToast({ title: '打开我的收藏失败', icon: 'none' }) })
}

async function publishProduct() {
  if (homeClosed || loading.value || publishing.value || checkingAccount.value || exiting.value || uploadingCover.value ||
      publicationStorageBlocked.value || (!pendingPublication.value && !publishCategoryReady.value)) return
  publishError.value = ''
  if (!getToken() || !currentUser.value) {
    publishError.value = '请先登录后发布商品'; openLogin(); return
  }
  const publisherId = currentUser.value.id
  const publisherToken = getToken()
  const stillCurrent = () => !homeClosed && getToken() === publisherToken && currentUser.value?.id === publisherId
  if (!pendingPublication.value) {
    publicationMessage.value = ''
    const title = draftTitle.value.trim(), description = draftDescription.value.trim(), priceText = draftPrice.value.trim()
    if (title.length < 2 || title.length > 50) { publishError.value = '商品标题需要2～50个字符'; return }
    if (description.length > 500) { publishError.value = '商品描述不能超过500个字符'; return }
    if (!/^\d{1,6}(\.\d{1,2})?$/.test(priceText)) { publishError.value = '价格需为数字，整数最多6位，小数最多2位'; return }
    const priceInCents = Math.round(Number(priceText) * 100)
    if (priceInCents <= 0) { publishError.value = '价格必须大于0'; return }
    try {
      pendingPublication.value = savePendingPublication(publisherId, {
        requestId: newPublicationRequestId(), createdAt: Date.now(),
        product: { title, description, priceInCents, coverImageId: draftCoverId.value, categoryCode: draftCategory.value, details: productDetails(draftDetails.value,draftCoverId.value) }
      })
    } catch (error) {
      publishError.value = error.message
      // 不覆盖另一个窗口留下的待确认提交。
      restorePublication(publisherId)
      return
    }
  }
  const pending = pendingPublication.value
  publishing.value = true
  let published = false
  try {
    const receipt = await submitPublication(pending)
    if (!stillCurrent()) return
    publicationMessage.value = '已发布，商品编号：' + receipt.productId + (activeSavedDraft.value ? '；已保存的本机草稿仍保留，可手动管理' : '')
    clearPendingPublication(publisherId, pending.requestId)
    pendingPublication.value = null
    clearPublicationDraft()
    keyword.value = ''; onlyFavorites.value = false
    published = true
    uni.showToast({ title: receipt.replayed ? '已确认原商品' : '发布成功', icon: 'success' })
  } catch (error) {
    if (!stillCurrent()) return
    // 只有明确的参数校验失败才解除锁定；500、超时、409均保留原提交。
    if (error.statusCode === 400 && ['INVALID_PRODUCT', 'INVALID_PUBLICATION', 'INVALID_IMAGE'].includes(error.code)) {
      try { clearPendingPublication(publisherId, pending.requestId); pendingPublication.value = null }
      catch (storageError) { publishError.value = storageError.message; return }
    }
    publishError.value = error.statusCode === 401 ? '登录已失效，请重新登录后确认原发布'
      : (error.statusCode === 0 || error.statusCode >= 500) ? '未收到发布结果，原内容已保存，请点击确认这次发布'
      : error.message
  } finally {
    publishing.value = false
  }
  if (published && stillCurrent()) await fetchProducts(1, '', '', 'NEWEST')
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
  if (currentUser.value?.id) restorePublication(currentUser.value.id)
  loadLocalData()
  fetchProducts()
})
const hotKeywords = ['高等数学', '台灯', '自行车']
const pageButtons = computed(() => {
  const count = totalPages.value, current = page.value
  if (count <= 7) return Array.from({ length: count }, (_, index) => index + 1)
  const result = [1]
  if (current > 3) result.push('…')
  for (let number = Math.max(2, current - 1); number <= Math.min(count - 1, current + 1); number++) result.push(number)
  if (current < count - 2) result.push('…')
  result.push(count)
  return result
})
function searchHotKeyword(word) {
  if (loading.value || publishing.value || uploadingCover.value) return
  keyword.value = word
  return searchProducts()
}
function filterAccountFavorites() {
  if (!currentUser.value || !getToken()) { openLogin(); return }
  if (favoriteLoading.value || favoriteBusyId.value > 0) return
  toggleFavoritesFilter()
}
async function goToPage(number) {
  if (!Number.isInteger(number) || number < 1 || number > totalPages.value || loading.value || publishing.value) return
  await fetchProducts(number)
  if (!homeClosed && intentionPageActive.value) return focusCatalogue()
}
function openAccount() { return currentUser.value ? openProfile() : openLogin() }
function showCampusInfo() {
  uni.showModal({ title: '全部校区', content: '目前商品尚未录入校区信息，正在展示全部商品。交易地点请与卖家确认。', showCancel: false })
}
function showAbout() {
  uni.showModal({ title: '关于校园集市', content: '让闲置好物在同学之间继续发挥价值。商品、价格和收藏均来自校园集市的真实数据。', showCancel: false })
}
function showTradeGuide() {
  uni.showModal({ title: '校园交易小贴士', content: '下单前聊清成色、配件与价格；尽量在校园公共场所交接；当面检查商品后再确认收货。有问题可在商品或订单页提交举报与售后申请。', showCancel: false })
}
function backToTop() {
  cancelSectionNavigation()
  // #ifdef H5
  window.scrollTo({ top: 0, behavior: reduceMotion() ? 'auto' : 'smooth' })
  // #endif
  // #ifndef H5
  uni.pageScrollTo({ scrollTop: 0, duration: 420 })
  // #endif
}

// Keep the form mounted; animate intrinsic height instead of switching display.
const REVEAL_DURATION = 340
function reduceMotion() {
  // #ifdef H5
  return typeof window !== 'undefined' && window.matchMedia('(prefers-reduced-motion: reduce)').matches
  // #endif
  // #ifndef H5
  return false
  // #endif
}
function cancelSectionNavigation() {
  sectionNavigation++
  if (sectionScrollTimer !== null) {
    clearTimeout(sectionScrollTimer)
    sectionScrollTimer = null
  }
  // Stop an older browser scroll before a newer command or route takes over.
  // #ifdef H5
  if (typeof window !== 'undefined') window.scrollTo({ top: window.scrollY, left: window.scrollX, behavior: 'auto' })
  // #endif
}
async function locateSection(selector) {
  cancelSectionNavigation()
  const navigation = sectionNavigation
  await nextTick()
  const valid = () => !homeClosed && intentionPageActive.value && navigation === sectionNavigation
  const scroll = () => {
    sectionScrollTimer = null
    if (!valid()) return
    // Native browser scrolling replaces an older smooth scroll, unlike stacked RAF loops.
    // #ifdef H5
    const target = document.querySelector(selector)
    if (target) target.scrollIntoView({ behavior: reduceMotion() ? 'auto' : 'smooth', block: 'start' })
    else uni.showToast({ title: '请滑动到对应区域', icon: 'none' })
    // #endif
    // #ifndef H5
    uni.pageScrollTo({ selector, duration: 420,
      fail: () => uni.showToast({ title: '请滑动到对应区域', icon: 'none' }) })
    // #endif
  }
  if (!valid()) return
  // The catalogue moves with the reveal; target its final position after the layout settles.
  const delay = selector === '#catalogue' && !reduceMotion() ? Math.max(0, revealSettlesAt - Date.now()) : 0
  if (delay > 0) sectionScrollTimer = setTimeout(scroll, delay + 24)
  else scroll()
}
function togglePublish() {
  if (publishing.value || uploadingCover.value) return
  publishExpanded.value = !publishExpanded.value
  revealSettlesAt = Date.now() + (reduceMotion() ? 0 : REVEAL_DURATION)
  return locateSection(publishExpanded.value ? '#publish-form' : '#catalogue')
}
function focusCatalogue() {
  return locateSection('#catalogue')
}
</script>

<style scoped>
.market-layout { display: flex; flex-direction: column; align-items: stretch; gap: 0; }
.publish-reveal { display: grid; grid-template-rows: 0fr; margin-bottom: 0; transition: grid-template-rows 340ms cubic-bezier(.22,.68,0,1), margin-bottom 340ms cubic-bezier(.22,.68,0,1); }
.publish-reveal.is-open { grid-template-rows: 1fr; margin-bottom: 32px; }
.publish-clip { min-height: 0; overflow: hidden; }
.publish-column { opacity: 0; transform: translateY(-8px); visibility: hidden; transition: opacity 220ms ease, transform 340ms cubic-bezier(.22,.68,0,1), visibility 0s linear 340ms; }
.publish-reveal.is-open .publish-column { opacity: 1; transform: translateY(0); visibility: visible; transition: opacity 240ms ease 40ms, transform 340ms cubic-bezier(.22,.68,0,1), visibility 0s; }
.publish-link, .welcome-notes .welcome-action, .collapse-publish { transition: background 160ms ease, color 160ms ease, transform 160ms ease; }
.publish-column, .catalogue { width: 100%; min-width: 0; }
.publish-form { padding: 30px 32px; }
.section-eyebrow { display: flex; align-items: center; gap: 6px; color: #8b9ab1; font-size: 10px; letter-spacing: 1.4px; margin-bottom: 10px; }
.tiny-dot { width: 5px; height: 5px; border-radius: 50%; background: #3d78ee; }
.form-title { display: block; font-size: 23px; color: #172a4c; font-weight: 750; }
.form-subtitle { display: block; font-size: 12px; line-height: 1.8; color: #8994a7; margin: 8px 0 18px; }
.publish-note { display: block; color: #5875a5; font-size: 12px; line-height: 1.8; padding: 12px; background: #f0f5ff; border-radius: 9px; margin-bottom: 20px; }
.field-label { display: block; font-size: 13px; font-weight: 600; margin: 20px 0 9px; }
.form-field { width: 100%; height: 43px; padding: 10px 12px; border: 1px solid #e1e7f1; border-radius: 9px; background: #f9fafd; box-sizing: border-box; font-size: 13px; }
.description-input { height: 100px; line-height: 1.7; }
.publish-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; }
.collapse-publish { margin: 0; font-size: 12px; color: #7d90ae; border-color: #e4ebf6; background: #fafcff; }
.collapse-publish text { margin-left: 8px; }
.publish-fields { display: grid; grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr); gap: 32px; }
.publish-main, .publish-aside { min-width: 0; }
.publish-fields .field-label { margin-top: 0; }
.publish-main .description-input { min-height: 132px; }
.publish-main .field-label:not(:first-child) { margin-top: 22px; }
.publish-facts { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 18px; }
.publish-category :deep(.category-picker) { margin: 0; }
.publish-category :deep(.label) { font-size: 13px; font-weight: 600; color: #17243e; margin-bottom: 9px; }
.publish-category :deep(.selection) { height: 43px; min-height: 43px; }
.publish-aside :deep(.picker) { margin-bottom: 0; }
.publish-footer { display: flex; justify-content: flex-end; margin-top: 24px; padding-top: 20px; border-top: 1px solid #edf1f7; }
.publish-submit { margin: 0; width: 230px; font-size: 14px; height: 44px; line-height: 42px; border-radius: 10px; }
.publish-form .summary, .form-error { display: block; font-size: 12px; line-height: 1.7; margin-top: 16px; }
.form-error, .request-error { color: #bd3e40; }
.campus-note { margin: 18px 6px 0; color: #94a0b2; font-size: 11px; line-height: 1.9; }
.campus-note-title { display: block; color: #7b8ca7; font-size: 12px; margin-bottom: 5px; }

.market-page { padding: 0; max-width: none; background: #f5f8fc; min-height: 100vh; color: #102449; font-size: 14px; }
.site-header { background: #fff; border-bottom: 1px solid #e9eef7; padding-top: var(--status-bar-height, 0px); }
.header-inner { max-width: 1240px; min-height: 72px; padding: 0 32px; margin: auto; display: flex; align-items: center; justify-content: space-between; gap: 20px; }
.brand-group, .site-brand, .header-actions { display: flex; align-items: center; }
.brand-group { gap: 28px; min-width: 0; }
.site-brand { gap: 10px; flex-shrink: 0; }
.brand-mark { width: 36px; height: 42px; }
.brand-name { display: block; color: #123b86; font-size: 24px; font-weight: 800; letter-spacing: .5px; line-height: 1.2; }
.brand-caption { display: block; margin-top: 5px; color: #7c91b6; font-size: 8px; font-weight: 600; letter-spacing: 2.6px; }
.campus-select { display: flex; align-items: center; gap: 6px; padding: 8px 0; margin: 0; min-height: 32px; line-height: 1.5; color: #344869; font-size: 13px; border: 0; background: transparent; flex-shrink: 0; }
.campus-select :deep(.market-icon) { width: 15px; height: 15px; }
.header-actions { gap: 14px; flex-shrink: 0; }
.header-actions button { margin: 0; font-size: 13px; min-height: 38px; line-height: 36px; padding: 0 6px; border: 0; border-radius: 10px; background: transparent; }
.nav-button { color: #4b5c78; }
.login-button, .register-button { color: #2563eb; }
.header-actions .publish-link { display: flex; align-items: center; gap: 7px; background: #2463f5; color: #fff; padding: 0 17px; height: 44px; line-height: 44px; box-shadow: 0 4px 12px #2463f520; }
.publish-link :deep(.market-icon) { width: 20px; height: 20px; }
.publish-link.is-active { background: #164bc6; }
.account-button { max-width: 120px; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.market-shell { box-sizing: border-box; max-width: 1240px; padding: 22px 32px 0; margin: auto; }
.surface { background: #fff; border: 1px solid #e4ebf5; border-radius: 18px; box-shadow: 0 4px 20px #243d6b04; }
.welcome-banner { height: 338px; border-radius: 19px; position: relative; overflow: hidden; display: flex; align-items: center; background: #e8f3ff; }
.hero-photo { position: absolute; width: 100%; height: 100%; inset: 0; }
.hero-shade { position: absolute; inset: 0; background: linear-gradient(90deg,#e9f5ff75 0%,#e9f5ff25 40%,transparent 65%); }
.welcome-copy { position: relative; z-index: 1; padding: 30px 48px; }
.welcome-eyebrow { display: block; color: #3169c5; font-size: 14px; letter-spacing: 4px; margin-bottom: 13px; }
.welcome-title { display: block; font-size: 46px; line-height: 1.22; font-weight: 800; letter-spacing: -1.3px; color: #0d2854; }
.welcome-accent { display: block; color: #175bf4; }
.welcome-description { display: block; margin-top: 15px; color: #4c6a95; font-size: 17px; line-height: 1.7; }
.hero-benefits { display: flex; align-items: center; gap: 27px; margin-top: 22px; color: #244b89; font-size: 12px; }
.hero-benefits > view { display: flex; align-items: center; gap: 8px; }
.hero-benefits :deep(.market-icon) { padding: 6px; width: 27px; height: 27px; border: 1px solid #ffffffc0; border-radius: 50%; background: #ffffff30; }
.welcome-notes { display: flex; align-items: center; justify-content: flex-end; gap: 14px; margin: 6px 5px 10px; }
.welcome-notes .welcome-action { border: 0; background: transparent; color: #738bad; margin: 0; padding: 4px 0; min-height: 27px; font-size: 11px; line-height: 19px; }
.welcome-action.is-active { color: #2463f5; }
.note-divider { color: #c2d0e5; font-size: 11px; }
.catalogue { width: 100%; min-width: 0; }
.discovery-controls { max-width: 1030px; margin: 0 auto; }
.search-box { display: flex; align-items: center; gap: 14px; padding: 3px 3px 3px 22px; border: 1.5px solid #9cb9ff; box-shadow: 0 3px 12px #2463f50a; border-radius: 13px; background: #fff; height: 55px; box-sizing: border-box; }
.search-box :deep(.market-icon) { width: 24px; height: 24px; flex-shrink: 0; }
.search-input { flex: 1; min-width: 0; height: 45px; font-size: 16px; background: transparent; padding: 0; border: 0; }
.search-button { flex-shrink: 0; margin: 0; border: 0; min-height: 45px; height: 45px; line-height: 45px; padding: 0 44px; font-size: 15px; border-radius: 9px; background: #2463f5; }
.hot-searches { display: flex; align-items: center; flex-wrap: wrap; gap: 17px; padding: 7px 2px; color: #7d8eaa; font-size: 12px; min-height: 27px; }
.hot-searches button { border: 0; background: transparent; padding: 3px 0; margin: 0; min-height: 25px; line-height: 19px; font-size: 12px; color: #6c80a2; }
.hot-searches .clear-search { color: #2463f5; margin-left: auto; }
.catalogue-heading { display: flex; align-items: center; justify-content: space-between; gap: 14px; margin: 25px 0 13px; }
.catalogue-heading-copy { display: flex; align-items: baseline; flex-wrap: wrap; column-gap: 21px; row-gap: 6px; }
.catalogue-title { font-size: 28px; font-weight: 800; color: #0c2247; letter-spacing: -.5px; }
.catalogue-subtitle { font-size: 14px; color: #8a9bb7; }
.refresh-button { display: flex; align-items: center; gap: 7px; margin: 0; padding: 5px 0 5px 10px; min-height: 33px; line-height: 23px; font-size: 13px; color: #2463f5; border: 0; background: transparent; flex-shrink: 0; }
.refresh-button :deep(.market-icon) { width: 20px; height: 20px; }
.catalogue-toolbar { display: flex; align-items: center; gap: 22px; min-height: 46px; margin-bottom: 10px; }
.latest-sort { align-self: stretch; margin: 0; padding: 9px 4px; border: 0; border-radius: 0; background: transparent; color: #7c8fac; font-size: 14px; line-height: 24px; position: relative; }
.latest-sort.selected { color: #2463f5; font-weight: 600; }
.latest-sort.selected::before { content: ''; position: absolute; bottom: 0; height: 3px; border-radius: 2px; background: #2463f5; left: 2px; right: 2px; }
.favorites-filter { margin: 0; padding: 0 0 0 20px; min-height: 24px; line-height: 24px; border: 0; border-left: 1px solid #e0e7f3; border-radius: 0; background: transparent; color: #8494ad; font-size: 12px; display: flex; align-items: center; gap: 9px; white-space: nowrap; }
.checkbox-mark { width: 17px; height: 17px; border: 1px solid #b9c8de; border-radius: 4px; background: #fff; box-sizing: border-box; display: flex; align-items: center; justify-content: center; }
.checkbox-mark.checked { background: #2463f5; border-color: #2463f5; color: #fff; font-size: 12px; }
.result-total { margin-left: auto; color: #8091ad; font-size: 13px; flex-shrink: 0; }
.filter-summary { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; font-size: 12px; color: #8292aa; padding: 2px 2px 13px; }
.filter-summary button { border: 0; background: transparent; color: #2463f5; font-size: 12px; margin: 0; padding: 0; min-height: 24px; line-height: 24px; }
.product-grid { display: grid; grid-template-columns: repeat(4,minmax(0,1fr)); gap: 14px; align-items: stretch; }
.product-grid :deep(.card) { margin-bottom: 0; }
.pagination { display: flex; align-items: center; justify-content: center; gap: 12px; margin: 22px 0 10px; color: #203353; }
.pagination button { margin: 0; padding: 0 10px; min-width: 35px; min-height: 35px; line-height: 33px; border: 1px solid #e1e8f2; border-radius: 7px; background: #fff; font-size: 12px; color: #264168; box-shadow: 0 3px 10px #273e6504; }
.pagination .current-page { background: #2463f5; color: #fff; border-color: #2463f5; box-shadow: 0 3px 10px #2463f520; }
.desktop-pages { display: flex; align-items: center; gap: 7px; }
.page-ellipsis { font-size: 12px; color: #8091ad; padding: 0 3px; }
.pagination .page-arrow { display: flex; align-items: center; justify-content: center; padding: 0 8px; }
.page-arrow :deep(.market-icon) { width: 18px; height: 18px; }
.page-arrow[disabled] { opacity: .4; }
.mobile-page-count { display: none; }
.data-source { display: block; text-align: center; color: #a1afc3; font-size: 10px; margin-bottom: 15px; }
.empty { padding: 50px 20px; text-align: center; color: #8c9bb2; font-size: 13px; }
.empty image { width: 40px; height: 44px; opacity: .45; }
.empty-title { display: block; margin: 15px 0 10px; font-size: 17px; color: #5e7496; }
.inline-message { display: block; padding: 12px 15px; margin-bottom: 16px; border-radius: 9px; background: #fff3f1; font-size: 13px; line-height: 1.7; }
.storage-message { color: #9d661a; }
.member-panel { padding: 14px 18px; margin-bottom: 20px; }
.member-links { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-bottom: 10px; }
.member-links button { margin: 0; }
.account-error { display: block; margin-bottom: 16px; }
.intents-section { margin-top: 28px; }
.site-footer { margin-top: 24px; border-top: 1px solid #e1e9f4; padding: 22px 0; display: flex; align-items: center; justify-content: space-between; gap: 20px; }
.footer-identity { display: flex; align-items: center; gap: 10px; }
.footer-identity image { width: 28px; height: 34px; }
.footer-brand { display: block; color: #16418b; font-size: 18px; font-weight: 750; }
.footer-brand-en { display: block; color: #8298b9; font-size: 6px; letter-spacing: 2px; margin-top: 2px; }
.footer-motto { color: #8b9bb6; font-size: 12px; margin-left: 18px; }
.footer-links { display: flex; flex-wrap: wrap; gap: 18px; }
.footer-links button { margin: 0; padding: 0; background: transparent; border: 0; font-size: 11px; line-height: 24px; min-height: 24px; color: #8396b5; }
.mobile-navigation { display: none; }
@media (min-width: 1500px) { .header-inner, .market-shell { max-width: 1380px; } .welcome-banner { height: 365px; } }
@media (max-width: 1050px) { .header-actions { gap: 9px; } .brand-group { gap: 18px; } .header-inner { padding: 0 24px; } .market-shell { padding: 20px 24px 0; } .welcome-copy { padding-left: 36px; } .welcome-title { font-size: 40px; } .welcome-description { font-size: 15px; } .welcome-banner { height: 300px; } .product-grid { gap: 12px; } .footer-motto { display: none; } }
@media (max-width: 760px) {
  .market-page { padding-bottom: calc(70px + env(safe-area-inset-bottom)); }
  .header-inner { min-height: 66px; padding: 0 16px; gap: 14px; }
  .brand-group { flex: 1; justify-content: space-between; gap: 8px; }
  .brand-mark { width: 27px; height: 33px; }
  .brand-name { font-size: 21px; letter-spacing: 0; }
  .brand-caption { display: none; }
  .site-brand { gap: 7px; }
  .campus-select { font-size: 12px; gap: 4px; }
  .campus-select :deep(.market-icon) { width: 12px; height: 12px; }
  .header-actions { gap: 0; }
  .header-actions .desktop-link, .header-actions .publish-link { display: none; }
  .header-actions .login-button, .header-actions .account-button { padding: 0 0 0 12px; min-height: 22px; line-height: 22px; border-left: 1px solid #e3e9f3; border-radius: 0; font-size: 13px; color: #2463f5; max-width: 65px; }
  .market-shell { padding: 14px 14px 0; }
  .welcome-banner { height: 190px; border-radius: 14px; }
  .hero-photo { width: 150%; left: -50%; }
  .hero-shade { background: linear-gradient(90deg,#e7f5ff 0%,#e7f5fff5 43%,#e7f5ffbb 56%,transparent 77%); }
  .welcome-copy { padding: 22px 17px; width: 100%; box-sizing: border-box; }
  .welcome-eyebrow { font-size: 10px; letter-spacing: 2px; margin-bottom: 12px; }
  .welcome-title { font-size: 29px; line-height: 1.24; letter-spacing: -.7px; }
  .welcome-description { font-size: 12px; line-height: 1.7; max-width: 190px; margin-top: 12px; color: #44618c; }
  .hero-benefits { display: none; }
  .welcome-notes { gap: 11px; margin: 4px 3px 8px; }
  .welcome-notes .welcome-action { font-size: 10px; min-height: 24px; line-height: 20px; padding: 2px 0; }
  .search-box { height: 46px; padding-left: 12px; gap: 9px; border-radius: 10px; }
  .search-box :deep(.market-icon) { width: 21px; height: 21px; }
  .search-input { font-size: 13px; height: 38px; }
  .search-button { min-height: 38px; height: 38px; line-height: 38px; padding: 0 23px; font-size: 14px; border-radius: 7px; }
  .hot-searches { gap: 14px; padding: 5px 2px; font-size: 11px; }
  .hot-searches button { font-size: 11px; min-height: 24px; }
  .catalogue-heading { margin: 20px 0 7px; gap: 8px; }
  .catalogue-heading-copy { display: block; min-width: 0; }
  .catalogue-title { font-size: 21px; letter-spacing: -.7px; }
  .catalogue-subtitle { display: none; }
  .refresh-button { font-size: 12px; gap: 5px; padding: 4px 0 4px 5px; min-height: 30px; }
  .refresh-button :deep(.market-icon) { width: 18px; height: 18px; }
  .catalogue-toolbar { gap: 12px; min-height: 39px; margin-bottom: 10px; }
  .latest-sort { font-size: 12px; line-height: 19px; padding: 8px 0; white-space: nowrap; }
  .favorites-filter { font-size: 10px; gap: 5px; padding-left: 11px; }
  .checkbox-mark { width: 15px; height: 15px; border-radius: 3px; }
  .result-total { font-size: 11px; }
  .total-suffix { display: none; }
  .product-grid { grid-template-columns: repeat(2,minmax(0,1fr)); gap: 10px; }
  .filter-summary { font-size: 11px; }
  .publish-form { padding: 23px 18px; border-radius: 14px; }
  .publish-heading { gap: 12px; }
  .publish-heading .form-title { font-size: 22px; }
  .publish-heading .form-subtitle { max-width: 210px; }
  .collapse-publish { flex-shrink: 0; padding: 0 10px; }
  .publish-fields { grid-template-columns: minmax(0,1fr); gap: 22px; }
  .publish-facts { gap: 14px; }
  .publish-submit { width: 100%; }
  .publish-reveal.is-open { margin-bottom: 24px; }
  .pagination { margin-top: 17px; gap: 24px; }
  .desktop-pages { display: none; }
  .mobile-page-count { display: block; font-size: 12px; font-weight: 600; }
  .site-footer { padding: 15px 0 10px; gap: 14px; flex-wrap: wrap; margin-top: 18px; }
  .footer-identity { display: none; }
  .footer-links { gap: 20px; }
  .footer-links button { font-size: 10px; }
  .member-panel { padding: 12px; }
  .mobile-navigation { position: fixed; bottom: 0; left: 0; right: 0; z-index: 30; display: flex; align-items: center; justify-content: space-around; padding: 6px 5px calc(6px + env(safe-area-inset-bottom)); background: #fffffffa; border-top: 1px solid #dee7f4; box-shadow: 0 -3px 16px #27457006; }
  .mobile-nav-item { margin: 0; padding: 2px 0; min-width: 52px; min-height: 50px; line-height: 17px; background: transparent; border: 0; border-radius: 0; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 3px; color: #607697; font-size: 11px; }
  .mobile-nav-item :deep(.market-icon) { width: 24px; height: 24px; }
  .mobile-nav-item.active, .mobile-publish { color: #2463f5; }
  .mobile-plus { display: flex; justify-content: center; align-items: center; height: 29px; width: 48px; background: #1460ff; border-radius: 18px; }
  .mobile-plus :deep(.market-icon) { width: 26px; height: 26px; }
}
@media (max-width: 360px) { .header-inner { padding: 0 12px; } .brand-name { font-size: 19px; } .catalogue-toolbar { gap: 8px; } .favorites-filter { padding-left: 8px; } .welcome-title { font-size: 27px; } .welcome-banner { height: 185px; } }
/* #ifdef H5 */
.market-layout { overflow-anchor: none; }
#publish-form, #catalogue { scroll-margin-top: 20px; }
.publish-link:active, .welcome-action:active, .collapse-publish:active, .mobile-nav-item:active { transform: scale(.97); }
button { transition: color 160ms ease, background 160ms ease, transform 160ms ease; }
@media (hover:hover) { .welcome-action:hover, .hot-searches button:hover, .footer-links button:hover { color: #2463f5; } .search-box:focus-within { border-color: #2463f5; box-shadow: 0 0 0 3px #2463f50b; } }
@media (prefers-reduced-motion: reduce) { .publish-reveal, .publish-column, .publish-reveal.is-open .publish-column, button { transition: none; } .publish-column, button:active { transform: none; } }
/* #endif */
/* #ifdef MP-WEIXIN */
.header-inner { padding-top: 46px; }
.header-actions { margin-right: 90px; }
@media (max-width:760px) { .brand-group { justify-content: flex-start; gap: 16px; } .header-actions { margin-right: 0; } }
/* #endif */


</style>

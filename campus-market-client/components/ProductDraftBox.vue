<template>
  <view class="draft-box">
    <text class="heading">发布草稿箱</text>
    <text class="hint">按账号保存在当前设备，最多10份；可手动备份到云端，登录同一账号后读取。已保存草稿发布后仍保留，可手动删除。</text>
    <button size="mini" :disabled="disabled || busy" @click="save()">{{ activeDraft ? '更新当前草稿' : '保存草稿' }}</button>
    <button v-if="activeDraft" size="mini" :disabled="disabled || busy" @click="save(true)">另存新草稿</button>
    <button size="mini" :disabled="disabled || busy" @click="toggle">{{ opened ? '收起草稿箱' : '查看草稿箱' }}（{{ records.length }}）</button>
    <text v-if="disabled" class="hint">发布或账号操作期间暂停草稿管理；待确认发布请先确认原提交。</text>
    <text v-if="message" class="hint">{{ message }}</text>
    <text v-if="error" class="error">{{ error }}</text>
    <view v-if="opened">
      <button size="mini" :disabled="disabled || busy" @click="reload">刷新草稿箱</button>
      <text v-if="!records.length && !error" class="hint">还没有保存草稿。</text>
      <view v-for="item in records" :key="item.id" class="draft-item">
        <text>{{ item.content.title.trim() || '未填写标题的草稿' }}</text>
        <text class="hint">价格：{{ item.content.priceText || '未填写' }}；草稿版本 {{ item.revision }}</text>
        <button size="mini" :disabled="disabled || busy" @click="restore(item)">继续编辑</button>
        <button size="mini" :disabled="disabled || busy" @click="uploadBackup(item)">备份到云端</button>
        <button size="mini" :disabled="disabled || busy" @click="remove(item)">删除草稿</button>
      </view>
    </view>
    <button size="mini" :disabled="disabled || busy" @click="toggleCloud">{{ cloudOpened ? '收起云端备份' : '查看云端备份' }}</button>
    <view v-if="cloudOpened">
      <text class="heading">云端草稿备份</text>
      <text class="hint">每个账号最多10份；仅手动备份。载入表单后可另存为新的本机草稿，发布商品不会删除备份。</text>
      <input v-model="cloudKeywordInput" :maxlength="50" :disabled="disabled || busy || cloudLoading" placeholder="按云端草稿标题搜索" @confirm="searchCloud" />
      <CategoryPicker :model-value="cloudCategory" include-all label="云端草稿分类"
        :disabled="disabled || busy || cloudLoading" @update:modelValue="changeCloudCategory" />
      <text class="hint">云端草稿排序</text>
      <picker :range="CLOUD_SORT_OPTIONS" range-key="name" :value="cloudSortIndex"
        :disabled="disabled || busy || cloudLoading" @change="chooseCloudSort">
        <view class="sort-selection">{{ CLOUD_SORT_OPTIONS[cloudSortIndex].name }} ▾</view>
      </picker>
      <button size="mini" :disabled="disabled || busy || cloudLoading" @click="searchCloud">搜索云端草稿</button>
      <button size="mini" :disabled="disabled || busy || cloudLoading" @click="clearCloudSearch">清除草稿搜索</button>
      <button size="mini" :disabled="disabled || busy || cloudLoading" @click="resetCloudFilters">重置草稿筛选</button>
      <text class="hint">分类与标题同时筛选；清除搜索保留分类与排序，重置筛选恢复全部和最近更新。</text>
      <button size="mini" :disabled="disabled || busy || cloudLoading" @click="refreshCloud()">刷新云端备份</button>
      <text class="hint">当前关键词：{{ cloudKeyword || '全部' }}；匹配 {{ cloudTotal }} 份，每页 {{ CLOUD_PAGE_SIZE }} 份。</text>
      <text v-if="cloudLoading" class="hint">正在读取云端备份…</text>
      <text v-if="cloudStale" class="hint">列表尚未确认最新状态，请刷新后再操作。</text>
      <text v-if="cloudError" class="error">{{ cloudError }}</text>
      <text v-if="cloudMessage" class="hint">{{ cloudMessage }}</text>
      <text v-if="!cloudLoading && !cloudError && !cloudStale && !cloudRecords.length" class="hint">{{ cloudKeyword || cloudCategory ? '没有符合当前标题与分类的云端备份，请调整筛选。' : '还没有云端备份。' }}</text>
      <view v-for="item in cloudRecords" :key="item.draftId" class="draft-item">
        <text>{{ item.content.title.trim() || '未填写标题的草稿' }}</text>
        <text class="hint">本机来源版本 {{ item.sourceRevision }}；云端版本 {{ item.version }}；{{ item.updatedAt }}</text>
        <button size="mini" :disabled="disabled || busy || cloudLoading || cloudStale" @click="restoreCloud(item)">载入云端备份</button>
        <button size="mini" :disabled="disabled || busy || cloudLoading || cloudStale" @click="removeCloud(item)">删除云端备份</button>
      </view>
      <button size="mini" :disabled="disabled || busy || cloudLoading || cloudStale || cloudPage <= 1" @click="changeCloudPage(-1)">上一页草稿</button>
      <text class="hint">云端草稿第 {{ cloudPage }} / {{ cloudPages }} 页</text>
      <button size="mini" :disabled="disabled || busy || cloudLoading || cloudStale || cloudPage >= cloudPages" @click="changeCloudPage(1)">下一页草稿</button>
    </view>
  </view>
</template>

<script setup>
import CategoryPicker from '@/components/CategoryPicker.vue'
import { getDraftBackupPage, getDraftBackup, backupDraft, deleteDraftBackup } from '@/api/draft-backup.js'
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { currentUser, getToken } from '@/utils/session.js'
import { newPublicationRequestId } from '@/utils/product-publications.js'
import { draftContent, hasDraftContent, loadProductDrafts, saveProductDraft, removeProductDraft } from '@/utils/product-drafts.mjs'
const props = defineProps({ draft: { type: Object, required: true }, activeDraft: { type: Object, default: null }, disabled: { type: Boolean, default: false } })
const emit = defineEmits(['saved', 'restore', 'removed', 'restore-cloud'])
const records = ref([]), opened = ref(false), busy = ref(false), message = ref(''), error = ref('')
const cloudRecords = ref([]), cloudOpened = ref(false), cloudLoading = ref(false), cloudStale = ref(false), cloudError = ref(''), cloudMessage = ref('')
const CLOUD_PAGE_SIZE = 3
const CLOUD_SORT_OPTIONS = [
  { code: 'UPDATED_DESC', name: '最近更新' },
  { code: 'UPDATED_ASC', name: '最早更新' },
  { code: 'TITLE_ASC', name: '标题顺序' }
]
const cloudSort = ref('UPDATED_DESC')
const cloudSortIndex = computed(() => CLOUD_SORT_OPTIONS.findIndex(item => item.code === cloudSort.value))
const cloudCategory = ref('')
const cloudKeywordInput = ref(''), cloudKeyword = ref(''), cloudPage = ref(1), cloudPages = ref(1), cloudTotal = ref(0)
let cloudRequest = 0
let disposed = false
function identity() {
  const token = getToken(), userId = currentUser.value?.id
  if (disposed || props.disabled || busy.value || !token || !userId) return null
  return { token, userId }
}
function stillCurrent(actor) {
  return !disposed && !props.disabled && actor.token === getToken() && actor.userId === currentUser.value?.id
}
function reload() {
  if (disposed) return
  try { records.value = currentUser.value?.id ? loadProductDrafts(currentUser.value.id) : []; error.value = '' }
  catch (cause) { records.value = []; error.value = cause.message || '读取草稿失败' }
}
function toggle() {
  if (!identity()) return
  opened.value = !opened.value
  if (opened.value) reload()
}
function save(asNew = false) {
  const actor = identity(); if (!actor) return
  error.value = ''; message.value = ''
  try {
    const active = asNew ? null : props.activeDraft
    const item = saveProductDraft(actor.userId, props.draft, { id: active?.id || newPublicationRequestId(), expectedRevision: active?.revision || 0 })
    if (!stillCurrent(actor)) return
    emit('saved', item); reload(); message.value = '草稿已保存到当前设备'; opened.value = true
  } catch (cause) { error.value = cause.message || '保存草稿失败，请刷新检查' }
}
function confirm(title, content) {
  return new Promise(resolve => {
    try { uni.showModal({ title, content, success: result => resolve(result.confirm === true), fail: () => resolve(false) }) }
    catch { resolve(false) }
  })
}
function currentRecord(actor, item) {
  const latest = loadProductDrafts(actor.userId).find(row => row.id === item.id)
  if (!latest || latest.revision !== item.revision) throw new Error('草稿已变化，请刷新后重新选择')
  return latest
}
async function restore(item) {
  const actor = identity(); if (!actor) return
  busy.value = true; error.value = ''; message.value = ''
  try {
    const latest = currentRecord(actor, item), before = JSON.stringify(draftContent(props.draft))
    if (hasDraftContent(props.draft) && before !== JSON.stringify(latest.content) &&
        !await confirm('载入这份草稿？', '将替换发布表单中尚未保存的内容。需要保留时，请先取消并保存当前内容。')) return
    if (!stillCurrent(actor)) return
    if (JSON.stringify(draftContent(props.draft)) !== before) throw new Error('发布表单已变化，请重新选择草稿')
    emit('restore', currentRecord(actor, item)); message.value = '草稿已载入发布表单，尚未发布商品'
  } catch (cause) { if (stillCurrent(actor)) error.value = cause.message || '载入草稿失败' }
  finally { busy.value = false }
}
async function remove(item) {
  const actor = identity(); if (!actor) return
  busy.value = true; error.value = ''; message.value = ''
  try {
    currentRecord(actor, item)
    if (!await confirm('删除这份草稿？', '删除后无法恢复。发布表单中的现有内容会保留。')) return
    if (!stillCurrent(actor)) return
    removeProductDraft(actor.userId, item.id, item.revision)
    emit('removed', item.id); reload(); message.value = '草稿已删除，发布表单内容保留'
  } catch (cause) { if (stillCurrent(actor)) error.value = cause.message || '删除草稿失败' }
  finally { busy.value = false }
}
function invalidateCloud() { cloudRequest++; cloudLoading.value = false; cloudStale.value = true }
async function refreshCloud({ page = cloudPage.value, keyword = cloudKeyword.value, category = cloudCategory.value, sort = cloudSort.value } = {}) {
  const actor = identity(); if (!actor) return
  const requestId = ++cloudRequest
  cloudLoading.value = true; cloudError.value = ''
  try {
    const result = await getDraftBackupPage({ page, size: CLOUD_PAGE_SIZE, keyword, category, sort })
    if (!stillCurrent(actor) || requestId !== cloudRequest) return
    // 查询成功后一起提交条件、页码和记录，失败时保留上次结果。
    cloudRecords.value = result.items; cloudPage.value = result.page; cloudPages.value = result.totalPages
    cloudTotal.value = result.total; cloudKeyword.value = result.keyword; cloudCategory.value = result.category; cloudSort.value = result.sort; cloudStale.value = false
  } catch (cause) {
    if (stillCurrent(actor) && requestId === cloudRequest) { cloudError.value = (cause.message || '读取云端备份失败') + '；当前保留上次成功结果，请刷新重试'; cloudStale.value = true }
  } finally { if (requestId === cloudRequest) cloudLoading.value = false }
}
function searchCloud() { return refreshCloud({ page: 1, keyword: cloudKeywordInput.value }) }
function clearCloudSearch() { cloudKeywordInput.value = ''; return refreshCloud({ page: 1, keyword: '' }) }
function changeCloudCategory(category) { return refreshCloud({ page: 1, category }) }
function resetCloudFilters() { cloudKeywordInput.value = ''; return refreshCloud({ page: 1, keyword: '', category: '', sort: 'UPDATED_DESC' }) }
function chooseCloudSort(event) {
  if (!identity() || cloudLoading.value) return
  const index = Number(event?.detail?.value)
  if (!Number.isInteger(index) || index < 0 || index >= CLOUD_SORT_OPTIONS.length) return
  return refreshCloud({ page: 1, sort: CLOUD_SORT_OPTIONS[index].code })
}
function changeCloudPage(direction) {
  if (cloudLoading.value || cloudStale.value || ![-1, 1].includes(direction)) return
  const next = cloudPage.value + direction
  if (next < 1 || next > cloudPages.value) return
  return refreshCloud({ page: next })
}
function toggleCloud() {
  if (!identity()) return
  cloudOpened.value = !cloudOpened.value
  if (cloudOpened.value) refreshCloud()
}
async function uploadBackup(item) {
  const actor = identity(); if (!actor) return
  busy.value = true; cloudOpened.value = true; cloudError.value = ''; cloudMessage.value = ''; invalidateCloud()
  try {
    const snapshot = currentRecord(actor, item)
    await backupDraft(snapshot)
    if (stillCurrent(actor)) cloudMessage.value = '这份本机版本已备份；本机草稿仍保留'
  } catch (cause) {
    if (stillCurrent(actor)) cloudError.value = (cause.message || '备份失败') + '；请刷新云端确认。重试同一份本机版本不会新增重复备份。'
  } finally { busy.value = false }
  // 只重新读取结果，失败后不自动重复写入。
  if (stillCurrent(actor) && !cloudError.value) await refreshCloud({ page: 1 })
}
async function latestCloud(actor, item) {
  // 读取具体草稿，不用当前搜索页判断它是否仍存在。
  const latest = await getDraftBackup(item.draftId)
  if (!stillCurrent(actor)) return null
  if (latest.version !== item.version) throw new Error('云端备份已变化，请重新选择')
  cloudStale.value = false
  return latest
}
async function restoreCloud(item) {
  const actor = identity(); if (!actor || cloudLoading.value || cloudStale.value) return
  busy.value = true; cloudError.value = ''; cloudMessage.value = ''; invalidateCloud()
  try {
    const before = JSON.stringify(draftContent(props.draft))
    if (hasDraftContent(props.draft) && before !== JSON.stringify(item.content) &&
        !await confirm('载入云端备份？', '将替换表单中尚未保存的内容。需要保留时，请先取消并保存当前内容。')) { if (stillCurrent(actor)) cloudStale.value = false; return }
    if (!stillCurrent(actor)) return
    const latest = await latestCloud(actor, item)
    if (!latest || !stillCurrent(actor)) return
    if (JSON.stringify(draftContent(props.draft)) !== before) throw new Error('发布表单已变化，请重新选择')
    emit('restore-cloud', { ...latest, userId: actor.userId }); cloudMessage.value = '已载入表单，尚未发布；保存时将创建新的本机草稿'
  } catch (cause) { if (stillCurrent(actor)) { cloudError.value = cause.message || '载入失败'; cloudStale.value = true } }
  finally { busy.value = false }
}
async function removeCloud(item) {
  const actor = identity(); if (!actor || cloudLoading.value || cloudStale.value) return
  busy.value = true; cloudError.value = ''; cloudMessage.value = ''
  try {
    if (!await confirm('删除这份云端备份？', '删除后无法恢复。本机草稿和发布表单会保留。')) return
    if (!stillCurrent(actor)) return
    invalidateCloud(); await deleteDraftBackup(item.draftId, item.version)
    if (stillCurrent(actor)) cloudMessage.value = '云端备份已删除；本机草稿和表单保留'
  } catch (cause) { if (stillCurrent(actor)) { cloudError.value = (cause.message || '删除失败') + '；请刷新云端确认'; cloudStale.value = true } }
  finally { busy.value = false }
  if (stillCurrent(actor) && !cloudError.value) await refreshCloud()
}
watch(currentUser, () => { invalidateCloud(); cloudSort.value = 'UPDATED_DESC'; cloudCategory.value = ''; cloudKeywordInput.value = ''; cloudKeyword.value = ''; cloudPage.value = 1; cloudPages.value = 1; cloudTotal.value = 0; cloudRecords.value = []; cloudOpened.value = false; cloudError.value = ''; cloudMessage.value = ''; records.value = []; opened.value = false; message.value = ''; error.value = ''; reload() })
onMounted(reload)
onUnmounted(() => { disposed = true; invalidateCloud() })
</script>

<style scoped>
.draft-box { padding: 24rpx; margin-top: 24rpx; background: #f5f7fa; border-radius: 12rpx; }
.sort-selection { padding: 18rpx; border: 1px solid #ddd; border-radius: 10rpx; background: white; }
input { background: white; padding: 16rpx; margin-top: 16rpx; border: 1px solid #d0d5dd; border-radius: 8rpx; }
.heading { display: block; font-weight: bold; font-size: 30rpx; }
.hint, .error { display: block; margin: 16rpx 0; font-size: 24rpx; color: #666; }
.error { color: #b42318; }
.draft-item { padding: 20rpx; margin-top: 20rpx; background: white; border-radius: 10rpx; }
</style>

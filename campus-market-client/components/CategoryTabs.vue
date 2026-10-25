<template>
  <view class="category-tabs">
    <scroll-view class="category-scroll" scroll-x :show-scrollbar="false">
      <view class="category-track"><button v-for="item in options" :key="item.code" class="category-tab" :class="{ selected: modelValue === item.code }" :disabled="disabled || loading" :aria-pressed="modelValue === item.code" @click="choose(item.code)"><MarketIcon :name="iconFor(item.code)" /><text>{{ item.name }}</text></button></view>
    </scroll-view>
    <view v-if="error" class="category-error"><text>{{ error }}</text><button :disabled="disabled || loading" @click="load">重试分类</button></view>
    <text v-if="loading" class="category-loading">正在加载分类…</text>
  </view>
</template>
<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import MarketIcon from '@/components/MarketIcon.vue'
import { getCategories } from '@/api/category.js'
const props = defineProps({ modelValue: { type: String, default: '' }, disabled: { type: Boolean, default: false } })
const emit = defineEmits(['update:modelValue'])
const items = ref([]), loading = ref(false), error = ref('')
let disposed = false
const options = computed(() => [{ code: '', name: '全部好物' }, ...items.value])
function iconFor(code) {
  if (code === '') return props.modelValue === '' ? 'grid-white' : 'grid'
  return { BOOK: 'book', DIGITAL: 'laptop', DAILY: 'bed', SPORT: 'bike', OTHER: 'bag', UNCLASSIFIED: 'box' }[code] || 'box'
}
function choose(code) {
  if (disposed || props.disabled || loading.value || !options.value.some(item => item.code === code)) return
  emit('update:modelValue', code)
}
async function load() {
  if (loading.value || disposed) return
  loading.value = true; error.value = ''
  try { const result = await getCategories(); if (!disposed) items.value = result }
  catch (cause) { if (!disposed) { items.value = []; error.value = cause.message || '分类暂不可用' } }
  finally { if (!disposed) loading.value = false }
}
onMounted(load)
onUnmounted(() => { disposed = true })
</script>
<style scoped>
.category-tabs { margin-top: 15px; }
.category-scroll { width: 100%; white-space: nowrap; }
.category-track { display: flex; align-items: center; gap: 13px; padding: 4px 2px 8px; }
.category-tab { flex: 1; flex-shrink: 0; display: flex; align-items: center; justify-content: center; gap: 11px; margin: 0; padding: 0 17px; min-height: 48px; line-height: 46px; border: 1px solid #ffffff; border-radius: 28px; background: #fff; color: #667e9f; font-size: 14px; white-space: nowrap; box-shadow: 0 3px 15px #26436f03; transition: background 180ms ease,color 180ms ease,box-shadow 180ms ease; }
.category-tab :deep(.market-icon) { width: 27px; height: 27px; }
.category-tab.selected { background: #2463f5; border-color: #2463f5; color: #fff; box-shadow: 0 4px 12px #2463f525; }
.category-tab.selected :deep(.market-icon) { filter: brightness(0) invert(1); }
.category-loading, .category-error { color: #8799b2; font-size: 12px; padding: 8px 2px; }
.category-error { display: flex; align-items: center; gap: 12px; }
.category-error button { color: #2463f5; font-size: 12px; padding: 0 10px; line-height: 27px; min-height: 28px; margin: 0; }
@media (max-width:1050px) { .category-track { gap: 10px; } .category-tab { padding: 0 12px; font-size: 12px; gap: 8px; } .category-tab :deep(.market-icon) { width: 23px; height: 23px; } }
@media (max-width:760px) { .category-tabs { margin-top: 8px; margin-left: -2px; margin-right: -14px; } .category-track { gap: 9px; padding: 4px 14px 8px 2px; } .category-tab { flex: 0 0 auto; min-height: 40px; line-height: 38px; padding: 0 15px; gap: 8px; font-size: 12px; } .category-tab :deep(.market-icon) { width: 23px; height: 23px; } }
/* #ifdef H5 */
@media (prefers-reduced-motion:reduce) { .category-tab { transition: none; } }
@media (hover:hover) { .category-tab:not(.selected):hover { color: #2463f5; border-color: #d8e5ff; } }
/* #endif */
</style>

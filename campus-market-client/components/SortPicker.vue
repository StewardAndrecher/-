<template>
  <view class="sort-picker" :class="{ compact }">
    <text v-if="!compact" class="label">商品排序</text>
    <picker :range="items" range-key="name" :value="selectedIndex"
      :disabled="disabled || loading || !!error || !items.length" @change="choose">
      <view class="selection">{{ loading ? '加载中…' : (compact && modelValue === 'NEWEST' ? '价格 / 更多' : selectedName) }} ▾</view>
    </picker>
    <view v-if="error">
      <text class="error">{{ error }}</text>
      <button size="mini" :disabled="disabled || loading" @click="load">重试加载排序</button>
    </view>
  </view>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { getProductSorts } from '@/api/sort.js'
const props = defineProps({
  modelValue: { type: String, default: 'NEWEST' },
  disabled: { type: Boolean, default: false },
  compact: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue'])
const items = ref([])
const loading = ref(false)
const error = ref('')
let disposed = false
const selectedIndex = computed(() => items.value.findIndex(item => item.code === props.modelValue))
const selectedName = computed(() => items.value[selectedIndex.value]?.name || '请选择排序')
async function load() {
  if (loading.value || disposed) return
  loading.value = true
  error.value = ''
  try {
    const result = await getProductSorts()
    if (!disposed) items.value = result
  } catch (cause) {
    if (!disposed) { items.value = []; error.value = cause.message || '加载排序失败' }
  } finally { if (!disposed) loading.value = false }
}
function choose(event) {
  if (disposed || props.disabled || loading.value || error.value) return
  const item = items.value[Number(event.detail.value)]
  if (item) emit('update:modelValue', item.code)
}
onMounted(load)
onUnmounted(() => { disposed = true })
</script>

<style scoped>
.sort-picker { margin: 16px 0; min-width: 0; }
.label { display: block; margin-bottom: 8px; font-size: 12px; color: #7788a1; }
picker { min-width: 0; }
.selection { padding: 10px 12px; min-height: 40px; border: 1px solid #e1e7f1; border-radius: 8px; background: #f9fafd; color: #526681; font-size: 12px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.error { display: block; color: #b42318; font-size: 12px; margin: 10px 0; }
.compact { margin: 0; }
.compact .selection { padding: 5px 0; min-height: 24px; border: 0; border-radius: 0; background: transparent; font-size: 13px; color: #8093b1; }
.compact .error { font-size: 10px; }
@media (max-width:760px) { .compact .selection { font-size: 11px; } }
</style>

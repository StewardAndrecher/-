<template>
  <view class="sort-picker">
    <text class="label">商品排序</text>
    <picker :range="items" range-key="name" :value="selectedIndex"
      :disabled="disabled || loading || !!error || !items.length" @change="choose">
      <view class="selection">{{ loading ? '正在加载排序…' : selectedName }} ▾</view>
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
  disabled: { type: Boolean, default: false }
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
.sort-picker { margin: 20rpx 0; }
.label { display: block; margin-bottom: 12rpx; font-size: 28rpx; }
.selection { padding: 18rpx; border: 1px solid #ddd; border-radius: 10rpx; background: #fff; }
.error { display: block; color: #b42318; font-size: 26rpx; margin: 10rpx 0; }
</style>

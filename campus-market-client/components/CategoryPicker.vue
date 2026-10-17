<template>
  <view class="category-picker">
    <text class="label">{{ label }}</text>
    <picker :range="options" range-key="name" :value="selectedIndex"
      :disabled="disabled || loading || !!error || !items.length" @change="choose">
      <view class="selection">{{ loading ? '正在加载分类…' : selectedName }} ▾</view>
    </picker>
    <view v-if="error">
      <text class="error">{{ error }}</text>
      <button size="mini" :disabled="disabled || loading" @click="load">重试加载分类</button>
    </view>
  </view>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { getCategories } from '@/api/category.js'
const props = defineProps({
  modelValue: { type: String, default: '' },
  includeAll: { type: Boolean, default: false },
  disabled: { type: Boolean, default: false },
  label: { type: String, default: '商品分类' }
})
const emit = defineEmits(['update:modelValue', 'ready'])
const items = ref([])
const loading = ref(false)
const error = ref('')
const options = computed(() => props.includeAll
  ? [{ code: '', name: '全部分类' }, ...items.value] : items.value)
const selectedIndex = computed(() => options.value.findIndex(item => item.code === props.modelValue))
const selectedName = computed(() => options.value[selectedIndex.value]?.name || '请选择分类')
async function load() {
  if (loading.value) return
  loading.value = true
  error.value = ''
  emit('ready', false)
  try { items.value = await getCategories(); emit('ready', true) }
  catch (cause) { items.value = []; error.value = cause.message || '加载分类失败' }
  finally { loading.value = false }
}
function choose(event) {
  if (props.disabled || loading.value || error.value) return
  const item = options.value[Number(event.detail.value)]
  if (item) emit('update:modelValue', item.code)
}
onMounted(load)
</script>

<style scoped>
.category-picker { margin: 20rpx 0; }
.label { display: block; margin-bottom: 12rpx; font-size: 28rpx; }
.selection { padding: 18rpx; border: 1px solid #ddd; border-radius: 10rpx; background: #fff; }
.error { display: block; color: #b42318; font-size: 26rpx; margin: 10rpx 0; }
</style>

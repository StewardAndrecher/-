<template>
  <view class="picker">
    <text class="picker-label">商品封面 <text class="optional">可选</text></text>
    <text class="picker-hint">PNG / JPG · 最大5MB · 宽高不超过2000像素</text>
    <image v-if="imageUrl(modelValue)" class="preview" :src="imageUrl(modelValue)" mode="aspectFit" />
    <button size="mini" :disabled="disabled || uploading" @click="choose">
      {{ uploading ? '上传中…' : '选择封面图片' }}
    </button>
    <button v-if="modelValue" size="mini" :disabled="disabled || uploading" @click="remove">移除封面</button>
    <text v-if="errorMessage" class="error">{{ errorMessage }}</text>
  </view>
</template>

<script setup>
import { ref, onUnmounted } from 'vue'
import { imageUrl, uploadImage } from '@/api/image.js'
import { getToken } from '@/utils/session.js'

const props = defineProps({ modelValue: { type: String, default: '' }, disabled: Boolean })
const emit = defineEmits(['update:modelValue', 'busy'])
const uploading = ref(false)
const errorMessage = ref('')
let closed = false

function finish() { uploading.value = false; if (!closed) emit('busy', false) }
function choose() {
  if (props.disabled || uploading.value) return
  const token = getToken()
  if (!token) { errorMessage.value = '请先登录后上传图片'; return }
  errorMessage.value = ''; uploading.value = true; emit('busy', true)
  uni.chooseImage({
    count: 1, sourceType: ['album', 'camera'], sizeType: ['compressed'],
    success: async (res) => {
      try {
        if (closed || token !== getToken()) return
        if (!res.tempFilePaths?.[0]) throw new Error('没有获取到图片文件')
        if (res.tempFiles?.[0]?.size > 5 * 1024 * 1024) throw new Error('图片不能超过5MB')
        const result = await uploadImage(res.tempFilePaths[0])
        if (!closed && token === getToken()) emit('update:modelValue', result.id)
      } catch (error) {
        if (!closed) errorMessage.value = error.message || '上传失败'
      } finally { finish() }
    },
    fail: (error) => {
      if (!closed && !String(error.errMsg).includes('cancel')) errorMessage.value = '选择图片失败，请重试'
      finish()
    }
  })
}
function remove() {
  if (props.disabled || uploading.value) return
  errorMessage.value = ''; emit('update:modelValue', '')
}
onUnmounted(() => { closed = true })
</script>

<style scoped>
.picker { margin: 20px 0; padding: 14px; border: 1px dashed #cedbf0; border-radius: 10px; background: #fbfcff; }
.picker-label { display: block; color: #5c708f; font-size: 12px; font-weight: 600; }
.optional { font-size: 10px; color: #9ba9bc; font-weight: 400; margin-left: 5px; }
.picker-hint { display: block; color: #97a4b8; font-size: 10px; line-height: 1.8; margin: 7px 0 9px; }
.preview { display: block; width: 100%; height: 160px; margin: 12px 0; border-radius: 8px; }
.error { display: block; color: #b42318; font-size: 12px; margin-top: 10px; }
button { margin: 4px 6px 0 0; font-size: 11px; min-height: 31px; line-height: 29px; }
</style>

<template>
  <view class="picker">
    <text>商品封面（可选，PNG/JPG，最大5MB、宽高不超过2000像素）</text>
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
.picker { margin: 24rpx 0; }
.preview { display: block; width: 100%; height: 280rpx; margin: 20rpx 0; }
.error { display: block; color: #d93025; margin-top: 16rpx; }
button { margin-top: 16rpx; }
</style>

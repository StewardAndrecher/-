<template>
 <view class="media-picker">
  <view class="media-heading"><text class="media-title">{{ label }}</text><text class="media-count">{{ modelValue.length }} / {{ limit }}</text></view>
  <text class="media-hint">{{ limit > 1 ? '第一张作为封面，可点击「设为封面」调整。' : '选择一张清晰的图片。' }} PNG / JPG，每张最大 5MB、宽高不超过 2000 像素。</text>
  <view v-if="modelValue.length" class="media-grid" :class="{ avatar: limit === 1 }">
   <view v-for="(id,index) in modelValue" :key="id" class="media-tile">
    <image :src="imageUrl(id)" mode="aspectFill" @click="preview(id)" />
    <button class="media-remove" :disabled="disabled || uploading || !active" :aria-label="'移除第' + (index + 1) + '张图片'" @click="remove(index)">×</button>
    <button v-if="limit > 1" class="media-cover" :class="{ selected: index === 0 }" :disabled="disabled || uploading || !active || index === 0" @click="makeCover(index)">{{ index === 0 ? '封面' : '设为封面' }}</button>
   </view>
  </view>
  <button class="media-add" :disabled="disabled || uploading || !active || modelValue.length >= limit" @click="choose">{{ uploading ? '正在上传图片…' : modelValue.length >= limit ? '图片已达到上限' : '+ 选择图片' }}</button>
  <text v-if="error" class="media-error">{{ error }}</text>
 </view>
</template>
<script setup>
import { ref, watch, onUnmounted } from 'vue'
import { imageUrl, uploadImage } from '@/api/image.js'
import { getToken,currentUser } from '@/utils/session.js'
const props = defineProps({ modelValue: { type: Array, default: () => [] }, limit: { type: Number, default: 6 }, label: { type: String, default: '商品图片' }, disabled: Boolean, active: { type: Boolean, default: true } })
const emit = defineEmits(['update:modelValue', 'busy'])
const uploading = ref(false), error = ref('')
let closed = false, generation = 0
function invalidate() { generation++; uploading.value = false; error.value = ''; if (!closed) emit('busy', false) }
watch(() => [props.active,currentUser.value?.id,getToken()], (next,previous) => { if(!previous || next.some((value,index) => value !== previous[index])) invalidate() }, { flush: 'sync' })
function valid(turn, token) { return !closed && props.active && turn === generation && token === getToken() }
function choose() {
 if (closed || !props.active || props.disabled || uploading.value || props.modelValue.length >= props.limit) return
 const token = getToken(); if (!token) { error.value = '请先登录后上传图片'; return }
 const turn = ++generation, original = [...props.modelValue], remaining = props.limit - original.length
 error.value = ''; uploading.value = true; emit('busy', true)
 function finish() { if (valid(turn, token)) { uploading.value = false; emit('busy', false) } }
 uni.chooseImage({ count: remaining, sourceType: ['album', 'camera'], sizeType: ['compressed'],
  success: async result => {
   const added = []
   try {
    if (!valid(turn, token)) return
    const paths = result.tempFilePaths
    if (!Array.isArray(paths) || !paths.length || paths.length > remaining) throw new Error('请选择有效数量的图片')
    for (let index = 0; index < paths.length; index++) {
     if (!valid(turn, token)) return
     if (result.tempFiles?.[index]?.size > 5 * 1024 * 1024) throw new Error('每张图片不能超过5MB')
     const uploaded = await uploadImage(paths[index])
     if (!valid(turn, token)) return
     added.push(uploaded.id)
    }
   } catch (failure) { if (valid(turn, token)) error.value = failure.message || '上传失败，请重试' }
   finally { if (valid(turn, token) && added.length) emit('update:modelValue', [...original, ...added]); finish() }
  },
  fail: failure => { if (valid(turn, token) && !String(failure.errMsg).includes('cancel')) error.value = '选择图片失败，请重试'; finish() }
 })
}
function remove(index) { if (!closed && props.active && !props.disabled && !uploading.value) emit('update:modelValue', props.modelValue.filter((_, number) => number !== index)) }
function makeCover(index) { if (!closed && props.active && !props.disabled && !uploading.value && index > 0 && index < props.modelValue.length) emit('update:modelValue', [props.modelValue[index], ...props.modelValue.filter((_, number) => number !== index)]) }
function preview(id) { if (!closed && props.active && !uploading.value) uni.previewImage({ current: imageUrl(id), urls: props.modelValue.map(imageUrl) }) }
onUnmounted(() => { invalidate(); closed = true })
</script>
<style scoped>
.media-picker{padding:18px;border:1px dashed #c8d8f2;border-radius:16px;background:#f8fbff}.media-heading{display:flex;justify-content:space-between;align-items:center}.media-title{font-size:14px;font-weight:650;color:#213b64}.media-count,.media-hint{font-size:11px;color:#788ba6}.media-hint{display:block;line-height:1.8;margin:8px 0 14px}.media-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:9px;margin-bottom:12px}.media-tile{position:relative;aspect-ratio:1;min-height:85px;border-radius:12px;overflow:hidden;background:#eaf0f8}.media-tile image{width:100%;height:100%;position:absolute}.media-remove{position:absolute;right:5px;top:5px;width:25px;height:25px;min-height:25px;line-height:23px;border:0;border-radius:50%;background:#fff;color:#28416b;padding:0;margin:0;font-size:18px}.media-cover{position:absolute;left:0;bottom:0;width:100%;padding:3px 0;min-height:25px;line-height:22px;border:0;border-radius:0;margin:0;background:#ffffffed;color:#506789;font-size:10px}.media-cover.selected{background:#2463f5;color:#fff}.media-add{border:1px solid #d1def4;background:#fff;color:#2463f5;border-radius:9px;font-size:12px;min-height:37px;line-height:35px;margin:0}.media-error{display:block;color:#b42318;font-size:12px;line-height:1.7;margin-top:10px}.avatar .media-tile{max-width:110px}.avatar{grid-template-columns:110px}.media-remove:after,.media-cover:after,.media-add:after{border:0}
</style>

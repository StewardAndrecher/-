<template>
 <view class="details-fields">
  <view class="details-row">
   <view class="detail-field"><text class="field-label">所在校区</text><picker :range="campuses" range-key="name" :value="campusIndex" :disabled="disabled || !active" @change="select('campusCode',campuses,$event)"><view class="details-select">{{ campusName(modelValue.campusCode) }} <text>⌄</text></view></picker></view>
   <view class="detail-field"><text class="field-label">商品成色</text><picker :range="conditions" range-key="name" :value="conditionIndex" :disabled="disabled || !active" @change="select('conditionCode',conditions,$event)"><view class="details-select">{{ conditionName(modelValue.conditionCode) }} <text>⌄</text></view></picker></view>
  </view>
  <text class="field-label">交接地点 <text class="optional">可选</text></text>
  <input class="location-field" :value="modelValue.handoverLocation" :disabled="disabled || !active" :maxlength="80" placeholder="例如：东校区图书馆门口" @input="locationInput" />
  <text class="field-hint">填写公共交接地点，请勿填写宿舍门牌、手机号等个人信息。</text>
  <MediaPicker :model-value="modelValue.imageIds" :disabled="disabled" :active="active" @update:modelValue="imagesChanged" @busy="$emit('busy',$event)" />
 </view>
</template>
<script setup>
import { computed } from 'vue'
import MediaPicker from '@/components/MediaPicker.vue'
import { campuses, conditions, campusName, conditionName, legacyDetails } from '@/utils/product-details.mjs'
const props = defineProps({ modelValue: { type: Object, default: () => legacyDetails() }, disabled: Boolean, active: { type: Boolean, default: true } })
const emit = defineEmits(['update:modelValue','cover','busy'])
const campusIndex = computed(() => Math.max(0,campuses.findIndex(item => item.code === props.modelValue.campusCode)))
const conditionIndex = computed(() => Math.max(0,conditions.findIndex(item => item.code === props.modelValue.conditionCode)))
function select(key,options,event) { const index = Number(event?.detail?.value); if (!props.disabled && props.active && Number.isInteger(index) && options[index]) emit('update:modelValue',{...props.modelValue,[key]:options[index].code}) }
function locationInput(event) { if (!props.disabled && props.active) emit('update:modelValue',{...props.modelValue,handoverLocation:event.detail.value}) }
function imagesChanged(imageIds) { emit('update:modelValue',{...props.modelValue,imageIds}); emit('cover',imageIds[0] || '') }
</script>
<style scoped>
.details-fields{margin-top:22px}.details-row{display:grid;grid-template-columns:1fr 1fr;gap:14px}.field-label{display:block;margin:0 0 9px;font-size:13px;font-weight:600;color:#263c5d}.details-select{display:flex;justify-content:space-between;padding:12px 14px;border:1px solid #dbe4f0;border-radius:10px;background:#f8faff;color:#456080;font-size:13px;margin-bottom:20px}.location-field{height:42px;padding:0 13px;border:1px solid #dbe4f0;border-radius:10px;background:#f8faff;font-size:13px}.field-hint{display:block;font-size:10px;color:#8a9bb3;line-height:1.8;margin:8px 0 16px}.optional{font-weight:400;color:#93a1b7;font-size:11px;margin-left:5px}
</style>

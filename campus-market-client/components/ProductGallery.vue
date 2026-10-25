<template>
 <view class="product-gallery">
  <swiper v-if="ids.length" class="gallery-swiper" :indicator-dots="ids.length > 1" :current="current" indicator-color="#bfcee4" indicator-active-color="#2463f5" @change="current = $event.detail.current">
   <swiper-item v-for="id in ids" :key="id"><image class="gallery-image" :src="imageUrl(id)" mode="aspectFit" :aria-label="alt" @click="preview(id)" /></swiper-item>
  </swiper>
  <ProductCover v-else :image-id="''" :category-code="categoryCode" :alt="alt" fit="aspectFit" />
  <text v-if="ids.length > 1" class="gallery-counter">{{ current + 1 }} / {{ ids.length }} · 点击查看大图</text>
 </view>
</template>
<script setup>
import { ref,computed,watch } from 'vue'
import ProductCover from '@/components/ProductCover.vue'
import { imageUrl } from '@/api/image.js'
const props = defineProps({ imageIds: { type:Array,default:()=>[] }, coverImageId: {type:String,default:''}, categoryCode:String,alt:String })
const current=ref(0),ids=computed(()=>props.imageIds.length?props.imageIds:props.coverImageId?[props.coverImageId]:[])
watch(()=>ids.value.join(','),()=>{current.value=0})
function preview(id){uni.previewImage({current:imageUrl(id),urls:ids.value.map(imageUrl)})}
</script>
<style scoped>
.product-gallery{position:relative;width:100%;height:100%;min-height:280px;background:#edf3fa;border-radius:16px;overflow:hidden}.gallery-swiper{height:100%;min-height:340px}.gallery-image{width:100%;height:100%}.gallery-counter{position:absolute;right:12px;bottom:12px;background:#ffffffeb;color:#5a7295;border-radius:20px;padding:6px 12px;font-size:11px}@media(max-width:650px){.product-gallery,.gallery-swiper{min-height:280px}}
</style>

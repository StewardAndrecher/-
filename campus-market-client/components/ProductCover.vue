<template>
  <view class="product-cover">
    <image v-if="source && !failed" class="cover-image" :src="source" :mode="fit" :aria-label="alt" @error="failed = true" />
    <image v-else class="cover-image placeholder-image" :src="placeholder" mode="aspectFill" aria-label="商品分类插画，暂无封面" />
    <text v-if="!source || failed" class="placeholder-caption">{{ source ? '封面暂不可用' : '好物待你发现' }}</text>
  </view>
</template>
<script setup>
import { computed, ref, watch } from 'vue'
import { imageUrl } from '@/api/image.js'
const props = defineProps({ imageId: { type: String, default: '' }, categoryCode: { type: String, default: '' }, alt: { type: String, default: '商品封面' }, fit: { type: String, default: 'aspectFill' } })
const failed = ref(false)
const source = computed(() => imageUrl(props.imageId))
const placeholder = computed(() => '/static/ui/placeholder-' + ({ BOOK: 'book', DIGITAL: 'digital', DAILY: 'daily', SPORT: 'sport' }[props.categoryCode] || 'other') + '.png')
watch(source, () => { failed.value = false })
</script>
<style scoped>
.product-cover { position: relative; width: 100%; height: 100%; overflow: hidden; background: #edf2fa; }
.cover-image { display: block; width: 100%; height: 100%; }
.placeholder-caption { position: absolute; bottom: 12px; left: 0; right: 0; text-align: center; font-size: 10px; letter-spacing: 2px; color: #7487a2; }
@media (max-width: 500px) { .placeholder-caption { font-size: 9px; bottom: 8px; letter-spacing: 1px; } }
</style>

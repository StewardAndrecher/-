<template><image class="market-icon" :src="'/static/ui/icons/' + resolvedName + '.png'" mode="aspectFit" aria-hidden="true" /></template>
<script setup>
import { computed } from 'vue'
const props = defineProps({ name: { type: String, default: 'grid' } })
const names = ['grid','grid-white','book','laptop','bed','bike','bag','box','search','refresh','heart','heart-filled','chevron-down','chevron-left','chevron-right','plus-white','close-white','home-filled','clock','user','people','pin','leaf']
const resolvedName = computed(() => names.includes(props.name) ? props.name : 'grid')
</script>
<style scoped>
.market-icon { width: 24px; height: 24px; display: block; flex-shrink: 0; }
</style>

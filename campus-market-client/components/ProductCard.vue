<template>
  <view class="card" :class="{ compact }" @click="requestOpenDetail">
    <view class="card-visual"><ProductCover :image-id="product.coverImageId || ''" :category-code="product.category?.code || ''" :alt="product.title" /><button v-if="compact && showFavoriteButton" class="floating-favorite" role="button" tabindex="0" @keydown.enter.stop.prevent="requestToggleFavorite" @keydown.space.stop.prevent="requestToggleFavorite" :aria-label="favoritePending ? '正在同步收藏' : (isFavorite ? '取消收藏：' + product.title : '收藏：' + product.title)" :aria-pressed="isFavorite" :disabled="favoriteDisabled" @click.stop="requestToggleFavorite"><text v-if="favoritePending" class="favorite-pending">···</text><MarketIcon v-else :name="isFavorite ? 'heart-filled' : 'heart'" /></button><text class="category-tag">{{ product.details?.conditionCode && product.details.conditionCode !== 'UNSPECIFIED' ? conditionName(product.details.conditionCode) : (product.category?.name || '未分类') }}</text></view>
    <view class="card-content">
      <button class="title" role="button" tabindex="0" @keydown.enter.stop.prevent="requestOpenDetail" @keydown.space.stop.prevent="requestOpenDetail" @click.stop="requestOpenDetail">{{ product.title }}</button>
      <text v-if="!compact" class="description">{{ product.description || '点开看看这件校园好物' }}</text>
      <text v-if="compact && product.details && (product.details.campusCode !== 'UNSPECIFIED' || product.details.conditionCode !== 'UNSPECIFIED')" class="product-facts">{{ conditionName(product.details.conditionCode) }} · {{ campusName(product.details.campusCode) }}</text>
      <view class="price-line"><text class="price"><text class="currency">¥</text>{{ compact ? formatPrice(product.priceInCents) : (product.priceInCents / 100).toFixed(2) }}</text><FavoriteCount :value="product.favoriteCount" :stale="statsStale" /></view>
      <view class="seller-line"><view class="seller-avatar"><image v-if="imageUrl(product.seller?.avatarImageId)" :src="imageUrl(product.seller.avatarImageId)" mode="aspectFill" /><text v-else>{{ product.seller ? Array.from(product.seller.nickname)[0] : '校' }}</text></view><button v-if="compact && showSellerButton && product.seller" class="seller seller-link" :aria-label="'查看卖家主页：' + product.seller.nickname" @click.stop="requestOpenSeller">{{ product.seller.nickname }}</button><text v-else class="seller">{{ product.seller ? product.seller.nickname : '历史商品，卖家未登记' }}</text><text v-if="isFavorite" class="saved-dot">已收藏</text></view>
      <view v-if="!compact" class="card-actions">
        <button v-if="showSellerButton && product.seller" size="mini" class="seller-button" @click.stop="requestOpenSeller">查看卖家主页</button>
        <button v-if="showFavoriteButton" size="mini" class="favorite-button" :class="{ 'is-favorite': isFavorite }" :disabled="favoriteDisabled" @click.stop="requestToggleFavorite">{{ favoritePending ? '同步中…' : (isFavorite ? '取消收藏' : '收藏商品') }}</button>
      </view>
    </view>
  </view>
</template>

<script setup>
import { campusName, conditionName } from '@/utils/product-details.mjs'
import { imageUrl } from '@/api/image.js'
import MarketIcon from '@/components/MarketIcon.vue'
import ProductCover from '@/components/ProductCover.vue'
import FavoriteCount from '@/components/FavoriteCount.vue'
import { parseSellerId } from '@/utils/seller-store.js'
const props = defineProps({
  product: {
    type: Object,
    required: true
  },
  compact: { type: Boolean, default: false },
  showSellerButton: { type: Boolean, default: false },
  showFavoriteButton: { type: Boolean, default: true },
  statsStale: { type: Boolean, default: false },
  favoriteDisabled: { type: Boolean, default: false },
  favoritePending: { type: Boolean, default: false },
  isFavorite: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits([
  'toggle-favorite',
  'open-detail',
  'open-seller'
])

function formatPrice(cents) {
  return (cents / 100).toFixed(2).replace(/\.00$/, '').replace(/(\.\d)0$/, '$1')
}

function requestOpenDetail() {
  emit('open-detail', props.product.id)
}

function requestOpenSeller() {
  const id = parseSellerId(props.product.seller?.id)
  if (props.showSellerButton && id) emit('open-seller', id)
}

function requestToggleFavorite() {
  if (props.showFavoriteButton && !props.favoriteDisabled) emit('toggle-favorite', props.product.id)
}
</script>

<style scoped>
.product-facts{display:block;font-size:11px;color:#8a9db8;margin:5px 0}.seller-avatar image{width:100%;height:100%;border-radius:50%}
.card { margin: 0 0 20px; background: #fff; border: 1px solid #e4eaf3; border-radius: 16px; overflow: hidden; box-shadow: 0 3px 12px #233d6b04; cursor: pointer; transition: transform .2s, box-shadow .2s; }
.card-visual { height: 205px; position: relative; overflow: hidden; background: #edf2fa; }
.category-tag { position: absolute; top: 12px; left: 12px; padding: 4px 9px; border: 1px solid #ffffff90; border-radius: 6px; background: #ffffffed; color: #5d7394; font-size: 10px; line-height: 1.5; }
.card-content { padding: 17px 18px 16px; }
.title { min-height: 0; padding: 0; margin: 0; border: 0; border-radius: 0; background: transparent; text-align: left; width: 100%; display: block; color: #1c2e4b; font-size: 16px; font-weight: 650; line-height: 1.5; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.description { display: block; height: 36px; line-height: 18px; color: #94a0b2; font-size: 11px; margin-top: 7px; overflow: hidden; overflow-wrap: anywhere; }
.price-line { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin: 12px 0 14px; }
.price { color: #e07b43; font-size: 22px; font-weight: 650; letter-spacing: -.6px; line-height: 1; }
.currency { font-size: 13px; margin-right: 3px; font-weight: 500; }
.price-line :deep(.count) { margin: 0; font-size: 10px; color: #9aa7ba; }
.seller-line { display: flex; align-items: center; gap: 7px; min-height: 23px; }
.seller-avatar { height: 22px; width: 22px; display: flex; justify-content: center; align-items: center; border-radius: 50%; background: #edf3ff; color: #6a8abb; font-size: 10px; flex-shrink: 0; }
.seller { font-size: 11px; color: #8493aa; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.saved-dot { font-size: 10px; color: #477b67; margin-left: auto; flex-shrink: 0; }
.card-actions { display: flex; gap: 8px; margin-top: 13px; padding-top: 12px; border-top: 1px solid #f0f3f8; }
.card-actions button { margin: 0; font-size: 10px; padding: 0 10px; min-height: 29px; line-height: 27px; border-radius: 7px; }
.seller-button { color: #7b8da8; border-color: #e5eaf3; }
.favorite-button { margin-left: auto !important; color: #6087c5; background: #f6f9ff; border-color: #dde8fa; }
.favorite-button.is-favorite { color: #2865e8; background: #edf4ff; border-color: #bed3fa; }
/* #ifdef H5 */
@media (hover:hover) { .card:hover { transform: translateY(-3px); box-shadow: 0 10px 25px #2648730c; } }
@media (prefers-reduced-motion:reduce) { .card { transition: none; } .card:hover { transform: none; } }
/* #endif */
@media (max-width:760px) { .card { margin-bottom: 14px; border-radius: 13px; } .card-visual { height: 180px; } }
@media (max-width:500px) { .card-visual { height: 146px; } .card-content { padding: 13px 11px 12px; } .title { font-size: 13px; } .description { font-size: 10px; margin-top: 5px; } .price { font-size: 19px; } .price-line { gap: 3px; margin: 10px 0 12px; flex-wrap: wrap; } .price-line :deep(.count) { font-size: 9px; } .seller { font-size: 10px; } .seller-avatar { height: 19px; width: 19px; font-size: 9px; } .card-actions { flex-wrap: wrap; gap: 6px; padding-top: 10px; margin-top: 10px; } .card-actions button { font-size: 10px; min-height: 29px; line-height: 27px; padding: 0 8px; } .category-tag { top: 9px; left: 9px; padding: 3px 7px; font-size: 9px; } }
.compact { border-radius: 10px; border-color: #e8eef7; box-shadow: 0 3px 12px #25436a03; }
.compact .card-visual { height: 185px; }
.compact .category-tag { top: 11px; left: 11px; color: #3c506b; font-size: 11px; padding: 4px 9px; border-radius: 6px; }
.floating-favorite { position: absolute; right: 10px; top: 10px; height: 33px; min-height: 33px; width: 33px; line-height: 31px; margin: 0; padding: 0; display: flex; align-items: center; justify-content: center; background: #fffffff2; border: 0; border-radius: 50%; box-shadow: 0 2px 5px #18345a12; }
.floating-favorite :deep(.market-icon) { width: 22px; height: 22px; }
.favorite-pending { color: #2463f5; font-size: 18px; letter-spacing: 1px; }
.compact .card-content { padding: 10px 13px 12px; }
.compact .title { font-size: 15px; line-height: 1.5; }
.compact .price-line { margin: 10px 0 9px; gap: 4px; }
.compact .price { color: #f15b2a; font-size: 24px; font-weight: 700; }
.compact .currency { font-size: 13px; }
.compact .seller-line { gap: 7px; min-height: 22px; }
.compact .seller { color: #8395b2; font-size: 11px; }
.compact .seller-avatar { width: 23px; height: 23px; background: #edf2f9; color: #6a85aa; }
.seller-link { margin: 0; padding: 0; min-height: 22px; line-height: 22px; background: transparent; border: 0; border-radius: 0; text-align: left; max-width: calc(100% - 30px); }
.compact .saved-dot { display: none; }
@media (max-width:1050px) { .compact .card-visual { height: 150px; } .compact .title { font-size: 13px; } .compact .card-content { padding: 10px; } }
@media (max-width:760px) { .compact .card-visual { height: 155px; } .compact .card-content { padding: 9px 10px 10px; } .compact .title { font-size: 13px; } .compact .price { font-size: 23px; } .compact .price-line { margin: 8px 0; flex-wrap: nowrap; } .compact .seller { font-size: 10px; } .compact .seller-avatar { width: 20px; height: 20px; } .compact .category-tag { top: 8px; left: 8px; font-size: 10px; padding: 3px 7px; } .floating-favorite { top: 8px; right: 8px; width: 30px; height: 30px; min-height: 30px; } .floating-favorite :deep(.market-icon) { width: 20px; height: 20px; } .compact .price-line :deep(.count) { font-size: 9px; } }
@media (max-width:400px) { .compact .card-visual { height: 132px; } }
</style>

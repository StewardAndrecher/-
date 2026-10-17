export function isValidFavoriteCount(value) {
  return Number.isSafeInteger(value) && value >= 0
}

export function favoriteCountText(value, stale = false) {
  if (!isValidFavoriteCount(value)) return '收藏人数待同步'
  return `${value} 人收藏${stale ? '（上次查询）' : ''}`
}

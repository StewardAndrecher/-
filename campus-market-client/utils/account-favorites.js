import { ref, watch } from 'vue'
import { currentUser, getToken } from './session.js'
import { getFavoriteIds, setProductFavorite } from '@/api/favorite.js'

export const favoriteIds = ref([])
export const favoriteLoading = ref(false)
export const favoriteBusyId = ref(0)
export const favoriteMessage = ref('')
let readNumber = 0
let writeNumber = 0
let queuedIds = null

watch(() => currentUser.value?.id, () => {
  readNumber++; writeNumber++; queuedIds = null
  favoriteIds.value = []
  favoriteLoading.value = false
  favoriteBusyId.value = 0
  favoriteMessage.value = ''
})

export async function loadAccountFavorites(productIds) {
  if (favoriteBusyId.value > 0) { queuedIds = [...productIds]; return }
  const number = ++readNumber
  const token = getToken()
  favoriteIds.value = []
  favoriteMessage.value = ''
  if (!token || !currentUser.value || productIds.length === 0) {
    favoriteLoading.value = false
    return
  }
  favoriteLoading.value = true
  try {
    const ids = await getFavoriteIds(productIds)
    if (number !== readNumber || token !== getToken()) return
    if (!Array.isArray(ids) || !ids.every(id => Number.isSafeInteger(id) && productIds.includes(id))) {
      throw new Error('收藏状态格式不正确，请刷新')
    }
    favoriteIds.value = [...new Set(ids)]
  } catch (error) {
    if (number === readNumber && (token === getToken() || !getToken())) {
      favoriteMessage.value = error.statusCode === 401 ? '登录已失效，请重新登录'
        : '收藏状态读取失败，请刷新后再操作'
    }
  } finally {
    if (number === readNumber) favoriteLoading.value = false
  }
}

export async function changeAccountFavorite(productId, favorite) {
  if (favoriteLoading.value || favoriteBusyId.value > 0) return false
  const token = getToken()
  if (!token || !currentUser.value) { favoriteMessage.value = '请先登录后收藏'; return false }
  const number = ++writeNumber
  favoriteBusyId.value = productId
  favoriteMessage.value = ''
  try {
    const result = await setProductFavorite(productId, favorite)
    if (number !== writeNumber || token !== getToken()) return false
    if (result?.productId !== productId || result.favorite !== favorite) {
      throw new Error('收藏结果异常，请刷新确认')
    }
    favoriteIds.value = favorite ? [...new Set([...favoriteIds.value, productId])]
      : favoriteIds.value.filter(id => id !== productId)
    return true
  } catch (error) {
    if (number === writeNumber && (token === getToken() || !getToken())) {
      favoriteMessage.value = error.statusCode === 0 ? '未收到收藏结果，请刷新确认后再操作'
        : error.statusCode === 401 ? '登录已失效，请重新登录' : error.message || '收藏操作失败'
    }
    return false
  } finally {
    if (number === writeNumber) {
      favoriteBusyId.value = 0
      if (queuedIds) {
        const ids = queuedIds; queuedIds = null
        await loadAccountFavorites(ids)
      }
    }
  }
}

import { ref } from 'vue'

export const currentUser = ref(null)
let accessToken = ''

export function getToken() {
  return accessToken
}

function isValidUser(user) {
  return user && Number.isSafeInteger(user.id) && user.id > 0 &&
    typeof user.username === 'string' && /^[a-z0-9_]{4,32}$/.test(user.username) &&
    typeof user.nickname === 'string' && !!user.nickname.trim()
}

export function saveSession(result) {
  if (!result || typeof result.accessToken !== 'string' ||
      !/^[A-Za-z0-9_-]{43}$/.test(result.accessToken) ||
      result.tokenType !== 'Bearer' || !Number.isSafeInteger(result.expiresInSeconds) ||
      result.expiresInSeconds <= 0 || !isValidUser(result.user)) {
    throw new Error('登录响应格式不正确')
  }
  accessToken = result.accessToken
  currentUser.value = {
    id: result.user.id, username: result.user.username, nickname: result.user.nickname
  }
}

export function updateCurrentUser(user, expectedToken) {
  if (!isValidUser(user)) throw new Error('用户响应格式不正确')
  if (expectedToken !== accessToken || !accessToken) return
  currentUser.value = { id: user.id, username: user.username, nickname: user.nickname }
}

export function clearSession(expectedToken) {
  if (expectedToken !== undefined && expectedToken !== accessToken) return
  accessToken = ''
  currentUser.value = null
}

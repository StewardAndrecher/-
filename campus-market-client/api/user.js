import { sessionListQuery } from '@/utils/session-list.mjs'
import { request } from './request.js'

export function registerUser(data) {
  return request({ path: '/api/users/register', method: 'POST', data })
}

export function loginUser(data) {
  return request({ path: '/api/users/login', method: 'POST', data })
}

export function getCurrentUser() {
  return request({ path: '/api/users/me', auth: true })
}

export function logoutUser() {
  return request({ path: '/api/users/logout', method: 'POST', auth: true })
}

export function updateMyNickname(data) {
  return request({ path: '/api/users/me/nickname', method: 'PUT', auth: true, data })
}

export function getMySessionSummary() {
  return request({ path: '/api/users/me/sessions', auth: true })
}
export function revokeMyOtherSessions() {
  return request({ path: '/api/users/me/sessions/revoke-others', method: 'POST', auth: true })
}

export function changeMyPassword(data) {
  return request({ path: '/api/users/me/password', method: 'POST', auth: true, data })
}

export function getMyActiveSessions(value = {}) {
  const q = sessionListQuery(value)
  return request({ path: `/api/users/me/sessions/active?page=${q.page}&size=${q.size}`, auth: true })
}

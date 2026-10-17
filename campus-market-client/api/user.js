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

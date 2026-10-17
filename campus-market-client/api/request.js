import { getToken, clearSession } from '@/utils/session.js'

import { API_BASE_URL, SERVER_CONFIG } from '@/config/server.js'
export { API_BASE_URL }

export class ApiError extends Error {
  constructor(message, statusCode, code = '') {
    super(message)
    this.name = 'ApiError'
    this.statusCode = statusCode
    this.code = code
  }
}

export function request({ path, method = 'GET', data, auth = false }) {
  const requestToken = auth ? getToken() : ''
  if (auth && !requestToken) {
    return Promise.reject(new ApiError('请先登录', 401))
  }
  if (SERVER_CONFIG.error) {
    return Promise.reject(new ApiError(SERVER_CONFIG.error, -1, 'CLIENT_CONFIG_ERROR'))
  }
  const header = method === 'GET' ? {} : { 'Content-Type': 'application/json' }
  if (requestToken) header.Authorization = `Bearer ${requestToken}`

  return new Promise((resolve, reject) => {
    uni.request({
      url: `${API_BASE_URL}${path}`, method, data, header,
      dataType: 'json', timeout: 8000,
      success: (res) => {
        if (res.statusCode >= 200 && res.statusCode < 300) {
          resolve(res.data)
          return
        }
        if (auth && res.statusCode === 401) clearSession(requestToken)
        const message = typeof res.data?.message === 'string' && res.data.message.trim()
          ? res.data.message : `请求失败：HTTP ${res.statusCode}`
        const code = typeof res.data?.code === 'string' ? res.data.code : ''
        reject(new ApiError(message, res.statusCode, code))
      },
      fail: () => reject(new ApiError('无法连接后端，请稍后重试', 0))
    })
  })
}

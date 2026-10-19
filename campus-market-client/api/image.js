import { responseRequestId } from '@/utils/request-reference.mjs'
import { API_BASE_URL, ApiError } from './request.js'
import { SERVER_CONFIG } from '@/config/server.js'
import { getToken, clearSession } from '@/utils/session.js'

const ID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
export function imageUrl(id) {
  return !!API_BASE_URL && typeof id === 'string' && ID_PATTERN.test(id) ? `${API_BASE_URL}/api/images/${id}` : ''
}
export function uploadImage(filePath) {
  const token = getToken()
  if (!token) return Promise.reject(new ApiError('请先登录后上传图片', 401))
  if (SERVER_CONFIG.error) return Promise.reject(new ApiError(SERVER_CONFIG.error, -1, 'CLIENT_CONFIG_ERROR'))
  return new Promise((resolve, reject) => {
    uni.uploadFile({
      url: `${API_BASE_URL}/api/images`, filePath, name: 'file', fileType: 'image',
      header: { Authorization: `Bearer ${token}` }, timeout: 15000,
      success: (res) => {
        const status = Number(res.statusCode)
        if (status === 401) clearSession(token)
        let data
        try { data = typeof res.data === 'string' ? JSON.parse(res.data) : res.data }
        catch { data = null }
        if (status >= 200 && status < 300) {
          if (!data || !ID_PATTERN.test(data.id) || data.url !== `/api/images/${data.id}` ||
              !Number.isSafeInteger(data.width) || data.width < 1 || data.width > 2000 ||
              !Number.isSafeInteger(data.height) || data.height < 1 || data.height > 2000) {
            reject(new ApiError('上传结果格式不正确', status, '', responseRequestId(res.header))); return
          }
          resolve(data)
        } else reject(new ApiError(data?.message || `上传失败：HTTP ${status}`, status, '', responseRequestId(res.header)))
      },
      fail: () => reject(new ApiError('图片上传未收到结果，请重新选择后重试', 0))
    })
  })
}

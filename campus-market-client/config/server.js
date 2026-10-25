import { resolveWebEndpoint } from '@/utils/web-endpoint.mjs'

// 开发者工具连接本机；手机开发版连接电脑当前的局域网地址。
export const SERVER_ENDPOINTS = Object.freeze({
  web: 'http://localhost:8080',
  weixinDevelop: 'http://127.0.0.1:8080',
  weixinDevice: 'http://192.168.1.100:8080',
  weixinTrial: '',
  weixinRelease: '',
  appDevelop: 'http://192.168.1.100:8080',
  appRelease: ''
})

export function resolveServerConfig(platform, envVersion = '', endpoints = SERVER_ENDPOINTS, devicePlatform = 'devtools') {
  let endpoint = '', error = ''
  if (platform === 'web') endpoint = endpoints.web
  else if (platform === 'weixin') {
    const deviceKnown = ['devtools', 'ios', 'android', 'ohos', 'harmonyos', 'windows', 'mac'].includes(devicePlatform)
    const key = { develop: devicePlatform === 'devtools' ? 'weixinDevelop' : 'weixinDevice', trial: 'weixinTrial', release: 'weixinRelease' }[envVersion]
    if (envVersion === 'develop' && !deviceKnown) error = '无法识别开发设备，请更新基础库后重新运行'
    if (!key) error = '无法识别小程序运行环境，请重新编译后运行'
    else endpoint = endpoints[key]
  } else if (platform === 'app') {
    if (!['android', 'ios'].includes(devicePlatform)) error = '无法识别App设备，目前支持Android和iOS'
    const key = { develop: 'appDevelop', release: 'appRelease' }[envVersion]
    if (!key) error = '无法识别App运行环境，请重新编译后运行'
    else endpoint = endpoints[key]
  } else error = '当前平台尚未配置后端地址'

  const baseUrl = typeof endpoint === 'string' ? endpoint.trim().replace(/\/+$/, '') : ''
  // 仅接受 origin，不允许把账号、密码、路径或查询参数写入服务器配置。
  const match = /^(https?):\/\/([a-zA-Z0-9.-]+)(?::([0-9]{1,5}))?$/.exec(baseUrl)
  if (!error && (!match || (match[3] && (Number(match[3]) < 1 || Number(match[3]) > 65535)))) {
    error = '后端地址未配置或格式不正确，请检查 config/server.js'
  }
  if (!error && (platform === 'app' && envVersion === 'release' || platform === 'weixin' && envVersion !== 'develop') &&
      (match[1] !== 'https' || match[2].toLowerCase() === 'localhost' || /^[0-9.]+$/.test(match[2]))) {
    error = platform === 'app' ? 'App正式版需配置可访问的 HTTPS 域名' : '体验版和正式版需配置可访问的 HTTPS 域名'
  }
  if (!error && platform === 'app' && envVersion === 'develop' &&
      (match[2].toLowerCase() === 'localhost' || /^127\./.test(match[2]))) {
    error = 'App开发地址应使用电脑可访问的局域网地址，localhost指向手机自身'
  }
  return Object.freeze({ platform, envVersion, devicePlatform, baseUrl: error ? '' : baseUrl, error })
}

let platform = 'unsupported'
let envVersion = ''
let devicePlatform = ''
// #ifdef H5
platform = 'web'
// #endif
// #ifdef MP-WEIXIN
platform = 'weixin'
try {
  envVersion = uni.getAccountInfoSync().miniProgram.envVersion
  if (envVersion === 'develop') devicePlatform = uni.getDeviceInfo().platform
}
catch { envVersion = '' }
// #endif

// #ifdef APP-PLUS
platform = 'app'
envVersion = process.env.NODE_ENV === 'development' ? 'develop'
  : process.env.NODE_ENV === 'production' ? 'release' : ''
try { devicePlatform = uni.getDeviceInfo().platform }
catch { devicePlatform = '' }
// #endif

let effectiveEndpoints = SERVER_ENDPOINTS
let webConfigurationError = ''
// #ifdef H5
const webEndpoint = resolveWebEndpoint(typeof window !== 'undefined' ? window.location : null, SERVER_ENDPOINTS.web)
effectiveEndpoints = { ...SERVER_ENDPOINTS, web: webEndpoint.baseUrl }
webConfigurationError = webEndpoint.error
// #endif
const resolvedConfig = resolveServerConfig(platform, envVersion, effectiveEndpoints, devicePlatform)
export const SERVER_CONFIG = webConfigurationError
  ? Object.freeze({ ...resolvedConfig, baseUrl: '', error: webConfigurationError }) : resolvedConfig
export const API_BASE_URL = SERVER_CONFIG.baseUrl

export const DIAGNOSTICS_ENABLED = platform === 'web' ? process.env.NODE_ENV !== 'production'
  : ['weixin', 'app'].includes(platform) && envVersion === 'develop'

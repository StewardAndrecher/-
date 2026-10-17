export const DESKTOP_PORT = 5188
export const DESKTOP_ORIGIN = `http://127.0.0.1:${DESKTOP_PORT}`
export const API_ORIGINS = Object.freeze(['http://localhost:8080', 'http://127.0.0.1:8080'])
export const WINDOW_PREFERENCES = Object.freeze({
  nodeIntegration: false,
  contextIsolation: true,
  sandbox: true,
  webSecurity: true,
  partition: 'persist:campus-market-desktop'
})
export function isAppURL(value, origin = DESKTOP_ORIGIN) {
  try {
    const url = new URL(value)
    return !url.username && !url.password && url.origin === origin && url.pathname === '/' && !url.search
  } catch { return false }
}
export function isAllowedRequest(value, origin = DESKTOP_ORIGIN) {
  try {
    const url = new URL(value)
    if (url.username || url.password) return false
    if (url.origin === origin) return true
    return API_ORIGINS.includes(url.origin) && url.pathname.startsWith('/api/')
  } catch { return false }
}
export function allowPermission(permission, pageURL, origin = DESKTOP_ORIGIN) {
  return isAppURL(pageURL, origin) && ['clipboard-sanitized-write', 'loopback-network'].includes(permission)
}

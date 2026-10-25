// Public H5 uses its own HTTPS origin. Only local previews may use the developer backend.
export function resolveWebEndpoint(location, developmentEndpoint) {
  if (!location || typeof location.protocol !== 'string' || typeof location.hostname !== 'string') {
    return { baseUrl: '', error: '无法识别网页运行地址，请通过浏览器打开网站' }
  }
  const hostname = location.hostname.toLowerCase()
  const local = hostname === 'localhost' || hostname === '[::1]' || hostname === '::1' || /^127(?:\.\d{1,3}){3}$/.test(hostname)
  if (local && ['http:', 'https:'].includes(location.protocol)) return { baseUrl: developmentEndpoint, error: '' }
  if (location.protocol !== 'https:') return { baseUrl: '', error: '正式网站需要通过 HTTPS 访问，请检查网站部署地址' }
  try {
    const origin = new URL(location.origin)
    if (origin.protocol !== 'https:' || origin.hostname.toLowerCase() !== hostname || origin.username || origin.password ||
        origin.pathname !== '/' || origin.search || origin.hash || origin.origin !== location.origin) throw new Error()
    if (!/^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?$/.test(hostname) ||
        /\.(?:localhost|local|invalid|example|test)$/.test(hostname)) throw new Error()
    return { baseUrl: origin.origin, error: '' }
  } catch { return { baseUrl: '', error: '正式网站地址格式不正确，请检查部署域名' } }
}

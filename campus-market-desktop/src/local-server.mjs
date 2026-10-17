import http from 'node:http'
import fs from 'node:fs/promises'
import path from 'node:path'
import { createHash } from 'node:crypto'
import { API_ORIGINS, DESKTOP_PORT } from './window-policy.mjs'

const mimeTypes = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.json': 'application/json; charset=utf-8',
  '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon', '.woff': 'font/woff', '.woff2': 'font/woff2', '.ttf': 'font/ttf' }
export function contentSecurityPolicy(html) {
  const hashes = [...html.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/gi)]
    .filter(match => !/\bsrc\s*=/i.test(match[1]) && match[2].trim())
    .map(match => `'sha256-${createHash('sha256').update(match[2]).digest('base64')}'`)
  return ["default-src 'self'", `script-src 'self' ${hashes.join(' ')}`,
    "style-src 'self' 'unsafe-inline'", `connect-src 'self' ${API_ORIGINS.join(' ')}`,
    `img-src 'self' data: blob: ${API_ORIGINS.join(' ')}`, "font-src 'self' data:",
    "object-src 'none'", "frame-src 'none'", "frame-ancestors 'none'", "base-uri 'none'", "form-action 'self'"].join('; ')
}
export async function startLocalServer({ root, port = DESKTOP_PORT }) {
  const realRoot = await fs.realpath(root)
  const html = await fs.readFile(path.join(realRoot, 'index.html'), 'utf8')
  const csp = contentSecurityPolicy(html)
  let origin = ''
  const server = http.createServer(async (request, response) => {
    response.setHeader('Content-Security-Policy', csp)
    response.setHeader('X-Content-Type-Options', 'nosniff')
    response.setHeader('Cache-Control', 'no-store')
    const reject = status => { response.writeHead(status, { 'Content-Type': 'text/plain; charset=utf-8' }); response.end(http.STATUS_CODES[status]) }
    try {
      if (request.headers.host !== new URL(origin).host) return reject(403)
      if (!['GET', 'HEAD'].includes(request.method)) { response.setHeader('Allow', 'GET, HEAD'); return reject(405) }
      const url = new URL(request.url, origin)
      if (url.origin !== origin) return reject(403)
      const pathname = decodeURIComponent(url.pathname)
      if (pathname.includes('\0') || pathname.includes('\\')) return reject(400)
      const file = path.resolve(realRoot, '.' + (pathname === '/' ? '/index.html' : pathname))
      if (file !== realRoot && !file.startsWith(realRoot + path.sep)) return reject(403)
      const resolved = await fs.realpath(file)
      if (!resolved.startsWith(realRoot + path.sep)) return reject(403)
      if (!(await fs.stat(resolved)).isFile()) return reject(404)
      const bytes = await fs.readFile(resolved)
      response.writeHead(200, { 'Content-Type': mimeTypes[path.extname(resolved)] || 'application/octet-stream', 'Content-Length': bytes.length })
      response.end(request.method === 'HEAD' ? undefined : bytes)
    } catch (error) {
      if (error instanceof URIError) reject(400)
      else if (['ENOENT', 'ENOTDIR'].includes(error.code)) reject(404)
      else reject(500)
    }
  })
  await new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(port, '127.0.0.1', resolve)
  })
  origin = `http://127.0.0.1:${server.address().port}`
  let closing = null
  return { origin, server, close: () => {
    if (!closing) closing = new Promise((resolve, reject) => {
      server.close(error => error ? reject(error) : resolve())
      // 静态服务没有业务写入；退出时关闭未完成连接，避免等待浏览器连接。
      server.closeAllConnections()
    })
    return closing
  } }
}

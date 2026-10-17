import { API_BASE_URL, SERVER_CONFIG, DIAGNOSTICS_ENABLED } from '@/config/server.js'

function probe(path) {
  const started = Date.now()
  let task, finish, settled = false
  const promise = new Promise(resolve => {
    finish = (state, httpStatus = 0) => {
      if (settled) return
      settled = true
      resolve({ path, state, httpStatus, elapsedMs: Math.max(0, Date.now() - started) })
    }
    if (!DIAGNOSTICS_ENABLED) { finish('DISABLED'); return }
    if (SERVER_CONFIG.error) { finish('CONFIG_ERROR'); return }
    try {
      task = uni.request({
        url: `${API_BASE_URL}${path}`, method: 'GET', timeout: 5000,
        dataType: 'json', header: { Accept: 'application/json' },
        success: response => {
          const status = Number(response.statusCode)
          const state = response.data?.status
          if (status === 200 && state === 'UP') finish('UP', status)
          else if (status === 503 && ['DOWN', 'OUT_OF_SERVICE'].includes(state)) finish('NOT_READY', status)
          else if (status === 200) finish('INVALID_RESPONSE', status)
          else finish('HTTP_ERROR', status)
        },
        fail: error => finish(String(error?.errMsg).toLowerCase().includes('timeout') ? 'TIMEOUT' : 'NETWORK_ERROR')
      })
    } catch { finish('NETWORK_ERROR') }
  })
  return { promise, abort() { if (settled) return; finish('CANCELLED'); task?.abort?.() } }
}

export function summarizeConnection(liveness, readiness) {
  if (liveness.state === 'UP' && readiness.state === 'UP') {
    return { title: '连接正常，数据库就绪', hint: '后端应用可访问，可以继续验证商品、登录等业务接口。' }
  }
  if (liveness.state === 'CONFIG_ERROR' || readiness.state === 'CONFIG_ERROR') {
    return { title: '服务器配置有误', hint: SERVER_CONFIG.error }
  }
  if (liveness.state === 'DISABLED') return { title: '诊断已关闭', hint: '此页面只用于开发调试。' }
  if (liveness.state === 'UP' && readiness.state === 'NOT_READY') {
    return { title: '应用存活，服务尚未就绪', hint: '检查数据库连接和后端日志；就绪状态异常也可能发生在应用停止过程中。' }
  }
  if ([liveness, readiness].some(item => item.state === 'HTTP_ERROR' || item.state === 'INVALID_RESPONSE')) {
    return { title: '健康接口响应异常', hint: '确认后端已更新并重启，检查健康接口路径和响应格式。' }
  }
  if (liveness.state === 'NOT_READY') return { title: '应用存活状态异常', hint: '查看后端日志，确认应用已经正常启动。' }
  return { title: '部分检查未收到正常结果', hint: '不能仅凭超时断定后端未启动。检查地址、同一网络、域名规则、端口及后端日志。' }
}

export function startConnectionCheck() {
  const live = probe('/api/health/liveness')
  const ready = probe('/api/health/readiness')
  return {
    promise: Promise.all([live.promise, ready.promise]).then(([liveness, readiness]) => ({
      liveness, readiness, summary: summarizeConnection(liveness, readiness)
    })),
    abort() { live.abort(); ready.abort() }
  }
}

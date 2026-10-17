// 只观察设备连接，不发送业务请求，也不据此断定后端可用。
export function normalizeNetwork(result) {
  const type = typeof result?.networkType === 'string' ? result.networkType : 'unknown'
  if (result?.isConnected === false || type === 'none') return { state: 'offline', networkType: 'none' }
  const known = ['wifi', '2g', '3g', '4g', '5g', 'ethernet'].includes(type)
  return { state: result?.isConnected === true || known ? 'online' : 'unknown', networkType: known ? type : 'unknown' }
}

export function createNetworkMonitor(getApi, publish) {
  let users = 0, generation = 0, revision = 0, listener = null, api = null
  let snapshot = Object.freeze({ state: 'unknown', networkType: 'unknown', recovered: false })
  function update(result) {
    const next = normalizeNetwork(result)
    next.recovered = next.state === 'online' && (snapshot.state === 'offline' || snapshot.recovered)
    snapshot = Object.freeze(next)
    publish(snapshot)
  }
  function refresh() {
    if (!users) return
    const run = generation, request = ++revision
    const current = () => users > 0 && run === generation && request === revision
    try {
      if (typeof api?.getNetworkType !== 'function') { update(null); return }
      api.getNetworkType({
        success(result) { if (current()) update(result) },
        fail() { if (current()) update(null) }
      })
    } catch { if (current()) update(null) }
  }
  function acquire() {
    users++
    if (users === 1) {
      const run = ++generation
      snapshot = Object.freeze({ state: 'unknown', networkType: 'unknown', recovered: false })
      publish(snapshot)
      try { api = getApi() } catch { api = null }
      if (typeof api?.onNetworkStatusChange === 'function' && typeof api?.offNetworkStatusChange === 'function') {
        const callback = result => {
          if (!users || run !== generation || listener !== callback) return
          revision++ // 新状态事件使尚未返回的旧查询失效。
          update(result)
        }
        listener = callback
        try { api.onNetworkStatusChange(callback) }
        catch {
          listener = null
          try { api.offNetworkStatusChange(callback) } catch { /* 状态查询仍可使用。 */ }
        }
      }
      refresh()
    }
    let released = false
    return () => {
      if (released) return
      released = true
      if (--users > 0) return
      generation++; revision++
      const previous = listener
      listener = null
      try { if (previous) api.offNetworkStatusChange(previous) } catch { /* 已使旧回调失效。 */ }
      api = null
    }
  }
  function acknowledgeRecovery() {
    if (!snapshot.recovered) return
    snapshot = Object.freeze({ ...snapshot, recovered: false })
    publish(snapshot)
  }
  return { acquire, refresh, acknowledgeRecovery }
}

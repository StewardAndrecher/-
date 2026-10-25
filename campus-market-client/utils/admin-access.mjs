const positive = value => Number.isSafeInteger(value) && value > 0
export function validatePermissions(data, userId) {
  if (!data || data.userId !== userId || !Array.isArray(data.roles) || !Array.isArray(data.permissions)) throw new Error('账号权限响应无效')
  const ordinary = JSON.stringify(data.roles) === '["USER"]' && data.permissions.length === 0
  const admin = JSON.stringify(data.roles) === '["USER","ADMIN"]' && JSON.stringify(data.permissions) === '["ADMIN_ACCESS"]'
  if (!positive(userId) || (!ordinary && !admin)) throw new Error('账号权限响应无效')
  return data
}
export function validateAdminDashboard(data, userId) {
  const user = data?.identity?.user, overview = data?.overview
  if (data?.identity?.role !== 'ADMIN' || !user || user.id !== userId || !positive(userId) ||
      typeof user.username !== 'string' || !/^[a-z0-9_]{4,32}$/.test(user.username) ||
      typeof user.nickname !== 'string' || !user.nickname.trim() || !overview ||
      !['users', 'products', 'onSaleProducts', 'orders'].every(key => Number.isSafeInteger(overview[key]) && overview[key] >= 0) ||
      overview.onSaleProducts > overview.products) throw new Error('管理概览响应无效')
  return data
}

// Visibility is convenience only; every admin HTTP endpoint still checks the live grant server-side.
export function createAdminLoader(fetchData, getIdentity, validate, notify = () => {}) {
  let generation = 0, disposed = false
  let state = { data: null, busy: false, error: '', status: 0 }
  const emit = () => notify({ ...state })
  const matches = expected => {
    const actual = getIdentity()
    return actual?.userId === expected.userId && actual?.token === expected.token
  }
  async function refresh(active = true) {
    if (disposed) return
    const ticket = ++generation, identity = getIdentity()
    state = { data: null, busy: false, error: '', status: 0 }; emit()
    if (!active || !positive(identity?.userId) || !identity?.token) return
    state = { ...state, busy: true }; emit()
    try {
      const data = await fetchData()
      if (disposed || ticket !== generation || !matches(identity)) return
      state = { data: validate(data, identity.userId), busy: true, error: '', status: 200 }
    } catch (error) {
      if (!disposed && ticket === generation && matches(identity)) state = { data: null, busy: true, error: error?.message || '暂时无法读取管理信息', status: error?.statusCode || 0 }
    } finally {
      if (!disposed && ticket === generation) {
        if (!matches(identity)) state = { data: null, busy: false, error: '', status: 0 }
        else state = { ...state, busy: false }
        emit()
      }
    }
  }
  return { refresh, dispose() { disposed = true; generation++ } }
}

// Private pages/components clear their state on hide and on every session replacement.
export function createOwnedResource({ identity, load, validate, notify = () => {} }) {
 const empty = () => ({ data: null, loading: false, saving: false, error: '', message: '', uncertain: false })
 let state = empty(), generation = 0, active = false, disposed = false
 const emit = () => notify({ ...state })
 const who = () => ({ ...identity() })
 const valid = value => Number.isSafeInteger(value?.userId) && value.userId > 0 && typeof value.token === 'string' && !!value.token
 const alive = (turn, owner) => active && !disposed && turn === generation && JSON.stringify(owner) === JSON.stringify(who())
 async function refresh() {
  if (!active || disposed || state.loading || state.saving) return
  const owner = who(), turn = ++generation
  state = empty(); if (!valid(owner)) { emit(); return }
  state.loading = true; emit()
  try { const result = await load(owner); if (alive(turn, owner)) state.data = validate(result, owner) }
  catch (error) { if (alive(turn, owner)) state.error = error?.message || '读取失败，请重试' }
  finally { if (alive(turn, owner)) { state.loading = false; emit() } }
 }
 async function mutate({ confirm = async () => true, write, validateReceipt = value => value, message = '操作已保存，请查看最新记录' }) {
  if (!active || disposed || !state.data || state.loading || state.saving || state.uncertain) return false
  const owner = who(), snapshot = state.data, turn = ++generation
  if (!valid(owner)) return false
  let sent = false
  state.saving = true; state.error = ''; state.message = ''; emit()
  try {
   if (!await confirm() || !alive(turn, owner)) return false
   sent = true; const receipt = await write(snapshot, owner)
   if (!alive(turn, owner)) return false
   validateReceipt(receipt, snapshot, owner)
   state.message = message
   // Read back the server state; never synthesize financial success from a button click.
   state.data = null
   const result = await load(owner)
   if (!alive(turn, owner)) return false
   state.data = validate(result, owner)
   return true
  } catch (error) {
   if (alive(turn, owner)) { state.error = (error?.message || '操作结果暂不确定') + (sent ? '；请重新加载确认，不会自动重复提交。' : ''); state.uncertain = sent; if (sent) state.data = null }
   return false
  } finally { if (alive(turn, owner)) { state.saving = false; emit() } }
 }
 return { refresh, mutate, show() { if (disposed) return; active = true; generation++; state = empty(); emit(); return refresh() }, hide() { active = false; generation++; state = empty(); emit() }, dispose() { active = false; disposed = true; generation++; state = empty(); emit() } }
}

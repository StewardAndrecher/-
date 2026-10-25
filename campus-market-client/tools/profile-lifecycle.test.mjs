import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { createPageTaskScope } from '../utils/page-task-scope.mjs'

const source = await readFile(new URL('../pages/user/profile.vue', import.meta.url), 'utf8')
const script = source.split('<script setup>')[1].split('</script>')[0].replace(/^import .*\n/gm, '')
const person = (id = 101, nickname = '原昵称') => ({ id, username: 'student_' + id, nickname })
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const settle = async () => { for (let i = 0; i < 12; i++) await Promise.resolve() }
const error = (statusCode, code = '') => Object.assign(new Error('模拟错误'), { statusCode, code })
function mount(options = {}) {
  const hooks = {}, watchers = [], reads = [], writes = [], updates = [], navigations = []
  let token = options.anonymous ? '' : 'a', user = options.anonymous ? null : person()
  const currentUser = { get value() { return user }, set value(value) { user = value; notify() } }
  function notify() {
    for (const w of watchers) { const previous = w.previous, next = w.getter(); w.previous = next; w.callback(next, previous) }
  }
  const app = new Function('ref', 'watch', 'onShow', 'onHide', 'onUnload', 'getCurrentUser', 'updateMyNickname', 'currentUser', 'getToken', 'updateCurrentUser', 'createPageTaskScope', 'uni', 'getCurrentPages', script +
    ';return {profile,draftNickname,expectedNickname,loading,saving,conflict,uncertain,loadError,message,pageVisible,loadProfile,saveNickname,openPassword,openSessions,openLogin,goHome}')
  (value => ({ value }), (getter, callback, config) => watchers.push({ getter, callback, config, previous: getter() }),
    f => hooks.show = f, f => hooks.hide = f, f => hooks.unload = f,
    async () => { reads.push(token); return options.read ? options.read(token) : person(user.id, user.nickname) },
    async body => { writes.push({ ...body, token }); return options.write ? options.write(body, token) : person(user.id, body.nickname) },
    currentUser, () => token, (value, expectedToken) => { assert.equal(expectedToken, token); updates.push(value); currentUser.value = value },
    createPageTaskScope, { navigateTo: v => navigations.push(v), navigateBack: v => navigations.push({ back: true, ...v }), reLaunch: v => navigations.push(v), showToast() {} },
    () => options.root ? [{}] : [{}, {}])
  return { app, hooks, reads, writes, updates, navigations, watchers, currentUser,
    identity(id, nextToken) { token = nextToken; currentUser.value = id ? person(id) : null },
    nickname(nickname) { currentUser.value = { ...user, nickname } } }
}
function cleared(f) {
  const a = f.app
  assert.equal(a.profile.value, null)
  for (const key of ['draftNickname', 'expectedNickname', 'loadError', 'message']) assert.equal(a[key].value, '', key)
  for (const key of ['loading', 'saving', 'conflict', 'uncertain']) assert.equal(a[key].value, false, key)
}
test('actual page waits for show, loads exact account and nickname, and does not write', async () => {
  const f = mount(); await f.app.loadProfile(); await f.app.saveNickname(); assert.equal(f.reads.length, 0)
  await f.hooks.show(); assert.deepEqual(f.app.profile.value, person()); assert.equal(f.app.draftNickname.value, '原昵称'); assert.equal(f.writes.length, 0); assert.equal(f.app.loading.value, false)
})
test('anonymous page does not fetch or save and offers login', async () => {
  const f = mount({ anonymous: true }); await f.hooks.show(); await f.app.saveNickname(); f.app.openLogin(); assert.equal(f.reads.length, 0); assert.equal(f.writes.length, 0); assert.equal(f.navigations[0].url, '/pages/user/login')
})
test('session watcher is synchronous; nickname-only updates do not fetch or erase drafts', async () => {
  const f = mount(); await f.hooks.show(); f.app.draftNickname.value = '本页草稿'; f.nickname('新昵称')
  assert.equal(f.watchers[0].config.flush, 'sync'); assert.deepEqual(f.watchers[0].getter(), [101, 'a']); assert.equal(f.reads.length, 1); assert.equal(f.app.draftNickname.value, '本页草稿')
})
for (const action of ['hide', 'unload', 'logout']) {
  test(`${action} clears private snapshot draft expectations errors and locks synchronously`, async () => {
    const f = mount(); await f.hooks.show(); f.app.draftNickname.value = '草稿'; f.app.conflict.value = true; f.app.uncertain.value = true; f.app.message.value = '旧消息'; f.app.loadError.value = '旧错误'
    action === 'logout' ? f.identity(null, '') : f.hooks[action](); cleared(f); assert.equal(f.reads.length, 1)
  })
}
test('return after hiding discards unsaved draft and reads latest server value', async () => {
  let n = 0; const f = mount({ read: () => person(101, ++n === 1 ? '原昵称' : '服务端最新昵称') })
  await f.hooks.show(); f.app.draftNickname.value = '未保存'; f.hooks.hide(); await f.hooks.show(); assert.equal(f.app.draftNickname.value, '服务端最新昵称'); assert.equal(f.reads.length, 2); assert.equal(f.writes.length, 0)
})
test('hidden session change waits until next show', async () => {
  const f = mount(); await f.hooks.show(); f.hooks.hide(); f.identity(102, 'b'); cleared(f); assert.equal(f.reads.length, 1); await f.hooks.show(); assert.equal(f.app.profile.value.id, 102)
})
for (const phase of ['read', 'write']) for (const action of ['hide', 'unload', 'logout']) for (const fail of [false, true]) {
  test(`late ${phase} ${fail ? 'error' : 'result'} after ${action} cannot restore private data or update global user`, async () => {
    const d = deferred(), f = mount({ [phase]: () => d.promise })
    let pending
    if (phase === 'write') { f.hooks.show(); d.resolve(person()); await settle(); const next = deferred(); f.app.draftNickname.value = '新昵称'; d.promise = next.promise; d.resolve = next.resolve; d.reject = next.reject; pending = f.app.saveNickname() }
    else pending = f.hooks.show()
    const count = f.updates.length, readCount = f.reads.length
    action === 'logout' ? f.identity(null, '') : f.hooks[action]()
    fail ? d.reject(error(500)) : d.resolve(person(101, phase === 'write' ? '新昵称' : '原昵称')); await pending; await settle()
    cleared(f); assert.equal(f.updates.length, count); assert.equal(f.reads.length, readCount)
  })
}
for (const id of [101, 102]) {
  test(`old read finally cannot unlock new session read for user ${id}`, async () => {
    const a = deferred(), b = deferred(); let n = 0; const f = mount({ read: () => ++n === 1 ? a.promise : b.promise })
    const old = f.hooks.show(); f.identity(id, 'b'); assert.equal(f.reads.length, 2); a.resolve(person()); await old
    assert.equal(f.app.loading.value, true); assert.equal(f.app.profile.value, null); assert.equal(f.reads.length, 2)
    b.resolve(person(id, '新会话昵称')); await settle(); assert.equal(f.app.loading.value, false); assert.equal(f.app.draftNickname.value, '新会话昵称')
  })
}
test('same-user replacement while saving clears old draft and cannot overwrite new session', async () => {
  const a = deferred(), b = deferred(); let writes = 0; const f = mount({ write: () => ++writes === 1 ? a.promise : b.promise })
  await f.hooks.show(); f.app.draftNickname.value = '旧保存'; const old = f.app.saveNickname(); f.identity(101, 'b'); await settle()
  assert.equal(f.app.draftNickname.value, '原昵称'); f.app.draftNickname.value = '新保存'; const fresh = f.app.saveNickname()
  a.resolve(person(101, '旧保存')); await old; assert.equal(f.app.saving.value, true); assert.equal(f.currentUser.value.nickname, '原昵称')
  b.resolve(person(101, '新保存')); await fresh; assert.equal(f.app.saving.value, false); assert.equal(f.currentUser.value.nickname, '新保存'); assert.equal(f.app.message.value, '昵称已保存')
})
test('saving sends trimmed nickname and original expectation; own nickname watcher does not invalidate receipt', async () => {
  const f = mount(); await f.hooks.show(); f.app.draftNickname.value = ' 新昵称 '; await f.app.saveNickname()
  assert.deepEqual(f.writes, [{ nickname: '新昵称', expectedNickname: '原昵称', token: 'a' }]); assert.equal(f.app.profile.value.nickname, '新昵称'); assert.equal(f.app.expectedNickname.value, '新昵称'); assert.equal(f.app.message.value, '昵称已保存'); assert.equal(f.reads.length, 1)
})
test('refresh preserves same-session draft and detects remote nickname conflict without writing', async () => {
  let n = 0; const f = mount({ read: () => person(101, ++n === 1 ? '原昵称' : '异地修改') }); await f.hooks.show(); f.app.draftNickname.value = '我的草稿'; await f.app.loadProfile()
  assert.equal(f.app.draftNickname.value, '我的草稿'); assert.equal(f.app.expectedNickname.value, '原昵称'); assert.equal(f.app.conflict.value, true); await f.app.saveNickname(); assert.equal(f.writes.length, 0)
  await f.app.loadProfile(true); assert.equal(f.app.draftNickname.value, '异地修改'); assert.equal(f.app.conflict.value, false)
})
test('409 preserves draft and requires explicit reload before another save', async () => {
  const f = mount({ write: () => { throw error(409, 'USER_PROFILE_CONFLICT') } }); await f.hooks.show(); f.app.draftNickname.value = '我的草稿'; await f.app.saveNickname(); await f.app.saveNickname()
  assert.equal(f.writes.length, 1); assert.equal(f.app.conflict.value, true); assert.equal(f.app.draftNickname.value, '我的草稿'); assert.match(f.app.message.value, /草稿仍保留/); await f.app.loadProfile(true); assert.equal(f.app.conflict.value, false)
})
for (const status of [0, 500, 503]) {
  test(`uncertain ${status} response never retries PUT automatically and requires explicit reconciliation`, async () => {
    const f = mount({ write: () => { throw error(status) } }); await f.hooks.show(); f.app.draftNickname.value = '新昵称'; await f.app.saveNickname(); await f.app.saveNickname()
    assert.equal(f.writes.length, 1); assert.equal(f.app.uncertain.value, true); await f.app.loadProfile(); assert.equal(f.app.uncertain.value, true); await f.app.loadProfile(true); assert.equal(f.app.uncertain.value, false)
  })
}
test('malformed read response cannot populate page or global user', async () => {
  for (const value of [null, person(102), { ...person(), username: 'wrong' }, person(101, ''), person(101, 'x'.repeat(31)), person(101, 'a\nb')]) {
    const f = mount({ read: () => value }); await f.hooks.show(); assert.equal(f.app.profile.value, null); assert.equal(f.updates.length, 0); assert.match(f.app.loadError.value, /响应不匹配/)
  }
})
test('malformed save response is uncertain and cannot claim success', async () => {
  for (const value of [null, person(102, '新昵称'), person(101, '其他昵称')]) {
    const f = mount({ write: () => value }); await f.hooks.show(); f.app.draftNickname.value = '新昵称'; await f.app.saveNickname(); assert.equal(f.app.uncertain.value, true); assert.equal(f.currentUser.value.nickname, '原昵称'); assert.notEqual(f.app.message.value, '昵称已保存')
  }
})
test('invalid nickname sends no write; Unicode code points are counted', async () => {
  const f = mount(); await f.hooks.show()
  for (const nickname of ['', ' ', 'x'.repeat(31), 'a\nb', 'a\u0085b']) { f.app.draftNickname.value = nickname; await f.app.saveNickname() }
  assert.equal(f.writes.length, 0); f.app.draftNickname.value = '😀'.repeat(30); await f.app.saveNickname(); assert.equal(f.writes.length, 1); assert.equal(f.app.message.value, '昵称已保存')
})
test('pending read or write blocks duplicates and authenticated navigation', async () => {
  const d = deferred(), f = mount({ read: () => d.promise }); const pending = f.hooks.show(); await f.app.loadProfile(); await f.app.saveNickname(); f.app.openPassword(); f.app.openSessions(); f.app.goHome(); assert.equal(f.reads.length, 1); assert.equal(f.writes.length, 0); assert.equal(f.navigations.length, 0); d.resolve(person()); await pending
  const w = deferred(), g = mount({ write: () => w.promise }); await g.hooks.show(); g.app.draftNickname.value = '新昵称'; const write = g.app.saveNickname(); await g.app.saveNickname(); await g.app.loadProfile(); g.app.openPassword(); g.app.openSessions(); g.app.goHome(); assert.equal(g.writes.length, 1); assert.equal(g.reads.length, 1); assert.equal(g.navigations.length, 0); w.resolve(person(101, '新昵称')); await write
})
test('unload is terminal even if later hooks or watcher callbacks fire', async () => {
  const f = mount(); await f.hooks.show(); f.hooks.unload(); f.identity(102, 'b'); await f.hooks.show(); await f.app.loadProfile(); await f.app.saveNickname(); f.app.openPassword(); f.app.openSessions(); f.app.goHome(); cleared(f); assert.equal(f.app.pageVisible.value, false); assert.equal(f.reads.length, 1); assert.equal(f.navigations.length, 0)
})
test('current-session read failure can be retried and save failure keeps draft', async () => {
  let n = 0; const f = mount({ read: () => { if (++n === 1) throw error(503); return person() }, write: () => { throw error(400) } }); await f.hooks.show(); assert.equal(f.app.loading.value, false); assert.match(f.app.loadError.value, /模拟错误/); await f.app.loadProfile(); f.app.draftNickname.value = '草稿'; await f.app.saveNickname(); assert.equal(f.app.saving.value, false); assert.equal(f.app.draftNickname.value, '草稿'); assert.equal(f.app.uncertain.value, false)
})
test('visible authenticated page can navigate; hidden callbacks cannot', async () => {
  const f = mount(); await f.hooks.show(); f.app.openPassword(); f.app.openSessions(); f.app.goHome(); assert.equal(f.navigations.length, 3); f.hooks.hide(); f.app.openPassword(); f.app.openSessions(); f.app.openLogin(); f.app.goHome(); assert.equal(f.navigations.length, 3)
})

import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { createFavoriteInbox, favoriteAvailability, favoriteKeyword, FAVORITE_AVAILABILITY } from '../utils/favorite-inbox.mjs'
const row = id => ({ productId: id, product: { id, title: '台灯' + id, description: '', priceInCents: 1000, status: 'ON_SALE', version: 0 } })
const page = (records = [row(1)], changes = {}) => ({ records, total: records.length, page: 1, size: 5, ...changes })
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const settle = async () => { for (let i = 0; i < 12; i++) await Promise.resolve() }
function fixture(options = {}) {
  let state, who = { userId: 101, token: 'a' }; const reads = [], writes = [], marks = []
  const inbox = createFavoriteInbox({ identity: () => who, load: async q => { reads.push(q); return options.load ? options.load(q) : page() },
    setFavorite: async (id, favorite) => { writes.push([id, favorite]); return options.write ? options.write(id, favorite) : { productId: id, favorite } },
    loadLegacy: () => options.legacy || [], markImported: (...args) => marks.push(args), notify: v => { state = v } })
  return { inbox, reads, writes, marks, get state() { return state }, identity: v => { who = v } }
}
test('keyword is normalized bounded literal text and UTF16 length matches Java', () => {
  assert.equal(favoriteKeyword(), ''); assert.equal(favoriteKeyword('  台灯  '), '台灯'); assert.equal(favoriteKeyword('   '), '')
  for (const keyword of ['%', '_', '!', "O'Reilly", '\\', 'x'.repeat(50), '😀'.repeat(25)]) assert.equal(favoriteKeyword(keyword), keyword)
  for (const keyword of [null, 7, {}, 'x'.repeat(51), '😀'.repeat(26), '中\n间', '中\u0085间']) assert.throws(() => favoriteKeyword(keyword))
})
test('search starts at page one and preserves server total instead of filtering one page locally', async () => {
  const f = fixture({ load: q => page(q.keyword ? Array.from({ length: 5 }, (_, i) => row(i + 1)) : [row(99)], { total: q.keyword ? 6 : 1 }) }); await f.inbox.show(); await f.inbox.search(' 台灯 ')
  assert.deepEqual(f.reads.at(-1), { page: 1, size: 5, keyword: '台灯' }); assert.equal(f.state.keyword, '台灯'); assert.equal(f.state.page.total, 6); assert.equal(f.state.page.records.length, 5)
})
test('refresh and pagination keep current keyword', async () => {
  const f = fixture({ load: q => page(q.page === 2 ? [row(6)] : Array.from({ length: 5 }, (_, i) => row(i + 1)), { total: 6, page: q.page }) }); await f.inbox.show(); await f.inbox.search('台灯'); await f.inbox.refresh(2); await f.inbox.refresh()
  assert.deepEqual(f.reads.at(-1), { page: 2, size: 5, keyword: '台灯' }); assert.equal(f.state.page.total, 6)
})
test('availability filter retains keyword; clearing keyword retains availability', async () => {
  const f = fixture(); await f.inbox.show(); await f.inbox.search('台灯'); await f.inbox.filter('AVAILABLE'); assert.deepEqual(f.reads.at(-1), { page: 1, size: 5, availability: 'AVAILABLE', keyword: '台灯' }); await f.inbox.search(''); assert.deepEqual(f.reads.at(-1), { page: 1, size: 5, availability: 'AVAILABLE' })
})
test('unavailable and keyword can represent a valid empty intersection', async () => {
  const f = fixture({ load: () => page([]) }); await f.inbox.show(); await f.inbox.search('台灯'); await f.inbox.filter('UNAVAILABLE'); assert.equal(f.state.page.total, 0); assert.equal(f.state.keyword, '台灯'); assert.equal(f.state.availability, 'UNAVAILABLE')
})
test('hidden product projection in nonempty title search is rejected', async () => {
  const f = fixture({ load: () => page([{ productId: 2, product: null }]) }); await f.inbox.show(); assert.equal(await f.inbox.search('台灯'), false); assert.equal(f.state.page, null); assert.match(f.state.error, /不可查看商品/)
})
test('invalid keyword sends no request and preserves current loaded query', async () => {
  const f = fixture(); await f.inbox.show(); const old = f.state.page; await f.inbox.search('x'.repeat(51)); assert.equal(f.reads.length, 1); assert.equal(f.state.page, old); assert.equal(f.state.keyword, ''); assert.equal(f.writes.length, 0)
})
for (const action of ['hide', 'dispose']) for (const fail of [false, true]) {
  test(`${action} clears keyword and ignores late ${fail ? 'error' : 'result'}`, async () => {
    const d = deferred(), f = fixture({ load: q => q.keyword ? d.promise : page() }); await f.inbox.show(); const pending = f.inbox.search('台灯'); f.inbox[action](); fail ? d.reject(new Error('旧错误')) : d.resolve(page()); await pending; assert.equal(f.state.keyword, ''); assert.equal(f.state.page, null); assert.equal(f.state.error, ''); assert.equal(f.state.loading, false)
  })
}
test('latest search wins when the earlier query returns last', async () => {
  const a = deferred(), b = deferred(), f = fixture({ load: q => !q.keyword ? page() : q.keyword === '台灯' ? a.promise : b.promise }); await f.inbox.show(); const old = f.inbox.search('台灯'), fresh = f.inbox.search('教材'); a.resolve(page()); await old; assert.equal(f.state.keyword, '教材'); assert.equal(f.state.loading, true); b.resolve(page([row(2)])); await fresh; assert.equal(f.state.loading, false); assert.equal(f.state.page.records[0].productId, 2)
})
test('same account new token clears search and rejects old session result', async () => {
  const d = deferred(), f = fixture({ load: q => q.keyword ? d.promise : page() }); await f.inbox.show(); const old = f.inbox.search('台灯'); f.identity({ userId: 101, token: 'b' }); await f.inbox.identityChanged(); d.resolve(page([row(9)])); await old; assert.equal(f.state.keyword, ''); assert.equal(f.state.page.records[0].productId, 1)
})
test('successful cancellation refreshes same query and repairs shrinking last page', async () => {
  let removed = false; const f = fixture({ load: q => page(q.page === 2 ? removed ? [] : [row(6)] : Array.from({ length: 5 }, (_, i) => row(i + 1)), { total: removed ? 5 : 6, page: q.page }), write: (id, favorite) => { removed = true; return { productId: id, favorite } } }); await f.inbox.show(); await f.inbox.search('台灯'); await f.inbox.refresh(2); await f.inbox.remove(6); assert.deepEqual(f.writes, [[6, false]]); assert.equal(f.state.page.page, 1); assert.equal(f.state.page.total, 5); assert.ok(f.reads.slice(-2).every(q => q.keyword === '台灯'))
})
test('uncertain removal keeps keyword for manual reconciliation and never auto-retries', async () => {
  const f = fixture({ write: () => { throw new Error('超时') } }); await f.inbox.show(); await f.inbox.search('台灯'); await f.inbox.remove(1); await f.inbox.remove(1); assert.equal(f.writes.length, 1); assert.equal(f.state.uncertain, true); assert.equal(f.state.keyword, '台灯'); await f.inbox.refresh(); assert.equal(f.reads.at(-1).keyword, '台灯'); assert.equal(f.state.uncertain, false)
})
test('legacy import is independent of keyword and refreshes current query', async () => {
  const f = fixture({ legacy: [7] }); await f.inbox.show(); await f.inbox.search('台灯'); await f.inbox.importLegacy(); assert.deepEqual(f.writes, [[7, true]]); assert.deepEqual(f.marks, [[101, 7]]); assert.equal(f.reads.at(-1).keyword, '台灯')
})
test('unknown import response retains search and blocks repeated writes', async () => {
  const f = fixture({ legacy: [7], write: () => { throw new Error('超时') } }); await f.inbox.show(); await f.inbox.search('台灯'); await f.inbox.importLegacy(); await f.inbox.importLegacy(); assert.equal(f.writes.length, 1); assert.equal(f.state.keyword, '台灯'); assert.equal(f.state.uncertain, true)
})
test('query change is blocked during write and hidden search starts no read', async () => {
  const d = deferred(), f = fixture({ write: () => d.promise }); await f.inbox.show(); await f.inbox.search('台灯'); const pending = f.inbox.remove(1); await f.inbox.search('教材'); assert.equal(f.state.keyword, '台灯'); d.resolve({ productId: 1, favorite: false }); await pending; f.inbox.hide(); const n = f.reads.length; await f.inbox.search('教材'); assert.equal(f.reads.length, n)
})
test('actual API forwards normalized optional keyword and availability through auth request', async () => {
  const source = await readFile(new URL('../api/favorite.js', import.meta.url), 'utf8'), calls = []
  const getFavorites = new Function('request', 'favoriteAvailability', 'favoriteKeyword', source.replace(/^import .*\n/gm, '').replace(/export function/g, 'function') + ';return getFavorites')(q => calls.push(q), favoriteAvailability, favoriteKeyword)
  getFavorites(); getFavorites({ page: 2, availability: 'AVAILABLE', keyword: '  100%  ' }); assert.deepEqual(calls, [{ path: '/api/favorites', auth: true, data: { page: 1, size: 5 } }, { path: '/api/favorites', auth: true, data: { page: 2, size: 5, availability: 'AVAILABLE', keyword: '100%' } }]); assert.throws(() => getFavorites({ keyword: null })); assert.equal(calls.length, 2)
})
test('actual Vue page submits draft only on search, clears on hide and uses search-aware empty copy', async () => {
  const source = await readFile(new URL('../pages/product/favorites.vue', import.meta.url), 'utf8'), script = source.split('<script setup>')[1].split('</script>')[0].replace(/^import .*\n/gm, ''), hooks = {}, watchers = [], reads = []
  const app = new Function('ref', 'computed', 'watch', 'onShow', 'onHide', 'onUnload', 'currentUser', 'getToken', 'getFavorites', 'setProductFavorite', 'loadLegacyFavoriteIds', 'markFavoriteImported', 'createFavoriteInbox', 'FAVORITE_PAGE_SIZE', 'FAVORITE_AVAILABILITY', 'uni', script + ';return {inbox,state,draftKeyword,searchFavorites,clearSearch}')
  (value => ({ value }), fn => ({ get value() { return fn() } }), (get, cb) => watchers.push(cb), fn => hooks.show = fn, fn => hooks.hide = fn, fn => hooks.unload = fn, { value: { id: 101 } }, () => 'a', async q => { reads.push(q); return page() }, async () => {}, () => [], () => {}, createFavoriteInbox, 5, FAVORITE_AVAILABILITY, {})
  await hooks.show(); app.draftKeyword.value = ' 台灯 '; assert.equal(reads.length, 1); await app.searchFavorites(); assert.equal(app.state.value.keyword, '台灯'); assert.equal(app.draftKeyword.value, '台灯'); await app.clearSearch(); assert.equal(app.state.value.keyword, ''); app.draftKeyword.value = '私有草稿'; hooks.hide(); assert.equal(app.draftKeyword.value, ''); assert.equal(app.state.value.keyword, ''); await settle(); assert.ok(source.includes('已失效商品不参与标题搜索')); assert.ok(source.includes("state.availability === 'ALL' && !state.keyword")); assert.ok(!source.includes('v-html'))
})

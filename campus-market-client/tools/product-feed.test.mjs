import test from 'node:test'
import assert from 'node:assert/strict'
import { createProductFeed, validateFeedPage } from '../utils/product-feed.mjs'
const item = id => ({ id, title: '教材', description: '', priceInCents: 100, status: 'ON_SALE', category: { code: 'BOOK', name: '书籍' }, favoriteCount: 0, seller: null })
const page = (ids, more = false, cursor = null, size = 2) => ({ records: ids.map(item), hasMore: more, nextCursor: cursor, size })
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }

test('first load and continuation forward the exact cursor, then stop at the end', async () => {
  let state, calls = []; const feed = createProductFeed(async args => { calls.push(args); return calls.length === 1 ? page([5, 4], true, 'position1') : page([3]) }, s => state = s, 2)
  await feed.refresh(); assert.equal(state.cursor, 'position1'); await feed.loadMore(); await feed.loadMore()
  assert.deepEqual(state.items.map(x => x.id), [5, 4, 3]); assert.equal(calls[1].cursor, 'position1'); assert.equal(calls.length, 2); assert.equal(state.hasMore, false)
})
test('failure preserves already loaded rows and cursor so retry cannot skip a page', async () => {
  let state, count = 0; const feed = createProductFeed(async args => { count++; if (count === 1) return page([4, 3], true, 'position1'); assert.equal(args.cursor, 'position1'); if (count === 2) throw Error('网络中断'); return page([2]) }, s => state = s, 2)
  await feed.refresh(); await feed.loadMore(); assert.equal(state.error, '网络中断'); assert.equal(state.cursor, 'position1'); assert.equal(state.items.length, 2)
  await feed.loadMore(); assert.equal(state.error, ''); assert.deepEqual(state.items.map(x => x.id), [4, 3, 2])
})
test('concurrent loadMore calls issue only one request', async () => {
  const pending = deferred(); let count = 0; const feed = createProductFeed(() => { count++; return pending.promise }, () => {}, 2)
  const loading = feed.refresh(); await feed.loadMore(); assert.equal(count, 1); pending.resolve(page([])); await loading
})
test('filter refresh ignores an older successful response and its cursor', async () => {
  const older = deferred(), newer = deferred(); let state; const feed = createProductFeed(args => args.keyword === '旧' ? older.promise : newer.promise, s => state = s, 2)
  const oldRequest = feed.refresh({ keyword: '旧' }); const newRequest = feed.refresh({ keyword: ' 新 ', category: 'BOOK' })
  older.resolve(page([9, 8], true, 'oldCursor')); await oldRequest; assert.equal(state.loading, true)
  newer.resolve(page([2])); await newRequest; assert.equal(state.keyword, '新'); assert.deepEqual(state.items.map(x => x.id), [2]); assert.equal(state.cursor, '')
})
test('an obsolete failure cannot overwrite a refreshed list', async () => {
  const old = deferred(); let state; const feed = createProductFeed(args => args.keyword ? old.promise : page([1]), s => state = s, 2)
  const pending = feed.refresh({ keyword: '旧' }); await feed.refresh(); old.reject(Error('过期错误')); await pending; assert.equal(state.error, ''); assert.deepEqual(state.items.map(x => x.id), [1])
})
test('unload ignores late results', async () => {
  const pending = deferred(); let count = 0; const feed = createProductFeed(() => pending.promise, () => count++, 2)
  const request = feed.refresh(); const before = count; feed.dispose(); pending.resolve(page([1])); await request; assert.equal(count, before)
})
test('repeated cursor is rejected without advancing', async () => {
  let state, count = 0; const feed = createProductFeed(async () => ++count === 1 ? page([5, 4], true, 'same') : page([3, 2], true, 'same'), s => state = s, 2)
  await feed.refresh(); await feed.loadMore(); assert.equal(state.items.length, 2); assert.equal(state.cursor, 'same'); assert.match(state.error, /没有前进/)
})
test('duplicate IDs within a response and across pages are rejected', async () => {
  assert.throws(() => validateFeedPage(page([1, 1]), 2)); let state, count = 0
  const feed = createProductFeed(async () => ++count === 1 ? page([5, 4], true, 'next') : page([4]), s => state = s, 2)
  await feed.refresh(); await feed.loadMore(); assert.deepEqual(state.items.map(x => x.id), [5, 4]); assert.match(state.error, /没有前进/)
})
test('invalid envelope and hidden products are rejected', () => {
  for (const value of [{ ...page([1]), size: 3 }, page([1], true, 'next'), page([1], false, 'unexpected'), { ...page([1]), hasMore: 'false' }, { ...page([1]), records: [{ ...item(1), status: 'OFF_SHELF' }] }]) assert.throws(() => validateFeedPage(value, 2))
})
test('unsafe identifiers and invalid filters are rejected before use', async () => {
  assert.throws(() => validateFeedPage({ ...page([1]), records: [{ ...item(1), id: Number.MAX_SAFE_INTEGER + 1 }] }, 2))
  let called = false; const feed = createProductFeed(() => { called = true })
  await assert.rejects(feed.refresh({ keyword: '中'.repeat(51) })); await assert.rejects(feed.refresh({ category: 'BAD' })); assert.equal(called, false)
})
test('empty result is terminal and can be explicitly refreshed', async () => {
  let count = 0, state; const feed = createProductFeed(async () => { count++; return page([]) }, s => state = s, 2)
  await feed.refresh(); await feed.loadMore(); assert.equal(count, 1); await feed.refresh(); assert.equal(count, 2); assert.equal(state.hasMore, false)
})
test('changing filters resets cursor and items before requesting', async () => {
  let state, count = 0; const feed = createProductFeed(async args => { count++; assert.equal(args.cursor, ''); return args.category ? page([2]) : page([5, 4], true, 'old') }, s => state = s, 2)
  await feed.refresh(); await feed.refresh({ category: 'BOOK' }); assert.deepEqual(state.items.map(x => x.id), [2]); assert.equal(count, 2)
})

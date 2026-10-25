import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { createFavoriteInbox } from '../utils/favorite-inbox.mjs'

const row = id => ({ productId: id, product: { id, title: '收藏样本' + id,
  description: '隔离测试', priceInCents: 100, status: 'ON_SALE', version: 0 } })
const page = (changes = {}) => ({ records: [row(1), row(2)], total: 2, page: 1, size: 5, ...changes })
const deferred = () => { let resolve, reject; const promise = new Promise((a,b) => { resolve=a; reject=b }); return { promise, resolve, reject } }
function fixture(options = {}) {
  let state, who = { userId: 101, token: 'a' }
  const reads = [], writes = [], marks = [], scopes = []
  const inbox = createFavoriteInbox({ identity: () => who,
    load: async query => { reads.push(query); return options.load ? options.load(query) : page() },
    setFavorite: async (id, favorite) => { writes.push([id, favorite]); return options.write ? options.write(id, favorite) : { productId: id, favorite } },
    loadLegacy: id => { scopes.push(id); if (options.legacyError) throw new Error('存储读取失败'); return options.ids || [3, 4] },
    markImported: (user, id) => { marks.push([user, id]); if (options.markError) throw new Error('存储写入失败') },
    notify: value => { state = value }
  })
  return { inbox, reads, writes, marks, scopes, get state() { return state }, identity: value => { who = value } }
}
test('hidden page cannot start reads imports or removals', async () => {
  const f=fixture(); await f.inbox.refresh(); await f.inbox.remove(1); await f.inbox.importLegacy()
  assert.equal(f.reads.length,0); assert.equal(f.writes.length,0)
})
test('show loads cloud page and account-specific legacy IDs', async () => {
  const f=fixture(); assert.equal(await f.inbox.show(),true)
  assert.deepEqual(f.reads,[{page:1,size:5}]); assert.deepEqual(f.scopes,[101]); assert.equal(f.state.page.total,2)
  assert.deepEqual(f.state.legacyIds,[3,4]); assert.equal(f.state.loading,false)
  assert.equal(JSON.stringify(f.state).includes('token'),false)
})
test('anonymous show sends no request', async () => {
  const f=fixture(); f.identity({userId:null,token:''}); assert.equal(await f.inbox.show(),false)
  assert.equal(f.reads.length,0); assert.match(f.state.error,/登录/)
})
test('hide clears cloud records legacy IDs notices and pending flags immediately', async () => {
  const f=fixture(); await f.inbox.show(); f.inbox.hide()
  assert.equal(f.state.page,null); assert.deepEqual(f.state.legacyIds,[]); assert.equal(f.state.loading,false); assert.equal(f.state.notice,'')
})
for (const action of ['hide','dispose']) {
  for (const rejected of [false,true]) {
    test(`${action} ignores a late ${rejected?'failed':'successful'} read`,async()=>{
      const d=deferred(),f=fixture({load:()=>d.promise}); const pending=f.inbox.show(); f.inbox[action]()
      rejected?d.reject(new Error('旧请求错误')):d.resolve(page()); await pending
      assert.equal(f.state.page,null); assert.equal(f.state.error,''); assert.equal(f.state.loading,false)
    })
  }
}
test('same account with a new token clears old snapshot and loads new scope', async () => {
  let n=0;const d=deferred(),f=fixture({load:()=>++n===1?page():d.promise});await f.inbox.show()
  f.identity({userId:101,token:'b'});const pending=f.inbox.identityChanged()
  assert.equal(f.state.page,null);assert.equal(f.state.loading,true);d.resolve(page({records:[],total:0}));await pending
  assert.equal(f.state.page.total,0)
})
test('late old read cannot clear loading or overwrite records of a newer account', async () => {
  const a=deferred(),b=deferred();let n=0;const f=fixture({load:()=>++n===1?a.promise:b.promise})
  const old=f.inbox.show();f.identity({userId:102,token:'b'});const fresh=f.inbox.identityChanged()
  a.resolve(page());await old;assert.equal(f.state.loading,true);assert.equal(f.state.page,null)
  b.resolve(page({records:[row(8)],total:1}));await fresh;assert.equal(f.state.page.records[0].productId,8)
})
test('identity mismatch is rejected even before a watcher runs', async () => {
  const d=deferred(),f=fixture({load:()=>d.promise});const pending=f.inbox.show();f.identity({userId:102,token:'b'})
  d.resolve(page());await pending;assert.equal(f.state.page,null);assert.equal(f.state.loading,false)
})
test('hidden identity change sends no read until show', async () => {
  const f=fixture();await f.inbox.show();f.inbox.hide();f.identity({userId:102,token:'b'});await f.inbox.identityChanged()
  assert.equal(f.reads.length,1);await f.inbox.show();assert.equal(f.reads.length,2);assert.deepEqual(f.scopes,[101,102])
})
test('refresh clears stale records before waiting', async () => {
  const d=deferred();let n=0;const f=fixture({load:()=>++n===1?page():d.promise});await f.inbox.show()
  const pending=f.inbox.refresh();assert.equal(f.state.page,null);assert.deepEqual(f.state.legacyIds,[])
  d.resolve(page());await pending
})
test('malformed duplicate or incomplete cloud pages never expose rows', async () => {
  for (const data of [page({records:[row(1),row(1)]}),page({records:[row(1)]}),page({total:-1}),page({size:10})]) {
    const f=fixture({load:()=>data});assert.equal(await f.inbox.show(),false);assert.equal(f.state.page,null);assert.ok(f.state.error)
  }
})
test('missing products are retained as unavailable favorites', async () => {
  const f=fixture({load:()=>page({records:[{productId:1,product:null}],total:1})});await f.inbox.show();assert.equal(f.state.page.records[0].product,null)
})
test('legacy storage failures do not hide cloud favorites', async () => {
  const f=fixture({legacyError:true});assert.equal(await f.inbox.show(),true);assert.equal(f.state.page.total,2);assert.deepEqual(f.state.legacyIds,[]);assert.match(f.state.error,/仍可使用/)
})
test('invalid legacy IDs are not eligible for import',async()=>{
  const f=fixture({ids:[3,'4']});await f.inbox.show();await f.inbox.importLegacy();assert.equal(f.writes.length,0)
})
test('legacy IDs are copied and deduplicated',async()=>{
  const ids=[3,3,4],f=fixture({ids});await f.inbox.show();ids.push(9);assert.deepEqual(f.state.legacyIds,[3,4])
})
test('successful remove sends one visible ID then refreshes once', async () => {
  const f=fixture();await f.inbox.show();assert.equal(await f.inbox.remove(1),true)
  assert.deepEqual(f.writes,[[1,false]]);assert.equal(f.reads.length,2);assert.match(f.state.notice,/已取消/);assert.equal(f.state.removingId,0)
})
test('unlisted or invalid ID cannot be removed', async () => {
  const f=fixture();await f.inbox.show();for(const id of [0,-1,3,'1',null])assert.equal(await f.inbox.remove(id),false);assert.equal(f.writes.length,0)
})
test('double remove import and refresh are blocked while a write is pending', async () => {
  const d=deferred(),f=fixture({write:()=>d.promise});await f.inbox.show();const pending=f.inbox.remove(1)
  await f.inbox.remove(2);await f.inbox.importLegacy();await f.inbox.refresh();assert.equal(f.writes.length,1);assert.equal(f.reads.length,1)
  d.resolve({productId:1,favorite:false});await pending
})
for (const rejected of [false,true]) {
  test(`hide ignores late remove ${rejected?'error':'receipt'} without refresh`,async()=>{
    const d=deferred(),f=fixture({write:()=>d.promise});await f.inbox.show();const pending=f.inbox.remove(1);f.inbox.hide()
    rejected?d.reject(new Error('超时')):d.resolve({productId:1,favorite:false});await pending
    assert.equal(f.reads.length,1);assert.equal(f.state.page,null);assert.equal(f.state.error,'');assert.equal(f.state.notice,'')
  })
}
test('unknown remove result requires a fresh read before further writes',async()=>{
  const f=fixture({write:()=>{throw new Error('超时')}});await f.inbox.show();await f.inbox.remove(1)
  assert.equal(f.state.uncertain,true);assert.equal(f.state.page,null);assert.match(f.state.error,/先刷新/)
  await f.inbox.remove(1);await f.inbox.importLegacy();assert.equal(f.writes.length,1)
  await f.inbox.refresh();assert.equal(f.state.uncertain,false);assert.equal(f.state.page.total,2)
})
test('mismatched remove receipt is never reported as success',async()=>{
  const f=fixture({write:()=>({productId:2,favorite:false})});await f.inbox.show();assert.equal(await f.inbox.remove(1),false);assert.equal(f.state.uncertain,true);assert.equal(f.reads.length,1)
})
test('import uses captured IDs and records only acknowledged successes for captured account',async()=>{
  const f=fixture();await f.inbox.show();assert.equal(await f.inbox.importLegacy(),true)
  assert.deepEqual(f.writes,[[3,true],[4,true]]);assert.deepEqual(f.marks,[[101,3],[101,4]]);assert.match(f.state.notice,/已合并2件/);assert.equal(f.reads.length,2)
})
test('a batch imports at most twenty IDs',async()=>{
  const f=fixture({ids:Array.from({length:25},(_,i)=>i+3)});await f.inbox.show();await f.inbox.importLegacy();assert.equal(f.writes.length,20);assert.deepEqual(f.writes.at(-1),[22,true])
})
for (const rejected of [false,true]) {
  test(`hide during import stops later IDs after ${rejected?'late failure':'late success'}`,async()=>{
    const d=deferred(),f=fixture({write:()=>d.promise});await f.inbox.show();const pending=f.inbox.importLegacy();assert.deepEqual(f.writes,[[3,true]])
    f.inbox.hide();rejected?d.reject(Object.assign(new Error('不可用'),{statusCode:404})):d.resolve({productId:3,favorite:true})
    await pending;assert.equal(f.writes.length,1);assert.equal(f.marks.length,0);assert.equal(f.state.page,null);assert.equal(f.state.notice,'')
  })
}
test('same-user token replacement stops an old import before next ID or storage write',async()=>{
  const d=deferred(),f=fixture({write:()=>d.promise});await f.inbox.show();const pending=f.inbox.importLegacy()
  f.identity({userId:101,token:'b'});await f.inbox.identityChanged();d.resolve({productId:3,favorite:true});await pending
  assert.equal(f.writes.length,1);assert.equal(f.marks.length,0);assert.equal(f.state.page.total,2);assert.equal(f.state.importing,false);assert.equal(f.state.notice,'')
})
test('old import cannot unset a new account pending write',async()=>{
  const a=deferred(),b=deferred();let n=0;const f=fixture({write:()=>++n===1?a.promise:b.promise});await f.inbox.show();const old=f.inbox.importLegacy()
  f.identity({userId:102,token:'b'});await f.inbox.identityChanged();const fresh=f.inbox.remove(1)
  a.resolve({productId:3,favorite:true});await old;assert.equal(f.state.removingId,1)
  b.resolve({productId:1,favorite:false});await fresh;assert.match(f.state.notice,/已取消/)
})
test('unavailable import skips only 404 and preserves original local favorite storage',async()=>{
  const f=fixture({write:(id,favorite)=>{if(id===3)throw Object.assign(new Error('不存在'),{statusCode:404});return {productId:id,favorite}}})
  await f.inbox.show();await f.inbox.importLegacy();assert.deepEqual(f.marks,[[101,4]]);assert.match(f.state.notice,/已合并1件，1件不可用/)
})
test('partial import timeout stops queue, keeps acknowledged mark, and requires refresh',async()=>{
  const f=fixture({ids:[3,4,5],write:(id,favorite)=>{if(id===4)throw new Error('超时');return {productId:id,favorite}}})
  await f.inbox.show();assert.equal(await f.inbox.importLegacy(),false);assert.deepEqual(f.writes,[[3,true],[4,true]]);assert.deepEqual(f.marks,[[101,3]])
  assert.equal(f.state.uncertain,true);assert.equal(f.state.page,null);assert.match(f.state.notice,/已合并1件/);assert.equal(f.reads.length,1)
})
test('invalid import receipt never marks local ID or starts next request',async()=>{
  const f=fixture({write:()=>({productId:99,favorite:true})});await f.inbox.show();await f.inbox.importLegacy();assert.equal(f.writes.length,1);assert.equal(f.marks.length,0);assert.equal(f.state.uncertain,true)
})
test('local mark failure distinguishes acknowledged cloud success and stops queue',async()=>{
  const f=fixture({markError:true});await f.inbox.show();await f.inbox.importLegacy();assert.equal(f.writes.length,1);assert.match(f.state.notice,/云端收藏已合并/);assert.match(f.state.notice,/已合并1件/);assert.equal(f.state.uncertain,false)
})
test('success notice survives subsequent read failure without stale rows',async()=>{
  let n=0;const f=fixture({load:()=>{if(++n>1)throw new Error('刷新超时');return page()}});await f.inbox.show();assert.equal(await f.inbox.remove(1),true)
  assert.equal(f.state.page,null);assert.match(f.state.notice,/已取消/);assert.match(f.state.error,/刷新超时/)
})
test('out of range page retries actual last page once',async()=>{
  const f=fixture({load:q=>q.page===1?page():page({records:[],page:q.page})});await f.inbox.show();await f.inbox.refresh(3)
  assert.deepEqual(f.reads.map(q=>q.page),[1,3,1]);assert.equal(f.state.page.page,1)
})
test('continuously shrinking totals stop after two reads',async()=>{
  const f=fixture({load:q=>q.page===1?page():page({records:[],total:q.page===3?6:0,page:q.page})});await f.inbox.show();await f.inbox.refresh(3)
  assert.equal(f.reads.length,3);assert.equal(f.state.page,null);assert.match(f.state.error,/重新刷新/)
})
test('invalid page never reaches transport',async()=>{
  const f=fixture();await f.inbox.show();for(const p of [0,-1,'1',1.1,1000001])await f.inbox.refresh(p);assert.equal(f.reads.length,1)
})
test('dispose is terminal even if show or identity callbacks run later',async()=>{
  const f=fixture();await f.inbox.show();f.inbox.dispose();await f.inbox.show();await f.inbox.identityChanged();await f.inbox.refresh();await f.inbox.importLegacy();assert.equal(f.reads.length,1);assert.equal(f.writes.length,0)
})
test('actual Vue page wires hide/show/unload and synchronous identity invalidation',async()=>{
  const source=await readFile(new URL('../pages/product/favorites.vue',import.meta.url),'utf8')
  const script=source.split('<script setup>')[1].split('</script>')[0].replace(/^import .*\n/gm,'')
  const hooks={},user={value:{id:101}},ref=value=>({value});let watched,watchOptions,token='a'
  const d=deferred(),writes=[]
  const app=new Function('computed','ref','watch','onShow','onHide','onUnload','getFavorites','setProductFavorite','currentUser','getToken','loadLegacyFavoriteIds','markFavoriteImported','createFavoriteInbox','FAVORITE_PAGE_SIZE','uni',script+';return {state,inbox,writesBlocked}')
    (f=>({get value(){return f()}}),ref,(getter,callback,options)=>{watched=callback;watchOptions=options},f=>hooks.show=f,f=>hooks.hide=f,f=>hooks.unload=f,async()=>page(),async(id,favorite)=>{writes.push([id,favorite]);return d.promise},user,()=>token,()=>[3,4],()=>{},createFavoriteInbox,5,{})
  assert.equal(watchOptions.flush,'sync');await hooks.show();const pending=app.inbox.importLegacy();hooks.hide()
  d.resolve({productId:3,favorite:true});await pending;assert.equal(writes.length,1);assert.equal(app.state.value.page,null)
  await hooks.show();token='b';await watched();assert.equal(app.state.value.page.total,2)
  hooks.unload();assert.equal(app.state.value.page,null);assert.ok(source.includes(':disabled="writesBlocked"'));assert.ok(!source.includes('v-html'))
})

import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { createPageTaskScope } from '../utils/page-task-scope.mjs'

const source = await readFile(new URL('../pages/product/mine.vue', import.meta.url), 'utf8')
const script = source.split('<script setup>')[1].split('</script>')[0].replace(/^import .*\n/gm, '')
const row = (id=1, changes={}) => ({ id, title:'卖家商品'+id, description:'虚构数据',
  priceInCents:1000, seller:{id:101,nickname:'测试卖家'}, status:'ON_SALE', version:0, ...changes })
const page = (changes={}) => ({records:[row()],total:1,page:1,size:5,...changes})
const deferred = () => { let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b});return {promise,resolve,reject} }
function mount(options={}) {
  const hooks={},reads=[],writes=[],modals=[],toasts=[],navigations=[],watchers=[]
  let token='a';const user={value:{id:101,nickname:'测试卖家'}},ref=value=>({value})
  const app=new Function('ref','computed','watch','onShow','onHide','onUnload','getMyProducts',
    'updateProductStatus','currentUser','getToken','createPageTaskScope','uni',script+
    ';return {records,total,page,loading,busyId,message,draftKeyword,activeKeyword,activeStatus,activeSort,listNeedsRefresh,pageVisible,fetchMine,changeStatus,markSold,searchMine,clearSearch,changeFilter,changeSort,openPage,refreshAll}')
  (ref,f=>({get value(){return f()}}),(getter,callback,config)=>watchers.push({getter,callback,config}),
    f=>hooks.show=f,f=>hooks.hide=f,f=>hooks.unload=f,
    async q=>{reads.push(q);return options.load?options.load(q):page()},
    async(id,status,version)=>{writes.push({id,status,version});return options.write?options.write(id,status,version):row(id,{status,version:version+1})},
    user,()=>token,createPageTaskScope,{showToast:v=>toasts.push(v),showModal:v=>{modals.push(v);if(options.modalThrow)throw new Error('弹窗失败')},navigateTo:v=>navigations.push(v)})
  return {app,hooks,reads,writes,modals,toasts,navigations,watchers,
    async identity(id,newToken){user.value=id?{id,nickname:'会话'+id}:null;token=newToken;for(const watcher of watchers)watcher.callback();await Promise.resolve()},
    replaceIdentitySilently(id,newToken){user.value=id?{id,nickname:'会话'+id}:null;token=newToken}}
}
test('task scope starts hidden and does not admit anonymous or malformed identity',()=>{
  for(const who of [null,{}, {userId:0,token:'a'}, {userId:'1',token:'a'}, {userId:1,token:''}]){
    const scope=createPageTaskScope(()=>who);assert.equal(scope.capture(),null);scope.show();assert.equal(scope.capture(),null)
  }
})
test('scope snapshots are immutable and expire on hide show reset or dispose',()=>{
  const scope=createPageTaskScope(()=>({userId:1,token:'a'}));scope.show();const a=scope.capture();assert.ok(Object.isFrozen(a));assert.ok(scope.valid(a))
  scope.invalidate();assert.equal(scope.valid(a),false);const b=scope.capture();scope.hide();assert.equal(scope.valid(b),false);assert.equal(scope.capture(),null)
  scope.show();assert.equal(scope.valid(b),false);scope.dispose();assert.equal(scope.show(),false);assert.equal(scope.capture(),null)
})
test('same-user token replacement invalidates captured task without a watcher',()=>{
  let token='a';const scope=createPageTaskScope(()=>({userId:1,token}));scope.show();const a=scope.capture();token='b';assert.equal(scope.valid(a),false);assert.equal(scope.valid(null),false)
})
test('actual page starts hidden and sends no read or write until shown',async()=>{
  const f=mount();await f.app.fetchMine();await f.app.changeStatus(row());await f.app.markSold(row());assert.equal(f.reads.length,0);assert.equal(f.writes.length,0);assert.equal(f.modals.length,0)
  await f.hooks.show();assert.equal(f.app.records.value.length,1);assert.equal(f.app.pageVisible.value,true)
})
test('show refreshes defaults; hide clears records query errors and busy state',async()=>{
  const f=mount();await f.hooks.show();f.app.draftKeyword.value='私有关键词';f.app.activeStatus.value='ON_SALE';f.app.message.value='旧错误';f.hooks.hide()
  assert.equal(f.app.pageVisible.value,false);assert.deepEqual(f.app.records.value,[]);assert.equal(f.app.draftKeyword.value,'');assert.equal(f.app.activeStatus.value,'');assert.equal(f.app.message.value,'');assert.equal(f.app.busyId.value,0)
  await f.hooks.show();assert.equal(f.reads.at(-1).page,1);assert.equal(f.reads.at(-1).status,'')
})
for(const action of ['hide','unload']) for(const fail of [false,true]){
  test(`${action} ignores a late ${fail?'failed':'successful'} listing`,async()=>{
    const d=deferred(),f=mount({load:()=>d.promise});const pending=f.hooks.show();f.hooks[action]()
    fail?d.reject(new Error('旧错误')):d.resolve(page());await pending
    assert.deepEqual(f.app.records.value,[]);assert.equal(f.app.loading.value,false);assert.equal(f.app.message.value,'')
  })
}
test('old read finally does not clear newer account loading or issue extra requests',async()=>{
  const a=deferred(),b=deferred();let n=0;const f=mount({load:()=>++n===1?a.promise:b.promise});const old=f.hooks.show();await f.identity(102,'b')
  a.resolve(page());await old;assert.equal(f.app.loading.value,true);assert.equal(f.reads.length,2)
  b.resolve(page({records:[row(2,{seller:{id:102,nickname:'新卖家'}})]}));await Promise.resolve();await Promise.resolve()
  assert.equal(f.app.loading.value,false);assert.equal(f.app.records.value[0].seller.id,102)
})
test('same account re-login clears snapshot and refetches with synchronous watcher',async()=>{
  const d=deferred();let n=0;const f=mount({load:()=>++n===1?page():d.promise});await f.hooks.show();assert.equal(f.watchers[0].config.flush,'sync')
  await f.identity(101,'b');assert.deepEqual(f.app.records.value,[]);assert.equal(f.app.loading.value,true);d.resolve(page());await Promise.resolve();await Promise.resolve();assert.equal(f.app.loading.value,false)
})
test('identity change while hidden waits until next show before fetching',async()=>{
  const f=mount();await f.hooks.show();f.hooks.hide();await f.identity(102,'b');assert.equal(f.reads.length,1)
})
test('a pending read refuses to render another account even without watcher notification',async()=>{
  const d=deferred(),f=mount({load:()=>d.promise});const pending=f.hooks.show();f.replaceIdentitySilently(102,'b');d.resolve(page());await pending;assert.equal(f.app.records.value.length,0)
})
test('malformed owner duplicate rows or inconsistent pagination never render',async()=>{
  for(const data of [page({records:[row(1,{seller:{id:102,nickname:'他人'}})]}),page({records:[row(),row()],total:2}),page({records:[],total:1}),page({page:2}),page({total:-1})]){
    const f=mount({load:()=>data});await f.hooks.show();assert.equal(f.app.records.value.length,0);assert.equal(f.app.listNeedsRefresh.value,true);assert.match(f.app.message.value,/响应格式/)
  }
})
test('filter and sort keep exact requested query semantics',async()=>{
  const f=mount({load:q=>page({records:[row(1,{status:q.status||'ON_SALE'})],page:q.page})});await f.hooks.show()
  await f.app.fetchMine(1,'OFF_SHELF','台灯','PRICE_ASC');assert.deepEqual(f.reads.at(-1),{page:1,size:5,status:'OFF_SHELF',keyword:'台灯',sort:'PRICE_ASC'});assert.equal(f.app.activeKeyword.value,'台灯')
})
test('invalid query sends no request and cross-status rows are rejected',async()=>{
  const f=mount();await f.hooks.show();for(const n of [0,1.2,1000001])await f.app.fetchMine(n)
  await f.app.fetchMine(1,'INVALID');await f.app.fetchMine(1,'','x'.repeat(51));await f.app.fetchMine(1,'','','INVALID');assert.equal(f.reads.length,1)
  assert.equal(await f.app.fetchMine(1,'SOLD'),false);assert.equal(f.app.activeStatus.value,'')
})
test('shrinking page retries once; continually shrinking totals are bounded',async()=>{
  const f=mount({load:q=>q.page===1?page():page({records:[],page:q.page,total:q.page===3?6:0})});await f.hooks.show();assert.equal(await f.app.fetchMine(3),false);assert.deepEqual(f.reads.map(q=>q.page),[1,3,2]);assert.match(f.app.message.value,/重新刷新/)
})
test('status change sends original version and accepts exactly the next version',async()=>{
  const f=mount();await f.hooks.show();assert.equal(await f.app.changeStatus(f.app.records.value[0]),true)
  assert.deepEqual(f.writes,[{id:1,status:'OFF_SHELF',version:0}]);assert.equal(f.app.records.value[0].status,'OFF_SHELF');assert.equal(f.app.records.value[0].version,1);assert.equal(f.toasts.length,1)
})
test('not-listed stale or foreign product cannot be submitted',async()=>{
  const f=mount();await f.hooks.show();await f.app.changeStatus(row(99));await f.app.changeStatus(row(1,{version:1}));await f.app.changeStatus(row(1,{seller:{id:102,nickname:'他人'}}));assert.equal(f.writes.length,0)
})
test('sold terminal row cannot reopen or show a confirmation',async()=>{
  const f=mount({load:()=>page({records:[row(1,{status:'SOLD'})]})});await f.hooks.show();await f.app.changeStatus(f.app.records.value[0]);await f.app.markSold(f.app.records.value[0]);assert.equal(f.writes.length,0);assert.equal(f.modals.length,0)
})
test('pending status write blocks duplicate writes reads edits and sale confirmation',async()=>{
  const d=deferred(),f=mount({write:()=>d.promise});await f.hooks.show();const item=f.app.records.value[0],pending=f.app.changeStatus(item)
  await f.app.changeStatus(item);await f.app.fetchMine();await f.app.markSold(item);f.app.openPage('edit',1)
  assert.equal(f.writes.length,1);assert.equal(f.reads.length,1);assert.equal(f.modals.length,0);assert.equal(f.navigations.length,0)
  d.resolve(row(1,{status:'OFF_SHELF',version:1}));await pending
})
for(const fail of [false,true]){
  test(`hidden late status ${fail?'error':'success'} does not mutate toast or refresh`,async()=>{
    const d=deferred(),f=mount({write:()=>d.promise});await f.hooks.show();const pending=f.app.changeStatus(f.app.records.value[0]);f.hooks.hide()
    fail?d.reject(new Error('旧错误')):d.resolve(row(1,{status:'OFF_SHELF',version:1}));await pending
    assert.equal(f.toasts.length,0);assert.equal(f.app.message.value,'');assert.equal(f.app.busyId.value,0);assert.equal(f.reads.length,1);assert.deepEqual(f.app.records.value,[])
  })
}
test('old write finally cannot unlock a newer session write',async()=>{
  const a=deferred(),b=deferred();let n=0;const f=mount({write:()=>++n===1?a.promise:b.promise});await f.hooks.show();const old=f.app.changeStatus(f.app.records.value[0])
  await f.identity(101,'b');await Promise.resolve();const fresh=f.app.changeStatus(f.app.records.value[0]);assert.equal(f.app.busyId.value,1)
  a.resolve(row(1,{status:'OFF_SHELF',version:1}));await old;assert.equal(f.app.busyId.value,1);assert.equal(f.toasts.length,0)
  b.resolve(row(1,{status:'OFF_SHELF',version:1}));await fresh;assert.equal(f.app.busyId.value,0);assert.equal(f.toasts.length,1)
})
test('uncertain result blocks status and editing until manual refresh',async()=>{
  const f=mount({write:()=>{throw Object.assign(new Error('连接超时'),{statusCode:0})}});await f.hooks.show();await f.app.changeStatus(f.app.records.value[0])
  assert.equal(f.app.listNeedsRefresh.value,true);assert.match(f.app.message.value,/刷新确认/);f.app.openPage('edit',1);await f.app.changeStatus(f.app.records.value[0]);assert.equal(f.navigations.length,0);assert.equal(f.writes.length,1)
  await f.app.fetchMine();assert.equal(f.app.listNeedsRefresh.value,false);f.app.openPage('edit',1);assert.equal(f.navigations.length,1)
})
test('malformed receipt is not success and requires refreshing',async()=>{
  for(const result of [row(1,{status:'OFF_SHELF',version:0}),row(2,{status:'OFF_SHELF',version:1}),row(1,{status:'SOLD',version:1}),row(1,{status:'OFF_SHELF',version:2})]){
    const f=mount({write:()=>result});await f.hooks.show();await f.app.changeStatus(f.app.records.value[0]);assert.equal(f.toasts.length,0);assert.equal(f.app.listNeedsRefresh.value,true)
  }
})
test('conflict performs a read only and never automatically retries PUT',async()=>{
  let n=0;const f=mount({load:()=>page({records:[row(1,{version:n++?1:0})]}),write:()=>{throw Object.assign(new Error('版本冲突'),{statusCode:409,code:'PRODUCT_CONFLICT'})}})
  await f.hooks.show();await f.app.changeStatus(f.app.records.value[0]);assert.equal(f.writes.length,1);assert.equal(f.reads.length,2);assert.equal(f.app.records.value[0].version,1);assert.match(f.app.message.value,/请确认最新内容/)
})
test('leaving during conflict refresh stops late feedback and cannot repopulate hidden page',async()=>{
  let n=0;const d=deferred(),f=mount({load:()=>++n===1?page():d.promise,write:()=>{throw Object.assign(new Error('版本冲突'),{statusCode:409,code:'PRODUCT_CONFLICT'})}})
  await f.hooks.show();const pending=f.app.changeStatus(f.app.records.value[0]);await Promise.resolve();await Promise.resolve();f.hooks.hide();d.resolve(page());await pending
  assert.deepEqual(f.app.records.value,[]);assert.equal(f.app.message.value,'');assert.equal(f.reads.length,2)
})
test('successful change leaving active filter refreshes and repairs last page',async()=>{
  let changed=false;const rows=Array.from({length:6},(_,i)=>row(i+1));const f=mount({load:q=>{
    const records=changed?rows.slice(0,5):rows;return page({records:records.slice((q.page-1)*5,q.page*5),total:records.length,page:q.page})
  },write:(id,status,version)=>{changed=true;return row(id,{status,version:version+1})}})
  await f.hooks.show();await f.app.fetchMine(2,'ON_SALE');assert.equal(await f.app.changeStatus(f.app.records.value[0]),true)
  assert.equal(f.app.page.value,1);assert.equal(f.app.total.value,5);assert.equal(f.app.activeStatus.value,'ON_SALE');assert.equal(f.app.listNeedsRefresh.value,false)
})
test('successful write followed by refresh failure says changed but refresh failed',async()=>{
  let changed=false;const f=mount({load:()=>{if(changed)throw new Error('读取超时');return page()},write:(id,status,version)=>{changed=true;return row(id,{status,version:version+1})}})
  await f.hooks.show();f.app.activeStatus.value='ON_SALE';await f.app.changeStatus(f.app.records.value[0]);assert.match(f.app.message.value,/状态已更新，但列表刷新失败/);assert.equal(f.app.listNeedsRefresh.value,true);assert.equal(f.writes.length,1)
})
test('sale confirmation sends no write until confirmed and checks captured version',async()=>{
  const f=mount();await f.hooks.show();const pending=f.app.markSold(f.app.records.value[0]);assert.equal(f.app.busyId.value,1);assert.equal(f.writes.length,0)
  f.modals[0].success({confirm:true});assert.equal(await pending,true);assert.deepEqual(f.writes,[{id:1,status:'SOLD',version:0}]);assert.equal(f.app.records.value[0].status,'SOLD')
})
for(const mode of ['cancel','fail','throw']){
  test(`confirmation ${mode} sends no state update`,async()=>{
    const f=mount({modalThrow:mode==='throw'});await f.hooks.show();const pending=f.app.markSold(f.app.records.value[0])
    if(mode==='cancel')f.modals[0].success({confirm:false});if(mode==='fail')f.modals[0].fail();assert.equal(await pending,false);assert.equal(f.writes.length,0);assert.equal(f.app.busyId.value,0)
  })
}
for(const action of ['hide','unload']){
  test(`sale confirmation after ${action} cannot issue PUT`,async()=>{
    const f=mount();await f.hooks.show();const pending=f.app.markSold(f.app.records.value[0]);f.hooks[action]();f.modals[0].success({confirm:true});assert.equal(await pending,false);assert.equal(f.writes.length,0);assert.equal(f.app.busyId.value,0)
  })
}
test('old sale dialog cannot unlock or submit over a newer session dialog',async()=>{
  const f=mount();await f.hooks.show();const old=f.app.markSold(f.app.records.value[0]);await f.identity(101,'b');await Promise.resolve();const fresh=f.app.markSold(f.app.records.value[0])
  assert.equal(f.modals.length,2);f.modals[0].success({confirm:true});await old;assert.equal(f.app.busyId.value,1);assert.equal(f.writes.length,0)
  f.modals[1].success({confirm:false});await fresh;assert.equal(f.app.busyId.value,0)
})
test('changed row version or status while confirming requires another review',async()=>{
  for(const changes of [{version:1},{status:'OFF_SHELF'}]){
    const f=mount();await f.hooks.show();const pending=f.app.markSold(f.app.records.value[0]);f.app.records.value=[row(1,changes)];f.modals[0].success({confirm:true});await pending;assert.equal(f.writes.length,0);assert.match(f.app.message.value,/信息已变化/)
  }
})
test('double sale confirmation is blocked and disposed page cannot reopen',async()=>{
  const f=mount();await f.hooks.show();const pending=f.app.markSold(f.app.records.value[0]);await f.app.markSold(f.app.records.value[0]);assert.equal(f.modals.length,1)
  f.hooks.unload();f.modals[0].success({confirm:false});await pending;await f.hooks.show();assert.equal(f.app.pageVisible.value,false);assert.equal(f.reads.length,1)
})
test('actual template unmounts owner summary on hide and blocks edit on stale data',()=>{
  assert.ok(source.includes('<OwnerProductSummary v-if="pageVisible"'))
  assert.ok(source.includes(':disabled="loading || busyId > 0 || listNeedsRefresh" @click="openPage(\'edit\''))
  assert.ok(!source.includes('v-html'))
})

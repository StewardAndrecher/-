import test from 'node:test'
import assert from 'node:assert/strict'
import {readFile} from 'node:fs/promises'
import {productLookupIds,validateProductLookup} from '../utils/product-lookup.mjs'
const product=(id=1)=>({id,title:'批量商品'+id,description:'公开样本',priceInCents:1000,seller:{id:101,nickname:'卖家'},status:'ON_SALE',coverImageId:null,coverImageUrl:null,version:0,category:{code:'BOOK',name:'教材书籍'},favoriteCount:0})
const payload=(ids,change=()=>undefined)=>({records:ids.map((id,i)=>({productId:id,product:change(id,i)??product(id)}))})
const history=(n=7)=>Array.from({length:n},(_,i)=>({productId:i+1,viewedAt:1700000000000-i}))
const deferred=()=>{let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b});return{promise,resolve,reject}}
const settle=async()=>{for(let i=0;i<12;i++)await Promise.resolve()}
const source=await readFile(new URL('../pages/product/history.vue',import.meta.url),'utf8'),script=source.split('<script setup>')[1].split('</script>')[0].replace(/^import .*\n/gm,'')
function mount(options={}){
 const hooks={},watchers=[],reads=[],loads=[],removes=[],clears=[],modals=[],paths=[];let token=options.guest?'':'a',user=options.guest?null:{id:101,nickname:'甲'},stored=options.history??history()
 const currentUser={get value(){return user},set value(value){user=value;for(const w of watchers){const next=w.get();const before=w.before;w.before=next;w.callback(next,before)}}}
 const app=new Function('ref','computed','watch','onShow','onHide','onUnload','getPublicProducts','currentUser','getToken','loadHistory','removeHistory','clearHistory','detailPath','uni',script+';return {rows,total,page,loading,confirming,busy,message,fetchHistory,removeOne,clearAll,openDetail,goHome}')
 (value=>({value}),fn=>({get value(){return fn()}}),(get,callback,config)=>watchers.push({get,callback,config,before:get()}),fn=>hooks.show=fn,fn=>hooks.hide=fn,fn=>hooks.unload=fn,
 async ids=>{reads.push([...ids]);return options.read?options.read(ids):ids.map(id=>({productId:id,product:product(id)}))},currentUser,()=>token,
 owner=>{loads.push(owner);if(options.storageError)throw new Error('存储失败');return options.load?options.load(owner):stored.map(v=>({...v}))},
 (owner,id)=>{if(options.removeError)throw new Error('移除失败');removes.push([owner,id]);stored=stored.filter(v=>v.productId!==id)},owner=>{if(options.clearError)throw new Error('清空失败');clears.push(owner);stored=[]},
 id=>'/pages/product/detail?id='+id,{showModal:args=>{modals.push(args);if(options.modalThrow)throw new Error('弹窗失败')},navigateTo:v=>paths.push(v),reLaunch:v=>paths.push(v),showToast(){}})
 return{app,hooks,reads,loads,removes,clears,modals,paths,watchers,identity(id,nextToken){token=nextToken;currentUser.value=id?{id,nickname:'新账号'}:null},nickname(){currentUser.value={...user,nickname:'仅改昵称'}}}
}
test('lookup IDs snapshot is bounded unique positive safe integers',()=>{
 const ids=[3,1],copy=productLookupIds(ids);ids.push(5);assert.deepEqual(copy,[3,1]);assert.doesNotThrow(()=>productLookupIds(Array.from({length:20},(_,i)=>i+1)))
 for(const v of [null,{},[],[0],[-1],['1'],[1.1],[true],[null],[Number.MAX_SAFE_INTEGER+1],[1,1],new Array(1),Array.from({length:21},(_,i)=>i+1)])assert.throws(()=>productLookupIds(v))
})
test('lookup response preserves input ordering and explicit null unavailable entries',()=>{
 const d={records:[{productId:3,product:null},{productId:1,product:product(1)}]};assert.equal(validateProductLookup(d,[3,1]),d.records);assert.doesNotThrow(()=>validateProductLookup(payload([1],id=>({...product(id),seller:null})),[1]))
})
test('lookup rejects wrong count order duplicate or unknown response fields',()=>{
 for(const d of [{records:[]},payload([2,1]),payload([1,1]),{...payload([1,2]),userId:101},{records:[{productId:1,product:product(1),viewedAt:1},{productId:2,product:product(2)}]}])assert.throws(()=>validateProductLookup(d,[1,2]))
})
test('lookup rejects hidden invalid or private product projections',()=>{
 for(const patch of [{status:'OFF_SHELF'},{id:2},{title:''},{priceInCents:0},{favoriteCount:-1},{seller:{id:1,nickname:'甲',username:'private'}},{category:{code:'INVALID',name:'异常'}},{passwordHash:'private'}])assert.throws(()=>validateProductLookup(payload([1],id=>({...product(id),...patch})),[1]))
})
test('actual API sends only explicit IDs with POST and no auth option',async()=>{
 const s=await readFile(new URL('../api/product-lookup.js',import.meta.url),'utf8'),calls=[]
 const api=new Function('request','productLookupIds','validateProductLookup',s.replace(/^import .*\n/gm,'').replace('export async function','async function')+';return getPublicProducts')(async q=>{calls.push(q);return payload(q.data.productIds)},productLookupIds,validateProductLookup)
 const result=await api([3,1]);assert.deepEqual(calls,[{path:'/api/products/lookup',method:'POST',data:{productIds:[3,1]}}]);assert.equal(result[0].productId,3);await assert.rejects(()=>api([]));assert.equal(calls.length,1)
})
test('actual history page uses one batch for five visible IDs and preserves local times',async()=>{
 const f=mount();await f.hooks.show();assert.deepEqual(f.reads,[[1,2,3,4,5]]);assert.equal(f.app.total.value,7);assert.equal(f.app.rows.value[0].viewedAt,history()[0].viewedAt);assert.equal(f.app.loading.value,false);assert.ok(!source.includes('getProduct('));assert.ok(!source.includes('Promise.allSettled'))
})
test('empty history requires no HTTP query',async()=>{
 const f=mount({history:[]});await f.hooks.show();assert.equal(f.reads.length,0);assert.equal(f.app.total.value,0);assert.deepEqual(f.app.rows.value,[])
})
test('second page sends only remaining IDs and out-of-range clamps locally',async()=>{
 const f=mount();await f.hooks.show();await f.app.fetchHistory(2);assert.deepEqual(f.reads.at(-1),[6,7]);await f.app.fetchHistory(99);assert.deepEqual(f.reads.at(-1),[6,7]);assert.equal(f.app.page.value,2)
})
test('explicit unavailable entry remains in correct history position',async()=>{
 const f=mount({read:ids=>ids.map(id=>({productId:id,product:id===2?null:product(id)}))});await f.hooks.show();assert.equal(f.app.rows.value.length,5);assert.equal(f.app.rows.value[1].productId,2);assert.equal(f.app.rows.value[1].unavailable,true);assert.equal(f.app.rows.value[2].product.id,3)
})
test('whole query failure keeps local rows as retryable errors rather than unavailable',async()=>{
 const f=mount({read:()=>{throw new Error('服务器失败')}});await f.hooks.show();assert.equal(f.app.total.value,7);assert.equal(f.app.rows.value.length,5);assert.ok(f.app.rows.value.every(v=>v.product===null&&!v.unavailable));assert.match(f.app.message.value,/本机浏览记录仍保留/);assert.equal(f.removes.length,0);assert.equal(f.clears.length,0)
})
test('local storage error sends no HTTP request',async()=>{
 const f=mount({storageError:true});await f.hooks.show();assert.equal(f.reads.length,0);assert.match(f.app.message.value,/本机浏览记录读取失败/);assert.equal(f.app.loading.value,false)
})
for(const action of ['hide','unload'])for(const fail of [false,true]){
 test(`${action} clears counts and ignores late batch ${fail?'error':'result'}`,async()=>{
  const d=deferred(),f=mount({read:()=>d.promise});const old=f.hooks.show();f.hooks[action]();fail?d.reject(new Error('旧错误')):d.resolve(payload([1,2,3,4,5]).records);await old;assert.deepEqual(f.app.rows.value,[]);assert.equal(f.app.total.value,0);assert.equal(f.app.page.value,1);assert.equal(f.app.loading.value,false);assert.equal(f.app.message.value,'')
 })
}
test('same-account new token clears old batch and cannot unlock new read',async()=>{
 const a=deferred(),b=deferred();let n=0;const f=mount({read:()=>++n===1?a.promise:b.promise});const old=f.hooks.show();f.identity(101,'b');a.resolve(payload([1,2,3,4,5]).records);await old;assert.equal(f.app.loading.value,true);assert.equal(f.app.rows.value.length,0);b.resolve(payload([1,2,3,4,5]).records);await settle();assert.equal(f.app.loading.value,false);assert.equal(f.watchers[0].config.flush,'sync')
})
test('nickname-only update keeps live result and does not refetch',async()=>{
 const f=mount();await f.hooks.show();const rows=f.app.rows.value;f.nickname();assert.equal(f.reads.length,1);assert.equal(f.app.rows.value,rows)
})
test('guest and account loads use separate storage owners without uploading them',async()=>{
 const f=mount({guest:true});await f.hooks.show();assert.deepEqual(f.loads,[0]);f.identity(101,'a');await settle();assert.deepEqual(f.loads,[0,101]);assert.ok(f.reads.every(Array.isArray));f.identity(null,'');await settle();assert.deepEqual(f.loads,[0,101,0])
})
test('pending batch blocks duplicate read mutation confirmation and navigation',async()=>{
 const d=deferred(),f=mount({read:()=>d.promise});const old=f.hooks.show();await f.app.fetchHistory();f.app.removeOne(1);f.app.clearAll();f.app.openDetail(1);f.app.goHome();assert.equal(f.reads.length,1);assert.equal(f.removes.length,0);assert.equal(f.modals.length,0);assert.equal(f.paths.length,0);d.resolve(payload([1,2,3,4,5]).records);await old
})
test('clear confirmation canceled means no storage or HTTP mutation',async()=>{
 const f=mount();await f.hooks.show();f.app.clearAll();assert.equal(f.app.confirming.value,true);f.modals[0].success({confirm:false});assert.equal(f.app.confirming.value,false);assert.equal(f.clears.length,0);assert.equal(f.reads.length,1)
})
test('clear confirmation blocks duplicate dialogs and actions',async()=>{
 const f=mount();await f.hooks.show();f.app.clearAll();f.app.clearAll();f.app.removeOne(1);await f.app.fetchHistory();f.app.openDetail(1);assert.equal(f.modals.length,1);assert.equal(f.removes.length,0);assert.equal(f.reads.length,1);assert.equal(f.paths.length,0);f.modals[0].success({confirm:false})
})
for(const action of ['hide','unload','identity']){
 test(`old clear confirmation after ${action} cannot erase current storage`,async()=>{
  const f=mount();await f.hooks.show();f.app.clearAll();action==='identity'?f.identity(102,'b'):f.hooks[action]();f.modals[0].success({confirm:true});await settle();assert.equal(f.clears.length,0);assert.equal(f.app.confirming.value,false)
 })
}
test('old confirmation cannot unlock a newer session confirmation',async()=>{
 const f=mount();await f.hooks.show();f.app.clearAll();f.identity(101,'b');await settle();f.app.clearAll();f.modals[0].success({confirm:true});assert.equal(f.app.confirming.value,true);assert.equal(f.clears.length,0);f.modals[1].success({confirm:false});assert.equal(f.app.confirming.value,false)
})
test('confirmed clear targets only current owner and empty page skips HTTP',async()=>{
 const f=mount();await f.hooks.show();f.app.clearAll();f.modals[0].success({confirm:true});await settle();assert.deepEqual(f.clears,[101]);assert.equal(f.app.total.value,0);assert.equal(f.reads.length,1);f.modals[0].success({confirm:true});assert.equal(f.clears.length,1)
})
test('dialog failure or throw releases lock without deleting',async()=>{
 for(const throwing of [false,true]){const f=mount({modalThrow:throwing});await f.hooks.show();f.app.clearAll();if(!throwing)f.modals[0].fail();assert.equal(f.app.confirming.value,false);assert.equal(f.clears.length,0);assert.match(f.app.message.value,/无法打开/)}
})
test('removal requires visible listed ID and repairs shrinking local page',async()=>{
 const f=mount({history:history(6)});await f.hooks.show();await f.app.fetchHistory(2);await f.app.removeOne(6);assert.deepEqual(f.removes,[[101,6]]);assert.equal(f.app.page.value,1);assert.deepEqual(f.reads.at(-1),[1,2,3,4,5]);f.app.removeOne(999);assert.equal(f.removes.length,1);f.hooks.hide();f.app.removeOne(1);assert.equal(f.removes.length,1)
})
test('unavailable items cannot navigate; current visible product can',async()=>{
 const f=mount({read:ids=>ids.map(id=>({productId:id,product:id===1?null:product(id)}))});await f.hooks.show();f.app.openDetail(1);f.app.openDetail(999);assert.equal(f.paths.length,0);f.app.openDetail(2);assert.equal(f.paths[0].url,'/pages/product/detail?id=2');f.hooks.hide();f.app.openDetail(2);assert.equal(f.paths.length,1)
})
test('invalid page and unloaded callbacks cannot start requests',async()=>{
 const f=mount();await f.hooks.show();for(const page of [0,-1,1.5,NaN,'2',1000001])await f.app.fetchHistory(page);assert.equal(f.reads.length,1);f.hooks.unload();await f.hooks.show();f.identity(102,'b');await f.app.fetchHistory();f.app.clearAll();f.app.goHome();assert.equal(f.reads.length,1);assert.equal(f.paths.length,0)
})
test('unchanged local history storage still separates guest/account and stores only ID/time',async()=>{
 const src=await readFile(new URL('../utils/browsing-history.js',import.meta.url),'utf8'),map=new Map()
 const storage=new Function('uni',src.replace(/export (const|function)/g,'$1')+';return {recordHistory,loadHistory,historyKey}')({getStorageSync:key=>map.get(key),setStorageSync:(key,value)=>map.set(key,value)})
 storage.recordHistory(0,product(1),1700000000000);storage.recordHistory(101,product(2),1700000000001);assert.deepEqual(storage.loadHistory(0,1700000000002),[{productId:1,viewedAt:1700000000000}]);assert.deepEqual(storage.loadHistory(101,1700000000002),[{productId:2,viewedAt:1700000000001}]);assert.notEqual(storage.historyKey(0),storage.historyKey(101));assert.ok(![...map.values()].join('').includes('批量商品'))
})

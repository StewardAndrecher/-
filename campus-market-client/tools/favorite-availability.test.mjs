import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { createFavoriteInbox, favoriteAvailability, favoriteKeyword, FAVORITE_AVAILABILITY } from '../utils/favorite-inbox.mjs'
const row=(id,available=true)=>({productId:id,product:available?{id,title:'可见商品'+id,description:'虚构数据',priceInCents:1000,status:'ON_SALE',version:0}:null})
const page=(records=[row(1)],changes={})=>({records,total:records.length,page:1,size:5,...changes})
const deferred=()=>{let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b});return{promise,resolve,reject}}
function fixture(options={}){
 let state,who={userId:101,token:'a'};const reads=[],writes=[]
 const inbox=createFavoriteInbox({identity:()=>who,load:async q=>{reads.push(q);return options.load?options.load(q):page(q.availability==='UNAVAILABLE'?[row(2,false)]:[row(1)])},setFavorite:async(id,favorite)=>{writes.push([id,favorite]);if(options.write)return options.write(id,favorite);return{productId:id,favorite}},loadLegacy:()=>options.legacy||[],markImported(){},notify:s=>{state=s}})
 return{inbox,reads,writes,get state(){return state},identity:v=>{who=v}}
}
test('availability is a strict allowlist with frozen display options',()=>{
 assert.equal(favoriteAvailability(),'ALL');for(const v of ['ALL','AVAILABLE','UNAVAILABLE'])assert.equal(favoriteAvailability(v),v)
 for(const v of ['',null,1,{},'available','ALL ','ALL,ALL','ALL\' OR 1=1'])assert.throws(()=>favoriteAvailability(v))
 assert.ok(Object.isFrozen(FAVORITE_AVAILABILITY));assert.ok(FAVORITE_AVAILABILITY.every(Object.isFrozen))
})
test('legacy load omits filter parameter and defaults to all',async()=>{
 const f=fixture();await f.inbox.show();assert.equal(f.state.availability,'ALL');assert.deepEqual(f.reads,[{page:1,size:5}])
})
test('switching filter requests first page with exact enum',async()=>{
 const f=fixture();await f.inbox.show();await f.inbox.filter('UNAVAILABLE');assert.deepEqual(f.reads.at(-1),{page:1,size:5,availability:'UNAVAILABLE'});assert.equal(f.state.page.records[0].product,null);assert.equal(f.state.availability,'UNAVAILABLE')
})
test('filter switch clears old rows before awaiting response',async()=>{
 const d=deferred(),f=fixture({load:q=>q.availability?d.promise:page()});await f.inbox.show();const pending=f.inbox.filter('AVAILABLE');assert.equal(f.state.page,null);assert.equal(f.state.loading,true);d.resolve(page());await pending
})
test('wrong visibility in a response is rejected instead of rendered',async()=>{
 for(const [filter,data] of [['AVAILABLE',page([row(2,false)])],['UNAVAILABLE',page()]]){
  const f=fixture({load:()=>data});await f.inbox.show();assert.equal(await f.inbox.filter(filter),false);assert.equal(f.state.page,null);assert.match(f.state.error,/筛选不匹配/)
 }
})
test('a filtered empty page is valid and retains selected filter',async()=>{
 const f=fixture({load:()=>page([])});await f.inbox.show();assert.equal(await f.inbox.filter('UNAVAILABLE'),true);assert.equal(f.state.page.total,0);assert.equal(f.state.availability,'UNAVAILABLE')
})
test('filtered pagination retains filter and server total',async()=>{
 const f=fixture({load:q=>page(q.page===2?[row(1,false)]:Array.from({length:5},(_,i)=>row(i+2,false)),{page:q.page,total:6})});await f.inbox.show();await f.inbox.filter('UNAVAILABLE');await f.inbox.refresh(2)
 assert.deepEqual(f.reads.at(-1),{page:2,size:5,availability:'UNAVAILABLE'});assert.equal(f.state.page.total,6)
})
test('late old filter response cannot overwrite newer choice',async()=>{
 const a=deferred(),b=deferred();const f=fixture({load:q=>!q.availability?page():q.availability==='AVAILABLE'?a.promise:b.promise});await f.inbox.show()
 const old=f.inbox.filter('AVAILABLE'),fresh=f.inbox.filter('UNAVAILABLE');a.resolve(page());await old;assert.equal(f.state.loading,true);assert.equal(f.state.availability,'UNAVAILABLE');b.resolve(page([row(2,false)]));await fresh;assert.equal(f.state.page.records[0].product,null)
})
test('invalid filter makes no request or mutation',async()=>{
 const f=fixture();await f.inbox.show();await f.inbox.filter('DROP');assert.equal(f.reads.length,1);assert.equal(f.writes.length,0);assert.equal(f.state.availability,'ALL')
})
test('filter change while hidden starts no request',async()=>{
 const f=fixture();await f.inbox.show();f.inbox.hide();await f.inbox.filter('AVAILABLE');assert.equal(f.reads.length,1)
})
test('hide and identity changes reset filter and clear prior account data',async()=>{
 const f=fixture();await f.inbox.show();await f.inbox.filter('UNAVAILABLE');f.inbox.hide();assert.equal(f.state.availability,'ALL');await f.inbox.show();await f.inbox.filter('UNAVAILABLE');f.identity({userId:102,token:'b'});await f.inbox.identityChanged();assert.equal(f.state.availability,'ALL');assert.deepEqual(f.reads.at(-1),{page:1,size:5})
})
test('cancellation after filtering preserves filter in follow-up read',async()=>{
 const f=fixture();await f.inbox.show();await f.inbox.filter('UNAVAILABLE');await f.inbox.remove(2)
 assert.deepEqual(f.writes,[[2,false]]);assert.deepEqual(f.reads.at(-1),{page:1,size:5,availability:'UNAVAILABLE'});assert.equal(f.state.availability,'UNAVAILABLE');assert.match(f.state.notice,/已取消/)
})
test('canceling final row of a filtered last page falls back with same filter',async()=>{
 let removed=false;const f=fixture({load:q=>page(removed?(q.page===2?[]:Array.from({length:5},(_,i)=>row(i+2,false))):(q.page===2?[row(1,false)]:Array.from({length:5},(_,i)=>row(i+2,false))),{page:q.page,total:removed?5:6}),write:(id,favorite)=>{removed=true;return{productId:id,favorite}}})
 await f.inbox.show();await f.inbox.filter('UNAVAILABLE');await f.inbox.refresh(2);await f.inbox.remove(1);assert.equal(f.state.page.page,1);assert.equal(f.state.page.total,5);assert.equal(f.state.availability,'UNAVAILABLE');assert.ok(f.reads.slice(-2).every(q=>q.availability==='UNAVAILABLE'))
})
test('uncertain write keeps filter for manual confirmation read',async()=>{
 const f=fixture({write:()=>{throw new Error('网络超时')}});await f.inbox.show();await f.inbox.filter('UNAVAILABLE');await f.inbox.remove(2);assert.equal(f.state.uncertain,true);assert.equal(f.state.availability,'UNAVAILABLE');await f.inbox.refresh();assert.equal(f.state.uncertain,false);assert.equal(f.reads.at(-1).availability,'UNAVAILABLE')
})
test('filter switch during pending mutation cannot change the operation scope',async()=>{
 const d=deferred(),f=fixture({write:()=>d.promise});await f.inbox.show();await f.inbox.filter('UNAVAILABLE');const pending=f.inbox.remove(2);await f.inbox.filter('AVAILABLE');assert.equal(f.state.availability,'UNAVAILABLE');d.resolve({productId:2,favorite:false});await pending;assert.equal(f.state.availability,'UNAVAILABLE')
})
test('legacy import uses explicit IDs independently then refreshes active filter',async()=>{
 const f=fixture({legacy:[9]});await f.inbox.show();await f.inbox.filter('UNAVAILABLE');await f.inbox.importLegacy();assert.deepEqual(f.writes,[[9,true]]);assert.equal(f.reads.at(-1).availability,'UNAVAILABLE')
})
test('unknown import result retains active filter and never auto repeats',async()=>{
 const f=fixture({legacy:[9],write:()=>{throw new Error('超时')}});await f.inbox.show();await f.inbox.filter('UNAVAILABLE');await f.inbox.importLegacy();assert.equal(f.state.availability,'UNAVAILABLE');assert.equal(f.state.uncertain,true);assert.equal(f.writes.length,1)
})
test('filtered read error shows selected choice and clears old list',async()=>{
 const f=fixture({load:q=>{if(q.availability)throw new Error('读取失败');return page()}});await f.inbox.show();await f.inbox.filter('AVAILABLE');assert.equal(f.state.page,null);assert.equal(f.state.availability,'AVAILABLE');assert.match(f.state.error,/读取失败/)
})
test('actual API forwards only normalized optional availability and stays authenticated',async()=>{
 const source=await readFile(new URL('../api/favorite.js',import.meta.url),'utf8'),calls=[]
 const fn=new Function('request','favoriteAvailability','favoriteKeyword',source.replace(/^import .*\n/gm,'').replace(/export function/g,'function')+';return getFavorites')(q=>{calls.push(q);return true},favoriteAvailability,favoriteKeyword)
 fn();fn({page:2,size:5,availability:'UNAVAILABLE'});assert.deepEqual(calls,[{path:'/api/favorites',auth:true,data:{page:1,size:5}},{path:'/api/favorites',auth:true,data:{page:2,size:5,availability:'UNAVAILABLE'}}]);assert.throws(()=>fn({availability:'INVALID'}));assert.equal(calls.length,2)
})
test('actual Vue script exposes correct filter labels and separates batch scope',async()=>{
 const source=await readFile(new URL('../pages/product/favorites.vue',import.meta.url),'utf8'),script=source.split('<script setup>')[1].split('</script>')[0].replace(/^import .*\n/gm,'');const hooks={}
 const app=new Function('ref','computed','watch','onShow','onHide','onUnload','currentUser','getToken','getFavorites','setProductFavorite','loadLegacyFavoriteIds','markFavoriteImported','createFavoriteInbox','FAVORITE_PAGE_SIZE','FAVORITE_AVAILABILITY','uni',script+';return {inbox,state,filterLabel}')
 (value=>({value}),f=>({get value(){return f()}}),()=>{},f=>hooks.show=f,f=>hooks.hide=f,f=>hooks.unload=f,{value:{id:101}},()=>'a',async q=>page(q.availability==='UNAVAILABLE'?[row(2,false)]:[row(1)]),async()=>{},()=>[],()=>{},createFavoriteInbox,5,FAVORITE_AVAILABILITY,{})
 await hooks.show();assert.equal(app.filterLabel.value,'全部收藏');await app.inbox.filter('UNAVAILABLE');assert.equal(app.filterLabel.value,'已失效商品');hooks.hide();assert.equal(app.filterLabel.value,'全部收藏');assert.ok(source.includes('批量管理全部收藏'));assert.ok(source.includes('当前筛选共'));assert.ok(source.includes('当前筛选下没有收藏商品'));assert.ok(!source.includes('v-html'))
})

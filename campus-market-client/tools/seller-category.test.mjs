import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { parseSellerId, validateSellerStore, sellerStoreCategory } from '../utils/seller-store.js'
const row=(id=1,seller=101,category='BOOK')=>({id,title:'分类商品'+id,description:'虚构',priceInCents:1000,status:'ON_SALE',version:0,favoriteCount:0,seller:{id:seller,nickname:'卖家'+seller},category:{code:category,name:category}})
const result=(id=101,q={},changes={})=>({seller:{id,nickname:'卖家'+id},products:{records:[row(1,id,q.category||'BOOK')],total:1,page:q.page||1,size:5,...changes}})
const deferred=()=>{let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b});return{promise,resolve,reject}}
const settle=async()=>{for(let i=0;i<12;i++)await Promise.resolve()}
const source=await readFile(new URL('../pages/user/store.vue',import.meta.url),'utf8'),script=source.split('<script setup>')[1].split('</script>')[0].replace(/^import .*\n/gm,'')
function mount(options={}){
 const hooks={},events={},reads=[],paths=[];let hash='#/pages/user/store?id=101'
 const window={location:{get hash(){return hash}},addEventListener:(type,fn)=>events[type]=fn,removeEventListener:(type,fn)=>{if(events[type]===fn)delete events[type]}}
 const app=new Function('ref','computed','onLoad','onShow','onHide','onUnload','getSellerStore','parseSellerId','webHashSellerId','detailPath','window','uni','getCurrentPages',script+';return {sellerId,store,page,sort,category,loading,message,fetchStore,changeSort,changeCategory,openDetail,syncWebRoute}')
 (value=>({value}),fn=>({get value(){return fn()}}),fn=>hooks.load=fn,fn=>hooks.show=fn,fn=>hooks.hide=fn,fn=>hooks.unload=fn,
 async(id,q)=>{reads.push({id,...q});return options.read?options.read(id,q):result(id,q)},parseSellerId,
 value=>{const m=/^#\/pages\/user\/store\?id=([^&]+)$/.exec(value);return m?parseSellerId(m[1]):null},id=>'/pages/product/detail?id='+id,window,{navigateTo:v=>paths.push(v),showToast(){}},()=>[])
 hooks.load({id:'101'})
 return{app,hooks,reads,events,paths,async route(id){hash='#/pages/user/store?id='+id;events.hashchange?.();await settle()}}
}
test('category allowlist distinguishes all and unclassified and rejects injected values',()=>{
 for(const v of ['','UNCLASSIFIED','BOOK','DIGITAL','DAILY','SPORT','OTHER'])assert.equal(sellerStoreCategory(v),v)
 for(const v of [null,{},1,'book',' BOOK','BOOK ','ALL',"BOOK' OR 1=1",'BOOK,DIGITAL'])assert.throws(()=>sellerStoreCategory(v));assert.equal(sellerStoreCategory(),'')
})
test('validator accepts exact matching filtered pagination',()=>{
 const d=result(101,{category:'BOOK'});assert.equal(validateSellerStore(d,101,1,5,'BOOK'),d);assert.deepEqual(validateSellerStore(result(),101,1,5),result())
})
test('validator rejects wrong category seller status or incomplete filtered page',()=>{
 for(const d of [result(101,{}, {records:[row(1,101,'DIGITAL')]}),result(102),result(101,{}, {records:[{...row(),status:'OFF_SHELF'}]}),result(101,{}, {records:[],total:1}),result(101,{}, {records:[row(),row()],total:2})])assert.throws(()=>validateSellerStore(d,101,1,5,'BOOK'))
})
test('validator allows empty beyond-last page but not fake empty in-range page',()=>{
 assert.doesNotThrow(()=>validateSellerStore(result(101,{page:3},{records:[],total:6}),101,3,5,'BOOK'))
 assert.throws(()=>validateSellerStore(result(101,{page:2},{records:[],total:6}),101,2,5,'BOOK'))
})
test('actual API sends optional category without auth and preserves legacy defaults',async()=>{
 const s=await readFile(new URL('../api/seller.js',import.meta.url),'utf8'),calls=[]
 const api=new Function('request','parseSellerId','validateSellerStore','sellerStoreCategory',s.replace(/^import .*\n/gm,'').replace('export async function','async function')+';return getSellerStore')(async v=>{calls.push(v);return result(101,v.data)},parseSellerId,validateSellerStore,sellerStoreCategory)
 await api(101);await api(101,{category:'BOOK',sort:'PRICE_ASC'});assert.deepEqual(calls,[{path:'/api/sellers/101/products',data:{page:1,size:5,sort:'NEWEST'}},{path:'/api/sellers/101/products',data:{page:1,size:5,sort:'PRICE_ASC',category:'BOOK'}}])
 for(const q of [{category:null},{category:'INVALID'},{sort:'p.id DESC'},{page:0},{page:1000001},{size:51}])await assert.rejects(()=>api(101,q));assert.equal(calls.length,2)
})
test('actual page shows all by default and uses reusable category picker',async()=>{
 const f=mount();await f.hooks.show();assert.deepEqual(f.reads,[{id:101,page:1,size:5,sort:'NEWEST',category:''}]);assert.equal(f.app.category.value,'');assert.ok(source.includes('CategoryPicker'));assert.ok(source.includes('include-all'));assert.ok(source.includes('当前分类共'))
})
test('category choice resets page one and retains current sort',async()=>{
 const f=mount();await f.hooks.show();await f.app.changeSort('PRICE_ASC');await f.app.changeCategory('BOOK');assert.deepEqual(f.reads.at(-1),{id:101,page:1,size:5,sort:'PRICE_ASC',category:'BOOK'});assert.equal(f.app.category.value,'BOOK')
})
test('pagination refresh and sort changes retain current category',async()=>{
 const f=mount({read:(id,q)=>result(id,q,{total:6,records:q.page===2?[row(1,id,'BOOK')]:Array.from({length:5},(_,i)=>row(i+2,id,q.category||'BOOK'))})});await f.hooks.show();await f.app.changeCategory('BOOK');await f.app.fetchStore(2);await f.app.fetchStore();assert.equal(f.reads.at(-1).page,2);assert.equal(f.reads.at(-1).category,'BOOK');await f.app.changeSort('PRICE_DESC');assert.equal(f.reads.at(-1).page,1);assert.equal(f.reads.at(-1).category,'BOOK')
})
test('all selection clears category without changing sort',async()=>{
 const f=mount();await f.hooks.show();await f.app.changeCategory('BOOK');await f.app.changeSort('MOST_FAVORITED');await f.app.changeCategory('');assert.equal(f.reads.at(-1).category,'');assert.equal(f.reads.at(-1).sort,'MOST_FAVORITED')
})
test('empty category result commits empty scope instead of prior records',async()=>{
 const f=mount({read:(id,q)=>q.category==='SPORT'?result(id,q,{records:[],total:0}):result(id,q)});await f.hooks.show();await f.app.changeCategory('SPORT');assert.equal(f.app.category.value,'SPORT');assert.equal(f.app.store.value.products.total,0);assert.deepEqual(f.app.store.value.products.records,[]);assert.ok(source.includes('当前分类下暂无在售商品'))
})
test('failed category read does not claim that filter was applied',async()=>{
 const f=mount({read:(id,q)=>{if(q.category==='BOOK')throw new Error('网络失败');return result(id,q)}});await f.hooks.show();await f.app.changeCategory('BOOK');assert.equal(f.app.category.value,'');assert.equal(f.app.store.value,null);assert.match(f.app.message.value,/网络失败/);assert.equal(f.app.loading.value,false)
})
for(const action of ['hide','unload'])for(const fail of [false,true]){
 test(`${action} ignores late category ${fail?'error':'result'}`,async()=>{
  const d=deferred(),f=mount({read:(id,q)=>q.category?d.promise:result(id,q)});await f.hooks.show();const old=f.app.changeCategory('BOOK');f.hooks[action]();fail?d.reject(new Error('旧错误')):d.resolve(result(101,{category:'BOOK'}));await old;assert.equal(f.app.store.value,null);assert.equal(f.app.category.value,'');assert.equal(f.app.loading.value,false);assert.equal(f.app.message.value,'')
 })
}
test('return to same seller refreshes saved filter without reusing old snapshot',async()=>{
 const f=mount();await f.hooks.show();await f.app.changeCategory('BOOK');f.hooks.hide();assert.equal(f.app.store.value,null);await f.hooks.show();assert.equal(f.reads.at(-1).category,'BOOK');assert.equal(f.app.store.value.seller.id,101)
})
test('switching seller invalidates request and resets category and sort',async()=>{
 const d=deferred();let count=0;const f=mount({read:(id,q)=>{if(id===101&&q.category==='BOOK'&&++count>1)return d.promise;return result(id,q)}});await f.hooks.show();await f.app.changeCategory('BOOK');const old=f.app.changeSort('PRICE_ASC');await f.route(102);assert.equal(f.app.category.value,'');assert.equal(f.app.sort.value,'NEWEST');assert.deepEqual(f.reads.at(-1),{id:102,page:1,size:5,sort:'NEWEST',category:''});d.resolve(result(101,{category:'BOOK'}));await old;assert.equal(f.app.store.value.seller.id,102)
})
test('late old seller finally cannot unlock a pending new seller load',async()=>{
 const a=deferred(),b=deferred(),f=mount({read:(id,q)=>id===102?b.promise:q.category?a.promise:result(id,q)});await f.hooks.show();const old=f.app.changeCategory('BOOK');await f.route(102);a.resolve(result(101,{category:'BOOK'}));await old;assert.equal(f.app.loading.value,true);b.resolve(result(102));await settle();assert.equal(f.app.loading.value,false);assert.equal(f.app.store.value.seller.id,102)
})
test('one bounded last-page repair retains category and sort',async()=>{
 const f=mount({read:(id,q)=>result(id,q,{total:1,records:q.page===1?[row(1,id,q.category||'BOOK')]:[]})});await f.hooks.show();await f.app.changeCategory('BOOK');await f.app.fetchStore(2,'PRICE_ASC');assert.deepEqual(f.reads.slice(-2).map(q=>[q.page,q.category,q.sort]),[[2,'BOOK','PRICE_ASC'],[1,'BOOK','PRICE_ASC']]);assert.equal(f.app.page.value,1)
})
test('continuously shrinking pages stop after two attempts',async()=>{
 const f=mount({read:(id,q)=>q.page===1?result(id,q):result(id,q,{records:[],total:q.page===3?6:0})});await f.hooks.show();const n=f.reads.length;await f.app.fetchStore(3);assert.equal(f.reads.length-n,2);assert.equal(f.app.store.value,null);assert.match(f.app.message.value,/数量正在变化/);assert.equal(f.app.loading.value,false)
})
test('pending load blocks filter sort refresh and detail navigation',async()=>{
 const d=deferred(),f=mount({read:()=>d.promise});const old=f.hooks.show();await f.app.changeCategory('BOOK');await f.app.changeSort('PRICE_ASC');await f.app.fetchStore();f.app.openDetail(1);assert.equal(f.reads.length,1);assert.equal(f.paths.length,0);d.resolve(result());await old
})
test('unload is terminal and removes hash listener',async()=>{
 const f=mount();await f.hooks.show();f.hooks.unload();await f.hooks.show();await f.app.changeCategory('BOOK');assert.equal(f.reads.length,1);assert.equal(f.events.hashchange,undefined)
})

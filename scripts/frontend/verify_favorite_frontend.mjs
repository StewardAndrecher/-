import fs from 'node:fs'
import path from 'node:path'
import assert from 'node:assert/strict'
import {createRequire} from 'node:module'
const require=createRequire(import.meta.url)
const {parse,compileScript,compileTemplate}=require(process.env.UNI_COMPILER_HOME+'/node_modules/@vue/compiler-sfc')
const root=path.resolve('campus-market-client')
const vue=await import('../.frontend-work/esm/node_modules/vue/index.mjs')
const session=await import('../.frontend-work/esm/utils/session.js'),api=await import('../.frontend-work/esm/api/favorite.js'),store=await import('../.frontend-work/esm/utils/account-favorites.js'),legacy=await import('../.frontend-work/esm/utils/favorites.js')
for(const name of ['pages/index/index.vue','pages/product/detail.vue','pages/product/edit.vue','pages/product/mine.vue','pages/product/favorites.vue','components/ProductComments.vue','components/ProductComments.vue','components/ProductCard.vue','components/ImagePicker.vue','pages/user/login.vue','pages/user/register.vue']){
 const filename=path.join(root,name),{descriptor,errors}=parse(fs.readFileSync(filename,'utf8'),{filename});assert.deepEqual(errors,[])
 const script=compileScript(descriptor,{id:name});assert.deepEqual(compileTemplate({source:descriptor.template.content,filename,id:name,compilerOptions:{bindingMetadata:script.bindings}}).errors,[])
}
const user={id:1,username:'student_01',nickname:'小明'},other={id:2,username:'student_02',nickname:'另一用户'}
let requests=[],storage=new Map()
globalThis.uni={request:o=>requests.push(o),getStorageSync:k=>storage.get(k),setStorageSync:(k,v)=>storage.set(k,v)}
async function login(user,letter='A'){session.saveSession({accessToken:letter.repeat(43),tokenType:'Bearer',expiresInSeconds:7200,user});await vue.nextTick()}
session.clearSession();await vue.nextTick();await assert.rejects(api.getFavorites(),e=>e.statusCode===401);assert.equal(requests.length,0)
await login(user)
let pending=store.loadAccountFavorites([7,6]);assert.equal(store.favoriteLoading.value,true);assert.equal(requests.at(-1).data.productIds,'7,6');assert.equal(requests.at(-1).header.Authorization,'Bearer '+'A'.repeat(43));requests.at(-1).success({statusCode:200,data:[7]});await pending;assert.deepEqual(store.favoriteIds.value,[7])
pending=store.changeAccountFavorite(6,true);assert.equal(requests.at(-1).method,'PUT');assert.deepEqual(store.favoriteIds.value,[7]);let n=requests.length;assert.equal(await store.changeAccountFavorite(6,true),false);assert.equal(requests.length,n);requests.at(-1).success({statusCode:200,data:{productId:6,favorite:true}});assert.equal(await pending,true);assert.deepEqual(store.favoriteIds.value,[7,6])
pending=store.changeAccountFavorite(7,false);assert.equal(requests.at(-1).method,'DELETE');requests.at(-1).fail({});assert.equal(await pending,false);assert.deepEqual(store.favoriteIds.value,[7,6]);assert.match(store.favoriteMessage.value,/刷新确认/)
// An old response after switching accounts must not mutate new account state.
pending=store.loadAccountFavorites([7]);const old=requests.at(-1);await login(other,'B');assert.deepEqual(store.favoriteIds.value,[]);old.success({statusCode:200,data:[7]});await pending;assert.deepEqual(store.favoriteIds.value,[])
pending=store.changeAccountFavorite(7,true);const oldWrite=requests.at(-1);await login(user,'C');oldWrite.success({statusCode:200,data:{productId:7,favorite:true}});assert.equal(await pending,false);assert.deepEqual(store.favoriteIds.value,[])
// A read queued during mutation must synchronize the latest displayed page afterward.
pending=store.changeAccountFavorite(7,true);const writing=requests.at(-1);await store.loadAccountFavorites([6]);writing.success({statusCode:200,data:{productId:7,favorite:true}});await Promise.resolve();assert.equal(requests.at(-1).data.productIds,'6');requests.at(-1).success({statusCode:200,data:[6]});await pending;assert.deepEqual(store.favoriteIds.value,[6])
pending=store.loadAccountFavorites([7]);const older=requests.at(-1);const newer=store.loadAccountFavorites([6]);requests.at(-1).success({statusCode:200,data:[6]});await newer;older.success({statusCode:200,data:[7]});await pending;assert.deepEqual(store.favoriteIds.value,[6])
pending=store.loadAccountFavorites([7]);requests.at(-1).success({statusCode:200,data:[99]});await pending;assert.deepEqual(store.favoriteIds.value,[]);assert.match(store.favoriteMessage.value,/读取失败/)
const original='[7,6,7]';storage.set('campus-market-favorite-ids-v1',original);assert.deepEqual(legacy.loadLegacyFavoriteIds(1),[7,6]);legacy.markFavoriteImported(1,7);assert.deepEqual(legacy.loadLegacyFavoriteIds(1),[6]);assert.deepEqual(legacy.loadLegacyFavoriteIds(2),[7,6]);assert.equal(storage.get('campus-market-favorite-ids-v1'),original)

const script=parse(fs.readFileSync(path.join(root,'pages/product/favorites.vue'),'utf8')).descriptor.scriptSetup.content.replace(/^import .*$/mg,'')
const names=['ref','computed','watch','onShow','onUnload','getFavorites','setProductFavorite','currentUser','getToken','loadLegacyFavoriteIds','markFavoriteImported','uni','getCurrentPages']
const factory=new Function(...names,script+'\nreturn {records,total,page,loading,removingId,importing,message,legacyIds,fetchFavorites,removeFavorite,importLegacy,readLegacy,isValidItem};')
const product={id:7,title:'测试商品',description:'正常',priceInCents:1250,status:'ON_SALE',version:6}
const item={productId:7,product},unavailable={productId:6,product:null},ref=value=>({value})
function setup(options={}){
 let token=options.loggedOut?'':'A'.repeat(43),watch,unload,calls=0,writes=[],marks=[];const currentUser=ref(options.loggedOut?null:user),events=[]
 const env={ref,computed:get=>({get value(){return get()}}),watch:(get,cb)=>watch=cb,onHide:()=>{},onShow:()=>{},onUnload:cb=>unload=cb,
  getFavorites:async({page,size})=>{calls++;return options.read?options.read({page,size}):{records:[item,unavailable],total:2,page,size}},
  setProductFavorite:async(id,favorite)=>{writes.push({id,favorite});return options.write?options.write(id,favorite):{productId:id,favorite}},currentUser,getToken:()=>token,
  loadLegacyFavoriteIds:()=>options.legacy||[7,6],markFavoriteImported:(u,id)=>marks.push({u,id}),uni:{navigateTo:o=>events.push(o.url)},getCurrentPages:()=>[{},{}]}
 return {model:factory(...names.map(n=>env[n])),calls:()=>calls,writes,marks,events,unload:()=>unload(),setUser:u=>{currentUser.value=u;token=u?'B'.repeat(43):'';watch()}}
}
let t=setup({loggedOut:true});await t.model.fetchFavorites();assert.equal(t.calls(),0)
t=setup();await t.model.fetchFavorites();assert.equal(t.model.records.value.length,2);assert.equal(t.model.isValidItem({productId:6,product:undefined}),false);await t.model.removeFavorite(6);assert.deepEqual(t.writes,[{id:6,favorite:false}]);assert.equal(t.calls(),2)
t=setup({read:async({page,size})=>page===2?{records:[],total:5,page,size}:{records:[item],total:5,page,size}});await t.model.fetchFavorites(2);assert.equal(t.model.page.value,1);assert.equal(t.calls(),2)
let complete;t=setup({write:()=>new Promise(r=>complete=r)});await t.model.fetchFavorites();pending=t.model.removeFavorite(7);await t.model.removeFavorite(7);assert.equal(t.writes.length,1);t.setUser(other);complete({productId:7,favorite:false});await pending;assert.equal(t.model.records.value.length,0);assert.equal(t.calls(),1)
t=setup({write:async()=>{throw Object.assign(new Error('网络'),{statusCode:0})}});await t.model.fetchFavorites();await t.model.removeFavorite(7);assert.match(t.model.message.value,/刷新确认/);assert.equal(t.calls(),1)
t=setup({write:async(id,favorite)=>{if(id===6)throw Object.assign(new Error('下架'),{statusCode:404});return{productId:id,favorite}}});t.model.readLegacy();await t.model.importLegacy();assert.deepEqual(t.marks,[{u:1,id:7}]);assert.deepEqual(t.model.legacyIds.value,[6]);assert.match(t.model.message.value,/已合并1件，1件不可用/)
let finish;t=setup({read:()=>new Promise(r=>finish=r)});pending=t.model.fetchFavorites();t.unload();finish({records:[item],total:1,page:1,size:5});await pending;assert.equal(t.model.records.value.length,0)
const home=fs.readFileSync(path.join(root,'pages/index/index.vue'),'utf8');assert(!home.includes('saveFavoriteIds'));assert(!home.includes('favoriteIds.value = loadFavoriteIds'));assert(home.includes('await loadAccountFavorites(products.value.map(item => item.id))'))
const routes=JSON.parse(fs.readFileSync(path.join(root,'pages.json'),'utf8')).pages;assert.equal(routes.filter(p=>p.path==='pages/product/favorites').length,1)
console.log('PASS: 9 Vue SFCs; favorite API auth/PUT/DELETE; confirmed-only updates; duplicate guards; account/stale-read/stale-write protection; queued page synchronization; legacy markers scoped per user without altering old cache; paginated collection, unavailable rows, page shrinking, cancellation, imports and unload guards; homepage no longer writes local favorites')

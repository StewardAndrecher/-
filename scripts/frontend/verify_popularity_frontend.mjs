import fs from 'node:fs'
import assert from 'node:assert/strict'
import {createRequire} from 'node:module'
const require=createRequire(import.meta.url), {parse}=require(process.env.UNI_COMPILER_HOME+'/node_modules/@vue/compiler-sfc')
const popularity=await import('../.frontend-work/esm/utils/product-popularity.js')
const {isValidFavoriteCount,favoriteCountText}=popularity
assert.equal(favoriteCountText(0),'0 人收藏');assert.equal(favoriteCountText(3,true),'3 人收藏（上次查询）')
for(const value of [undefined,null,-1,1.5,'1',NaN,Infinity,9007199254740992]) {assert.equal(isValidFavoriteCount(value),false);assert.equal(favoriteCountText(value),'收藏人数待同步')}
let requests=[];globalThis.uni={request:o=>requests.push(o)}
const sorts=await import('../.frontend-work/esm/api/sort.js'), productsApi=await import('../.frontend-work/esm/api/product.js')
let pending=sorts.getProductSorts();const catalog=[{code:'NEWEST',name:'最新发布'},{code:'PRICE_ASC',name:'价格从低到高'},{code:'PRICE_DESC',name:'价格从高到低'},{code:'MOST_FAVORITED',name:'收藏最多'}]
requests.at(-1).success({statusCode:200,data:catalog});assert.equal((await pending)[3].code,'MOST_FAVORITED')
pending=productsApi.getProducts({sort:'MOST_FAVORITED',category:'BOOK',keyword:'100%'});assert.deepEqual(requests.at(-1).data,{page:1,size:5,keyword:'100%',category:'BOOK',sort:'MOST_FAVORITED'});requests.at(-1).success({statusCode:200,data:{}});await pending
const ref=value=>({value}),home=parse(fs.readFileSync('campus-market-client/pages/index/index.vue','utf8')).descriptor.scriptSetup.content
const code=home.slice(home.indexOf('function isValidProduct'),home.indexOf('function openMyFavorites'))
const product={id:7,title:'教材',description:'',priceInCents:1250,status:'ON_SALE',version:0,favoriteCount:2,seller:null}
function model(){let token='A'.repeat(43),changeResult=true,saved,resolvePage,rejectPage,resolveWrite;const criteria=[],writes=[]
 const state={products:ref([product]),page:ref(1),total:ref(1),keyword:ref(''),activeKeyword:ref(''),activeCategory:ref(''),activeSort:ref('MOST_FAVORITED'),dataSource:ref('后端接口'),storageMessage:ref(''),requestError:ref(''),loading:ref(false),publishing:ref(false),PAGE_SIZE:5,PAGE_CACHE_KEY:'cache',isValidFavoriteCount,
  currentUser:ref({id:1}),getToken:()=>token,favoriteIds:ref([]),onlyFavorites:ref(false),openLogin:()=>writes.push('login'),loadAccountFavorites:async()=>{},changeAccountFavorite:(...args)=>{writes.push(args);return new Promise(r=>resolveWrite=r)},
  getProducts:args=>{criteria.push(args);return new Promise((r,j)=>{resolvePage=r;rejectPage=j})},uni:{getStorageSync:()=>saved,setStorageSync:(_k,v)=>saved=v}}
 const page=new Function(...Object.keys(state),code+'\nreturn {toggleFavorite,fetchProducts,loadLocalData,isValidProduct};')(...Object.values(state))
 return {page,state,criteria,writes,write:v=>resolveWrite(v),response:rows=>resolvePage({records:rows,total:rows.length,page:1,size:5}),fail:()=>rejectPage(new Error('刷新断网')),saved:()=>saved,setToken:v=>token=v,setSaved:v=>saved=v}
}
let m=model();for(const value of [undefined,-1,'2'])assert.equal(m.page.isValidProduct({...product,favoriteCount:value}),false)
// Confirmed write triggers a server read; no arithmetic or optimistic count mutation.
pending=m.page.toggleFavorite(7);assert.equal(m.criteria.length,0);assert.equal(m.state.products.value[0].favoriteCount,2)
m.write(true);await new Promise(r=>setImmediate(r));assert.equal(m.state.dataSource.value,'待刷新');assert.equal(m.state.products.value[0].favoriteCount,2);assert.equal(m.criteria[0].sort,'MOST_FAVORITED')
m.response([{...product,favoriteCount:6}]);await pending;assert.equal(m.state.products.value[0].favoriteCount,6);assert.equal(m.state.dataSource.value,'后端接口');assert.equal(JSON.parse(m.saved()).sort,'MOST_FAVORITED')
m.state.activeSort.value='NEWEST';m.page.loadLocalData();assert.equal(m.state.activeSort.value,'MOST_FAVORITED');assert.equal(m.state.dataSource.value,'本地缓存')
// An unconfirmed write and a response from an old identity must not refresh or change counts.
m=model();pending=m.page.toggleFavorite(7);m.write(false);await pending;assert.equal(m.criteria.length,0);assert.equal(m.state.products.value[0].favoriteCount,2)
m=model();pending=m.page.toggleFavorite(7);m.setToken('B'.repeat(43));m.write(true);await pending;assert.equal(m.criteria.length,0)
// Confirmed write, failed refresh retains old count marked stale.
m=model();pending=m.page.toggleFavorite(7);m.write(true);await new Promise(r=>setImmediate(r));m.fail();await pending;assert.equal(m.state.products.value[0].favoriteCount,2);assert.equal(m.state.dataSource.value,'待刷新');assert.match(m.state.requestError.value,/断网/)
// Corrupt or old-schema cache is rejected rather than shown as zero.
m=model();m.setSaved(JSON.stringify({records:[{...product,favoriteCount:undefined}],total:1,page:1,size:5,keyword:'',category:'',sort:'MOST_FAVORITED'}));m.page.loadLocalData();assert.match(m.state.storageMessage.value,/缓存失败/);assert.equal(m.state.dataSource.value,'后端接口')
assert(home.includes('campus-market-page-cache-v7'))
console.log('PASS: safe counts/zero/unknown/stale label; dynamic popular catalog and combined API criteria; confirmed-only refresh, no optimistic arithmetic, old-token guard, failed-refresh labeling, popularity cache roundtrip and old/malformed cache rejection.')

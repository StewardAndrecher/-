import fs from 'node:fs'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
const require=createRequire(import.meta.url)
const {parse}=require(process.env.UNI_COMPILER_HOME+'/node_modules/@vue/compiler-sfc')
const root='campus-market-client/'
const ref=value=>({value}),computed=fn=>({get value(){return fn()}})
let calls=[];globalThis.uni={request:o=>calls.push(o)}
const api=await import('../.frontend-work/esm/api/sort.js');let p=api.getProductSorts()
assert.equal(calls.at(-1).url,'http://localhost:8080/api/product-sorts');assert.equal(calls.at(-1).header.Authorization,undefined)
const catalog=[{code:'NEWEST',name:'最新发布'},{code:'PRICE_ASC',name:'价格从低到高'},{code:'PRICE_DESC',name:'价格从高到低'}]
calls.at(-1).success({statusCode:200,data:catalog});assert.deepEqual(await p,catalog)
for(const value of [[],[{code:'price_asc',name:'低价'}],[catalog[0],catalog[0]],[{code:'NEWEST',name:''}]]) {
 p=api.getProductSorts();calls.at(-1).success({statusCode:200,data:value});await assert.rejects(p,/排序列表格式/)
}
const productApi=await import('../.frontend-work/esm/api/product.js');p=productApi.getProducts({page:2,size:5,keyword:'教材',category:'BOOK',sort:'PRICE_DESC'})
assert.deepEqual(calls.at(-1).data,{page:2,size:5,keyword:'教材',category:'BOOK',sort:'PRICE_DESC'});calls.at(-1).success({statusCode:200,data:{}});await p
const picker=parse(fs.readFileSync(root+'components/SortPicker.vue','utf8')).descriptor.scriptSetup.content.replace(/^import .*$/mg,'')
const factory=new Function('ref','computed','onMounted','onUnmounted','getProductSorts','defineProps','defineEmits',picker+'\nreturn {load,choose,items,error,loading,selectedName};')
function setupPicker(){let mount,unmount,resolve,reject,events=[];const props={modelValue:'NEWEST',disabled:false}
 const model=factory(ref,computed,cb=>mount=cb,cb=>unmount=cb,()=>new Promise((r,j)=>{resolve=r;reject=j}),()=>props,()=>((...args)=>events.push(args)))
 return {model,props,events,mount:()=>mount(),unmount:()=>unmount(),resolve:v=>resolve(v),reject:e=>reject(e)}
}
let x=setupPicker();p=x.mount();x.model.choose({detail:{value:1}});assert.equal(x.events.length,0);assert.equal(x.model.loading.value,true)
x.resolve(catalog);await p;assert.equal(x.model.selectedName.value,'最新发布');x.model.choose({detail:{value:2}});assert.deepEqual(x.events.at(-1),['update:modelValue','PRICE_DESC'])
x.props.modelValue='PRICE_DESC';assert.equal(x.model.selectedName.value,'价格从高到低');x.props.disabled=true;x.model.choose({detail:{value:1}});assert.equal(x.events.length,1)
x=setupPicker();p=x.mount();x.reject(new Error('测试失败'));await p;assert.match(x.model.error.value,/失败/);x.model.choose({detail:{value:1}});assert.equal(x.events.length,0)
p=x.model.load();x.resolve(catalog);await p;assert.equal(x.model.error.value,'');assert.equal(x.model.items.value.length,3)
x=setupPicker();p=x.mount();x.unmount();x.resolve(catalog);await p;assert.equal(x.model.items.value.length,0);x.model.choose({detail:{value:1}});assert.equal(x.events.length,0)
const home=parse(fs.readFileSync(root+'pages/index/index.vue','utf8')).descriptor.scriptSetup.content
const code=home.slice(home.indexOf('function isValidProduct'),home.indexOf('function toggleFavoritesFilter'))
let saved,queries=[],resolve,reject
const popularity=await import('../.frontend-work/esm/utils/product-popularity.js')
const state={isValidFavoriteCount:popularity.isValidFavoriteCount,products:ref([]),page:ref(2),total:ref(7),keyword:ref('教材'),activeKeyword:ref('教材'),activeCategory:ref('BOOK'),activeSort:ref('NEWEST'),dataSource:ref('待加载'),storageMessage:ref(''),loading:ref(false),publishing:ref(false),requestError:ref(''),PAGE_SIZE:5,PAGE_CACHE_KEY:'cache'}
Object.assign(state,{uni:{getStorageSync:()=>saved,setStorageSync:(k,v)=>saved=v},getProducts:args=>{queries.push(args);return new Promise((r,j)=>{resolve=r;reject=j})},loadAccountFavorites:async()=>{}})
const f=new Function(...Object.keys(state),code+'\nreturn {fetchProducts,changeSort,changeCategory,searchProducts,loadLocalData};')(...Object.values(state))
const item={id:1,title:'教材',description:'',priceInCents:1200,status:'ON_SALE',version:0,favoriteCount:0,seller:null,category:{code:'BOOK',name:'教材书籍'}}
const finish=async()=>{resolve({records:[item],total:1,page:1,size:5});await new Promise(r=>setImmediate(r))}
f.changeSort('PRICE_ASC');assert.deepEqual(queries.at(-1),{page:1,size:5,keyword:'教材',category:'BOOK',sort:'PRICE_ASC'});assert.equal(state.activeSort.value,'NEWEST')
f.changeSort('PRICE_DESC');assert.equal(queries.length,1);await finish();assert.equal(state.activeSort.value,'PRICE_ASC');assert.equal(state.page.value,1);assert.equal(JSON.parse(saved).sort,'PRICE_ASC')
state.activeSort.value='NEWEST';f.loadLocalData();assert.equal(state.activeSort.value,'PRICE_ASC');assert.equal(state.dataSource.value,'本地缓存')
f.changeCategory('UNCLASSIFIED');assert.equal(queries.at(-1).sort,'PRICE_ASC');await finish();assert.equal(state.activeCategory.value,'UNCLASSIFIED')
f.searchProducts();assert.equal(queries.at(-1).sort,'PRICE_ASC');await finish()
p=f.fetchProducts(2);assert.equal(queries.at(-1).sort,'PRICE_ASC');assert.equal(queries.at(-1).category,'UNCLASSIFIED');resolve({records:[],total:1,page:2,size:5});await p
p=f.fetchProducts(1,'教材','BOOK','PRICE_DESC');reject(new Error('测试断网'));await p;assert.equal(state.activeSort.value,'PRICE_ASC');assert.equal(state.activeCategory.value,'UNCLASSIFIED');assert.equal(state.page.value,2)
saved=JSON.stringify({...JSON.parse(saved),sort:'invalid'});f.loadLocalData();assert.match(state.storageMessage.value,/读取商品缓存失败/);assert.equal(state.activeSort.value,'PRICE_ASC')
console.log('PASS: anonymous sort API and malformed responses, sort request, picker loading/disabled/retry/unmount, page reset, combined criteria/paging, busy guard, failure retention, cached sort restoration and invalid cache rejection')

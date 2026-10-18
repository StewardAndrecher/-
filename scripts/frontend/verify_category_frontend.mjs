import fs from 'node:fs'
import path from 'node:path'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
const require=createRequire(import.meta.url)
const {parse,compileScript,compileTemplate}=require(process.env.UNI_COMPILER_HOME+'/node_modules/@vue/compiler-sfc')
const root=path.resolve('campus-market-client')
let count=0
function walk(dir){for(const item of fs.readdirSync(dir,{withFileTypes:true})){
 if(['node_modules','unpackage'].includes(item.name))continue
 const p=path.join(dir,item.name);if(item.isDirectory())walk(p)
 else if(p.endsWith('.vue') && item.name!=='App.vue'){const {descriptor,errors}=parse(fs.readFileSync(p,'utf8'),{filename:p});assert.deepEqual(errors,[])
 const compiled=compileScript(descriptor,{id:p});assert.deepEqual(compileTemplate({source:descriptor.template.content,id:p,filename:p,compilerOptions:{bindingMetadata:compiled.bindings}}).errors,[]);count++}
}}
walk(root);assert.equal(count,28)
let calls=[]
globalThis.uni={request:o=>calls.push(o)}
const categories=await import('../.frontend-work/esm/api/category.js')
let operation=categories.getCategories();assert.equal(calls.at(-1).url,'http://localhost:8080/api/categories');assert.equal(calls.at(-1).header.Authorization,undefined)
calls.at(-1).success({statusCode:200,data:[{code:'BOOK',name:'教材书籍'}]});assert.equal((await operation)[0].name,'教材书籍')
for(const data of [[],[{code:'book',name:'错误'}],[{code:'BOOK',name:'书'},{code:'BOOK',name:'重复'}]]) {
 operation=categories.getCategories();calls.at(-1).success({statusCode:200,data});await assert.rejects(operation,/分类列表格式/)
}
const api=await import('../.frontend-work/esm/api/product.js')
operation=api.getProducts({page:2,size:5,keyword:'100%',category:'BOOK',sort:'NEWEST'});assert.deepEqual(calls.at(-1).data,{page:2,size:5,keyword:'100%',category:'BOOK',sort:'NEWEST'});calls.at(-1).success({statusCode:200,data:{}});await operation
const ref=value=>({value});const computed=fn=>({get value(){return fn()}})
const picker=parse(fs.readFileSync(path.join(root,'components/CategoryPicker.vue'),'utf8')).descriptor.scriptSetup.content.replace(/^import .*$/mg,'')
let events=[],ready,resolveCatalog
const props={modelValue:'OTHER',includeAll:false,disabled:false}
const model=new Function('ref','computed','onMounted','getCategories','defineProps','defineEmits',picker+'\nreturn {load,choose,items,error,loading,options,selectedName};')(ref,computed,cb=>ready=cb,()=>new Promise(r=>resolveCatalog=r),()=>props,()=>((...args)=>events.push(args)))
let loading=ready();assert.equal(model.loading.value,true);model.choose({detail:{value:0}});assert.deepEqual(events,[['ready',false]])
resolveCatalog([{code:'BOOK',name:'教材书籍'},{code:'OTHER',name:'其他'}]);await loading
assert.equal(model.selectedName.value,'其他');assert.deepEqual(events.at(-1),['ready',true]);model.choose({detail:{value:0}});assert.deepEqual(events.at(-1),['update:modelValue','BOOK'])
props.includeAll=true;props.modelValue='';assert.equal(model.selectedName.value,'全部分类');props.disabled=true;let size=events.length;model.choose({detail:{value:1}});assert.equal(events.length,size)
// Test actual list/cache functions: commit criteria only after successful response.
const home=parse(fs.readFileSync(path.join(root,'pages/index/index.vue'),'utf8')).descriptor.scriptSetup.content
const code=home.slice(home.indexOf('function isValidProduct'),home.indexOf('function toggleFavoritesFilter'))
const popularity=await import('../.frontend-work/esm/utils/product-popularity.js')
const state={isValidFavoriteCount:popularity.isValidFavoriteCount,products:ref([]),page:ref(3),total:ref(9),keyword:ref('教材'),activeKeyword:ref(''),activeCategory:ref(''),activeSort:ref('NEWEST'),dataSource:ref('待加载'),storageMessage:ref(''),loading:ref(false),publishing:ref(false),requestError:ref(''),PAGE_SIZE:5,PAGE_CACHE_KEY:'cache'}
let saved,requests=[],resolvePage,rejectPage
Object.assign(state,{uni:{getStorageSync:()=>saved,setStorageSync:(k,v)=>saved=v},getProducts:args=>{requests.push(args);return new Promise((r,j)=>{resolvePage=r;rejectPage=j})},loadAccountFavorites:async()=>{}})
const functions=new Function(...Object.keys(state),code+'\nreturn {fetchProducts,changeCategory,searchProducts,loadLocalData};')(...Object.values(state))
const item={id:1,title:'教材甲',description:'',priceInCents:1200,status:'ON_SALE',version:0,favoriteCount:0,seller:null,category:{code:'BOOK',name:'教材书籍'}}
operation=functions.fetchProducts(1,'教材','BOOK');assert.deepEqual(requests.at(-1),{page:1,size:5,keyword:'教材',category:'BOOK',sort:'NEWEST'})
await functions.fetchProducts(2,'','OTHER');assert.equal(requests.length,1,'busy guard prevents stale concurrent filter')
resolvePage({records:[item],total:1,page:1,size:5});await operation
assert.equal(state.activeCategory.value,'BOOK');assert.equal(state.page.value,1);assert.equal(JSON.parse(saved).category,'BOOK')
state.activeCategory.value='';functions.loadLocalData();assert.equal(state.activeCategory.value,'BOOK');assert.equal(state.dataSource.value,'本地缓存')
operation=functions.fetchProducts(1,'','DAILY');rejectPage(new Error('测试断网'));await operation
assert.equal(state.activeCategory.value,'BOOK');assert.equal(state.products.value[0].id,1);assert.match(state.requestError.value,/断网/)
console.log('PASS: category API validation/anonymous access, 25 SFCs, shared picker loading/disabled/all/labels, combined request criteria, page reset, cache criteria and failure retention/concurrent guards')

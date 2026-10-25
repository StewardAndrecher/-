import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
const read = file => readFileSync(new URL('../' + file, import.meta.url), 'utf8')
const ref = value => ({value})
const computed = getter => ({get value(){ return getter() }})
function categoryFixture() {
  const state = {calls:0, pending:[], events:[], hooks:{}}
  const props = {modelValue:'',disabled:false}
  const getCategories = () => {state.calls++;return new Promise((resolve,reject)=>state.pending.push({resolve,reject}))}
  const script = read('components/CategoryTabs.vue').split('<script setup>')[1].split('</script>')[0].replace(/^import .+$/gm,'')
  const app = new Function('ref','computed','onMounted','onUnmounted','getCategories','defineProps','defineEmits',script+';return {load,choose,items,options,error,loading}')(
    ref,computed,fn=>state.hooks.mount=fn,fn=>state.hooks.unmount=fn,getCategories,()=>props,()=>((...event)=>state.events.push(event)))
  return {state,props,app}
}
test('categories come from the server; all-products remains available',async()=>{
  const f=categoryFixture(),loading=f.app.load()
  f.app.choose('BOOK');assert.deepEqual(f.state.events,[])
  f.state.pending[0].resolve([{code:'BOOK',name:'教材书籍'}]);await loading
  assert.deepEqual(f.app.options.value,[{code:'',name:'全部好物'},{code:'BOOK',name:'教材书籍'}])
  f.app.choose('BOOK');f.app.choose('CLOTHING');f.app.choose('')
  assert.deepEqual(f.state.events,[['update:modelValue','BOOK'],['update:modelValue','']])
})
test('disabled category cannot trigger a new query',async()=>{
  const f=categoryFixture(),loading=f.app.load();f.state.pending[0].resolve([{code:'BOOK',name:'教材书籍'}]);await loading
  f.props.disabled=true;f.app.choose('BOOK');assert.deepEqual(f.state.events,[])
})
test('failed category load is recoverable and duplicate loading is blocked',async()=>{
  const f=categoryFixture(),first=f.app.load();await f.app.load();assert.equal(f.state.calls,1)
  f.state.pending[0].reject(new Error('分类请求失败'));await first;assert.equal(f.app.error.value,'分类请求失败')
  const second=f.app.load();f.state.pending[1].resolve([{code:'DAILY',name:'生活用品'}]);await second
  assert.equal(f.app.error.value,'');assert.equal(f.app.loading.value,false);assert.equal(f.app.items.value[0].code,'DAILY')
})
test('unmounted category ignores late result and navigation',async()=>{
  const f=categoryFixture(),first=f.app.load();f.state.hooks.unmount();f.state.pending[0].resolve([{code:'BOOK',name:'教材书籍'}]);await first
  assert.deepEqual(f.app.items.value,[]);f.app.choose('');await f.app.load();assert.equal(f.state.calls,1);assert.deepEqual(f.state.events,[])
})
const cardScript=read('components/ProductCard.vue').split('<script setup>')[1].split('</script>')[0]
const priceFunction=cardScript.slice(cardScript.indexOf('function formatPrice'),cardScript.indexOf('function requestOpenDetail'))
const formatPrice=new Function(priceFunction+';return formatPrice')()
test('compact prices preserve cents and remove only redundant trailing zeros',()=>{
  for(const [cents,expected] of [[0,'0'],[100,'1'],[110,'1.1'],[101,'1.01'],[1250,'12.5'],[99999999,'999999.99']]) assert.equal(formatPrice(cents),expected)
})
function navigationFixture({count=1,current=1,user=null}={}) {
  const state={fetches:[],focus:0,login:0,profile:0,toggles:0}
  const page=ref(current),totalPages=ref(count),loading=ref(false),publishing=ref(false),uploadingCover=ref(false),keyword=ref(''),favoriteLoading=ref(false),favoriteBusyId=ref(0),currentUser=ref(user),intentionPageActive=ref(true)
  const source=read('pages/index/index.vue'),script=source.slice(source.indexOf('const hotKeywords ='),source.indexOf('// Keep the form mounted;'))
  const names=['computed','page','totalPages','loading','publishing','uploadingCover','keyword','favoriteLoading','favoriteBusyId','currentUser','getToken','openLogin','openProfile','toggleFavoritesFilter','fetchProducts','focusCatalogue','homeClosed','intentionPageActive','searchProducts']
  const values=[computed,page,totalPages,loading,publishing,uploadingCover,keyword,favoriteLoading,favoriteBusyId,currentUser,()=>currentUser.value?'test-token':'',()=>state.login++,()=>state.profile++,()=>state.toggles++,async number=>state.fetches.push(number),()=>state.focus++,false,intentionPageActive,()=>state.fetches.push(keyword.value)]
  const app=new Function(...names,script+';return {pageButtons,goToPage,searchHotKeyword,filterAccountFavorites,openAccount}')(...values)
  return {app,state,page,loading,intentionPageActive}
}
test('pagination renders valid edge and ellipsis windows for a large catalogue',()=>{
  for(let current=1;current<=32;current++){
    const f=navigationFixture({count:32,current}),numbers=f.app.pageButtons.value.filter(v=>Number.isInteger(v))
    assert.ok(numbers.includes(current));assert.equal(numbers[0],1);assert.equal(numbers.at(-1),32)
    assert.equal(new Set(numbers).size,numbers.length);assert.ok(numbers.length<=5)
  }
  assert.deepEqual(navigationFixture().app.pageButtons.value,[1])
})
test('page navigation rejects invalid and duplicate busy requests',async()=>{
  const f=navigationFixture({count:3});for(const n of [0,4,1.1,'2']) await f.app.goToPage(n)
  f.loading.value=true;await f.app.goToPage(2);assert.deepEqual(f.state.fetches,[])
  f.loading.value=false;await f.app.goToPage(2);assert.deepEqual(f.state.fetches,[2]);assert.equal(f.state.focus,1)
})
test('leaving the homepage during a page request prevents late scrolling',async()=>{
  const f=navigationFixture({count:3}),pending=f.app.goToPage(2);f.intentionPageActive.value=false;await pending;assert.equal(f.state.focus,0)
})
test('guest favorites and account actions open login instead of changing a filter',()=>{
  const f=navigationFixture();f.app.filterAccountFavorites();f.app.openAccount()
  assert.equal(f.state.login,2);assert.equal(f.state.toggles,0)
})
test('hot search reuses the normal query path and respects busy state',()=>{
  const f=navigationFixture();f.loading.value=true;f.app.searchHotKeyword('台灯');assert.deepEqual(f.state.fetches,[])
  f.loading.value=false;f.app.searchHotKeyword('台灯');f.app.searchHotKeyword('');assert.deepEqual(f.state.fetches,['台灯',''])
})

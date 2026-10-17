import fs from 'node:fs'
import assert from 'node:assert/strict'
const pending=await import('../.frontend-work/esm/utils/product-publications.js')
const api=await import('../.frontend-work/esm/api/publication.js')
const session=await import('../.frontend-work/esm/utils/session.js')
const ref=value=>({value})
let store=new Map(),writes=[],requests=[]
const uuid='12345678-1234-4234-8234-123456789abc'
function storage(){return {getStorageSync:key=>store.get(key)||'',setStorageSync:(k,v)=>{writes.push(k);store.set(k,v)},removeStorageSync:k=>store.delete(k)}}
globalThis.uni={...storage(),request:o=>requests.push(o)}
const product={title:'闲置台灯',description:'正常使用',priceInCents:950,coverImageId:'',categoryCode:'DAILY'}
const item={requestId:uuid,createdAt:Date.now(),product}
assert.equal(pending.loadPendingPublication(1),null)
const saved=pending.savePendingPublication(1,item)
assert.equal(Object.isFrozen(saved.product),true)
assert.deepEqual(pending.loadPendingPublication(1),saved)
assert.equal(pending.loadPendingPublication(2),null)
assert.equal(pending.savePendingPublication(1,item).requestId,uuid)
assert.throws(()=>pending.savePendingPublication(1,{...item,product:{...product,title:'另外商品'}}))
assert.throws(()=>pending.clearPendingPublication(1,'different'))
assert.equal(store.size,1)
pending.clearPendingPublication(1,uuid);assert.equal(store.size,0)
for(const categoryCode of ['UNCLASSIFIED','BOOK','DIGITAL','DAILY','SPORT','OTHER']){
 pending.savePendingPublication(1,{...item,product:{...product,categoryCode}});pending.clearPendingPublication(1,uuid)
}
assert.throws(()=>pending.savePendingPublication(1,{...item,product:{...product,priceInCents:0}}))
assert.throws(()=>pending.savePendingPublication(1,{...item,product:{...product,title:' 未修剪 '}}))
store.set('campus-publication-pending-v1:1','bad JSON');assert.throws(()=>pending.loadPendingPublication(1));store.clear()
const user={id:1,username:'student_01',nickname:'小明'},token='A'.repeat(43)
session.clearSession();await assert.rejects(api.submitPublication(item),e=>e.statusCode===401);assert.equal(requests.length,0)
session.saveSession({accessToken:token,tokenType:'Bearer',expiresInSeconds:7200,user})
let request=api.submitPublication(item);assert.equal(requests.at(-1).header.Authorization,'Bearer '+token)
assert.deepEqual(requests.at(-1).data,{requestId:uuid,product});requests.at(-1).success({statusCode:201,data:{productId:8,requestId:uuid,replayed:false}});assert.equal((await request).productId,8)
request=api.submitPublication(item);requests.at(-1).success({statusCode:200,data:{productId:8,requestId:'bad',replayed:true}});await assert.rejects(request,/回执格式异常/)
const home=fs.readFileSync('campus-market-client/pages/index/index.vue','utf8')
const code=home.slice(home.indexOf('function clearPublicationDraft'),home.indexOf('watch(currentUser'))+
 home.slice(home.indexOf('async function publishProduct'),home.indexOf('function openProductDetail'))
const names=['activeSavedDraft','loading','publishing','checkingAccount','exiting','publishError','getToken','currentUser','openLogin','draftTitle','draftDescription','draftPrice','draftCoverId','draftCategory','publishCategoryReady','uploadingCover','pendingPublication','publicationMessage','publicationStorageBlocked','newPublicationRequestId','loadPendingPublication','savePendingPublication','clearPendingPublication','submitPublication','keyword','onlyFavorites','uni','fetchProducts']
const factory=new Function(...names,'let homeClosed=false;\n'+code+'\nreturn {publishProduct,restorePublication,close:()=>{homeClosed=true}}')
function setup(handler,loggedIn=true){
 store=new Map();writes=[];const events=[];let calls=0,payloads=[],activeToken=loggedIn?token:''
 const state={activeSavedDraft:ref(null),loading:ref(false),publishing:ref(false),checkingAccount:ref(false),exiting:ref(false),publishError:ref(''),getToken:()=>activeToken,currentUser:ref(loggedIn?user:null),openLogin:()=>events.push('login'),draftTitle:ref('闲置台灯'),draftDescription:ref('正常使用'),draftPrice:ref('9.50'),draftCoverId:ref(''),draftCategory:ref('DAILY'),publishCategoryReady:ref(true),uploadingCover:ref(false),pendingPublication:ref(null),publicationMessage:ref(''),publicationStorageBlocked:ref(false),...pending,newPublicationRequestId:()=>uuid,
 submitPublication:async data=>{assert.ok(store.size,'must persist before network');calls++;payloads.push(data);return handler(data,calls)},keyword:ref('台灯'),onlyFavorites:ref(true),uni:{showToast:o=>events.push(o.title)},fetchProducts:async(...args)=>events.push(args)}
 globalThis.uni=storage()
 return {state,events,calls:()=>calls,payloads,...factory(...names.map(n=>state[n])),changeAccount:()=>{activeToken='B'.repeat(43);state.currentUser.value={...user,id:2}}}
}
const receipt={productId:8,requestId:uuid,replayed:false}
let m=setup(async()=>receipt,false);await m.publishProduct();assert.equal(m.calls(),0);assert.deepEqual(m.events,['login'])
m=setup(async()=>receipt);await m.publishProduct();assert.equal(m.state.draftTitle.value,'');assert.equal(store.size,0);assert.match(m.state.publicationMessage.value,/编号：8/);assert.deepEqual(m.events,['发布成功',[1,'','','NEWEST']])
m=setup(async(data,calls)=>{if(calls===1)throw Object.assign(new Error('timeout'),{statusCode:0});return {...receipt,replayed:true}})
await m.publishProduct();assert.equal(store.size,1);assert.match(m.state.publishError.value,/原内容已保存/)
m.state.draftTitle.value='试图改变的草稿';m.state.publishCategoryReady.value=false;await m.publishProduct();assert.equal(m.payloads[0],m.payloads[1]);assert.equal(m.payloads[1].product.title,'闲置台灯');assert.equal(store.size,0);assert.equal(m.events[0],'已确认原商品')
for(const statusCode of [409,500,0,401]){m=setup(async()=>{throw Object.assign(new Error('保留记录'),{statusCode})});await m.publishProduct();assert.equal(store.size,1);assert.equal(m.state.pendingPublication.value.requestId,uuid)}
m=setup(async()=>{throw Object.assign(new Error('校验失败'),{statusCode:400,code:'INVALID_PRODUCT'})});await m.publishProduct();assert.equal(store.size,0);assert.equal(m.state.pendingPublication.value,null);assert.equal(m.state.draftTitle.value,'闲置台灯')
m=setup(async()=>receipt);globalThis.uni.setStorageSync=()=>{throw new Error('storage failed')};await m.publishProduct();assert.equal(m.calls(),0);assert.equal(store.size,0)
m=setup(async()=>receipt);m.state.draftPrice.value='0';await m.publishProduct();assert.equal(m.calls(),0)
m=setup(async()=>receipt);pending.savePendingPublication(1,item);m.restorePublication(1);assert.equal(m.state.draftPrice.value,'9.50');assert.equal(m.state.pendingPublication.value.requestId,uuid)
m=setup(async()=>receipt);globalThis.uni.removeStorageSync=()=>{};await m.publishProduct();assert.equal(m.state.pendingPublication.value.requestId,uuid);assert.equal(store.size,1);assert.match(m.state.publicationMessage.value,/编号：8/)
let finish;m=setup(()=>new Promise(r=>finish=r));let first=m.publishProduct();await m.publishProduct();assert.equal(m.calls(),1);m.changeAccount();finish(receipt);await first;assert.equal(store.size,1);assert.equal(m.events.length,0)
m=setup(()=>new Promise(r=>finish=r));first=m.publishProduct();m.close();finish(receipt);await first;assert.equal(store.size,1);assert.equal(m.events.length,0)
assert.match(home,/if \(previous\?\.id\) clearPublicationDraft\(\)/)
const draftApi=await import('../../campus-market-client/utils/product-drafts.mjs')
m=setup(async()=>receipt);const localDraft=draftApi.saveProductDraft(1,{title:'闲置台灯',description:'正常使用',priceText:'9.50',coverImageId:'',categoryCode:'DAILY'},{id:uuid});m.state.activeSavedDraft.value={id:localDraft.id,revision:localDraft.revision};await m.publishProduct();assert.equal(m.calls(),1);assert.equal(m.state.activeSavedDraft.value,null);assert.equal(draftApi.loadProductDrafts(1).length,1);assert.equal(pending.loadPendingPublication(1),null);assert.match(m.state.publicationMessage.value,/本机草稿仍保留/)
console.log('PASS publication: durable account-scoped frozen snapshots; category whitelist; receipt validation; persistence before POST; timeout same-key/content retry; 400 unlock; 401/409/500 retention; reload restore; storage failure; stale account/unload guard; duplicate-click guard.')

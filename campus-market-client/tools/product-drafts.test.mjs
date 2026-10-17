import test,{beforeEach} from 'node:test'
import assert from 'node:assert/strict'
import {loadProductDrafts,saveProductDraft,removeProductDraft,hasDraftContent} from '../utils/product-drafts.mjs'
let storage,writes
const id=n=>'00000000-0000-4000-8000-'+n.toString(16).padStart(12,'0')
const content={title:'未完成台灯',description:'九成新',priceText:'',coverImageId:'',categoryCode:'OTHER'}
const blank={title:'',description:'',priceText:'',coverImageId:'',categoryCode:'OTHER'}
beforeEach(()=>{storage=new Map();writes=0;globalThis.uni={getStorageSync:k=>storage.get(k)||'',setStorageSync:(k,v)=>{writes++;storage.set(k,v)}}})
test('partial content roundtrip only persists whitelisted fields',()=>{
 const item=saveProductDraft(1,{...content,password:'never-store',token:'never-store'},{id:id(1)},100)
 assert.equal(item.revision,1);assert.equal(loadProductDrafts(1)[0].content.priceText,'');assert(!storage.values().next().value.includes('never-store'));assert(!hasDraftContent(blank));assert.throws(()=>saveProductDraft(1,blank,{id:id(2)},101),/先填写/)
})
test('account keys isolate content and reject forged stored ownership',()=>{
 saveProductDraft(1,content,{id:id(1)},100);assert.deepEqual(loadProductDrafts(2),[])
 storage.set('campus-product-drafts-v1:2',storage.get('campus-product-drafts-v1:1'));assert.throws(()=>loadProductDrafts(2),/格式/)
})
test('update increments revision without duplicating draft and changes latest order',()=>{
 saveProductDraft(1,content,{id:id(1)},100);saveProductDraft(1,content,{id:id(2)},101)
 const updated=saveProductDraft(1,{...content,title:'更新台灯'},{id:id(1),expectedRevision:1},102)
 assert.equal(updated.revision,2);assert.deepEqual(loadProductDrafts(1).map(x=>x.id),[id(1),id(2)]);assert.equal(loadProductDrafts(1).length,2)
})
test('eleventh draft is refused without evicting existing ten',()=>{
 for(let i=1;i<=10;i++)saveProductDraft(1,content,{id:id(i)},i)
 const before=storage.get('campus-product-drafts-v1:1');assert.throws(()=>saveProductDraft(1,content,{id:id(11)},11),/最多/);assert.equal(storage.get('campus-product-drafts-v1:1'),before)
})
test('stale update cannot overwrite known newer revision',()=>{
 saveProductDraft(1,content,{id:id(1)},100);saveProductDraft(1,{...content,title:'新窗口内容'},{id:id(1),expectedRevision:1},101)
 assert.throws(()=>saveProductDraft(1,content,{id:id(1),expectedRevision:1},102),/已变化/);assert.equal(loadProductDrafts(1)[0].content.title,'新窗口内容')
})
test('delete checks revision and absent delete is idempotent',()=>{
 saveProductDraft(1,content,{id:id(1)},100);assert.throws(()=>removeProductDraft(1,id(1),2),/已变化/);assert.equal(loadProductDrafts(1).length,1)
 assert.equal(removeProductDraft(1,id(2),1),false);assert.equal(removeProductDraft(1,id(1),1),true);assert.equal(removeProductDraft(1,id(1),1),false)
})
test('delete keeps other draft and other account untouched',()=>{
 saveProductDraft(1,content,{id:id(1)},100);saveProductDraft(1,content,{id:id(2)},101);saveProductDraft(2,content,{id:id(3)},100)
 removeProductDraft(1,id(1),1);assert.deepEqual(loadProductDrafts(1).map(x=>x.id),[id(2)]);assert.equal(loadProductDrafts(2).length,1)
})
test('invalid field shapes lengths and cover/category are refused before writing',()=>{
 for(const patch of [{title:'字'.repeat(51)},{description:'字'.repeat(501)},{priceText:'1'.repeat(10)},{priceText:'abc'},{title:1},{coverImageId:'../file'},{categoryCode:'ALL'}])assert.throws(()=>saveProductDraft(1,{...content,...patch},{id:id(1)},100))
 assert.equal(writes,0)
})
test('corrupt JSON schema and duplicated IDs never get silently cleared',()=>{
 for(const value of ['{','{"schema":2,"userId":1,"records":[]}',JSON.stringify({schema:1,userId:1,records:[{id:id(1),userId:1,revision:1,updatedAt:1,content},{id:id(1),userId:1,revision:1,updatedAt:2,content}]})]){
  storage.set('campus-product-drafts-v1:1',value);assert.throws(()=>saveProductDraft(1,content,{id:id(2)},100));assert.equal(storage.get('campus-product-drafts-v1:1'),value)
 }
 assert.equal(writes,0)
})
test('storage quota error preserves prior content and propagates failure',()=>{
 saveProductDraft(1,content,{id:id(1)},100);const before=storage.get('campus-product-drafts-v1:1');uni.setStorageSync=()=>{throw new Error('quota')}
 assert.throws(()=>saveProductDraft(1,{...content,title:'改动'},{id:id(1),expectedRevision:1},101),/quota/);assert.equal(storage.get('campus-product-drafts-v1:1'),before)
})
test('silent ignored writes are detected by readback',()=>{
 uni.setStorageSync=()=>{};assert.throws(()=>saveProductDraft(1,content,{id:id(1)},100),/未能确认/);assert.deepEqual(loadProductDrafts(1),[])
})
test('observable storage change between read and write blocks overwrite',()=>{
 let calls=0;uni.getStorageSync=()=>++calls===1?'':'another-window';assert.throws(()=>saveProductDraft(1,content,{id:id(1)},100),/已变化/);assert.equal(writes,0)
})
test('invalid account id UUID timestamp and unsafe revision cannot mutate storage',()=>{
 for(const userId of [0,-1,1.5,NaN,'1'])assert.throws(()=>loadProductDrafts(userId))
 for(const options of [{id:'bad'},{id:id(1),expectedRevision:-1},{id:id(1),expectedRevision:Number.MAX_SAFE_INTEGER}])assert.throws(()=>saveProductDraft(1,content,options,100))
 assert.throws(()=>saveProductDraft(1,content,{id:id(1)},0));assert.equal(writes,0)
})
test('valid boundary text is preserved and returned objects do not alias stored content',()=>{
 const original={...content,title:'字'.repeat(50),description:'描'.repeat(500),priceText:'1.',coverImageId:id(9),categoryCode:'BOOK'}
 const saved=saveProductDraft(1,original,{id:id(1)},100);saved.content.title='改返回值';original.description='改原对象'
 assert.equal(loadProductDrafts(1)[0].content.title,'字'.repeat(50));assert.equal(loadProductDrafts(1)[0].content.description,'描'.repeat(500))
})

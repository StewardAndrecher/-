import fs from 'node:fs'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
const require = createRequire(import.meta.url)
const { parse } = require(process.env.UNI_COMPILER_HOME+'/node_modules/@vue/compiler-sfc')
const h = await import('../.frontend-work/esm/utils/browsing-history.js')
const { detailPath } = await import('../.frontend-work/esm/utils/product-share.js')
const storage = new Map()
globalThis.uni = { getStorageSync:k=>storage.get(k), setStorageSync:(k,v)=>storage.set(k,v), removeStorageSync:k=>storage.delete(k) }
const now = Date.now(), day = 86400000, product = {id:7,title:'教材',priceInCents:1250,status:'ON_SALE',description:'公开描述',version:0,accessToken:'must-not-store'}
assert.notEqual(h.historyKey(0), h.historyKey(1));assert.notEqual(h.historyKey(1),h.historyKey(2))
for(const id of [-1,1.5,'1',NaN])assert.throws(()=>h.historyKey(id))
h.recordHistory(0,product,now);h.recordHistory(1,{...product,id:8},now)
assert.equal(h.loadHistory(0,now)[0].productId,7);assert.equal(h.loadHistory(1,now)[0].productId,8);assert.deepEqual(h.loadHistory(2,now),[])
assert.deepEqual(Object.keys(JSON.parse(storage.get(h.historyKey(0)))[0]).sort(),['productId','viewedAt']);assert(!storage.get(h.historyKey(0)).includes('must-not-store'))
assert.equal(h.recordHistory(0,{...product,status:'OFF_SHELF'},now),false)
assert.equal(h.recordHistory(0,{...product,id:0},now),false)
for(let id=1;id<=25;id++)h.recordHistory(0,{...product,id},now-100+id)
assert.equal(h.loadHistory(0,now).length,20);assert.equal(h.loadHistory(0,now)[0].productId,25)
h.recordHistory(0,{...product,id:12},now);assert.equal(h.loadHistory(0,now)[0].productId,12);assert.equal(h.loadHistory(0,now).filter(x=>x.productId===12).length,1)
assert.deepEqual(h.normalizeHistory([{productId:1,viewedAt:now-30*day},{productId:2,viewedAt:now+1},{productId:3,viewedAt:now-1},{productId:3,viewedAt:now-2},{productId:0,viewedAt:now}],now),[{productId:3,viewedAt:now-1}])
for(const raw of ['bad','[]','null','{}']){storage.set(h.historyKey(2),raw);assert.deepEqual(h.loadHistory(2),[])}
h.removeHistory(1,8);assert.deepEqual(h.loadHistory(1),[]);h.clearHistory(1);assert.equal(storage.has(h.historyKey(1)),false);assert(h.loadHistory(0).length>0)
const code=parse(fs.readFileSync('campus-market-client/pages/product/history.vue','utf8')).descriptor.scriptSetup.content.replace(/^import .*$/mg,'')
const ref=value=>({value}),computed=fn=>({get value(){return fn()}})
function model(){let show,hide,unload,watcher,modal;let token='';const user=ref(null),calls=[],waiters=[]
 const uni={navigateTo:o=>calls.push(o.url),reLaunch:()=>{},showToast:()=>{},showModal:o=>modal=o}
 const names=['ref','computed','watch','onShow','onHide','onUnload','getProduct','currentUser','getToken','loadHistory','removeHistory','clearHistory','detailPath','imageUrl','uni']
 const values=[ref,computed,(_source,cb)=>watcher=cb,cb=>show=cb,cb=>hide=cb,cb=>unload=cb,id=>{calls.push(id);return new Promise((r,j)=>waiters.push({r,j}))},user,()=>token,h.loadHistory,h.removeHistory,h.clearHistory,detailPath,()=>'',uni]
 const page=new Function(...names,code+'\nreturn {fetchHistory,rows,total,page,loading,message,clearAll,removeOne,openDetail};')(...values)
 return {page,calls,waiters,show:()=>show(),hide:()=>hide(),unload:()=>unload(),switchUser:(id)=>{token=id?'A'.repeat(43):'';user.value=id?{id,nickname:'同学'}:null;watcher()},modal:()=>modal}
}
// Real module storage, real page code; settle independent GETs and simulated account/lifecycle changes.
h.clearHistory(0);for(const id of [1,2,3,4,5,6])h.recordHistory(0,{...product,id},Date.now())
let m=model(),pending=m.show();assert.equal(m.calls.length,5);assert(m.page.loading.value);await m.page.fetchHistory();assert.equal(m.calls.length,5)
for(let i=0;i<5;i++){
 if(i===1)m.waiters[i].j(Object.assign(new Error('下架'),{statusCode:404}))
 else if(i===2)m.waiters[i].j(Object.assign(new Error('断网'),{statusCode:0}))
 else m.waiters[i].r({...product,id:m.calls[i],status:i===3?'OFF_SHELF':'ON_SALE'})
}await pending
assert.equal(m.page.rows.value.length,5);assert(m.page.rows.value[0].product);assert(m.page.rows.value[1].unavailable);assert.equal(m.page.rows.value[2].unavailable,false);assert(m.page.rows.value[3].unavailable)
pending=m.page.fetchHistory(2);assert.equal(m.calls.length,6);m.waiters.at(-1).r({...product,id:m.calls.at(-1)});await pending;assert.equal(m.page.page.value,2)
m.page.openDetail(7);assert.equal(m.calls.at(-1),'/pages/product/detail?id=7')
m.page.clearAll();m.modal().success({confirm:false});assert.equal(h.loadHistory(0).length,6)
// Modal opened under guest must not clear another identity after switching.
m.page.clearAll();const oldModal=m.modal();m.switchUser(2);await new Promise(r=>setImmediate(r));oldModal.success({confirm:true});assert.equal(h.loadHistory(0).length,6);assert.equal(m.page.total.value,0)
// Guest responses arriving after account switch cannot show guest rows.
m=model();pending=m.show();m.switchUser(2);for(const w of m.waiters)w.r(product);await pending;await new Promise(r=>setImmediate(r));assert.deepEqual(m.page.rows.value,[])
m=model();pending=m.show();m.hide();for(const w of m.waiters)w.r(product);await pending;assert.deepEqual(m.page.rows.value,[])
m=model();pending=m.show();m.unload();for(const w of m.waiters)w.r(product);await pending;assert.deepEqual(m.page.rows.value,[])
// Storage failure is surfaced; clearing one account never clears guest records.
const original=globalThis.uni.getStorageSync;globalThis.uni.getStorageSync=()=>{throw new Error('quota')};m=model();await m.show();assert.match(m.page.message.value,/读取失败/);globalThis.uni.getStorageSync=original
h.recordHistory(2,product);m=model();m.switchUser(2);pending=m.show();m.waiters[0].r(product);await pending;m.page.clearAll();m.modal().success({confirm:true});await new Promise(r=>setImmediate(r));assert.deepEqual(h.loadHistory(2),[]);assert.equal(h.loadHistory(0).length,6)
console.log('PASS: identity keys, minimal storage, public-only, MRU/dedup/20/30days, corruption, remove/clear isolation; 5 GET bound, partial 404/offline/off-shelf handling, pagination, modal identity guard, account/hide/unload late responses and storage errors.')

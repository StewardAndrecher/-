import fs from 'node:fs'
import assert from 'node:assert/strict'
import {createRequire} from 'node:module'
const require=createRequire(import.meta.url),{parse}=require(process.env.UNI_COMPILER_HOME+'/node_modules/@vue/compiler-sfc')
const root='campus-market-client/',ref=value=>({value}),owner={id:1,username:'student_01',nickname:'卖家甲'}
const product={id:7,title:'测试教材',description:'',priceInCents:1200,status:'ON_SALE',version:0,seller:{id:1,nickname:'卖家甲'}}
const script=parse(fs.readFileSync(root+'pages/product/mine.vue','utf8')).descriptor.scriptSetup.content.replace(/^import .*$/mg,'')
const names=['ref','computed','watch','onShow','onUnload','getMyProducts','updateProductStatus','currentUser','getToken','uni','getCurrentPages']
function setup(options={}){
 let token='A'.repeat(43),unload,watch,modal,writes=[],finish
 const user=ref(owner),events=[]
 const env={ref,computed:fn=>({get value(){return fn()}}),watch:(r,cb)=>watch=cb,onShow:()=>{},onUnload:cb=>unload=cb,
 getMyProducts:async({page,size})=>({records:[options.sold?{...product,status:'SOLD',version:1}:product],total:1,page,size}),
 updateProductStatus:(id,status,version)=>{writes.push({id,status,version});return options.update?options.update():Promise.resolve({...product,status,version:version+1})},
 currentUser:user,getToken:()=>token,uni:{showModal:o=>modal=o,showToast:o=>events.push(o.title),navigateTo:o=>events.push(o.url)},getCurrentPages:()=>[{},{}]}
 const model=new Function(...names,script+'\nreturn {records,message,busyId,markSold,changeStatus,fetchMine};')(...names.map(n=>env[n]))
 return {model,writes,events,modal:()=>modal,unload:()=>unload(),switchUser:()=>{const old=user.value;user.value={id:2};token='B'.repeat(43);watch(user.value,old)}}
}
let f=setup();await f.model.fetchMine();let pending=f.model.markSold(product);assert.equal(f.model.busyId.value,7);assert.equal(f.writes.length,0);assert.match(f.modal().content,/停止编辑/)
await f.model.markSold(product);assert.equal(f.writes.length,0);f.modal().success({confirm:false});await pending;assert.equal(f.writes.length,0);assert.equal(f.model.busyId.value,0)
pending=f.model.markSold(product);f.modal().success({confirm:true});await pending;assert.deepEqual(f.writes,[{id:7,status:'SOLD',version:0}]);assert.equal(f.model.records.value[0].status,'SOLD');assert.equal(f.model.records.value[0].version,1);assert.deepEqual(f.events,['已标记售出'])
await f.model.changeStatus(f.model.records.value[0]);await f.model.markSold(f.model.records.value[0]);assert.equal(f.writes.length,1)
for(const interrupt of ['unload','switchUser','versionChanged','modalFail']){
 f=setup();await f.model.fetchMine();pending=f.model.markSold(product)
 if(interrupt==='unload')f.unload();if(interrupt==='switchUser')f.switchUser();if(interrupt==='versionChanged')f.model.records.value=[{...product,version:1}]
 if(interrupt==='modalFail')f.modal().fail();else f.modal().success({confirm:true})
 await pending;assert.equal(f.writes.length,0,interrupt)
}
f=setup({sold:true});await f.model.fetchMine();assert.equal(f.model.records.value[0].status,'SOLD')
f=setup({update:async()=>{throw Object.assign(new Error('timeout'),{statusCode:0})}});await f.model.fetchMine();pending=f.model.markSold(product);f.modal().success({confirm:true});await pending;assert.equal(f.model.records.value[0].status,'ON_SALE');assert.equal(f.writes.length,1);assert.match(f.model.message.value,/刷新确认/)
let resolve;f=setup({update:()=>new Promise(r=>resolve=r)});await f.model.fetchMine();pending=f.model.markSold(product);f.modal().success({confirm:true});await Promise.resolve();assert.equal(f.model.records.value[0].status,'ON_SALE');f.unload();resolve({...product,status:'SOLD',version:1});await pending;assert.equal(f.events.length,0)
// 实际编辑页读到SOLD时拒绝进入表单，而不是“格式错误”。
const editScript=parse(fs.readFileSync(root+'pages/product/edit.vue','utf8')).descriptor.scriptSetup.content.replace(/^import .*$/mg,'')
const editNames=['ref','onLoad','onShow','onUnload','getProduct','updateProduct','getCurrentUser','currentUser','getToken','updateCurrentUser','uni','getCurrentPages']
let load,updates=0
const editEnv={ref,onLoad:cb=>load=cb,onShow:()=>{},onUnload:()=>{},getProduct:async()=>({...product,status:'SOLD',version:1}),updateProduct:()=>updates++,getCurrentUser:async()=>owner,currentUser:ref(owner),getToken:()=> 'A'.repeat(43),updateCurrentUser:()=>{},uni:{},getCurrentPages:()=>[]}
const edit=new Function(...editNames,editScript+'\nreturn {loadForEdit,loadError,ready,saveProduct};')(...editNames.map(n=>editEnv[n]));load({id:'7'});await edit.loadForEdit();assert.match(edit.loadError.value,/已售出/);assert.equal(edit.ready.value,false);await edit.saveProduct();assert.equal(updates,0)
console.log('PASS: SOLD rendering, confirmation/cancel/failure, one versioned PUT, busy/unload/account/version guards, no optimistic or automatic writes, terminal actions blocked, direct SOLD edit route refused.')

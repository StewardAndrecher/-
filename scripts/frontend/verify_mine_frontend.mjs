import fs from 'node:fs'
import assert from 'node:assert/strict'
import {createRequire} from 'node:module'
const require=createRequire(import.meta.url)
const {parse}=require(process.env.UNI_COMPILER_HOME+'/node_modules/@vue/compiler-sfc')
const session=await import('../.frontend-work/esm/utils/session.js'),api=await import('../.frontend-work/esm/api/product.js')
const user={id:1,username:'student_01',nickname:'小明'}
const product={id:7,title:'测试台灯',description:'正常使用',priceInCents:1250,status:'ON_SALE',version:0,seller:{id:1,nickname:'小明'}}
let requests=[];globalThis.uni={request:o=>requests.push(o)}
session.clearSession();await assert.rejects(api.getMyProducts(),e=>e.statusCode===401);assert.equal(requests.length,0)
session.saveSession({accessToken:'A'.repeat(43),tokenType:'Bearer',expiresInSeconds:7200,user})
let promise=api.getMyProducts({page:2,size:5});assert.deepEqual(requests.at(-1).data,{page:2,size:5});assert.equal(requests.at(-1).header.Authorization,'Bearer '+'A'.repeat(43));requests.at(-1).success({statusCode:200,data:{}});await promise
promise=api.updateProductStatus(7,'OFF_SHELF',0);assert.equal(requests.at(-1).method,'PUT');assert.equal(requests.at(-1).url,'http://localhost:8080/api/products/7/status');assert.deepEqual(requests.at(-1).data,{status:'OFF_SHELF',version:0});requests.at(-1).success({statusCode:200,data:{...product,status:'OFF_SHELF'}});await promise
promise=api.getProduct(7);assert.equal(requests.at(-1).header.Authorization,'Bearer '+'A'.repeat(43));requests.at(-1).success({statusCode:200,data:product});await promise
const script=parse(fs.readFileSync('campus-market-client/pages/product/mine.vue','utf8')).descriptor.scriptSetup.content.replace(/^import .*$/mg,'')
const names=['ref','computed','watch','onShow','onUnload','getMyProducts','updateProductStatus','currentUser','getToken','uni','getCurrentPages']
const factory=new Function(...names,script+'\nreturn {records,total,page,loading,busyId,message,fetchMine,changeStatus,openPage};')
const ref=value=>({value})
function setup(options={}) {
 let watch,unload,token=options.loggedOut?'':'A'.repeat(43),calls=0,payload
 const events=[],currentUser=ref(options.loggedOut?null:user)
 const env={ref,computed:get=>({get value(){return get()}}),watch:(r,cb)=>watch=cb,onShow:()=>{},onUnload:cb=>unload=cb,
   getMyProducts:options.getMine||async function({page,size}){return {records:options.records||[product],total:1,page,size}},
   updateProductStatus:async(id,status,version)=>{calls++;payload={id,status,version};return options.update?options.update(id,status):{...product,status}},
   currentUser,getToken:()=>token,uni:{showToast:o=>events.push(o.title),navigateTo:o=>events.push(o.url)},getCurrentPages:()=>[{},{}]}
 const model=factory(...names.map(n=>env[n]))
 return {model,events,calls:()=>calls,payload:()=>payload,unload:()=>unload(),setUser:u=>{const old=currentUser.value;currentUser.value=u;token=u?'B'.repeat(43):'';watch(u,old)}}
}
let test=setup({loggedOut:true});await test.model.fetchMine();assert.equal(test.model.records.value.length,0)
test=setup();await test.model.fetchMine();assert.equal(test.model.total.value,1);await test.model.changeStatus(product);assert.deepEqual(test.payload(),{id:7,status:'OFF_SHELF',version:0});assert.equal(test.model.records.value[0].status,'OFF_SHELF');assert.deepEqual(test.events,['已下架'])
await test.model.changeStatus(test.model.records.value[0]);assert.deepEqual(test.payload(),{id:7,status:'ON_SALE',version:0});assert.equal(test.model.records.value[0].status,'ON_SALE')
test.model.openPage('detail',7);assert.equal(test.events.at(-1),'/pages/product/detail?id=7')
test.setUser(null);assert.equal(test.model.records.value.length,0);assert.equal(test.model.total.value,0)
test=setup({records:[{...product,seller:{id:2,nickname:'别人'}}]});await test.model.fetchMine();assert.match(test.model.message.value,/格式不正确/);assert.equal(test.model.records.value.length,0)
test=setup({update:async()=>{throw Object.assign(new Error('timeout'),{statusCode:0})}});await test.model.fetchMine();await test.model.changeStatus(product);assert.match(test.model.message.value,/刷新确认状态/);assert.equal(test.model.records.value[0].status,'ON_SALE');assert.equal(test.calls(),1)
let finish;test=setup({update:()=>new Promise(r=>finish=r)});await test.model.fetchMine();let pending=test.model.changeStatus(product);await test.model.changeStatus(product);assert.equal(test.calls(),1);finish({...product,status:'OFF_SHELF'});await pending
let finishClosed;test=setup({update:()=>new Promise(r=>finishClosed=r)});await test.model.fetchMine();pending=test.model.changeStatus(product);test.unload();finishClosed({...product,status:'OFF_SHELF'});await pending;assert.equal(test.events.length,0)
let finishOld,count=0;const other={id:2,username:'student_02',nickname:'另一用户'}
test=setup({getMine:({page,size})=>++count===1?new Promise(r=>finishOld=r):Promise.resolve({records:[{...product,seller:{id:2,nickname:'另一用户'}}],total:1,page,size})});pending=test.model.fetchMine();test.setUser(other);finishOld({records:[product],total:1,page:1,size:5});await pending;assert.equal(test.model.records.value[0].seller.id,2)
console.log('PASS: my-products auth and pagination, explicit status PUT, owner detail auth, list validation, ON/OFF UI updates, identity clearing/stale response guard, duplicate/unload protection, uncertain outcome handling')

let refreshes=0
const updated={...product,version:1,status:'OFF_SHELF',title:'服务器新标题'}
test=setup({getMine:async({page,size})=>({records:[++refreshes===1?product:updated],total:1,page,size}),update:async()=>{throw Object.assign(new Error('冲突'),{statusCode:409,code:'PRODUCT_CONFLICT'})}})
await test.model.fetchMine();await test.model.changeStatus(product);assert.equal(test.calls(),1);assert.equal(refreshes,2);assert.deepEqual(test.model.records.value,[updated]);assert.match(test.model.message.value,/列表已刷新/);assert.equal(test.events.length,0)
refreshes=0;test=setup({getMine:async({page,size})=>{if(++refreshes>1)throw new Error('网络不可用');return{records:[product],total:1,page,size}},update:async()=>{throw Object.assign(new Error('冲突'),{statusCode:409,code:'PRODUCT_CONFLICT'})}});await test.model.fetchMine();await test.model.changeStatus(product);assert.match(test.model.message.value,/刷新失败/);assert.equal(test.calls(),1)
console.log('PASS: status carries displayed version; conflicts refresh list once without automatic write; refresh failure remains visible')

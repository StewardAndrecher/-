import fs from 'node:fs'
import path from 'node:path'
import assert from 'node:assert/strict'
import {createRequire} from 'node:module'
const require=createRequire(import.meta.url)
const {parse,compileScript,compileTemplate}=require(process.env.UNI_COMPILER_HOME+'/node_modules/@vue/compiler-sfc')
const root=path.resolve('campus-market-client')
for(const name of ['pages/index/index.vue','pages/product/detail.vue','pages/product/edit.vue','pages/product/mine.vue','components/ProductCard.vue','components/ImagePicker.vue','pages/user/login.vue','pages/user/register.vue']) {
 const filename=path.join(root,name),{descriptor,errors}=parse(fs.readFileSync(filename,'utf8'),{filename})
 assert.deepEqual(errors,[])
 const script=compileScript(descriptor,{id:name})
 assert.deepEqual(compileTemplate({source:descriptor.template.content,filename,id:name,compilerOptions:{bindingMetadata:script.bindings}}).errors,[])
}
const session=await import('../.frontend-work/esm/utils/session.js'),api=await import('../.frontend-work/esm/api/product.js')
const user={id:1,username:'student_01',nickname:'小明'}
const product={id:7,title:'验收台灯',description:'正常使用',priceInCents:950,status:'ON_SALE',version:0,seller:{id:1,nickname:'小明'}}
let requests=[];globalThis.uni={request:o=>requests.push(o)}
session.clearSession();await assert.rejects(api.updateProduct(7,{}),e=>e.statusCode===401);assert.equal(requests.length,0)
session.saveSession({accessToken:'A'.repeat(43),tokenType:'Bearer',expiresInSeconds:7200,user})
let operation=api.updateProduct(7,{title:'修改台灯',description:'良好',priceInCents:1250})
assert.equal(requests.at(-1).method,'PUT');assert.equal(requests.at(-1).url,'http://localhost:8080/api/products/7')
assert.equal(requests.at(-1).header.Authorization,'Bearer '+'A'.repeat(43))
requests.at(-1).success({statusCode:403,data:{message:'只能修改自己发布的商品'}});await assert.rejects(operation,e=>e.statusCode===403)
assert.equal(session.getToken(),'A'.repeat(43))
operation=api.updateProduct(7,{});requests.at(-1).success({statusCode:401,data:{message:'过期'}});await assert.rejects(operation,e=>e.statusCode===401);assert.equal(session.getToken(),'')

const script=parse(fs.readFileSync(path.join(root,'pages/product/edit.vue'),'utf8')).descriptor.scriptSetup.content.replace(/^import .*$/mg,'')
const names=['ref','onLoad','onShow','onUnload','getProduct','updateProduct','getCurrentUser','currentUser','getToken','updateCurrentUser','uni','getCurrentPages']
const factory=new Function(...names,script+'\nreturn {productId,product,draftTitle,draftDescription,draftPrice,loading,saving,ready,saveDone,loadError,errorMessage,loadForEdit,saveProduct,goDetail,draftVersion,conflict,draftCoverId,draftCategory,categoryReady};')
const ref=value=>({value})
function setup(options={}) {
 let load,show,unload,token=options.loggedOut?'':'A'.repeat(43),calls=0,sent
 const currentUser=ref(options.loggedOut?null:user),events=[]
 const env={ref,onLoad:cb=>load=cb,onHide:()=>{},onShow:cb=>show=cb,onUnload:cb=>unload=cb,
   getProduct:options.getProduct||async function(){return options.product||product},
   updateProduct:async function(id,data){calls++;sent={id,data};return options.update?options.update(id,data):{...product,...data}},
   getCurrentUser:async function(){if(options.meError){token='';currentUser.value=null;throw Object.assign(new Error('过期'),{statusCode:401})}return currentUser.value},
   currentUser,getToken:()=>token,updateCurrentUser:u=>currentUser.value=u,
   uni:{navigateBack:o=>{events.push('back');if(options.navigationFails)o.fail()},redirectTo:o=>events.push(o.url),navigateTo:o=>events.push(o.url),showToast:o=>events.push(o.title)},
   getCurrentPages:()=>options.direct?[{route:'pages/product/edit'}]:[{route:'pages/product/detail'},{route:'pages/product/edit'}]}
 const model=factory(...names.map(n=>env[n]));model.categoryReady.value=true;load({id:options.invalidId?'bad':'7'})
 return {model,events,show:()=>show(),unload:()=>unload(),calls:()=>calls,sent:()=>sent,setUser:u=>{currentUser.value=u;token=u?'B'.repeat(43):''}}
}
let test=setup({loggedOut:true});await test.show();assert.match(test.model.loadError.value,/请先登录/);assert.equal(test.model.ready.value,false);assert.equal(test.calls(),0)
test=setup({product:{...product,seller:{id:2,nickname:'别人'}}});await test.show();assert.match(test.model.loadError.value,/只能修改自己/);await test.model.saveProduct();assert.equal(test.calls(),0)
test=setup({product:{...product,seller:null}});await test.show();assert.match(test.model.loadError.value,/历史商品/)
test=setup();await test.show();assert.equal(test.model.draftPrice.value,'9.50');test.model.draftTitle.value=' 修改后的台灯 ';test.model.draftPrice.value='12.50';await test.model.saveProduct()
assert.deepEqual(test.sent(),{id:7,data:{title:'修改后的台灯',description:'正常使用',priceInCents:1250,coverImageId:'',version:0,categoryCode:'UNCLASSIFIED'}});assert.equal(test.model.saveDone.value,true);assert.deepEqual(test.events,['back']);await test.model.saveProduct();assert.equal(test.calls(),1)
test=setup({direct:true});await test.show();await test.model.saveProduct();assert.deepEqual(test.events,['/pages/product/detail?id=7'])
test=setup({navigationFails:true});await test.show();await test.model.saveProduct();assert.equal(test.model.saveDone.value,true);assert.match(test.events.at(-1),/已保存/)
test=setup();await test.show();test.model.draftTitle.value='未保存草稿';test.setUser(null);await test.show();test.setUser(user);await test.show();assert.equal(test.model.draftTitle.value,'未保存草稿')
test=setup({meError:true});await test.show();assert.match(test.model.loadError.value,/登录已失效/)
for(const statusCode of [401,403,404,0]) {
 test=setup({update:async()=>{throw Object.assign(new Error('失败'),{statusCode})}});await test.show();test.model.draftTitle.value='修改中草稿';await test.model.saveProduct()
 assert.equal(test.model.draftTitle.value,'修改中草稿');assert.equal(test.model.saveDone.value,false)
 assert.equal(test.model.ready.value,statusCode===0)
 if(statusCode===0)assert.match(test.model.errorMessage.value,/重新加载确认/)
}
test=setup();await test.show();test.model.draftPrice.value='0';await test.model.saveProduct();assert.equal(test.calls(),0)
let finish;test=setup({update:()=>new Promise(r=>finish=r)});await test.show();let first=test.model.saveProduct();await test.model.saveProduct();assert.equal(test.calls(),1);finish(product);await first
let finishClosed;test=setup({update:()=>new Promise(r=>finishClosed=r)});await test.show();first=test.model.saveProduct();test.unload();finishClosed(product);await first;assert.equal(test.events.length,0)
let finishLoad;test=setup({getProduct:()=>new Promise(r=>finishLoad=r)});first=test.show();await Promise.resolve();await Promise.resolve();test.unload();finishLoad(product);await first;assert.equal(test.model.ready.value,false)
test=setup({invalidId:true});await test.show();assert.match(test.model.loadError.value,/商品ID不正确/)
let finishOldLoad,loadCount=0
test=setup({getProduct:()=>++loadCount===1?new Promise(r=>finishOldLoad=r):Promise.resolve(product)})
first=test.show();await Promise.resolve();await Promise.resolve()
test.setUser({id:2,username:'student_02',nickname:'另一用户'});finishOldLoad(product);await first
assert.equal(loadCount,2);assert.equal(test.model.ready.value,false);assert.match(test.model.loadError.value,/只能修改自己/)
const pages=JSON.parse(fs.readFileSync(path.join(root,'pages.json'),'utf8')).pages
assert.equal(pages.filter(p=>p.path==='pages/product/edit').length,1)
console.log('PASS: seven Vue SFCs, PUT/Token headers, 403 preserves login, 401 clears session, owner-only editor, legacy block, prefill/cents conversion, valid payload, unchanged save, relogin draft retention, duplicate/unload guards, error handling, success navigation/fallback')

// 409 must retain the complete draft and its original base version.
let latest={...product,version:1,title:'服务器新标题',coverImageId:'7b1075d8-1e80-4343-91ed-85ec3241bf21'}
let reads=0
test=setup({getProduct:async()=>++reads===1?product:latest,update:async()=>{throw Object.assign(new Error('冲突'),{statusCode:409,code:'PRODUCT_CONFLICT'})}})
await test.show();test.model.draftTitle.value='尚未保存的标题';test.model.draftDescription.value='保留描述';test.model.draftPrice.value='18.00';test.model.draftCoverId.value='local-image'
await test.model.saveProduct();assert.equal(test.model.conflict.value,true);assert.equal(test.model.draftVersion.value,0);assert.equal(test.model.draftTitle.value,'尚未保存的标题');assert.equal(test.model.draftDescription.value,'保留描述');assert.equal(test.model.draftPrice.value,'18.00');assert.equal(test.model.draftCoverId.value,'local-image')
await test.model.saveProduct();assert.equal(test.calls(),1)
await test.show();assert.equal(test.model.product.value.version,1);assert.equal(test.model.draftVersion.value,0);assert.equal(test.model.draftTitle.value,'尚未保存的标题');assert.equal(test.model.conflict.value,true)
await test.model.loadForEdit(true);assert.equal(test.model.draftVersion.value,1);assert.equal(test.model.draftTitle.value,'服务器新标题');assert.equal(test.model.conflict.value,false);assert.equal(test.model.draftCoverId.value,latest.coverImageId)
test=setup({getProduct:async()=>latest});await test.show();test.model.draftTitle.value='版本一草稿';latest={...latest,version:2};await test.show();assert.equal(test.model.draftVersion.value,1);assert.equal(test.model.conflict.value,true)
requests=[];globalThis.uni={request:o=>requests.push(o)};session.saveSession({accessToken:'C'.repeat(43),tokenType:'Bearer',expiresInSeconds:7200,user})
operation=api.updateProduct(7,{version:0});requests.at(-1).success({statusCode:409,data:{code:'PRODUCT_CONFLICT',message:'版本冲突'}});await assert.rejects(operation,e=>e.statusCode===409&&e.code==='PRODUCT_CONFLICT');assert.equal(session.getToken(),'C'.repeat(43))
console.log('PASS: 409 code preserved without logout; stale draft version never replaced by refreshed product; conflict blocks retries; explicit reload replaces all fields/cover/version; onShow detects stale drafts')

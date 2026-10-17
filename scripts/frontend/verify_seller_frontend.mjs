import fs from 'node:fs'
import assert from 'node:assert/strict'
import {createRequire} from 'node:module'
const require=createRequire(import.meta.url),{parse}=require(process.env.UNI_COMPILER_HOME+'/node_modules/@vue/compiler-sfc')
const helpers=await import('../.frontend-work/esm/utils/seller-store.js'),{parseSellerId,sellerStorePath,webHashSellerId,validateSellerStore}=helpers
const {detailPath}=await import('../.frontend-work/esm/utils/product-share.js')
for(const id of [1,'7','9007199254740991'])assert(parseSellerId(id)>0)
for(const id of [undefined,0,-1,'01','1e0','1.0',' 1','9007199254740992'])assert.equal(parseSellerId(id),0)
assert.equal(sellerStorePath(1),'/pages/user/store?id=1');assert.throws(()=>sellerStorePath(0))
assert.equal(webHashSellerId('#/pages/user/store?id=1'),1);assert.equal(webHashSellerId('#/pages/user/store?id=1&id=2'),0);assert.equal(webHashSellerId('#/pages/user/login'),null)
const product=id=>({id,title:'教材'+id,description:'',priceInCents:1250,status:'ON_SALE',version:0,favoriteCount:0,seller:{id:1,nickname:'小明'}})
const data=(page=1,total=1,records=[product(7)])=>({seller:{id:1,nickname:'小明'},products:{records,total,page,size:5}})
assert.equal(validateSellerStore(data(),1,1,5).seller.id,1)
for(const mutate of [d=>d.seller.id=2,d=>d.seller.nickname='',d=>d.products.page=2,d=>d.products.total=-1,d=>d.products.records[0].status='OFF_SHELF',d=>d.products.records[0].seller.id=2,d=>d.products.records[0].seller.nickname='其他昵称',d=>d.products.records[0].favoriteCount=-1,d=>d.products.records.push(d.products.records[0])]){
 const d=data();mutate(d);assert.throws(()=>validateSellerStore(d,1,1,5),/格式/)
}
const session=await import('../.frontend-work/esm/utils/session.js');let calls=[];globalThis.uni={request:o=>calls.push(o)}
session.saveSession({accessToken:'A'.repeat(43),tokenType:'Bearer',expiresInSeconds:7200,user:{id:1,username:'student_01',nickname:'小明'}})
const api=await import('../.frontend-work/esm/api/seller.js');let pending=api.getSellerStore(1,{page:2,size:5,sort:'MOST_FAVORITED'})
assert.equal(calls.at(-1).url,'http://localhost:8080/api/sellers/1/products');assert.deepEqual(calls.at(-1).data,{page:2,size:5,sort:'MOST_FAVORITED'});assert.equal(calls.at(-1).header.Authorization,undefined)
calls.at(-1).success({statusCode:200,data:data(2)});await pending
const count=calls.length;await assert.rejects(api.getSellerStore('1e0'));assert.equal(calls.length,count)
pending=api.getSellerStore(1);calls.at(-1).success({statusCode:401,data:{message:'模拟公共接口失败'}});await assert.rejects(pending);assert.equal(session.getToken(),'A'.repeat(43),'public request must not clear login')
function preprocess(text,platform){let lines=[],active=[true];for(const line of text.split('\n')){
 const match=line.match(/^\s*\/\/\s*#(ifdef|ifndef)\s+([\w-]+)/)
 if(match){const hit=match[2]===platform;active.push(active.at(-1)&&(match[1]==='ifdef'?hit:!hit))}
 else if(/^\s*\/\/\s*#endif/.test(line))active.pop();else if(active.at(-1))lines.push(line)
}return lines.join('\n')}
const ref=value=>({value}),computed=fn=>({get value(){return fn()}})
const pageCode=parse(fs.readFileSync('campus-market-client/pages/user/store.vue','utf8')).descriptor.scriptSetup.content
function model(platform='H5'){let load,show,hide,unload;const waiters=[],requests=[],navigation=[],events={};const window={location:{hash:'#/pages/user/store?id=1'},addEventListener:(name,fn)=>events[name]=fn,removeEventListener:name=>delete events[name]}
 const args={ref,computed,onLoad:fn=>load=fn,onShow:fn=>show=fn,onHide:fn=>hide=fn,onUnload:fn=>unload=fn,parseSellerId,webHashSellerId,detailPath,window,getCurrentPages:()=>[],uni:{navigateTo:o=>navigation.push(o.url),reLaunch:o=>navigation.push(o.url)},getSellerStore:(id,criteria)=>{requests.push({id,...criteria});return new Promise((r,j)=>waiters.push({r,j}))}}
 const page=new Function(...Object.keys(args),preprocess(pageCode,platform).replace(/^import .*$/mg,'')+'\nreturn {fetchStore,changeSort,store,page,sort,loading,message,sellerId,openDetail,goBack};')(...Object.values(args))
 return {page,requests,waiters,navigation,window,events,load:options=>load(options),show:()=>show(),hide:()=>hide(),unload:()=>unload()}
}
let m=model('MP-WEIXIN');m.load({id:'1e0'});await m.show();assert.equal(m.requests.length,0);assert.match(m.page.message.value,/ID不正确/)
m=model();m.load({id:'1'});pending=m.show();await m.page.fetchStore();assert.equal(m.requests.length,1);m.waiters[0].r(data());await pending;assert.equal(m.page.store.value.seller.id,1)
pending=m.page.changeSort('PRICE_ASC');assert.equal(m.requests.at(-1).page,1);assert.equal(m.page.sort.value,'NEWEST');m.waiters.at(-1).j(new Error('断网'));await pending;assert.equal(m.page.sort.value,'NEWEST');assert.equal(m.page.store.value,null);assert.match(m.page.message.value,/断网/)
pending=m.page.changeSort('MOST_FAVORITED');m.waiters.at(-1).r(data());await pending;assert.equal(m.page.sort.value,'MOST_FAVORITED');m.page.openDetail(7);assert.equal(m.navigation.at(-1),'/pages/product/detail?id=7')
// Two-stage page shrinking after server-side changes: refresh page2 then page1.
pending=m.page.fetchStore(2);m.waiters.at(-1).r(data(2,1,[]));await new Promise(r=>setImmediate(r));assert.equal(m.requests.at(-1).page,1);m.waiters.at(-1).r(data());await pending;assert.equal(m.page.page.value,1)
// Changing just the hash to another or malformed seller must clear previous content.
m.window.location.hash='#/pages/user/store?id=2';m.events.hashchange();assert.equal(m.page.store.value,null);assert.equal(m.requests.at(-1).id,2)
m.window.location.hash='#/pages/user/store?id=2&id=1';m.events.hashchange();assert.equal(m.page.sellerId.value,0);m.waiters.at(-1).r({...data(),seller:{id:2,nickname:'其他'}});await new Promise(r=>setImmediate(r));assert.equal(m.page.store.value,null)
m=model();m.load({id:'1'});pending=m.show();m.hide();m.waiters[0].r(data());await pending;assert.equal(m.page.store.value,null)
m=model();m.load({id:'1'});pending=m.show();m.unload();m.waiters[0].r(data());await pending;assert.equal(m.page.store.value,null);assert.equal(m.events.hashchange,undefined)
m=model();m.load({id:'1'});pending=m.show();m.waiters[0].j(Object.assign(new Error('missing'),{statusCode:404}));await pending;assert.match(m.page.message.value,/卖家不存在/)
const card=parse(fs.readFileSync('campus-market-client/components/ProductCard.vue','utf8')).descriptor
const props={product:product(7),showSellerButton:true,showFavoriteButton:true,favoriteDisabled:false},emitted=[]
const cardModel=new Function('defineProps','defineEmits','parseSellerId',card.scriptSetup.content.replace(/^import .*$/mg,'')+'\nreturn {requestOpenSeller,requestOpenDetail,requestToggleFavorite};')(()=>props,()=>((...args)=>emitted.push(args)),parseSellerId)
cardModel.requestOpenSeller();assert.deepEqual(emitted.at(-1),['open-seller',1]);assert(card.template.content.includes('@click.stop="requestOpenSeller"'))
props.product.seller=null;let before=emitted.length;cardModel.requestOpenSeller();assert.equal(emitted.length,before)
props.showFavoriteButton=false;cardModel.requestToggleFavorite();assert.equal(emitted.length,before)
console.log('PASS: strict route IDs, API validation/anonymous headers/public failure does not log out; seller ownership/status/duplicate response checks; paging/shrinking/success-only sort; changed hash/hide/unload late responses; missing seller; stopped card events and legacy/read-only guards.')

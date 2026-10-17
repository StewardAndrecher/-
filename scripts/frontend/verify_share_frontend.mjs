import fs from 'node:fs'
import assert from 'node:assert/strict'
import {createRequire} from 'node:module'
const require=createRequire(import.meta.url),{parse}=require(process.env.UNI_COMPILER_HOME+'/node_modules/@vue/compiler-sfc')
const share=await import('../.frontend-work/esm/utils/product-share.js')
const {parseProductId,detailPath,webProductLink,canShareProduct,wechatProductShare,webHashProductId}=share
for(const value of ['1','7','9007199254740991',7])assert(parseProductId(value)>0)
for(const value of [undefined,null,[],{},0,-1,1.5,'0','01','7.0','7e0','+7',' 7','7&token=x','9007199254740992'])assert.equal(parseProductId(value),0)
assert.equal(detailPath(7),'/pages/product/detail?id=7')
assert.equal(webProductLink(7,'http://localhost:5173'),'http://localhost:5173/#/pages/product/detail?id=7')
assert.equal(webProductLink(7,'https://market.example','/campus/index.html'),'https://market.example/campus/index.html#/pages/product/detail?id=7')
for(const origin of ['javascript:alert(1)','https://u:p@example.com','https://example.com/path','https://example.com?token=secret','https://example.com#token=secret'])assert.throws(()=>webProductLink(7,origin))
for(const path of ['//evil.example','/index.html?token=secret','/index.html#token=secret','/a\\b'])assert.throws(()=>webProductLink(7,'https://example.com',path))
const product={id:7,title:'公开教材',description:'公开描述',priceInCents:1200,status:'ON_SALE',version:0,favoriteCount:0,seller:{id:1,nickname:'同学'},accessToken:'MUST_NOT_SHARE',draft:'private note'}
assert(canShareProduct(product));assert.equal(canShareProduct({...product,status:'OFF_SHELF'}),false)
const packet=wechatProductShare(product);assert.deepEqual(packet,{title:'校园集市｜公开教材',path:'/pages/product/detail?id=7',imageUrl:'/static/logo.png'});assert(!JSON.stringify(packet).includes('MUST_NOT_SHARE'))
assert.deepEqual(wechatProductShare({...product,status:'OFF_SHELF',title:'PRIVATE'}),{title:'校园集市',path:'/pages/index/index',imageUrl:'/static/logo.png'})
function preprocess(text,platform){let out=[],active=[true];for(const line of text.split('\n')){
 const d=line.match(/^\s*\/\/\s*#(ifdef|ifndef)\s+([\w-]+)/)
 if(d){const hit=d[2]===platform;active.push(active.at(-1)&&(d[1]==='ifdef'?hit:!hit))}
 else if(/^\s*\/\/\s*#endif/.test(line))active.pop();else if(active.at(-1))out.push(line)
}return out.join('\n')}
assert.equal(webHashProductId('#/pages/product/detail?id=7&trace=test'),7)
for(const hash of ['#/pages/product/detail?id=7e0','#/pages/product/detail?id=7&id=8','#/pages/product/detail'])assert.equal(webHashProductId(hash),0)
assert.equal(webHashProductId('#/pages/index/index'),null)
const popularity=await import('../.frontend-work/esm/utils/product-popularity.js')
const history=await import('../.frontend-work/esm/utils/browsing-history.js')
const historyStorage=new Map();globalThis.uni={getStorageSync:k=>historyStorage.get(k),setStorageSync:(k,v)=>historyStorage.set(k,v)}
const ref=value=>({value})
const component=parse(fs.readFileSync('campus-market-client/components/ProductShare.vue','utf8')).descriptor.scriptSetup.content
function copyModel(platform='H5') {
 let watch,unload,copies=[];const props={product:{...product},disabled:false}
 const code=preprocess(component,platform).replace(/^import .*$/mg,'')
 const model=new Function('ref','watch','onUnmounted','defineProps','canShareProduct','webProductLink','window','uni',code+'\nreturn {copyLink,copying,link,message};')(
 ref,(getter,cb)=>watch=cb,cb=>unload=cb,()=>props,canShareProduct,webProductLink,
 {location:{origin:'http://localhost:5173',pathname:'/',hash:'#/pages/product/detail?id=7&accessToken=secret'}},
 {setClipboardData:o=>copies.push(o)})
 return {model,props,copies,watch:()=>watch(),unload:()=>unload()}
}
let c=copyModel(),pending=c.model.copyLink();assert.equal(c.copies.length,1);assert.equal(c.copies[0].data,'http://localhost:5173/#/pages/product/detail?id=7');assert.equal(c.copies[0].showToast,false)
await c.model.copyLink();assert.equal(c.copies.length,1);c.copies[0].success();await pending;assert.match(c.model.message.value,/已复制/)
pending=c.model.copyLink();c.copies.at(-1).fail();await pending;assert.match(c.model.message.value,/手动复制/);assert(c.model.link.value)
c=copyModel();pending=c.model.copyLink();c.props.product={...product,id:8};c.watch();c.copies[0].success();await pending;assert.equal(c.model.message.value,'');assert.equal(c.model.link.value,'')
c=copyModel();pending=c.model.copyLink();c.unload();c.copies[0].success();await pending;assert.equal(c.model.message.value,'')
for(const type of ['MP-WEIXIN','APP-PLUS']){c=copyModel(type);await c.model.copyLink();assert.equal(c.copies.length,0)}
c=copyModel();c.props.disabled=true;await c.model.copyLink();assert.equal(c.copies.length,0)
c=copyModel();c.props.product.status='OFF_SHELF';await c.model.copyLink();assert.equal(c.copies.length,0)
const detail=parse(fs.readFileSync('campus-market-client/pages/product/detail.vue','utf8')).descriptor.scriptSetup.content
function pageModel(platform='MP-WEIXIN'){let load,show,hide,unload,onShare,menus=[],calls=[],waiters=[];const watchers=[];const events={};const webWindow={location:{hash:'#/pages/product/detail?id=7'},addEventListener:(name,cb)=>events[name]=cb,removeEventListener:name=>delete events[name]}
 const names=['ref','watch','onLoad','onShow','onHide','onUnload','onShareAppMessage','getProduct','currentUser','getToken','imageUrl','parseProductId','canShareProduct','wechatProductShare','uni','getCurrentPages','window','webHashProductId','recordHistory','isValidFavoriteCount']
 const values=[ref,(source,cb)=>watchers.push(cb),cb=>load=cb,cb=>show=cb,cb=>hide=cb,cb=>unload=cb,cb=>onShare=cb,
 id=>{calls.push(id);return new Promise((r,j)=>waiters.push({r,j}))},ref(null),()=>'',()=>'',parseProductId,canShareProduct,wechatProductShare,
 {showShareMenu:o=>menus.push(['show',o.menus]),hideShareMenu:()=>menus.push(['hide']),navigateBack:()=>{},reLaunch:()=>{}},()=>[],webWindow,webHashProductId,history.recordHistory,popularity.isValidFavoriteCount]
 const model=new Function(...names,preprocess(detail,platform).replace(/^import .*$/mg,'')+'\nreturn {product,productId,loading,errorMessage,commentsBusy,fetchProduct,syncShareMenu};')(...values)
 return {model,load:o=>load(o),show:()=>show(),unload:()=>unload(),hide:()=>hide(),share:()=>onShare(),menus,calls,waiters,webWindow,events}
}
for(const id of ['01','7e0','0','bad',undefined]){const p=pageModel();p.load({id});await p.show();assert.equal(p.calls.length,0);assert.match(p.model.errorMessage.value,/ID不正确/);assert.equal(p.share().path,'/pages/index/index')}
let p=pageModel();p.load({id:'7'});pending=p.show();assert.equal(p.share().path,'/pages/index/index');p.waiters[0].r(product);await pending;p.model.syncShareMenu();assert.deepEqual(p.menus.at(-1),['show',['shareAppMessage']]);assert.deepEqual(p.share(),packet);assert.equal(history.loadHistory(0)[0].productId,7)
p.model.commentsBusy.value=true;p.model.syncShareMenu();assert.equal(p.share().path,'/pages/index/index');assert.equal(p.menus.at(-1)[0],'hide');p.model.commentsBusy.value=false
pending=p.model.fetchProduct();p.waiters.at(-1).r({...product,status:'OFF_SHELF'});await pending;p.model.syncShareMenu();assert.equal(p.share().path,'/pages/index/index');assert.equal(p.menus.at(-1)[0],'hide')
pending=p.model.fetchProduct();p.waiters.at(-1).j(Object.assign(new Error('下架'),{statusCode:404}));await pending;assert.equal(p.share().path,'/pages/index/index');assert.equal(p.model.product.value,null)
p.unload();assert.equal(p.share().path,'/pages/index/index')
p=pageModel('H5');p.load({id:'7'});pending=p.show();p.waiters[0].r(product);await pending
p.webWindow.location.hash='#/pages/product/detail?id=7e0';p.events.hashchange();assert.equal(p.model.product.value,null);assert.equal(p.model.productId.value,0);assert.equal(p.calls.length,1)
p.webWindow.location.hash='#/pages/product/detail?id=8';p.events.hashchange();assert.equal(p.calls.at(-1),8);p.waiters.at(-1).r({...product,id:8});await new Promise(r=>setImmediate(r));assert.equal(p.model.product.value.id,8)
p.webWindow.location.hash='#/pages/product/detail?id=8&id=7';p.events.hashchange();assert.equal(p.model.product.value,null);p.unload();assert.equal(p.events.hashchange,undefined)
console.log('PASS: strict IDs/safe origins/static base path; no private fields; public-only packets; copy/duplicate/failure/manual fallback/unload/changed-product; platform isolation; invalid route sends no request; loading/comment/off/error share fallback and menu visibility')

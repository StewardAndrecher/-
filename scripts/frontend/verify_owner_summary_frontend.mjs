import fs from 'node:fs';import assert from 'node:assert/strict';import {createRequire} from 'node:module';
const require=createRequire(import.meta.url),{parse}=require(process.env.UNI_COMPILER_HOME+'/node_modules/@vue/compiler-sfc');
const script=parse(fs.readFileSync('campus-market-client/components/OwnerProductSummary.vue','utf8')).descriptor.scriptSetup.content.replace(/^import .*$/mg,'');
const ref=value=>({value}),owner={id:1,username:'student_01',nickname:'卖家'},good={total:6,onSale:2,offShelf:1,sold:3,soldPriceInCents:1900};
function fixture(read=async()=>({...good})){
 let token='A'.repeat(43),mount,unmount;const user=ref(owner),props={revision:0,busy:false},watchers=[],reads=[];
 const env={ref,defineProps:()=>props,currentUser:user,getToken:()=>token,getMyProductSummary:async()=>{reads.push(token);return read(user.value)},watch:(source,cb)=>watchers.push({source,cb}),onMounted:cb=>mount=cb,onUnmounted:cb=>unmount=cb};
 const names=Object.keys(env),model=new Function(...names,script+'\nreturn {load,summary,loading,stale,error,formatCents};')(...names.map(n=>env[n]));
 const changeProp=(name,value)=>{props[name]=value;watchers.filter(w=>typeof w.source==='function'&&w.source()===value).forEach(w=>w.cb(value))};
 return{model,reads,mount:()=>mount(),unmount:()=>unmount(),busy:v=>changeProp('busy',v),refresh:()=>changeProp('revision',++props.revision),switchUser:(id=2)=>{user.value={...owner,id};token='B'.repeat(43);watchers.filter(w=>w.source===user).forEach(w=>w.cb(user.value))},logout:()=>{user.value=null;token='';watchers.filter(w=>w.source===user).forEach(w=>w.cb(null))}};
}
const flush=async()=>{for(let i=0;i<8;i++)await Promise.resolve()};
let f=fixture();await f.mount();assert.deepEqual(f.model.summary.value,good);assert.equal(f.model.formatCents(1900),'19.00');assert.equal(f.model.formatCents(1),'0.01');assert.equal(f.model.formatCents(3_000_001_870),'30000018.70');assert.equal(f.model.formatCents(Number.MAX_SAFE_INTEGER),'90071992547409.91');
let reads=0;f=fixture(async()=>{if(++reads>1)throw new Error('概览断网');return{...good}});await f.mount();await f.model.load();assert.deepEqual(f.model.summary.value,good);assert.equal(f.model.stale.value,true);assert.match(f.model.error.value,/断网/);
f=fixture(async()=>{throw new Error('首次失败')});await f.mount();assert.equal(f.model.summary.value,null);assert.match(f.model.error.value,/首次失败/);
let finish;f=fixture(()=>new Promise(r=>finish=r));const pending=f.mount();await f.model.load();assert.equal(f.reads.length,1);finish(good);await pending;
reads=0;f=fixture(()=>++reads===1?new Promise(r=>finish=r):Promise.resolve({...good,onSale:1,offShelf:2}));const old=f.mount();f.busy(true);assert.equal(f.model.loading.value,false);f.busy(false);await flush();assert.equal(f.reads.length,2);assert.equal(f.model.summary.value.offShelf,2);finish(good);await old;assert.equal(f.model.summary.value.offShelf,2);
f=fixture();await f.mount();f.busy(true);assert.equal(f.model.stale.value,true);await f.model.load();assert.equal(f.reads.length,1);f.busy(false);await flush();assert.equal(f.reads.length,2);assert.equal(f.model.stale.value,false);
reads=0;f=fixture(user=>++reads===1?new Promise(r=>finish=r):Promise.resolve({...good,total:1,onSale:1,offShelf:0,sold:0,soldPriceInCents:0}));const oldAccount=f.mount();f.switchUser();await flush();finish(good);await oldAccount;assert.equal(f.model.summary.value.total,1);
f=fixture();await f.mount();f.logout();await flush();assert.equal(f.model.summary.value,null);assert.equal(f.model.stale.value,false);
f=fixture(()=>new Promise(r=>finish=r));const unloaded=f.mount();f.unmount();finish(good);await unloaded;assert.equal(f.model.summary.value,null);
f=fixture();await f.mount();f.refresh();await flush();assert.equal(f.reads.length,2);
const mine=fs.readFileSync('campus-market-client/pages/product/mine.vue','utf8');assert(mine.includes('<OwnerProductSummary :revision="summaryRevision" :busy="busyId > 0"'));assert(mine.includes('onShow(refreshAll)'));assert(mine.includes('summaryRevision.value++'));assert(mine.includes('@click="refreshAll"'));
const session=await import('../.frontend-work/esm/utils/session.js'),api=await import('../.frontend-work/esm/api/product.js');let request;session.saveSession({accessToken:'A'.repeat(43),tokenType:'Bearer',expiresInSeconds:7200,user:owner});globalThis.uni={request:o=>request=o};
let call=api.getMyProductSummary();assert(request.url.endsWith('/api/products/mine/summary'));assert.equal(request.header.Authorization,'Bearer '+'A'.repeat(43));request.success({statusCode:200,data:good});assert.deepEqual(await call,good);
for(const data of [null,{...good,total:7},{...good,soldPriceInCents:-1},{...good,onSale:1.5},{...good,soldPriceInCents:Number.MAX_SAFE_INTEGER+1},{total:0,onSale:0,offShelf:0,sold:0,soldPriceInCents:1}]){
 call=api.getMyProductSummary();request.success({statusCode:200,data});await assert.rejects(call,/概览响应/);
}
call=api.getMyProductSummary();request.success({statusCode:200,data:{total:0,onSale:0,offShelf:0,sold:0,soldPriceInCents:0}});assert.equal((await call).total,0);
console.log('PASS: overview mount/manual refresh; integer-cent formatting; independent initial/stale error; duplicate read guard; in-flight-before-write invalidation and reload; account/logout/unmount guards; page wiring; real API auth and zero/count/money validation.');

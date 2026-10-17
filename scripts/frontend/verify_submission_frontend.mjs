import fs from 'node:fs'
import path from 'node:path'
import assert from 'node:assert/strict'
import {createRequire} from 'node:module'
const require=createRequire(import.meta.url)
const {parse,compileScript,compileTemplate}=require(process.env.UNI_COMPILER_HOME+'/node_modules/@vue/compiler-sfc')
const root=path.resolve('campus-market-client')
for(const name of ['pages/index/index.vue','pages/product/detail.vue','pages/product/edit.vue','pages/product/mine.vue','pages/product/favorites.vue','components/ProductComments.vue','components/ProductCard.vue','components/ImagePicker.vue','pages/user/login.vue','pages/user/register.vue']){
 const filename=path.join(root,name),{descriptor,errors}=parse(fs.readFileSync(filename,'utf8'),{filename});assert.deepEqual(errors,[])
 const script=compileScript(descriptor,{id:name});assert.deepEqual(compileTemplate({source:descriptor.template.content,filename,id:name,compilerOptions:{bindingMetadata:script.bindings}}).errors,[])
}
const session=await import('../.frontend-work/esm/utils/session.js'),api=await import('../.frontend-work/esm/api/comment.js'),drafts=await import('../.frontend-work/esm/utils/comment-submissions.js')
const user={id:1,username:'student_01',nickname:'小明'},other={id:2,username:'student_02',nickname:'另一用户'}
let requests=[],storage=new Map(),saveFails=false,clearFails=false
function install(){globalThis.uni={request:o=>requests.push(o),getStorageInfoSync:()=>({keys:[...storage.keys()]}),getStorageSync:k=>storage.get(k),setStorageSync:(k,v)=>{if(saveFails)throw new Error('缓存不可用');storage.set(k,v)},removeStorageSync:k=>{if(clearFails)throw new Error('清理失败');storage.delete(k)}}}install()
const keys=Array.from({length:100},()=>drafts.newCommentRequestId());assert.equal(new Set(keys).size,100);assert(keys.every(k=>/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(k)))
const key=keys[0];drafts.savePendingComment(1,7,{requestId:key,content:'第一份',createdAt:1});drafts.savePendingComment(1,7,{requestId:keys[1],content:'第二份',createdAt:2});drafts.savePendingComment(2,7,{requestId:keys[2],content:'其他账号',createdAt:3});drafts.savePendingComment(1,8,{requestId:keys[3],content:'其他商品',createdAt:4})
assert.equal(drafts.loadPendingComments(1,7).length,2);drafts.clearPendingComment(1,7,key);assert.equal(drafts.loadPendingComments(1,7)[0].requestId,keys[1]);assert.equal(drafts.loadPendingComments(2,7).length,1);assert.equal(drafts.loadPendingComments(1,8).length,1)
assert.throws(()=>drafts.savePendingComment(1,7,{requestId:keys[1],content:'换内容',createdAt:2}),/其他草稿/)
drafts.clearPendingComments(1,7);assert.equal(drafts.loadPendingComments(1,7).length,0);assert.equal(drafts.loadPendingComments(2,7).length,1)
storage.set('campus-comment-pending-v1:1:7:'+key,'broken');assert.throws(()=>drafts.loadPendingComments(1,7));drafts.clearPendingComments(1,7)
storage.clear();session.clearSession();await assert.rejects(api.createComment(7,{content:'留言',requestId:key}),e=>e.statusCode===401);assert.equal(requests.length,0)
session.saveSession({accessToken:'A'.repeat(43),tokenType:'Bearer',expiresInSeconds:7200,user});let operation=api.createComment(7,{content:'留言',requestId:key});assert.deepEqual(requests.at(-1).data,{content:'留言',requestId:key});requests.at(-1).success({statusCode:200,data:{replayed:true}});assert.equal((await operation).replayed,true)
const text=fs.readFileSync(path.join(root,'components/ProductComments.vue'),'utf8'),script=parse(text).descriptor.scriptSetup.content.replace(/^import .*$/mg,'');assert(!text.includes('v-html'));assert(text.includes('重试确认这次发表'))
const names=['ref','computed','watch','onMounted','onUnmounted','defineProps','defineEmits','getComments','createComment','withdrawComment','currentUser','getToken','uni',...Object.keys(drafts)]
const factory=new Function(...names,script+'\nreturn {records,total,page,loading,sending,message,draftContent,pendingSubmission,pendingCount,pendingStorageError,loadComments,sendComment,withdraw,restorePendingDraft,discardPendingDraft};')
const ref=value=>({value}),note={id:11,productId:7,content:'正常留言',author:{id:1,nickname:'小明'},seller:true,createdAt:'2026-10-12T07:00:00Z'}
function setup(options={}){
 let token=options.loggedOut?'':'A'.repeat(43),identity,mount,unmount;const currentUser=ref(options.loggedOut?null:(options.user||user)),props={productId:options.productId||7,status:options.offShelf?'OFF_SHELF':'ON_SALE'},writes=[],deletes=[],events=[]
 const env={ref,computed:get=>({get value(){return get()}}),watch:(s,cb)=>{if(Array.isArray(s))identity=cb},onMounted:cb=>mount=cb,onUnmounted:cb=>unmount=cb,defineProps:()=>props,defineEmits:()=>((e,v)=>events.push({e,v})),
  getComments:async(id,{page,size})=>({records:[{...note,productId:id}],total:1,page,size}),
  createComment:async(id,data)=>{writes.push({id,...data});return options.write?options.write(id,data):{comment:{...note,productId:id,content:data.content,author:{id:currentUser.value.id,nickname:currentUser.value.nickname}},requestId:data.requestId,replayed:false,withdrawn:false}},
  withdrawComment:async(id,commentId)=>{deletes.push({id,commentId})},currentUser,getToken:()=>token,uni:{navigateTo:o=>events.push(o.url)},...drafts}
 const model=factory(...names.map(n=>env[n]));return {model,writes,deletes,events,mount:async()=>{mount();await Promise.resolve();await Promise.resolve()},unmount:()=>unmount(),setUser:u=>{currentUser.value=u;token=u?'B'.repeat(43):'';identity()},setProduct:id=>{props.productId=id;identity()}}
}
let t=setup();t.model.draftContent.value='  新留言  ';await t.model.sendComment();assert.equal(t.writes[0].content,'新留言');assert(t.writes[0].requestId);assert.equal(storage.size,0);assert.equal(t.model.draftContent.value,'');assert.match(t.model.message.value,/已发表/)
// Lost outcome keeps durable content and ID; a fresh component restores and replays it.
t=setup({write:async()=>{throw Object.assign(new Error('超时'),{statusCode:0})}});t.model.draftContent.value='结果未收到';await t.model.sendComment();const sent=t.writes[0];assert.equal(drafts.loadPendingComments(1,7)[0].requestId,sent.requestId);assert.equal(t.model.pendingSubmission.value.content,'结果未收到');t.unmount()
let replay=setup({write:async(id,data)=>({comment:{...note,content:data.content},requestId:data.requestId,replayed:true,withdrawn:false})});await replay.mount();assert.equal(replay.model.draftContent.value,'结果未收到');await replay.model.sendComment();assert.equal(replay.writes[0].requestId,sent.requestId);assert.equal(replay.writes[0].content,sent.content);assert.equal(storage.size,0);assert.match(replay.model.message.value,/未重复发表/)
// Withdrawn receipts are acknowledged without creating a new key.
drafts.savePendingComment(1,7,{requestId:key,content:'已撤回的提交',createdAt:1});t=setup({write:async(id,data)=>({comment:{...note,content:data.content},requestId:data.requestId,replayed:true,withdrawn:true})});await t.mount();await t.model.sendComment();assert.match(t.model.message.value,/未重新创建/);assert.equal(storage.size,0)
// Queue entries are separate: acknowledging one does not delete another.
drafts.savePendingComment(1,7,{requestId:keys[0],content:'排队甲',createdAt:1});drafts.savePendingComment(1,7,{requestId:keys[1],content:'排队乙',createdAt:2});t=setup();await t.mount();await t.model.sendComment();assert.equal(t.writes.length,1);assert.equal(t.model.pendingSubmission.value.requestId,keys[1]);assert.equal(t.model.draftContent.value,'排队乙');assert.equal(storage.size,1);await t.model.sendComment();assert.equal(storage.size,0)
saveFails=true;t=setup();t.model.draftContent.value='不能保存';await t.model.sendComment();assert.equal(t.writes.length,0);assert.match(t.model.message.value,/尚未发送/);assert.equal(t.model.draftContent.value,'不能保存');saveFails=false
clearFails=true;t=setup();t.model.draftContent.value='已确认清理失败';await t.model.sendComment();assert.equal(storage.size,1);assert(t.model.pendingSubmission.value);assert.match(t.model.message.value,/清理失败/);const same=t.writes[0].requestId;clearFails=false;await t.model.sendComment();assert.equal(t.writes[1].requestId,same);assert.equal(storage.size,0)
let finish;t=setup({write:()=>new Promise(r=>finish=r)});t.model.draftContent.value='重复点击';let pending=t.model.sendComment();await t.model.sendComment();assert.equal(t.writes.length,1);const submitted=t.writes[0];finish({comment:{...note,content:submitted.content},requestId:submitted.requestId,replayed:false,withdrawn:false});await pending
let oldFinish;t=setup({write:()=>new Promise(r=>oldFinish=r)});t.model.draftContent.value='旧账号未确认';pending=t.model.sendComment();const old=t.writes[0];t.setUser(other);oldFinish({comment:{...note,content:old.content},requestId:old.requestId,replayed:false,withdrawn:false});await pending;assert.equal(drafts.loadPendingComments(1,7).length,1);assert.equal(t.model.pendingSubmission.value,null);assert.equal(t.model.draftContent.value,'');drafts.clearPendingComments(1,7)
for(const content of ['  ','a'.repeat(501),'😀'.repeat(501)]){t=setup();t.model.draftContent.value=content;await t.model.sendComment();assert.equal(t.writes.length,0)}
t=setup();t.model.draftContent.value='😀'.repeat(500);await t.model.sendComment();assert.equal(t.writes.length,1)
drafts.savePendingComment(1,7,{requestId:key,content:'原稿不可换内容',createdAt:1});t=setup({write:async()=>{throw Object.assign(new Error('内容冲突'),{statusCode:409})}});await t.mount();t.model.draftContent.value='其他文字';await t.model.sendComment();assert.equal(t.writes[0].content,'原稿不可换内容');assert.equal(drafts.loadPendingComments(1,7)[0].content,'原稿不可换内容');t.model.discardPendingDraft();assert.equal(storage.size,0)
t=setup({offShelf:true});t.model.draftContent.value='下架新稿';await t.model.sendComment();assert.equal(t.writes.length,0)
console.log('PASS: 10 SFCs; UUID format; per-account/product/request storage isolation; no cross-page overwrite; durable reload restoration; stable same-key retries; receipt/author validation; withdrawn acknowledgements; queue preservation; save-before-send; storage failures; duplicate clicks; stale identity protection; Unicode validation; original payload frozen until acknowledged or explicitly discarded')

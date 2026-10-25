import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

const source=await readFile(new URL('../components/PurchaseIntentList.vue',import.meta.url),'utf8')
const script=source.split('<script setup>')[1].split('</script>')[0].replace(/^import .*\n/gm,'')
const deferred=()=>{let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b});return{promise,resolve,reject}}
const settle=async()=>{for(let i=0;i<12;i++)await Promise.resolve()}
const row=(role='BUYER',id=11,actor=101,status='PENDING')=>({id,productId:7,productTitleSnapshot:'私有意向商品'+id,priceInCentsSnapshot:1000,productVersionSnapshot:0,
 buyer:{id:role==='BUYER'?actor:202,nickname:'买家'},seller:{id:role==='SELLER'?actor:202,nickname:'卖家'},status,createdAt:'2026-10-23T10:00:00',cancelledAt:status==='CANCELLED'?'2026-10-23T11:00:00':null,acceptedAt:status==='ACCEPTED'?'2026-10-23T11:00:00':null})
const page=(q,actor=101,changes={})=>({role:q.role,status:q.status,sort:q.sort,unreadOnly:q.unreadOnly,page:q.page,size:5,total:1,totalPages:1,
 items:[row(q.role,11,actor,q.status||'PENDING')],summary:{total:1,pending:1,accepted:0,cancelled:0},receivedMessageTimes:[{intentId:11,latestReceivedAt:'2026-10-23T10:30:00'}],...changes})
function mount(options={}){
 const props={active:true,disabled:false},user={value:{id:101,nickname:'原昵称'}},watchers=[],hooks={},reads=[],unreads=[],overviews=[],writes=[],modals=[],navigations=[]
 let token='a'
 const app=new Function('ref','computed','watch','onUnmounted','defineProps','currentUser','getToken','getPurchaseIntents','getPurchaseIntentUnread','getPurchaseIntentUnreadSummary','cancelPurchaseIntent','acceptPurchaseIntent','intentDetailPath','uni',script+
  ';return {expanded,loading,loaded,stale,role,page,totalPages,total,items,message,receivedMessageTimes,statusFilter,summary,unread,unreadOnly,overview,sort,writing,cancelId,acceptId,toggle,load,cancel,accept,openDetail}')
 (value=>({value}),f=>({get value(){return f()}}),(getter,callback,config)=>watchers.push({getter,callback,config,previous:getter()}),f=>hooks.unmount=f,()=>props,user,()=>token,
  async q=>{reads.push(q);return options.load?options.load(q):page(q,user.value.id)},
  async q=>{unreads.push(q);return options.unread?options.unread(q):{role:q.role,items:q.intentIds.map(id=>({intentId:id,unreadCount:2})),pageUnreadMessages:q.intentIds.length*2,pageUnreadIntents:q.intentIds.length}},
  async role=>{overviews.push(role);return options.overview?options.overview(role):{role,totalUnreadMessages:2,unreadIntentCount:1}},
  async id=>{writes.push(['cancel',id]);return options.write?options.write(id,false):{intent:row('BUYER',id,user.value.id,'CANCELLED'),replayed:false}},
  async id=>{writes.push(['accept',id]);return options.write?options.write(id,true):{intent:row('SELLER',id,user.value.id,'ACCEPTED'),replayed:false}},
  id=>'/pages/intent/detail?id='+id,{showModal:v=>{modals.push(v);if(options.modalThrow)throw new Error('弹窗失败')},navigateTo:v=>navigations.push(v),showToast(){}})
 function notify(index){const watch=watchers[index],next=watch.getter(),before=watch.previous;watch.previous=next;if(!Object.is(next,before))watch.callback(next,before)}
 return{app,props,user,reads,unreads,overviews,writes,modals,navigations,watchers,hooks,
  async open(){app.toggle();await settle()},
  identity(id,newToken){user.value=id?{id,nickname:'新昵称'}:null;token=newToken;notify(0)},
  nickname(name){user.value={...user.value,nickname:name};notify(0)},
  active(value){props.active=value;notify(1)}}
}
function cleared(f){
 const a=f.app;for(const name of ['expanded','loading','loaded','stale','writing','unreadOnly'])assert.equal(a[name].value,false,name)
 for(const name of ['summary','unread','overview','receivedMessageTimes'])assert.equal(a[name].value,null,name)
 assert.deepEqual(a.items.value,[]);assert.equal(a.total.value,0);assert.equal(a.page.value,1);assert.equal(a.totalPages.value,1)
 assert.equal(a.role.value,'BUYER');assert.equal(a.sort.value,'NEWEST');assert.equal(a.statusFilter.value,'');assert.equal(a.message.value,'');assert.equal(a.cancelId.value,0);assert.equal(a.acceptId.value,0)
}
test('actual component is lazy and commits page with both unread queries',async()=>{
 const f=mount();assert.equal(f.reads.length,0);await f.open();assert.equal(f.reads.length,1);assert.equal(f.unreads.length,1);assert.equal(f.overviews.length,1);assert.equal(f.app.items.value[0].id,11);assert.equal(f.app.loading.value,false);assert.equal(f.app.unread.value.pageUnreadMessages,2)
})
test('account and active watchers invalidate synchronously',()=>{
 const f=mount();assert.equal(f.watchers[0].config.flush,'sync');assert.equal(f.watchers[1].config.flush,'sync');assert.deepEqual(f.watchers[0].getter(),[101,'a'])
})
test('same-user new token clears all private state without fetching automatically',async()=>{
 const f=mount();await f.open();f.app.sort.value='RECENT_MESSAGE';f.app.role.value='SELLER';f.app.statusFilter.value='PENDING';f.app.unreadOnly.value=true;f.identity(101,'b');cleared(f);assert.equal(f.reads.length,1);await f.open();assert.equal(f.reads.length,2);assert.equal(f.app.loaded.value,true)
})
test('nickname update in same session does not collapse or invalidate a loaded list',async()=>{
 const f=mount();await f.open();const rows=f.app.items.value;f.nickname('只改昵称');assert.equal(f.app.expanded.value,true);assert.equal(f.app.items.value,rows);assert.equal(f.reads.length,1)
})
test('nickname update preserves an in-flight read ownership',async()=>{
 const d=deferred(),f=mount({load:()=>d.promise});f.app.toggle();f.nickname('新昵称');d.resolve(page(f.reads[0]));await settle();assert.equal(f.app.loaded.value,true);assert.equal(f.app.loading.value,false)
})
test('switching account or logout clears every private field',async()=>{
 for(const id of [102,null]){const f=mount();await f.open();f.identity(id,id?'b':'');cleared(f);assert.equal(f.reads.length,1)}
})
test('hidden component clears snapshots queries and locks; return waits for expansion',async()=>{
 const f=mount();await f.open();f.active(false);cleared(f);f.active(true);assert.equal(f.reads.length,1);await f.open();assert.equal(f.reads.length,2)
})
test('disabled interaction is not treated as hiding or changing identity',async()=>{
 const f=mount();await f.open();f.props.disabled=true;f.app.toggle();await f.app.load();assert.equal(f.app.expanded.value,true);assert.equal(f.app.loaded.value,true);assert.equal(f.reads.length,1)
})
test('normal collapse retains current session query choices',async()=>{
 const f=mount();await f.open();f.app.role.value='SELLER';f.app.statusFilter.value='PENDING';f.app.sort.value='RECENT_MESSAGE';f.app.unreadOnly.value=true;f.app.toggle();assert.equal(f.app.expanded.value,false);assert.equal(f.app.role.value,'SELLER');assert.equal(f.app.sort.value,'RECENT_MESSAGE');assert.equal(f.app.statusFilter.value,'PENDING')
})
for(const phase of ['page','unread','overview']){
 for(const fail of [false,true]){
  test(`same-account token replacement ignores late ${fail?'error':'result'} at ${phase} stage`,async()=>{
   const d=deferred(),options={};options[phase==='page'?'load':phase]=()=>d.promise;const f=mount(options);f.app.toggle();await settle();f.identity(101,'b');cleared(f)
   const value=phase==='page'?page(f.reads[0]):phase==='unread'?{role:'BUYER',items:[],pageUnreadMessages:0,pageUnreadIntents:0}:{role:'BUYER',totalUnreadMessages:0,unreadIntentCount:0}
   fail?d.reject(new Error('旧错误')):d.resolve(value);await settle();cleared(f)
  })
 }
}
test('old page completion cannot unlock a newer loading session or request its unread data',async()=>{
 const a=deferred(),b=deferred();let n=0;const f=mount({load:()=>++n===1?a.promise:b.promise});f.app.toggle();f.identity(101,'b');f.app.toggle();a.resolve(page(f.reads[0]));await settle();assert.equal(f.app.loading.value,true);assert.equal(f.unreads.length,0)
 b.resolve(page(f.reads[1]));await settle();assert.equal(f.app.loading.value,false);assert.equal(f.unreads.length,1)
})
for(const action of ['hide','unmount']){
 test(`${action} ignores an old list and cannot retain private rows`,async()=>{
  const d=deferred(),f=mount({load:()=>d.promise});f.app.toggle();action==='hide'?f.active(false):f.hooks.unmount();d.resolve(page(f.reads[0]));await settle();cleared(f);assert.equal(f.unreads.length,0)
 })
}
test('all read pieces must succeed before rendering; first failure does not invent counts',async()=>{
 const f=mount({overview:()=>{throw new Error('总览失败')}});await f.open();assert.equal(f.app.loaded.value,false);assert.equal(f.app.summary.value,null);assert.equal(f.app.overview.value,null);assert.deepEqual(f.app.items.value,[]);assert.match(f.app.message.value,/总览失败/)
})
test('same active session read failure retains old snapshot explicitly marked stale',async()=>{
 let n=0;const f=mount({load:q=>{if(++n>1)throw new Error('读取失败');return page(q)}});await f.open();const old=f.app.items.value;await f.app.load();assert.equal(f.app.items.value,old);assert.equal(f.app.stale.value,true);assert.match(f.app.message.value,/读取失败/)
})
test('late stale-read error after hide cannot restore old error or summary',async()=>{
 let n=0;const d=deferred(),f=mount({load:q=>++n===1?page(q):d.promise});await f.open();const pending=f.app.load();f.active(false);d.reject(new Error('旧请求失败'));await pending;cleared(f)
})
for(const kind of ['cancel','accept']){
 const role=kind==='accept'?'SELLER':'BUYER'
 test(`${kind} old confirmation cannot submit after same-user new token`,async()=>{
  const f=mount();await f.open();if(role==='SELLER')await f.app.load('SELLER',1);const pending=f.app[kind](f.app.items.value[0]);f.identity(101,'b');f.modals[0].success({confirm:true});await pending;cleared(f);assert.equal(f.writes.length,0)
 })
 test(`${kind} old confirmation cannot unlock a new session confirmation`,async()=>{
  const f=mount();await f.open();if(role==='SELLER')await f.app.load(role,1);const old=f.app[kind](f.app.items.value[0]);f.identity(101,'b');await f.open();if(role==='SELLER')await f.app.load(role,1);const fresh=f.app[kind](f.app.items.value[0]);f.modals[0].success({confirm:true});await old;assert.equal(f.app.writing.value,true);assert.equal(f.writes.length,0);f.modals[1].success({confirm:false});await fresh;assert.equal(f.app.writing.value,false)
 })
 test(`${kind} hidden confirmation sends no request`,async()=>{
  const f=mount();await f.open();if(role==='SELLER')await f.app.load(role,1);const pending=f.app[kind](f.app.items.value[0]);f.active(false);f.modals[0].success({confirm:true});await pending;cleared(f);assert.equal(f.writes.length,0)
 })
 for(const fail of [false,true]){
  test(`${kind} late ${fail?'error':'receipt'} cannot refresh or report success after identity change`,async()=>{
   const d=deferred(),f=mount({write:()=>d.promise});await f.open();if(role==='SELLER')await f.app.load(role,1);const pending=f.app[kind](f.app.items.value[0]);f.modals[0].success({confirm:true});await settle();const reads=f.reads.length;assert.equal(f.writes.length,1);f.identity(101,'b');fail?d.reject(new Error('旧写入失败')):d.resolve({intent:row(role,11,101,kind==='accept'?'ACCEPTED':'CANCELLED'),replayed:false});await pending;cleared(f);assert.equal(f.reads.length,reads)
  })
 }
}
test('new account pending write remains locked when old write finally completes',async()=>{
 const a=deferred(),b=deferred();let n=0;const f=mount({write:()=>++n===1?a.promise:b.promise});await f.open();const old=f.app.cancel(f.app.items.value[0]);f.modals[0].success({confirm:true});await settle();f.identity(102,'b');await f.open();const fresh=f.app.cancel(f.app.items.value[0]);f.modals[1].success({confirm:true});await settle();a.resolve({intent:row('BUYER',11,101,'CANCELLED'),replayed:false});await old;assert.equal(f.app.writing.value,true);b.reject(new Error('新写入超时'));await fresh;assert.equal(f.app.writing.value,false);assert.equal(f.app.stale.value,true)
})
test('hide during post-write refresh prevents new summary or success feedback',async()=>{
 const d=deferred();let n=0;const f=mount({load:q=>++n===1?page(q):d.promise});await f.open();const pending=f.app.cancel(f.app.items.value[0]);f.modals[0].success({confirm:true});await settle();assert.equal(f.reads.length,2);f.active(false);d.resolve(page(f.reads[1]));await pending;cleared(f);assert.equal(f.unreads.length,1)
})
test('canceling confirmation sends no write and keeps current list',async()=>{
 const f=mount();await f.open();const old=f.app.items.value,pending=f.app.cancel(old[0]);f.modals[0].success({confirm:false});await pending;assert.equal(f.writes.length,0);assert.equal(f.app.items.value,old);assert.equal(f.app.writing.value,false)
})
test('disabled while confirmation waits prevents the POST and releases the current lock',async()=>{
 const f=mount();await f.open();const pending=f.app.cancel(f.app.items.value[0]);f.props.disabled=true;f.modals[0].success({confirm:true});await pending;assert.equal(f.writes.length,0);assert.equal(f.app.writing.value,false)
})
test('double confirmation is blocked; dialog failure never writes',async()=>{
 const f=mount();await f.open();const pending=f.app.cancel(f.app.items.value[0]);await f.app.cancel(f.app.items.value[0]);assert.equal(f.modals.length,1);f.modals[0].fail(new Error('窗口失败'));await pending;assert.equal(f.writes.length,0);assert.equal(f.app.writing.value,false)
})
test('unmounted component is terminal even if active or identity callbacks run later',async()=>{
 const f=mount();await f.open();f.hooks.unmount();f.identity(101,'b');f.active(false);f.active(true);await f.open();await f.app.load();cleared(f);assert.equal(f.reads.length,1)
})
test('page data belonging to a different actor is rejected',async()=>{
 const f=mount({load:q=>page(q,102)});await f.open();assert.equal(f.app.loaded.value,false);assert.deepEqual(f.app.items.value,[]);assert.match(f.app.message.value,/账号不一致/)
})
test('existing session implementation makes same-account replacement observable without persisting tokens',async()=>{
 const session=await readFile(new URL('../utils/session.js',import.meta.url),'utf8');assert.ok(session.includes('accessToken = result.accessToken'));assert.ok(session.includes('currentUser.value = {'));assert.ok(!source.includes('setStorageSync'));assert.ok(!source.includes('v-html'))
})

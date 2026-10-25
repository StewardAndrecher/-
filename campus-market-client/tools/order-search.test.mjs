import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import {orderSearchQuery as query,readOrderSearchRoute,orderSearchListPath,orderSearchDetailPath,orderSearchBackPath,validateOrderSearchPage as validate} from '../utils/order-search.mjs'
const q=(patch={})=>({role:'BUYER',status:'ALL',orderId:'',page:1,...patch})
const summary=(patch={})=>({total:1,pendingPayment:1,paid:0,delivered:0,completed:0,cancelled:0,closedTimeout:0,completedAmountInCents:0,...patch})
const row=(patch={})=>({id:7,productId:7,productTitleSnapshot:'隔离订单样本',priceInCentsSnapshot:1200,productVersionSnapshot:0,buyer:{id:1,nickname:'甲'},seller:{id:2,nickname:'乙'},status:'PENDING_PAYMENT',createdAt:'2026-10-21T12:00:00',paymentDueAt:'2026-10-21T12:15:00',cancelledAt:null,paidAt:null,closedAt:null,paymentKind:null,deliveredAt:null,completedAt:null,...patch})
const page=(patch={})=>({items:[row()],total:1,page:1,size:10,totalPages:1,role:'BUYER',status:'ALL',orderId:null,summary:summary(),...patch})
const dataUrl=value=>'data:text/javascript;base64,'+Buffer.from(value).toString('base64')
let serial=0
async function apiFixture(reply=page()){
 globalThis.__ownOrderCalls=[];globalThis.__ownOrderReply=reply
 const stub=dataUrl('export async function request(options){globalThis.__ownOrderCalls.push(options);return globalThis.__ownOrderReply}')
 let source=await fs.readFile(new URL('../api/order.js',import.meta.url),'utf8')
 source=source.replaceAll('./request.js',stub).replace(/'@\/utils\/([^']+)'/g,(_,file)=>JSON.stringify(new URL('../utils/'+file,import.meta.url).href))
 return {api:await import(dataUrl(source+'\n//'+(++serial))),get calls(){return globalThis.__ownOrderCalls}}
}
test('default query keeps old buyer all entry',()=>assert.deepEqual(query(),q()))
test('canonical exact ids and largest safe number remain strings',()=>{assert.equal(query({orderId:'7'}).orderId,'7');assert.equal(query({orderId:'9007199254740991'}).orderId,'9007199254740991')})
test('invalid ids are rejected without coercion or truncation',()=>{for(const orderId of ['01',' 7','7 ','+7','-7','7e0','7.0','７','0','9007199254740992','12345678901234567',7,null,'7 OR 1=1'])assert.throws(()=>query({orderId}))})
test('role and status must be explicit whitelist members',()=>{for(const patch of [{role:'ADMIN'},{role:'buyer'},{role:null},{status:'BAD'},{status:''}])assert.throws(()=>query(patch))})
test('page is a bounded integer',()=>{for(const page of [0,-1,'1',1.5,1000001])assert.throws(()=>query({page}))})
test('route reads canonical page strings and preserves exact search',()=>assert.deepEqual(readOrderSearchRoute({role:'SELLER',status:'PAID',orderId:'7',page:'2'}),q({role:'SELLER',status:'PAID',orderId:'7',page:2})))
test('noncanonical URL pages fail before data request',()=>{for(const page of ['0','01',' 1','+1','1.0','1e0','1000001',1,null])assert.throws(()=>readOrderSearchRoute({page}))})
test('list path only carries fixed internal whitelist parameters',()=>assert.equal(orderSearchListPath({...q({orderId:'7',page:2}),returnUrl:'https://evil.invalid',userId:99}),'/pages/order/list?role=BUYER&status=ALL&orderId=7&page=2'))
test('detail carries the committed role status order and actual page',()=>assert.equal(orderSearchDetailPath(7,q({role:'SELLER',status:'PAID',orderId:'7',page:1})),'/pages/order/detail?id=7&fromRole=SELLER&fromStatus=PAID&fromOrderId=7&fromPage=1'))
test('return context round trip is a fixed list route',()=>assert.equal(orderSearchBackPath({id:'7',fromRole:'SELLER',fromStatus:'PAID',fromOrderId:'7',fromPage:'2'}),'/pages/order/list?role=SELLER&status=PAID&orderId=7&page=2'))
test('legacy direct detail links have no forced buyer return context',()=>assert.equal(orderSearchBackPath({id:'7'}),null))
test('detail and nonempty return order filter must match',()=>{assert.throws(()=>orderSearchDetailPath(8,q({orderId:'7'})));assert.throws(()=>orderSearchBackPath({id:'8',fromOrderId:'7'}))})
test('arbitrary external return destination is never adopted',()=>{assert.equal(orderSearchBackPath({id:'7',returnUrl:'https://evil.invalid'}),null);assert.throws(()=>orderSearchBackPath({id:'7',fromRole:'BUYER&userId=2'}))})
test('unfiltered response keeps original summary-count relationship',()=>validate(page(),q()))
test('filtered result retains whole-role summary',()=>validate(page({orderId:7,summary:summary({total:20,pendingPayment:20})}),q({orderId:'7'})))
test('empty exact search can coexist with nonempty whole-role summary',()=>validate(page({orderId:8,total:0,items:[]}),q({orderId:'8'})))
test('order filter echo must exactly match the committed query',()=>{for(const orderId of [null,8,'7'])assert.throws(()=>validate(page({orderId}),q({orderId:'7'})))})
test('a matching echo cannot hide unrelated order rows',()=>assert.throws(()=>validate(page({orderId:8}),q({orderId:'8'}))))
test('exact id can never return two different records',()=>assert.throws(()=>validate(page({orderId:7,total:2,items:[row(),row({id:8})],summary:summary({total:2,pendingPayment:2})}),q({orderId:'7'}))))
test('filtered count cannot exceed current-role status population',()=>assert.throws(()=>validate(page({orderId:7,status:'PAID'}),q({orderId:'7',status:'PAID'}))))
test('summary must still be a valid whole-role partition',()=>assert.throws(()=>validate(page({orderId:7,summary:summary({total:2})}),q({orderId:'7'}))))
test('server clamps out-of-range exact pages before detail navigation',()=>{const result=validate(page({orderId:7}),q({orderId:'7',page:999}));assert.equal(result.page,1);assert.match(orderSearchDetailPath(7,q({orderId:'7',page:result.page})),/fromPage=1$/)})
test('missing new echo or extra page fields fail closed',()=>{const missing=page();delete missing.orderId;assert.throws(()=>validate(missing,q()));assert.throws(()=>validate(page({requestId:'private'}),q()))})
test('unfiltered duplicate records are rejected',()=>assert.throws(()=>validate(page({total:2,summary:summary({total:2,pendingPayment:2}),items:[row(),row()]}),q())))
test('invalid row status price or time cannot enter result',()=>{for(const patch of [{priceInCentsSnapshot:0},{status:'BAD'},{createdAt:'2026-02-30T12:00'}])assert.throws(()=>validate(page({items:[row(patch)]}),q()))})
test('API sends authenticated legacy default search',async()=>{const f=await apiFixture();await f.api.getOrders();assert.equal(f.calls.length,1);assert.equal(f.calls[0].auth,true);assert.equal(f.calls[0].path,'/api/orders?role=BUYER&page=1&size=10&status=ALL&orderId=')})
test('API fourth argument is sent as exact string filter',async()=>{const f=await apiFixture(page({orderId:7}));await f.api.getOrders('BUYER',1,'ALL','7');assert.match(f.calls[0].path,/orderId=7$/)})
test('invalid input prevents the API request entirely',async()=>{const f=await apiFixture();await assert.rejects(f.api.getOrders('BUYER',1,'ALL','01'));assert.equal(f.calls.length,0)})
test('API rejects a response for another exact order',async()=>{const f=await apiFixture(page({orderId:8}));await assert.rejects(f.api.getOrders('BUYER',1,'ALL','7'));assert.equal(f.calls.length,1)})

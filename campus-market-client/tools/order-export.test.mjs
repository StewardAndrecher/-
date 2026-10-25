import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import {orderExportQuery as query,orderExportPath,readOrderExportRoute,validateOrderExport as validate,orderExportCsv as csv,orderExportFilename as filename} from '../utils/order-export.mjs'
import {downloadOrderCsv} from '../utils/order-export-download.mjs'
const q=(patch={})=>({role:'BUYER',status:'ALL',orderId:'',...patch})
const row=(patch={})=>({id:7,productTitleSnapshot:'私有台灯',priceInCentsSnapshot:1200,status:'PAID',createdAt:'2026-10-22T12:00:00',...patch})
const snapshot=(patch={})=>({role:'BUYER',status:'ALL',orderId:null,total:1,limit:200,records:[row()],...patch})
const dataUrl=s=>'data:text/javascript;base64,'+Buffer.from(s).toString('base64')
let serial=0
async function apiFixture(reply=snapshot()){
 const calls=[];globalThis.__orderExportState={calls,reply}
 const stub=dataUrl('export async function request(options){const s=globalThis.__orderExportState;s.calls.push(options);return s.reply}')
 let source=await fs.readFile(new URL('../api/order.js',import.meta.url),'utf8');source=source.replaceAll('./request.js',stub).replace(/'@\/utils\/([^']+)'/g,(_,file)=>JSON.stringify(new URL('../utils/'+file,import.meta.url).href))
 return {api:await import(dataUrl(source+'\n//'+(++serial))),calls}
}
// Independent CSV reader used to verify record/column boundaries and escaped text.
function parseCsv(text){const rows=[];let row=[],field='',quoted=false;for(let i=1;i<text.length;i++){const ch=text[i];if(ch==='"'){if(quoted&&text[i+1]==='"'){field+='"';i++}else quoted=!quoted}else if(!quoted&&ch===','){row.push(field);field=''}else if(!quoted&&ch==='\r'&&text[i+1]==='\n'){row.push(field);rows.push(row);row=[];field='';i++}else field+=ch}assert.equal(quoted,false);assert.equal(field,'');return rows}
function hostFixture({clickFails=false}={}){const calls=[],timers=[],host={Blob:class{constructor(parts,options){calls.push(['blob',parts,options])}},URL:{createObjectURL(){calls.push(['url']);return 'blob:private-export'},revokeObjectURL(url){calls.push(['revoke',url])}},setTimeout(fn,ms){timers.push(fn);calls.push(['timer',ms])},document:{body:{appendChild(){calls.push(['append'])}},createElement(tag){calls.push(['element',tag]);return {set href(x){calls.push(['href',x])},set download(x){calls.push(['filename',x])},click(){calls.push(['click']);if(clickFails)throw Error('click failed')},remove(){calls.push(['remove'])}}}}};return {host,calls,timers}}
test('default query has no user-selectable identity or limit',()=>assert.deepEqual(query({userId:999,limit:9999,page:9}),q()))
test('valid filter keeps exact string id and seller status',()=>assert.deepEqual(query(q({role:'SELLER',status:'COMPLETED',orderId:'9007199254740991'})),q({role:'SELLER',status:'COMPLETED',orderId:'9007199254740991'})))
test('invalid filters fail before export',()=>{for(const patch of [{role:'ADMIN'},{status:'BAD'},{orderId:'01'},{orderId:' 7'},{orderId:7},{orderId:null},{orderId:'9007199254740992'}])assert.throws(()=>query(patch))})
test('entry route carries actual page and only fixed parameters',()=>assert.equal(orderExportPath({...q({role:'SELLER',orderId:'7'}),page:2,returnUrl:'https://evil.invalid'}),'/pages/order/export?role=SELLER&status=ALL&orderId=7&page=2'))
test('export URL parses canonical scope and page',()=>assert.deepEqual(readOrderExportRoute({role:'SELLER',status:'PAID',orderId:'7',page:'2'}),{...q({role:'SELLER',status:'PAID',orderId:'7'}),page:2}))
test('malformed route rejects external destinations and pages',()=>{assert.throws(()=>readOrderExportRoute({role:'https://evil.invalid'}));assert.throws(()=>readOrderExportRoute({page:'01'}))})
test('valid minimal snapshot is accepted unchanged',()=>{const s=snapshot();assert.equal(validate(s,q()),s)})
test('empty export is valid and CSV has headers only',()=>{const s=snapshot({total:0,records:[]});assert.equal(validate(s,q()).total,0);assert.equal(parseCsv(csv(s,q())).length,1)})
test('200 is accepted but 201 is never silently truncated',()=>{const records=Array.from({length:200},(_,i)=>row({id:200-i}));assert.equal(validate(snapshot({total:200,records}),q()).total,200);assert.throws(()=>validate(snapshot({total:201,records:[row({id:201}),...records]}),q()))})
test('role status id and limit echo mismatch rejected',()=>{for(const patch of [{role:'SELLER'},{status:'PAID'},{orderId:7},{limit:201}])assert.throws(()=>validate(snapshot(patch),q()))})
test('extra fields carrying identity or tokens are rejected',()=>{for(const patch of [{userId:1},{accessToken:'secret'}])assert.throws(()=>validate(snapshot(patch),q()));assert.throws(()=>validate(snapshot({records:[row({buyerId:1})]}),q()))})
test('total must be exact nonnegative integer record count',()=>{for(const total of [-1,1.5,'1',0,2])assert.throws(()=>validate(snapshot({total}),q()))})
test('duplicate record ids are rejected',()=>assert.throws(()=>validate(snapshot({total:2,records:[row(),row()]}),q())))
test('record id price title time and state are validated',()=>{for(const patch of [{id:0},{id:9007199254740992},{priceInCentsSnapshot:0},{priceInCentsSnapshot:100000000},{priceInCentsSnapshot:1.5},{productTitleSnapshot:'x'},{productTitleSnapshot:'x'.repeat(51)},{productTitleSnapshot:'a\0b'},{createdAt:'2026-02-30T12:00:00'},{status:'UNKNOWN'}])assert.throws(()=>validate(snapshot({records:[row(patch)]}),q()))})
test('Unicode title length is measured in code points',()=>assert.equal(validate(snapshot({records:[row({productTitleSnapshot:'灯'.repeat(48)+'😀😀'})]}),q()).total,1))
test('rows must match nonempty id filter and status',()=>{assert.throws(()=>validate(snapshot({orderId:8}),q({orderId:'8'})));assert.throws(()=>validate(snapshot({status:'COMPLETED'}),q({status:'COMPLETED'})))})
test('filtered snapshot cannot contain multiple rows',()=>assert.throws(()=>validate(snapshot({orderId:7,total:2,records:[row(),row({id:6})]}),q({orderId:'7'}))))
test('created time ordering and equal-time id tie break validated',()=>{assert.throws(()=>validate(snapshot({total:2,records:[row(),row({id:8})]}),q()));assert.throws(()=>validate(snapshot({total:2,records:[row(),row({id:6,createdAt:'2026-10-22T13:00:00'})]}),q()));assert.equal(validate(snapshot({total:2,records:[row({createdAt:'2026-10-22T12:00'}),row({id:6,createdAt:'2026-10-22T12:00:00.000000'})]}),q()).total,2)})
test('CSV is UTF8 BOM with CRLF and six columns',()=>{const text=csv(snapshot(),q()),rows=parseCsv(text);assert.equal(text[0],'\uFEFF');assert.ok(text.endsWith('\r\n'));assert.equal(rows.length,2);assert.ok(rows.every(r=>r.length===6));assert.equal(rows[1][3],'12.00')})
test('all user titles have a visible nonformula text prefix',()=>{for(const title of ['=1+2','+SUM(A1)','-1+2','@SUM(A1)','\t=1+2','\r=1+2','\n=1+2','＝1+2','＋1+2','－1+2','＠SUM(A1)','  =1+2']){const rows=parseCsv(csv(snapshot({records:[row({productTitleSnapshot:title})]}),q()));assert.equal(rows[1][2],'商品：'+title);assert.ok(rows[1][2].startsWith('商品：'))}})
test('quotes commas CRLF and semicolon stay inside one CSV field',()=>{const title='=1+2",台灯;\r\n下一行';const rows=parseCsv(csv(snapshot({records:[row({productTitleSnapshot:title})]}),q()));assert.equal(rows.length,2);assert.equal(rows[1].length,6);assert.equal(rows[1][2],'商品：'+title)})
test('16 digit order ids remain exact text rather than spreadsheet numbers',()=>{const id=9007199254740991;assert.equal(parseCsv(csv(snapshot({records:[row({id})]}),q()))[1][0],'订单：9007199254740991')})
test('amount is formatted from integer cents without float rounding',()=>{for(const [cents,expected] of [[1,'0.01'],[100,'1.00'],[99999999,'999999.99']])assert.equal(parseCsv(csv(snapshot({records:[row({priceInCentsSnapshot:cents})]}),q()))[1][3],expected)})
test('file name is internal deterministic ascii and excludes private title',()=>{assert.equal(filename(), 'campus-orders-BUYER-ALL-all.csv');assert.equal(filename(q({role:'SELLER',status:'PAID',orderId:'7'})),'campus-orders-SELLER-PAID-7.csv');assert.throws(()=>filename({role:'../../tmp'}))})
test('CSV generation revalidates response before export',()=>assert.throws(()=>csv(snapshot({accessToken:'private'}),q())))
test('download builds only local Blob, safe file name and removes anchor',()=>{const f=hostFixture();assert.equal(downloadOrderCsv(snapshot(),q(),f.host),filename());assert.deepEqual(f.calls.filter(x=>['url','append','click','remove'].includes(x[0])).map(x=>x[0]),['url','append','click','remove']);assert.deepEqual(f.calls.find(x=>x[0]==='filename'),['filename',filename()]);f.timers.forEach(fn=>fn());assert.deepEqual(f.calls.at(-1),['revoke','blob:private-export'])})
test('download failure still removes anchor and revokes local URL',()=>{const f=hostFixture({clickFails:true});assert.throws(()=>downloadOrderCsv(snapshot(),q(),f.host));assert.ok(f.calls.some(x=>x[0]==='remove'));f.timers.forEach(fn=>fn());assert.deepEqual(f.calls.at(-1),['revoke','blob:private-export'])})
test('unsupported download environment fails before creating files',()=>assert.throws(()=>downloadOrderCsv(snapshot(),q(),{}),/复制CSV/))
test('API requests require auth and send only allowed filters',async()=>{const f=await apiFixture();await f.api.getOrderExport({...q(),userId:999});assert.deepEqual(f.calls,[{path:'/api/orders/export?role=BUYER&status=ALL&orderId=',auth:true}])})
test('API invalid filter sends no request',async()=>{const f=await apiFixture();await assert.rejects(()=>f.api.getOrderExport({orderId:'01'}));assert.equal(f.calls.length,0)})
test('API validates minimal response before returning it',async()=>{const f=await apiFixture(snapshot({role:'SELLER'}));await assert.rejects(()=>f.api.getOrderExport())})
async function pageFixture(options={}){
 const state={token:'private-token',user:{id:1},calls:[],pending:[],hooks:{},watchers:[],clipboard:[]}
 globalThis.__exportPage=state;const key=++serial
 const vue=dataUrl(`// fixture ${key}\nconst s=globalThis.__exportPage;export const ref=value=>({value});export const computed=fn=>({get value(){return fn()}});export const watch=(fn,cb)=>{s.watchers.push({fn,cb,last:fn()})}`)
 const uni=dataUrl(`// fixture ${key}\nconst s=globalThis.__exportPage;export const onLoad=fn=>s.hooks.load=fn;export const onShow=fn=>s.hooks.show=fn;export const onHide=fn=>s.hooks.hide=fn;export const onUnload=fn=>s.hooks.unload=fn`)
 const api=dataUrl(`// fixture ${key}\nconst s=globalThis.__exportPage;export function getOrderExport(query){s.calls.push(query);return new Promise((resolve,reject)=>s.pending.push({resolve,reject}))}`)
 const session=dataUrl(`// fixture ${key}\nconst s=globalThis.__exportPage;export const currentUser={get value(){return s.user}};export const getToken=()=>s.token`)
 let source=(await fs.readFile(new URL('../pages/order/export.vue',import.meta.url),'utf8')).split('<script setup>')[1].split('</script>')[0]
 source=source.replace("'vue'",JSON.stringify(vue)).replace("'@dcloudio/uni-app'",JSON.stringify(uni)).replace("'@/api/order.js'",JSON.stringify(api)).replace("'@/utils/session.js'",JSON.stringify(session)).replace(/'@\/utils\/([^']+)'/g,(_,file)=>JSON.stringify(new URL('../utils/'+file,import.meta.url).href))
 globalThis.uni={setClipboardData(v){state.clipboard.push(v)},navigateTo(v){state.navigation=v},navigateBack(){state.back=true},redirectTo(v){state.redirect=v}}
 globalThis.getCurrentPages=()=>[]
 const page=await import(dataUrl(source+'\nexport {query,snapshot,busy,error,message,routeError,generate,copy,back};\n//'+key))
 state.hooks.load(options);state.hooks.show()
 state.change=(user,token)=>{state.user=user;state.token=token;for(const w of state.watchers){const before=w.last;w.last=w.fn();w.cb(w.last,before)}}
 return {state,page}
}
test('actual page requires explicit generation and no auth prevents HTTP',async()=>{const f=await pageFixture();assert.equal(f.state.calls.length,0);f.state.change(null,'');await f.page.generate();assert.equal(f.state.calls.length,0);assert.match(f.page.error.value,/登录/)})
test('actual page rejects bad route before fetching',async()=>{const f=await pageFixture({orderId:'01'});await f.page.generate();assert.equal(f.state.calls.length,0);assert.ok(f.page.routeError.value)})
test('actual page hides private snapshot and ignores late response when hidden',async()=>{const f=await pageFixture();const p=f.page.generate();assert.equal(f.page.busy.value,true);f.state.hooks.hide();f.state.pending[0].resolve(snapshot());await p;assert.equal(f.page.snapshot.value,null);assert.equal(f.page.busy.value,false)})
test('actual page account change invalidates request and resets private filter',async()=>{const f=await pageFixture({role:'SELLER',status:'PAID',orderId:'7'});const p=f.page.generate();f.state.change({id:2},'new-token');f.state.pending[0].resolve(snapshot({role:'SELLER',status:'PAID',orderId:7}));await p;assert.equal(f.page.snapshot.value,null);assert.deepEqual(f.page.query.value,{...q(),page:1})})
test('actual page token rotation discards old snapshot without changing scope',async()=>{const f=await pageFixture({orderId:'7'});const p=f.page.generate();f.state.change({id:1},'new-token');f.state.pending[0].resolve(snapshot({orderId:7}));await p;assert.equal(f.page.snapshot.value,null);assert.equal(f.page.query.value.orderId,'7')})
test('actual page failure removes prior preview instead of exporting stale rows',async()=>{const f=await pageFixture();let p=f.page.generate();f.state.pending[0].resolve(snapshot());await p;assert.equal(f.page.snapshot.value.total,1);p=f.page.generate();f.state.pending[1].reject(Error('超过200笔'));await p;assert.equal(f.page.snapshot.value,null);assert.match(f.page.error.value,/200/);f.page.copy();assert.equal(f.state.clipboard.length,0)})
test('actual page copy uses validated CSV and late callback cannot affect new user',async()=>{const f=await pageFixture();const p=f.page.generate();f.state.pending[0].resolve(snapshot());await p;f.page.copy();assert.equal(f.state.clipboard[0].data,csv(snapshot(),q()));f.state.change({id:2},'new-token');f.state.clipboard[0].success();f.state.clipboard[0].complete();assert.equal(f.page.message.value,'');assert.equal(f.page.snapshot.value,null);assert.equal(f.page.busy.value,false)})

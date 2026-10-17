import assert from 'node:assert/strict'
import fs from 'node:fs'
import {createRequire} from 'node:module'
const require=createRequire(import.meta.url)
const {parse,compileScript,compileTemplate}=require(process.env.UNI_COMPILER_HOME+'/node_modules/@vue/compiler-sfc')
const root='campus-market-client/'
for(const file of ['components/NetworkStatusBanner.vue','pages/index/index.vue','pages/dev/connection.vue']){
 const {descriptor,errors}=parse(fs.readFileSync(root+file,'utf8'));assert.deepEqual(errors,[])
 const script=compileScript(descriptor,{id:file});const template=compileTemplate({source:descriptor.template.content,filename:file,id:file,compilerOptions:{bindingMetadata:script.bindings}});assert.deepEqual(template.errors,[])
}
const code=parse(fs.readFileSync(root+'components/NetworkStatusBanner.vue','utf8')).descriptor.scriptSetup.content.replace(/^import .*$/mg,'')
let mounted,unmounted,acquires=0,releases=0,acknowledged=0;const events=[]
const status={value:{state:'offline',recovered:false}},props={busy:false,allowRefresh:true}
const banner=new Function('onMounted','onUnmounted','defineProps','defineEmits','networkStatus','acquireNetworkStatus','refreshNetworkStatus','acknowledgeNetworkRecovery',code+'\nreturn {retry};')(
 cb=>mounted=cb,cb=>unmounted=cb,()=>props,()=>((...args)=>events.push(args)),status,()=>{acquires++;return()=>releases++},()=>{},()=>{acknowledged++;status.value.recovered=false})
mounted();assert.equal(acquires,1);banner.retry();assert.equal(events.length,0)
status.value={state:'online',recovered:true};props.busy=true;banner.retry();assert.equal(events.length,0)
props.busy=false;props.allowRefresh=false;banner.retry();assert.equal(events.length,0)
props.allowRefresh=true;banner.retry();assert.deepEqual(events,[['retry']]);assert.equal(acknowledged,1)
banner.retry();assert.equal(events.length,1)
unmounted();assert.equal(releases,1);status.value.recovered=true;banner.retry();mounted();assert.equal(acquires,1);assert.equal(events.length,1)
// 使用真实Vue状态适配器；只mock设备网络API。
let query,listener,removed,reads=0
const otherListener=()=>{};const listeners=new Set([otherListener])
globalThis.uni={getNetworkType:o=>{query=o;reads++},onNetworkStatusChange:cb=>{listener=cb;listeners.add(cb)},offNetworkStatusChange:cb=>{removed=cb;listeners.delete(cb)}}
const adapter=await import('../.frontend-work/esm/utils/network-status.js'),release=adapter.acquireNetworkStatus()
query.success({networkType:'none'});assert.equal(adapter.networkStatus.value.state,'offline')
listener({networkType:'wifi',isConnected:true});assert.equal(adapter.networkStatus.value.recovered,true)
// App应用生命周期刷新只读网络，不触发业务请求。
const appCode=parse(fs.readFileSync(root+'App.vue','utf8')).descriptor.script.content.replace(/^\s*import .*$/mg,'').replace('export default','return')
const app=new Function('refreshNetworkStatus',appCode)(adapter.refreshNetworkStatus)
const before=reads;app.onShow();assert.equal(reads,before+1);query.success({networkType:'wifi'})
adapter.acknowledgeNetworkRecovery();assert.equal(adapter.networkStatus.value.recovered,false)
release();assert.equal(removed,listener);assert.deepEqual([...listeners],[otherListener])
app.onShow();assert.equal(reads,before+1,'no active consumers: foreground hook performs no device query')
console.log('PASS: new/home/diagnostic SFC compilation; banner mount/unmount, busy/allowRefresh/offline guards and explicit single retry; real reactive adapter; App foreground checks; preserves other listeners; no transport APIs invoked.')

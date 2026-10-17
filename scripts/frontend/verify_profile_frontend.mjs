import fs from 'node:fs'
import assert from 'node:assert/strict'
import {createRequire} from 'node:module'
const require=createRequire(import.meta.url),{parse}=require(process.env.UNI_COMPILER_HOME+'/node_modules/@vue/compiler-sfc')
const session=await import('../.frontend-work/esm/utils/session.js'),api=await import('../.frontend-work/esm/api/user.js')
let requests=[];globalThis.uni={request:o=>requests.push(o)};session.clearSession()
await assert.rejects(api.updateMyNickname({nickname:'同学',expectedNickname:'原昵称'}),e=>e.statusCode===401);assert.equal(requests.length,0)
const user={id:1,username:'profile_01',nickname:'OriginalName'}
const saveSession=token=>session.saveSession({accessToken:token,tokenType:'Bearer',expiresInSeconds:7200,user})
saveSession('A'.repeat(43));let operation=api.updateMyNickname({nickname:'同学',expectedNickname:'OriginalName'})
assert.equal(requests.at(-1).url,'http://localhost:8080/api/users/me/nickname');assert.equal(requests.at(-1).method,'PUT');assert.equal(requests.at(-1).header.Authorization,'Bearer '+'A'.repeat(43))
requests.at(-1).success({statusCode:409,data:{code:'USER_PROFILE_CONFLICT',message:'冲突'}});await assert.rejects(operation,e=>e.code==='USER_PROFILE_CONFLICT');assert.equal(session.getToken(),'A'.repeat(43))
operation=api.updateMyNickname({});saveSession('B'.repeat(43));requests.at(-1).success({statusCode:401,data:{message:'旧请求失效'}});await assert.rejects(operation);assert.equal(session.getToken(),'B'.repeat(43))
const script=parse(fs.readFileSync('campus-market-client/pages/user/profile.vue','utf8')).descriptor.scriptSetup.content.replace(/^import .*$/mg,'')
const names=['ref','onShow','onUnload','getCurrentUser','updateMyNickname','currentUser','getToken','updateCurrentUser','uni','getCurrentPages']
const factory=new Function(...names,script+'\nreturn {profile,draftNickname,expectedNickname,loading,saving,conflict,uncertain,loadError,message,loadProfile,saveNickname};')
const ref=value=>({value})
function setup(options={}) {
 let show,unload,token=options.loggedOut?'':'A'.repeat(43),remote={...user},calls=0,payload
 const currentUser=ref(options.loggedOut?null:{...user})
 const env={ref,onShow:cb=>show=cb,onUnload:cb=>unload=cb,getCurrentUser:options.get||async function(){return {...remote}},
 updateMyNickname:async data=>{calls++;payload=data;if(options.update)return options.update(data);remote={...remote,nickname:data.nickname};return {...remote}},
 currentUser,getToken:()=>token,updateCurrentUser:(u,expected)=>{if(token===expected)currentUser.value={...u}},uni:{navigateTo:()=>{},navigateBack:()=>{},reLaunch:()=>{},showToast:()=>{}},getCurrentPages:()=>[{},{}]}
 return {model:factory(...names.map(n=>env[n])),show:()=>show(),unload:()=>unload(),calls:()=>calls,payload:()=>payload,currentUser,remote:u=>remote={...u},account:(u,t)=>{currentUser.value=u;token=t}}
}
let t=setup({loggedOut:true});await t.show();assert.match(t.model.loadError.value,/请先登录/);await t.model.saveNickname();assert.equal(t.calls(),0)
t=setup();await t.show();assert.equal(t.model.draftNickname.value,'OriginalName');assert.equal(t.model.expectedNickname.value,'OriginalName')
t.model.draftNickname.value='  校园同学😀  ';await t.model.saveNickname();assert.deepEqual(t.payload(),{nickname:'校园同学😀',expectedNickname:'OriginalName'});assert.equal(t.currentUser.value.nickname,'校园同学😀');assert.equal(t.model.message.value,'昵称已保存')
for(const bad of ['', ' '.repeat(3),'😀'.repeat(31),'甲\n乙','甲\u007f乙']){t=setup();await t.show();t.model.draftNickname.value=bad;await t.model.saveNickname();assert.equal(t.calls(),0);assert(t.model.message.value)}
t=setup();await t.show();t.model.draftNickname.value='😀'.repeat(30);await t.model.saveNickname();assert.equal(t.calls(),1)
t=setup({update:async()=>{throw Object.assign(new Error('冲突'),{statusCode:409,code:'USER_PROFILE_CONFLICT'})}});await t.show();t.model.draftNickname.value='保留草稿';await t.model.saveNickname();assert(t.model.conflict.value);assert.equal(t.model.expectedNickname.value,'OriginalName');await t.model.saveNickname();assert.equal(t.calls(),1)
t.remote({...user,nickname:'另一处的新昵称'});await t.model.loadProfile();assert.equal(t.model.draftNickname.value,'保留草稿');await t.model.loadProfile(true);assert.equal(t.model.draftNickname.value,'另一处的新昵称');assert.equal(t.model.expectedNickname.value,'另一处的新昵称');assert.equal(t.model.conflict.value,false)
for(const statusCode of [0,500]){t=setup({update:async()=>{throw Object.assign(new Error('失败'),{statusCode})}});await t.show();t.model.draftNickname.value='未确认草稿';await t.model.saveNickname();assert(t.model.uncertain.value);await t.model.saveNickname();assert.equal(t.calls(),1);await t.model.loadProfile(true);assert.equal(t.model.uncertain.value,false)}
t=setup({update:async()=>({...user,id:2,nickname:'新名字'})});await t.show();t.model.draftNickname.value='新名字';await t.model.saveNickname();assert(t.model.uncertain.value);assert.equal(t.currentUser.value.id,1);assert.equal(t.currentUser.value.nickname,'OriginalName')
t=setup({get:async()=>({...user,id:2})});await t.show();assert.match(t.model.loadError.value,/不匹配/);assert.equal(t.model.profile.value,null)
let finish;t=setup({update:()=>new Promise(r=>finish=r)});await t.show();t.model.draftNickname.value='并发提交';operation=t.model.saveNickname();await t.model.saveNickname();assert.equal(t.calls(),1);finish({...user,nickname:'并发提交'});await operation
let closed;t=setup({update:()=>new Promise(r=>closed=r)});await t.show();t.model.draftNickname.value='离开页面';operation=t.model.saveNickname();t.unload();closed({...user,nickname:'离开页面'});await operation;assert.equal(t.currentUser.value.nickname,'OriginalName')
let load;t=setup({get:()=>new Promise(r=>load=r)});operation=t.show();t.unload();load({...user});await operation;assert.equal(t.model.profile.value,null)
t=setup();await t.show();t.model.draftNickname.value='重新登录后的草稿';t.account(null,'');await t.show();t.account({...user},'B'.repeat(43));await t.show();assert.equal(t.model.draftNickname.value,'重新登录后的草稿');assert.equal(t.model.expectedNickname.value,'OriginalName')
t.account({id:2,username:'profile_02',nickname:'其他用户'},'C'.repeat(43));t.remote({id:2,username:'profile_02',nickname:'其他用户'});await t.show();assert.equal(t.model.profile.value.id,2);assert.equal(t.model.draftNickname.value,'其他用户')
console.log('PASS: profile API auth/PUT/conflict/old-token guard; prefill/normalization/Unicode/controls; local session sync; stale draft and explicit reload; uncertain outcomes; identity mismatch; duplicate submit/unload; same-account relogin and account switching')

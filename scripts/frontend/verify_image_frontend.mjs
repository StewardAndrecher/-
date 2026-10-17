import fs from 'node:fs'
import assert from 'node:assert/strict'
import {createRequire} from 'node:module'
const require=createRequire(import.meta.url)
const {parse}=require(process.env.UNI_COMPILER_HOME+'/node_modules/@vue/compiler-sfc')
const session=await import('../.frontend-work/esm/utils/session.js'),api=await import('../.frontend-work/esm/api/image.js')
const id='aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee',result={id,url:'/api/images/'+id,width:100,height:80}
const user={id:1,username:'student_01',nickname:'小明'}
const login=token=>session.saveSession({accessToken:token.repeat(43),tokenType:'Bearer',expiresInSeconds:7200,user})
let requests=[];globalThis.uni={uploadFile:o=>requests.push(o)}
session.clearSession();await assert.rejects(api.uploadImage('/tmp/sample.png'),e=>e.statusCode===401);assert.equal(requests.length,0)
login('A');let op=api.uploadImage('/tmp/sample.png');assert.equal(requests.at(-1).name,'file');assert.equal(requests.at(-1).header.Authorization,'Bearer '+'A'.repeat(43));assert.equal(requests.at(-1).header['Content-Type'],undefined)
requests.at(-1).success({statusCode:'201',data:JSON.stringify(result)});assert.deepEqual(await op,result)
assert.equal(api.imageUrl(id),'http://localhost:8080/api/images/'+id);assert.equal(api.imageUrl('../secret'),'');assert.equal(api.imageUrl(null),'')
op=api.uploadImage('/tmp/sample.png');const stale=requests.at(-1);login('B');stale.success({statusCode:401,data:'{"message":"过期"}'});await assert.rejects(op);assert.equal(session.getToken(),'B'.repeat(43))
op=api.uploadImage('/tmp/sample.png');requests.at(-1).success({statusCode:413,data:'{"message":"图片不能超过5MB"}'});await assert.rejects(op,e=>e.statusCode===413)
op=api.uploadImage('/tmp/sample.png');requests.at(-1).success({statusCode:201,data:'broken'});await assert.rejects(op,/格式不正确/)
op=api.uploadImage('/tmp/sample.png');requests.at(-1).fail();await assert.rejects(op,e=>e.statusCode===0);assert.equal(session.getToken(),'B'.repeat(43))
const script=parse(fs.readFileSync('campus-market-client/components/ImagePicker.vue','utf8')).descriptor.scriptSetup.content.replace(/^import .*$/mg,'')
const names=['ref','onUnmounted','defineProps','defineEmits','imageUrl','uploadImage','getToken','uni']
const factory=new Function(...names,script+'\nreturn {choose,remove,uploading,errorMessage};')
function setup(options={}) {
 let unmount,choice,calls=0,token=options.loggedOut?'':'A'.repeat(43);const events=[]
 const env={ref:value=>({value}),onUnmounted:cb=>unmount=cb,defineProps:()=>({modelValue:'',disabled:!!options.disabled}),defineEmits:()=>((...args)=>events.push(args)),imageUrl:api.imageUrl,
   uploadImage:async path=>{calls++;return options.upload?options.upload(path):result},getToken:()=>token,uni:{chooseImage:o=>choice=o}}
 return {model:factory(...names.map(n=>env[n])),events,calls:()=>calls,choice:()=>choice,unmount:()=>unmount(),switchToken:()=>token='B'.repeat(43)}
}
let test=setup();test.model.choose();await test.choice().success({tempFilePaths:['/tmp/sample.png']});assert.deepEqual(test.events,[['busy',true],['update:modelValue',id],['busy',false]])
test.model.remove();assert.deepEqual(test.events.at(-1),['update:modelValue',''])
test=setup({loggedOut:true});test.model.choose();assert.match(test.model.errorMessage.value,/请先登录/);assert.equal(test.calls(),0)
test=setup();test.model.choose();test.choice().fail({errMsg:'chooseImage:fail cancel'});assert.equal(test.model.errorMessage.value,'');assert.equal(test.model.uploading.value,false)
test=setup();test.model.choose();await test.choice().success({tempFilePaths:['/tmp/large.png'],tempFiles:[{size:5*1024*1024+1}]});assert.equal(test.calls(),0);assert.match(test.model.errorMessage.value,/5MB/)
test=setup();test.model.choose();test.switchToken();await test.choice().success({tempFilePaths:['/tmp/sample.png']});assert.equal(test.calls(),0)
let finish;test=setup({upload:()=>new Promise(r=>finish=r)});test.model.choose();const pending=test.choice().success({tempFilePaths:['/tmp/sample.png']});test.model.choose();assert.equal(test.calls(),1);test.unmount();finish(result);await pending;assert.equal(test.events.filter(e=>e[0]==='update:modelValue').length,0)
console.log('PASS: multipart headers/JSON validation, 413 handling, stale-401 guard, safe image URLs, picker ownership/size/cancel/unload guards, preview/remove events')

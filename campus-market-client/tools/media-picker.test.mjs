import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
const source=readFileSync(new URL('../components/MediaPicker.vue',import.meta.url),'utf8').split('<script setup>')[1].split('</script>')[0].replace(/^import .*\n/gm,'')
const deferred=()=>{let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b});return {promise,resolve,reject}}
function fixture(options={}){
 const events=[],uploads=[],watchers=[],hooks={};let picker,token='session-a',user={id:1}
 const props={modelValue:[],limit:6,label:'商品图片',disabled:false,active:true,...options.props}
 const app=new Function('ref','watch','onUnmounted','imageUrl','uploadImage','getToken','currentUser','defineProps','defineEmits','uni',source+';return {choose,remove,makeCover,uploading,error}')(
  value=>({value}),(getter,callback)=>watchers.push({getter,callback,previous:getter()}),f=>hooks.unmount=f,id=>'url:'+id,async path=>{uploads.push(path);return options.upload?options.upload(path):{id:path}},()=>token,{get value(){return user}},()=>props,()=>((event,value)=>events.push({event,value})),{chooseImage:value=>{picker=value},previewImage(){}})
 function notify(){for(const watcher of watchers){const next=watcher.getter();watcher.callback(next,watcher.previous);watcher.previous=next}}
 return {app,props,events,uploads,hooks,get picker(){return picker},hide(){props.active=false;notify()},show(){props.active=true;notify()},identity(id,next){user={id};token=next;notify()}}
}
test('media picker uploads several images in order and makes first the cover',async()=>{const f=fixture();f.app.choose();assert.equal(f.picker.count,6);await f.picker.success({tempFilePaths:['a','b']});assert.deepEqual(f.events.find(event=>event.event==='update:modelValue').value,['a','b']);assert.deepEqual(f.uploads,['a','b']);assert.equal(f.app.uploading.value,false)})
test('cover reordering and removal preserve remaining image order',()=>{const f=fixture({props:{modelValue:['a','b','c']}});f.app.makeCover(2);assert.deepEqual(f.events[0].value,['c','a','b']);f.app.remove(1);assert.deepEqual(f.events[1].value,['a','c'])})
test('duplicate click does not open a second picker while upload is pending',()=>{const f=fixture();f.app.choose();const first=f.picker;f.app.choose();assert.equal(f.picker,first);assert.equal(f.events.filter(event=>event.value===true).length,1)})
test('disabled and full gallery cannot choose or mutate',()=>{for(const props of [{disabled:true},{modelValue:Array(6).fill('a')}]){const f=fixture({props});f.app.choose();assert.equal(f.picker,undefined)}const f=fixture({props:{disabled:true,modelValue:['a','b']}});f.app.makeCover(1);f.app.remove(0);assert.equal(f.events.length,0)})
test('oversized selected file is rejected before any upload',async()=>{const f=fixture();f.app.choose();await f.picker.success({tempFilePaths:['large'],tempFiles:[{size:6*1024*1024}]});assert.equal(f.uploads.length,0);assert.match(f.app.error.value,/5MB/)})
for(const action of ['hide','unmount','logout'])test(`late upload after ${action} cannot assign images`,async()=>{const d=deferred(),f=fixture({upload:()=>d.promise});f.app.choose();const pending=f.picker.success({tempFilePaths:['a']});action==='hide'?f.hide():action==='unmount'?f.hooks.unmount():f.identity(2,'session-b');d.resolve({id:'old-user-image'});await pending;assert.equal(f.events.filter(event=>event.event==='update:modelValue').length,0);assert.equal(f.app.uploading.value,false)})
test('failed later file preserves successfully uploaded earlier files and shows an error',async()=>{const f=fixture({upload:path=>{if(path==='b')throw new Error('upload unavailable');return {id:path}}});f.app.choose();await f.picker.success({tempFilePaths:['a','b']});assert.deepEqual(f.events.find(event=>event.event==='update:modelValue').value,['a']);assert.match(f.app.error.value,/unavailable/)})

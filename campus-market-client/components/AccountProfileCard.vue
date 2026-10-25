<template>
 <view class="profile-section">
  <view class="profile-section-heading"><view><text class="profile-eyebrow">YOUR CAMPUS PROFILE</text><text class="profile-title">让同学更了解你</text></view><text class="profile-public">公开资料</text></view>
  <text class="profile-note">头像、校区和简介会展示在卖家主页及商品资料中。校区为自行填写，不代表校园身份认证。</text>
  <text v-if="state.loading" class="profile-note">正在读取个人资料…</text><text v-if="state.error" class="profile-error">{{ state.error }}</text><text v-if="state.message" class="profile-success">{{ state.message }}</text>
  <view v-if="state.data" class="profile-grid">
   <MediaPicker :model-value="avatar ? [avatar] : []" :limit="1" label="个人头像" :active="active" :disabled="busy" @update:modelValue="avatar=$event[0] || ''" @busy="uploading=$event" />
   <view><text class="profile-label">所在校区</text><picker :range="campuses" range-key="name" :value="campusIndex" :disabled="busy || uploading" @change="choose"><view class="profile-select">{{ campuses[campusIndex].name }} <text>⌄</text></view></picker><text class="profile-label">个人简介</text><textarea v-model="bio" class="profile-textarea" :maxlength="-1" :disabled="busy || uploading" placeholder="介绍自己、常见交接时间或闲置分享偏好…" /><text class="profile-counter">{{ Array.from(bio).length }} / 160</text><text class="profile-note">请勿填写证件号码、密码或详细住址。</text><button class="profile-save" :disabled="busy || uploading" @click="save">{{ state.saving ? '正在保存…' : '保存个人资料' }}</button></view>
  </view>
  <button class="profile-refresh" :disabled="busy || uploading" @click="manager.refresh()">重新加载资料（替换未保存内容）</button>
 </view>
</template>
<script setup>
import { ref,computed,watch,onUnmounted } from 'vue'
import MediaPicker from '@/components/MediaPicker.vue'
import { campuses } from '@/utils/product-details.mjs'
import { profileInput,validateAccountProfile } from '@/utils/account-profile.mjs'
import { currentUser,getToken } from '@/utils/session.js'
import { createOwnedResource } from '@/utils/owned-resource.mjs'
import { getAccountProfile,updateAccountProfile } from '@/api/account-profile.js'
const props=defineProps({active:Boolean}),avatar=ref(''),bio=ref(''),campus=ref('UNSPECIFIED'),uploading=ref(false),state=ref({data:null,loading:false,saving:false,error:'',message:''})
const busy=computed(()=>state.value.loading||state.value.saving),campusIndex=computed(()=>Math.max(0,campuses.findIndex(item=>item.code===campus.value)))
let displayed=null
const manager=createOwnedResource({identity:()=>({userId:currentUser.value?.id,token:getToken()}),load:getAccountProfile,validate:validateAccountProfile,notify:value=>{state.value=value;if(value.data && displayed!==value.data){displayed=value.data;avatar.value=value.data.avatarImageId || '';bio.value=value.data.bio;campus.value=value.data.campusCode}}})
function choose(event){const item=campuses[Number(event.detail.value)];if(item&&!busy.value&&!uploading.value)campus.value=item.code}
async function save(){
 if(busy.value||uploading.value||!state.value.data)return
 let input;try{input=profileInput({avatarImageId:avatar.value,bio:bio.value,campusCode:campus.value})}catch(error){state.value={...state.value,error:error.message};return}
 await manager.mutate({write:snapshot=>updateAccountProfile({...input,version:snapshot.version}),validateReceipt:(result,snapshot,owner)=>{validateAccountProfile(result,owner);if((result.avatarImageId||'')!==input.avatarImageId||result.bio!==input.bio||result.campusCode!==input.campusCode||result.version<snapshot.version||result.version>snapshot.version+1)throw new Error('资料保存回执不匹配')},message:'个人资料已保存。'})
}
watch(()=>[props.active,currentUser.value?.id,getToken()],()=>{manager.hide();displayed=null;avatar.value='';bio.value='';campus.value='UNSPECIFIED';uploading.value=false;if(props.active&&currentUser.value)manager.show()},{immediate:true,flush:'sync'})
onUnmounted(()=>{manager.dispose();avatar.value='';bio.value='';uploading.value=false})
</script>
<style scoped>
.profile-section{background:#fff;padding:28px;border:1px solid #e4ecf7;border-radius:18px;margin:24px 0;color:#203c61}.profile-section-heading{display:flex;justify-content:space-between;gap:15px;align-items:center}.profile-eyebrow{display:block;letter-spacing:1.7px;font-size:10px;color:#8a9fbe;margin-bottom:9px}.profile-title{font-size:21px;font-weight:700}.profile-public{font-size:11px;padding:5px 10px;color:#7591b7;background:#eff5ff;border-radius:16px}.profile-note{display:block;color:#8a9cb6;font-size:11px;line-height:1.9;margin:13px 0}.profile-grid{display:grid;grid-template-columns:190px minmax(0,1fr);gap:28px;margin-top:22px}.profile-label{display:block;font-size:13px;font-weight:600;margin:0 0 10px}.profile-select{display:flex;justify-content:space-between;border:1px solid #dce6f3;border-radius:10px;padding:12px;background:#f8faff;color:#557096;font-size:13px;margin-bottom:20px}.profile-textarea{width:100%;height:110px;padding:12px;box-sizing:border-box;border:1px solid #dce6f3;border-radius:10px;background:#f8faff;font-size:13px;line-height:1.8}.profile-counter{display:block;text-align:right;color:#a0adc1;font-size:11px;margin-top:8px}.profile-save{background:#2463f5;color:#fff;border:0;border-radius:10px;font-size:13px;line-height:42px;min-height:44px;margin-top:18px}.profile-refresh{background:#fff;color:#6e87aa;border:1px solid #dce6f3;border-radius:10px;font-size:12px;line-height:38px;min-height:40px;margin-top:20px}.profile-save:after,.profile-refresh:after{border:0}.profile-error,.profile-success{display:block;padding:12px 14px;border-radius:10px;margin:18px 0;font-size:12px;line-height:1.8}.profile-error{background:#fff0ed;color:#b42318}.profile-success{background:#edf9f2;color:#20764b}@media(max-width:650px){.profile-section{padding:22px 18px}.profile-grid{grid-template-columns:1fr;gap:20px}.profile-title{font-size:19px}}
</style>

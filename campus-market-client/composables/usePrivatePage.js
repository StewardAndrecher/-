import { ref,computed,watch } from 'vue'
import { onShow,onHide,onUnload } from '@dcloudio/uni-app'
import { currentUser,getToken } from '@/utils/session.js'
import { createOwnedResource } from '@/utils/owned-resource.mjs'
export function usePrivatePage({load,validate,clear=()=>{}}) {
 const state=ref({data:null,loading:false,saving:false,error:'',message:''})
 let active=false
 const manager=createOwnedResource({identity:()=>({userId:currentUser.value?.id,token:getToken()}),load,validate,notify:value=>state.value=value})
 watch(()=>[currentUser.value?.id,getToken()],()=>{manager.hide();clear();if(active&&currentUser.value)manager.show()},{flush:'sync'})
 onShow(()=>{active=true;clear();manager.show()})
 onHide(()=>{active=false;manager.hide();clear()})
 onUnload(()=>{active=false;manager.dispose();clear()})
 return {state,manager,currentUser,busy:computed(()=>state.value.loading||state.value.saving)}
}
export const ask = (title,content) => new Promise(resolve=>uni.showModal({title,content,success:r=>resolve(r.confirm===true),fail:()=>resolve(false)}))
export const login = () => uni.navigateTo({url:'/pages/user/login'})
export const home = () => uni.reLaunch({url:'/pages/index/index'})

export const openPage = url => uni.navigateTo({url})

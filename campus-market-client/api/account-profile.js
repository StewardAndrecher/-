import { request } from './request.js'
export const getAccountProfile = () => request({path:'/api/users/me/profile',auth:true})
export const updateAccountProfile = data => request({path:'/api/users/me/profile',method:'PUT',auth:true,data})
export const getRecoveryStatus = () => request({path:'/api/users/me/recovery',auth:true})
export const generateRecoveryCode = data => request({path:'/api/users/me/recovery',method:'POST',auth:true,data})
export const recoverAccount = data => request({path:'/api/users/recover',method:'POST',data})

import {request} from './request.js'
import {favoriteBatchIds} from '@/utils/favorite-batch.mjs'
export function removeFavoriteBatch(ids) {
 return request({path:'/api/favorites/remove-batch',method:'POST',auth:true,data:{productIds:favoriteBatchIds(ids)}})
}

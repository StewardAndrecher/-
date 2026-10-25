import {receivedReviewQuery} from '@/utils/received-reviews.mjs'
import {request} from './request.js'
export function getReceivedReviews(value={}) {
 const q=receivedReviewQuery(value)
 return request({path:`/api/orders/received-reviews?role=${q.role}&rating=${q.rating}&page=${q.page}&size=${q.size}`,auth:true})
}

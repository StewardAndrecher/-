import test from 'node:test'
import assert from 'node:assert/strict'
import {reviewSubmissionMessage} from '../utils/order-reviews.mjs'
import {notificationPath,validateReadNotification} from '../utils/notifications.mjs'
test('new review acknowledges scheduled reminder without claiming delivery',()=>{const text=reviewSubmissionMessage({replayed:false});assert.match(text,/评价已保存/);assert.match(text,/已安排/);assert.doesNotMatch(text,/已送达|已收到/)})
test('historical replay does not promise another reminder',()=>{assert.match(reviewSubmissionMessage({replayed:true}),/不会重复提醒/);for(const data of [null,{}, {replayed:'false'}])assert.throws(()=>reviewSubmissionMessage(data))})
const notice={id:7,category:'ORDER',target:'ORDER_REVIEWS',resourceId:12,title:'收到交易评价',body:'通用提醒',occurredAt:'2026-10-21T13:00',createdAt:'2026-10-21T13:00',readAt:null}
test('review reminder opens authenticated reviews using resource id',()=>{assert.equal(notificationPath(notice),'/pages/order/reviews?id=12');assert.equal(notificationPath({...notice,body:'https://evil.invalid/',title:'/pages/admin/orders'}),'/pages/order/reviews?id=12')})
test('changed resource in read receipt is rejected before navigation',()=>{assert.throws(()=>validateReadNotification({...notice,resourceId:13,readAt:'2026-10-21T14:00'},notice));validateReadNotification({...notice,readAt:'2026-10-21T14:00'},notice)})

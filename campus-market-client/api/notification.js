import { notificationQuery } from '@/utils/notifications.mjs'
import { request } from './request.js'
export const listNotifications = data => request({ path: '/api/notifications', auth: true, data: notificationQuery(data) })
export const getNotificationUnread = () => request({ path: '/api/notifications/unread-count', auth: true })
export const readNotification = id => request({ path: `/api/notifications/${id}/read`, method: 'POST', auth: true })
export const readNotificationBatch = ids => request({ path: '/api/notifications/read-batch', method: 'POST', auth: true, data: { ids } })

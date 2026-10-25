import { request } from './request.js'
export const createReport = (id, data) => request({ path: `/api/products/${id}/reports`, method: 'POST', auth: true, data })
export const listReports = (admin, data) => request({ path: admin ? '/api/admin/reports' : '/api/reports', auth: true, data })
export const getReport = (id, admin) => request({ path: admin ? `/api/admin/reports/${id}` : `/api/reports/${id}`, auth: true })
export const resolveReport = (id, data) => request({ path: `/api/admin/reports/${id}/resolve`, method: 'POST', auth: true, data })

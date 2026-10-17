import { request } from './request.js'
export async function submitPublication(submission) {
  const data = await request({ path: '/api/product-publications', method: 'POST', auth: true,
    data: { requestId: submission.requestId, product: submission.product } })
  if (!data || data.requestId !== submission.requestId || !Number.isSafeInteger(data.productId) ||
      data.productId <= 0 || typeof data.replayed !== 'boolean') {
    throw new Error('发布回执格式异常，请使用原内容再次确认')
  }
  return data
}

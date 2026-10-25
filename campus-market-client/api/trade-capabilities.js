import { request } from './request.js'
import { validateTradeCapabilities } from '@/utils/trade-capabilities.mjs'
export async function getTradeCapabilities() {
  return validateTradeCapabilities(await request({ path: '/api/trade-capabilities' }))
}

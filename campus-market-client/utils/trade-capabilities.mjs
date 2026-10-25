export function validateTradeCapabilities(value) {
  if (!value || !['SIMULATED', 'UNAVAILABLE'].includes(value.paymentMode) ||
      typeof value.orderCreationEnabled !== 'boolean' || typeof value.simulatedPaymentEnabled !== 'boolean' ||
      typeof value.message !== 'string' || !value.message.trim() || value.message.length > 200) throw new Error('交易能力响应格式不正确')
  const simulation = value.paymentMode === 'SIMULATED'
  if (value.orderCreationEnabled !== simulation || value.simulatedPaymentEnabled !== simulation) throw new Error('交易能力状态不一致')
  return value
}

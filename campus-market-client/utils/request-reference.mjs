// 编号只能来自服务端响应头；不从错误正文或本地随机值推测。
const ID = /^[0-9a-f]{32}$/
export function responseRequestId(headers) {
  if (!headers || typeof headers !== 'object' || Array.isArray(headers)) return ''
  const values = Object.entries(headers)
    .filter(([key]) => key.toLowerCase() === 'x-request-id')
    .map(([, value]) => value)
  return values.length === 1 && typeof values[0] === 'string' && ID.test(values[0]) ? values[0] : ''
}
export function withRequestReference(message, statusCode, requestId) {
  const id = responseRequestId({ 'X-Request-Id': requestId })
  return Number.isInteger(statusCode) && statusCode >= 500 && statusCode <= 599 && id
    ? `${message}（请求编号：${id}）` : message
}

export function responseRetryAfter(headers) {
 if(!headers||typeof headers!=='object'||Array.isArray(headers))return 0
 const names=Object.keys(headers).filter(k=>k.toLowerCase()==='retry-after')
 if(names.length!==1)return 0
 const value=headers[names[0]],raw=typeof value==='number'?String(value):value
 if(typeof raw!=='string'||! /^[1-9][0-9]{0,4}$/.test(raw)||Number(raw)>86400)return 0
 return Number(raw)
}

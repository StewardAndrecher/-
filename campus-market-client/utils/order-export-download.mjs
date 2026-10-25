import {orderExportCsv,orderExportFilename} from './order-export.mjs'
export function downloadOrderCsv(data,query,host=globalThis) {
 const csv=orderExportCsv(data,query),filename=orderExportFilename(query)
 if(!host.document?.createElement||!host.document.body||!host.Blob||!host.URL?.createObjectURL||!host.URL?.revokeObjectURL||!host.setTimeout)throw new Error('当前环境不支持文件下载，请复制CSV内容')
 const blob=new host.Blob([csv],{type:'text/csv;charset=utf-8'}),url=host.URL.createObjectURL(blob)
 let link
 try {
  link=host.document.createElement('a');link.href=url;link.download=filename
  host.document.body.appendChild(link);link.click()
 } finally {
  link?.remove();host.setTimeout(()=>host.URL.revokeObjectURL(url),1000)
 }
 return filename
}

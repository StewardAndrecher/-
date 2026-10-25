import {validOrderTime} from './order.js'
const counters=['total','open','resolved','answered','dismissed','openOver24h','openOver72h']
export function issueTimeKey(value) {
 if(!validOrderTime(value))throw new Error('工单采样时间无效')
 const [date,time]=value.split('T'),[h,m,seconds='00']=time.split(':'),[s,f='']=seconds.split('.')
 return `${date}T${h}:${m}:${s}.${f.padEnd(9,'0')}`
}
export function issueAgeCutoff(sampled,hours) {
 if(![24,72].includes(hours))throw new Error('工单等待时长无效')
 const normalized=issueTimeKey(sampled),[base,fraction]=normalized.split('.')
 return new Date(Date.parse(base+'Z')-hours*3600000).toISOString().slice(0,19)+'.'+fraction
}
export function validateIssueOverview(data) {
 if(!data||Object.keys(data).sort().join(',')!==['sampledAt',...counters,'oldestOpenAt'].sort().join(',')
   ||!counters.every(key=>Number.isSafeInteger(data[key])&&data[key]>=0)
   ||data.open+data.resolved!==data.total||data.answered+data.dismissed!==data.resolved
   ||data.openOver72h>data.openOver24h||data.openOver24h>data.open)throw new Error('反馈处理概览响应无效')
 const sample=issueTimeKey(data.sampledAt)
 if(data.open===0){if(data.oldestOpenAt!==null)throw new Error('空待处理清单时间无效')}
 else {
  const oldest=issueTimeKey(data.oldestOpenAt)
  if(oldest>sample||(oldest<=issueAgeCutoff(data.sampledAt,24))!==(data.openOver24h>0)
     ||(oldest<=issueAgeCutoff(data.sampledAt,72))!==(data.openOver72h>0))throw new Error('待处理时间与积压统计不一致')
 }
 return data
}

<template>
 <view class="page">
  <text class="title">个人订单导出</text>
  <text>当前身份：{{ query.role==='SELLER'?'卖家':'买家' }}；状态：{{ statusLabel }}；订单编号：{{ query.orderId||'全部' }}</text>
  <text>导出当前条件全部匹配记录，最多200笔，不限于列表当前页。超出时请返回缩小范围。</text>
  <text>仅包含订单编号、商品标题快照、金额、状态和下单时间；模拟交易记录不代表真实收支凭证。</text>
  <button v-if="!currentUser" @click="login">前往登录</button>
  <button type="primary" :disabled="busy||!!routeError||!currentUser" @click="generate">{{ busy?'处理中…':'生成导出预览' }}</button>
  <text v-if="routeError||error" class="error">{{ routeError||error }}</text>
  <view v-if="snapshot" class="card">
   <text>已生成{{ snapshot.total }}笔订单的导出预览，订单状态可能随后变化。</text>
   <text v-if="!snapshot.total">当前条件下暂无订单，可复制或下载只有表头的CSV。</text>
   <view v-for="item in snapshot.records.slice(0,10)" :key="item.id" class="row">
    <text>订单{{ item.id }} · {{ orderStatus(item.status) }}</text>
    <text>{{ item.productTitleSnapshot }} · ¥{{ formatOrderMoney(item.priceInCentsSnapshot) }}</text>
   </view>
   <text v-if="snapshot.total>10">预览前10笔；CSV包含本次全部{{ snapshot.total }}笔。</text>
   <text>CSV给编号和商品标题加可见文本前缀，避免长编号失真和标题被当作表格公式；双引号及换行会转义。</text>
   <button :disabled="busy" @click="copy">复制CSV内容</button>
   <!-- #ifdef H5 -->
   <button :disabled="busy" @click="download">下载CSV文件</button>
   <!-- #endif -->
   <text>复制或下载后，文件由你自行保管；退出账号不会删除已保存的副本。</text>
  </view>
  <text v-if="message">{{ message }}</text>
  <button :disabled="busy" @click="back">返回订单列表</button>
 </view>
</template>
<script setup>
import {ref,computed,watch} from 'vue'
import {onLoad,onShow,onHide,onUnload} from '@dcloudio/uni-app'
import {getOrderExport} from '@/api/order.js'
import {orderExportQuery,orderExportCsv,readOrderExportRoute} from '@/utils/order-export.mjs'
import {orderSearchListPath} from '@/utils/order-search.mjs'
import {orderStatus,orderFilters,formatOrderMoney} from '@/utils/order.js'
import {currentUser,getToken} from '@/utils/session.js'
// #ifdef H5
import {downloadOrderCsv} from '@/utils/order-export-download.mjs'
// #endif
const query=ref({role:'BUYER',status:'ALL',orderId:'',page:1}),snapshot=ref(null),busy=ref(false),error=ref(''),message=ref(''),routeError=ref('')
const statusLabel=computed(()=>orderFilters.find(x=>x.value===query.value.status)?.label||'条件无效')
let active=false,closed=false,generation=0
function reset(){generation++;snapshot.value=null;busy.value=false;error.value='';message.value=''}
function alive(n,token,user){return active&&!closed&&n===generation&&token===getToken()&&user===currentUser.value?.id}
async function generate(){
 if(busy.value||routeError.value||!active||closed)return
 reset();const token=getToken(),user=currentUser.value?.id,n=generation
 if(!token||!user){error.value='请先登录后生成订单导出';return}
 busy.value=true
 try {const result=await getOrderExport(orderExportQuery(query.value));if(alive(n,token,user))snapshot.value=result}
 catch(e){if(active&&!closed&&n===generation)error.value=e.message||'生成订单导出失败'}
 finally{if(n===generation)busy.value=false}
}
function ready(){return snapshot.value&&!busy.value&&!routeError.value&&active&&!closed&&!!getToken()&&!!currentUser.value}
function copy(){
 if(!ready())return
 const n=generation,token=getToken(),user=currentUser.value.id,data=orderExportCsv(snapshot.value,query.value);busy.value=true;message.value=''
 uni.setClipboardData({data,success:()=>{if(alive(n,token,user))message.value='CSV已复制，请妥善保管'},fail:()=>{if(alive(n,token,user))error.value='复制失败，请重试'},complete:()=>{if(n===generation)busy.value=false}})
}
function download(){
 // #ifdef H5
 if(!ready())return
 try{const filename=downloadOrderCsv(snapshot.value,query.value);message.value='已发起下载：'+filename+'；请在浏览器下载记录或下载目录确认'}catch(e){error.value=e.message||'下载失败'}
 // #endif
}
function login(){if(!busy.value)uni.navigateTo({url:'/pages/user/login'})}
function back(){if(busy.value||!active||closed)return;const pages=getCurrentPages();if(pages.length>1&&pages[pages.length-2].route==='pages/order/list'){uni.navigateBack();return}uni.redirectTo({url:orderSearchListPath(query.value)})}
watch(()=>[currentUser.value?.id,getToken()],(next,before)=>{reset();if(before?.[0]&&next[0]!==before[0]){query.value={role:'BUYER',status:'ALL',orderId:'',page:1}}},{flush:'sync'})
onLoad(options=>{try{query.value=readOrderExportRoute(options)}catch(e){routeError.value=e.message}})
onShow(()=>{active=true;reset()})
onHide(()=>{active=false;reset()})
onUnload(()=>{closed=true;active=false;reset()})
</script>
<style scoped>.page{padding:30rpx}.page>text,.card>text{display:block;margin:18rpx 0}.title{font-size:36rpx;font-weight:bold}.error{color:#d93025}.card{background:#fff;padding:24rpx;margin-top:24rpx;border-radius:16rpx}.row{padding:16rpx 0;border-bottom:1px solid #eee}.row text{display:block}button{margin-top:20rpx}</style>

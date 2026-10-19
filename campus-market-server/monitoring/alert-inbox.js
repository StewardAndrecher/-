"use strict";
const byId = id => document.getElementById(id);
const node = (tag, value, cls) => { const e = document.createElement(tag); e.textContent = value; if(cls) e.className = cls; return e; };
const time = value => new Date(value).toLocaleString("zh-CN", {hour12:false});
async function refresh() {
 try {
  const response = await fetch("/api/notifications", {cache:"no-store"});
  if(!response.ok) throw new Error("读取失败");
  const data = await response.json();
  byId("total").textContent = data.total;
  byId("latest").textContent = data.items.length ? (data.items[0].status === "resolved" ? "已恢复" : "故障告警") : "暂无通知";
  byId("connection").textContent = "正常";
  byId("updated").textContent = "更新于 " + new Date().toLocaleTimeString("zh-CN");
  byId("empty").hidden = data.items.length > 0;
  byId("empty").textContent = "暂未收到通知。故障达到告警规则的持续时间后，通知会自动出现在这里。";
  byId("list").replaceChildren();
  for(const item of data.items) {
   const card=node("article","","notification "+item.status), meta=node("div","","meta");
   meta.append(node("span",item.status === "resolved" ? "恢复通知" : "故障告警","badge"), node("span",item.groupLabels.component || "application"), node("span",time(item.receivedAt),"time"));
   card.append(meta, node("h3",`${item.alerts.length} 条告警 · ${item.groupLabels.instance || "本机服务"}`));
   for(const alert of item.alerts) {
    const detail=node("div","","detail");
    detail.append(node("p",alert.annotations.summary || alert.labels.alertname),node("div",`${alert.labels.alertname} · ${alert.labels.severity} · ${alert.status}`,"rule"));
    if(alert.annotations.description) detail.append(node("p",alert.annotations.description));
    card.append(detail);
   }
   if(item.truncatedAlerts) card.append(node("p",`另有 ${item.truncatedAlerts} 条被上游截断，请到 Alertmanager 查看。`));
   byId("list").append(card);
  }
 } catch(error) {byId("connection").textContent="读取失败";byId("updated").textContent="暂时无法更新，保留上次结果";}
}
refresh(); setInterval(refresh,5000);

# 第71课：交付与收货页面

订单详情新增交付、收货时间；PAID卖家显示“确认已交付”，DELIVERED买家显示“确认收货”。两个动作均二次确认，共用busy状态与身份/页面生命周期保护。响应未知清除旧详情并提示刷新，不自动确认、不重复下单。

接口：POST /api/orders/{id}/deliver、POST /api/orders/{id}/complete，无身份/价格/状态请求体。交付响应接受DELIVERED或COMPLETED（迟到重放），收货只接受COMPLETED；付款重放允许PAID/DELIVERED/COMPLETED。通过shared request带会话并校验编号、状态、replayed类型与六状态DTO。

页面和列表展示“已交付，待收货（模拟交易）”“交易完成（模拟交易）”；付款仍为教学模拟，没有真实资金结算。收货时间可以超过15分钟付款期限；不可在卖家交付前确认收货。全部最终代码和验收见第71课报告。

# 第125课：本人收藏的标题搜索

无需新增依赖、表、索引或迁移。在既有 GET /api/favorites 增加可选 keyword；仍由 Bearer 会话确定用户，userId/sellerId不能更换查询对象。响应继续为 records/total/page/size，成功 no-store。

## 参数与查询语义

- 缺省、空文本或去除首尾空白后为空：不限制标题，保留原接口行为，包括 product:null 的失效收藏。
- 非空：最长50个UTF-16代码单元（与既有商品搜索一致），禁止控制字符；Java String.strip 去除首尾空白。客户端也先校验，后端独立校验。
- 在可见的当前在售商品标题上做包含查询，大小写等比较规则遵循现有MySQL列的排序规则，不做前端二次过滤。
- SQL参数绑定用户ID及关键词。LIKE指定 ESCAPE '!'，先把 ! 转为 !!，再把 %/_ 转为 !%/!_；引号、反斜杠等也是普通绑定文本，不拼接到SQL。
- keyword与availability取交集。非空关键词不搜索失效商品的隐藏标题；UNAVAILABLE与非空关键词组合恒为空，不通过total泄露隐藏标题的匹配信息。
- COUNT与列表使用相同WHERE条件，先筛选再LIMIT/OFFSET；同一只读REPEATABLE_READ事务内一致。不同HTTP请求之间不是冻结快照。
- 保留收藏时间及商品ID倒序。越界页返回空records和真实筛选total，客户端最多一次回退到最后页。
- 校验绑定成功后先鉴权：匿名/失效401，非法keyword400。数据库异常仍500，不伪造成功零记录。

## 兼容与边界

旧Service/Repository重载保留，委托空关键词；收藏增删、批量取消、旧收藏导入范围不扩大。查询不改变关系、商品、账号权限、通知或MQ。

本课没有新增索引。包含式LIKE不承诺生产规模性能，后续应根据真实规模和查询计划评估搜索方案。

## 测试

FavoriteSearchIntegrationTest仅在私有临时MySQL启动：过滤先于分页、总数、可用性交集、隐藏标题不泄露、字面%/_/!/反斜杠/引号、注入文本、长度/控制字符、身份/失效会话、只读记录、状态变化、快照及数据库错误。ApiContractIntegrationTest同步验证可选参数和文档。

# DataAsk PC 智能问数 · Quick BI 体验参考版

此分支的 PC 页面参考了阿里云 Quick BI **小Q问数**的公开使用流程，但未复制其图片、图标、品牌或代码。重点是清楚标示可查询数据、快捷提问、多轮结果、图表切换及可信的数据来源。

参考资料：阿里云文档《发起问数》
https://help.aliyun.com/zh/quick-bi/user-guide/user-guide-for-smart-q-a

## 用户路径

1. 登录 DataAsk PC（视口宽度 >= 1024px）：主区展示简明欢迎语、管理员已开放的数据范围和四条**通用问法示例**。
2. 在首页数据范围卡片或底部输入区点击 **查看可用数据/选择数据**，系统只返回 `da_allowed_tables.enabled=1` 的 ADS 表。点击**查看字段**时，才向 StarRocks 查询该授权表的真实字段和注释，不复制或展示业务明细行。
3. 选择“全部已授权数据”则使用原有问数范围；选择特定 ADS 表时，本次 `POST /api/chat/ask` 请求携带 `dataset`，后端再次校验并**严格限制 SQL 可访问的表**。没有得到授权的表返回 403，不会靠前端隐藏来保护。
4. 发送问题：底部固定输入区、历史记录、查询中停止按钮均继续保留。回答以独立卡片出现，明确显示本次选择的数据范围、指标/时间、真实结果与图表。
5. 柱状图/折线图/饼图只是在**已返回数据**的基础上切换展示方式；饼图仅在非负且存在正数的度量场景提供，不生成额外或推断出的业务数据。表格显示同一次查询真实返回的数据。
6. 用户可继续追问、复制结果、收藏或共享。收藏图表重新打开时会尽量恢复原数据集；若数据集已被管理员取消授权，则提示并拒绝代换成其他表。
7. 模型、数据源、授权表和指标配置仅管理员可见且仅管理员可通过 `/api/admin/**` 接口修改。用户可**选择已授权的查询数据**，不可编辑 StarRocks 连接或授权规则。

## 主要变更

- `web/src/App.vue`：PC 场景与数据集选择、回答呈现、图表切换。
- `web/src/quickbi-pc.css`：仅 PC 的专业 BI 分析界面样式。
- `web/src/main.js`：加载 PC 专属样式。
- `web/src/components/AnswerChart.vue`：可选图表类型覆盖，兼容原移动端使用方式。
- `src/main/java/com/dataask/chat/ChatController.java`：授权 ADS 数据集列表、字段预览、可选 `dataset` 的问数请求。
- `src/main/java/com/dataask/mini/MiniappController.java`：保存图表从原始消息恢复数据范围的只读信息。
- `src/test/java/com/dataask/chat/AuthorizedDatasetsTest.java`：字段预览与未授权数据集限制测试。

## 新增安全 API

| Method | URL | 约束 |
|---|---|---|
| GET | `/api/chat/datasets` | 已登录用户，仅返回管理员批准的 ADS 表 |
| GET | `/api/chat/datasets/preview?name=ads.table` | 已登录用户且表已授权，仅返回前 40 个字段元数据 |
| POST | `/api/chat/ask` | 原有请求字段不变，增加可选 `dataset`；非空时必须属于授权表 |

**兼容性：** 老客户端无需提交 `dataset`，仍使用已授权的全部 ADS 范围。独立移动端 `web/public/miniapp/index.html` 未修改；原小程序不会显示 PC 新选择器。

## 未实现的功能

Quick BI 的自动问题澄清、预测走势、波动归因、完整查询过程展示、数据文件上传、真实语音问答和 per-user 行级权限不属于本轮实现，不能仅凭 UI 声称已经支持。DataAsk 当前 ADS 表授权是系统级；若需分用户细粒度授权和 SQL 行级限制，必须额外设计后端权限规则。

## 本地自行验证

```bash
git switch dev20261109-miniapp-v2
git pull origin dev20261109-miniapp-v2
cd web
npm install
npm run build
```

启动后验证：

- 管理员先开放 ADS 表，普通用户只能读取已开放数据、不能修改模型或数据源。
- 更换数据集后问数必须限定到所选表；尝试传入未开放的表，应返回 403。
- 查看字段只显示选中表的真实字段注释。
- 切换柱状/折线/饼图/表格，结果数字应当一致，不应生成示例数据。
- 打开历史会话及收藏图表时，原问数范围应得到恢复。
- 访问 `/miniapp/index.html`，应与之前的移动原型保持一致。

当前尚需在实际 MySQL 8、StarRocks 和模型环境下完成端到端业务验收。本次不手动制作或部署发布包。

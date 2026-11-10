# DataAsk V9：独立图表工作室与统一移动问数

## 一、为什么此前“我的图表”打开后会进入对话

旧流程收藏的是问数元信息（问题/标题/类型），点击收藏后前端调用 `/api/chat/ask`，切换到新的聊天页。导致图表不是独立资产，而且每次查看都会新增会话。

V9 改为：

1. 收藏时标题从**原问题简化生成**。示例：「请帮我查看昨天各城市订单量排行？」→「昨日各城市订单量排行」。已有收藏的显示标题也按原始问题重新整理，不修改原问题本身。
2. PC 的「我的图表」和小程序「图表」页点击项目后，打开**图表详情**，不进入聊天。
3. `GET /api/mini/charts/{id}/view`：首先校验图表所有者/共享 ACL，然后**重新校验当前 ADS 表白名单及 SQL 只读规则**，才展示原始保存结果。
4. `POST /api/mini/charts/{id}/refresh`：重新按原问题调用后端问数生成/执行链路和实时 StarRocks 数据，但不创建 `da_sessions` 或 `da_messages` 记录；既能更新「昨天」等相对日期，又避免刷新造成历史会话膨胀。
5. 详情显示保存时结果，刷新后切换为实时结果；刷新失败不覆盖有效快照，界面提示原因。无权访问的图表不能因旧快照而绕过权限。
6. PC 和小程序都支持图表/表格切换、收藏、共享与权限控制；小程序不提供文件导出和下载。

**注意**：已保存的历史图表首次打开会读取原始问数消息，需要它仍然存在、SQL 仍获授权；源表授权撤销、历史消息删除或安全验证失败时会拒绝展示。实时刷新依赖服务器已配置的模型、StarRocks、指标定义。

## 二、PC 系统管理界面

采用结构化的配置工作区：上方蓝白标题、状态，左侧标准化表单/保存/测试，右侧有序的接入步骤、权限与安全提示。覆盖 StarRocks、对话模型和 Embedding。

设计样式：`web/src/visualization-admin-v9.css`。不会修改移动端的管理界面，因为小程序不提供管理入口。

## 三、移动端统一能力

独立小程序 `web/public/miniapp/index.html`：
- 删除模型/数据源配置说明入口与语音按钮（包括语音弹窗事件）。
- 使用服务端已经配置的相同问数模型及授权 ADS 数据接口，不重复输入密钥或数据库信息。
- 与 PC 共享问数返回 JSON、图表/表格结果、历史消息、收藏与共享。
- 「我的图表」直接打开独立详情，读取保存结果并自动刷新，不进入对话，不会增加会话。
- 图表使用高清 SVG，自适应手机宽度，长排行/表格在固定容器内滚动；没有下载按钮或导出事件。

## 四、修改文件

- `src/main/java/com/dataask/chat/ChatController.java`：安全的非持久化图表刷新。
- `src/main/java/com/dataask/mini/MiniappController.java`：ACL 保护的图表详情、授权快照和实时刷新接口。
- `src/main/java/com/dataask/mini/ChartTitleFormatter.java`：自动简化标题。
- `web/src/App.vue`：PC 及响应式 Vue 的独立图表详情、管理表单结构。
- `web/src/visualization-admin-v9.css`：大厂风格管理配置和图表详情布局。
- `web/public/miniapp/index.html`：移动端图表详情、自动刷新及语音/模型入口移除。
- `web/scripts/chart-ui-smoke.mjs`：真实 Chromium 的 PC+小程序图表详情交互测试。
- `src/test/java/com/dataask/mini/MiniappControllerTest.java`：图表权限、标题、快照、刷新测试。
- `src/test/java/com/dataask/chat/ChatClarificationFlowTest.java`：刷新不写入会话的回归测试。

## 五、验收

测试应检查：
- 收藏一次后「我的图表」显示简化标题，点击进入图表详情，不跳转聊天。
- 保存时图表立即展示，随后实时刷新，刷新后表格中的数字变化，历史会话数量不增加。
- 取消某张 ADS 表授权后，旧图表不能通过缓存泄露已经失去授权的数据。
- 图表所有者/管理员才能管理分享，其他用户只可访问拥有共享权限的图表。
- 小程序没有模型配置、语音输入、下载按钮，长图表可滚动。
- 1920×1080、1366×768 的 PC 管理表单清晰可读，右侧有接入说明，原测试/保存接口仍可使用。

浏览器自动化测试使用模拟 HTTP 返回检验交互链路，不能代替生产环境的 MySQL、StarRocks 和模型连通性测试。

## 六、拉取和打包

```bash
git switch dev20261109-miniapp-v2
git pull origin dev20261109-miniapp-v2
bash package.sh
```

发布后访问 `/build-info.json`：`uiLayout` 应为 `desktop-chart-studio-v9`，`buildSha` 应与分支提交一致。本轮无数据库破坏性迁移，不删除收藏、账号或会话。

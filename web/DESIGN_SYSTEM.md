# DataAsk 企业控制台界面规范

参考腾讯 TDesign 的企业级后台信息层级与控制方式（仅借鉴视觉和交互规范，仍使用 Vue 3 + Vant，不引入新 UI 框架）。

## PC 统一规格

| 项目 | 约定 |
| --- | --- |
| 主色 | #0052D9（TDesign 风格蓝） |
| 默认按钮、输入框、下拉框 | 高度 32px，字号 13px，圆角 4px |
| 操作层次 | 主按钮：蓝底白字；次按钮：白底灰边；危险操作：红字；列表行：文字链接 |
| 搜索区域 | 左侧筛选项、查询与重置；右侧新增 / 刷新 / 批量操作，控件间距 8–10px |
| 列表 | 40px 表头、44px 行高、浅灰分隔线、鼠标悬停淡底色 |
| 分页 | 默认每页 10 条，提供 10/20/50/100 条、上一页/下一页、当前页和总条数 |
| 返回按钮 | 左箭头 + “返回…”文字，使用同一二级按钮尺寸（32px） |
| 弹窗 | 标题与表单明确分层，底部操作右对齐，取消为次按钮、保存为主按钮 |
| 焦点 | 清晰的 2px 轮廓和输入框焦点反馈，支持键盘操作 |

## 影响范围

- `src/tdesign-console.css`：统一 tokens、按钮、筛选、列表、分页、弹窗、暗色主题；桌面样式限定 1024px 以上
- `src/components/RecordList.vue`：查询/重置/刷新及额外筛选插槽分区
- `src/components/ListPagination.vue`：统一 10 条默认分页与文案
- `src/components/AdminAuditLog.vue`：审计列表与分页对齐
- `src/App.vue`：我的图表筛选由点击查询后应用、统一详情页及系统管理返回按钮
- `public/miniapp/index.html`：H5 列表、返回、按钮和弹窗的扁平化样式，不变更业务逻辑
- 数据源、用户维护弹窗只统一样式，不修改数据接口

## 回归

在已有项目依赖与浏览器环境中执行：

```bash
cd web
npm ci
npm run build
npm run preview -- --host 127.0.0.1 --port 4175
# 在另一个终端执行，管理界面测试默认使用 4175
npm run test:management
# 其余脚本默认使用 4173，可以通过 DATAASK_UI_URL 设置 URL
DATAASK_UI_URL=http://127.0.0.1:4175 npm run test:layout
DATAASK_UI_URL=http://127.0.0.1:4175 npm run test:audit
DATAASK_UI_URL=http://127.0.0.1:4175 npm run test:pc
```

注意：默认端口分别由各自脚本定义，避免把 `npm run test:management` 运行在 4173 上导致连接失败。

## 模型配置统一管理

系统管理现在只保留一个「模型配置」入口，同时显示对话模型（Base URL、模型 ID、API Key）
与 Embedding（启用、Base URL、模型 ID、向量维度、API Key）。两组模型分别测试与保存，
Embedding 的语义索引在本页查看与重建。

配置继续写入原有的 `/api/admin/settings/ai` 与 `/api/admin/settings/embedding`，
**不迁移数据库配置、不重置已保存密钥**。每次修改 API Key 时留空可保留原密钥，明确
选择“清除原有 API Key”后必须确认。Embedding 不启用时不会阻止对话模型正常配置。
手机端采用同一设置组件的窄屏布局。

回归验证：`npm run test:admin`，覆盖模型页面合并、密钥清除保护、两类模型各自测试和
保存，以及语义索引重建。

### 模型配置折叠与铺满布局

- 模型页独占主内容区，不再套用其它管理页的居中卡片宽度、外边距或背景间隙。
- 「对话模型」与「Embedding 模型」默认收起，只显示标题、用途与配置状态。
- 点击标题行展开，点击再次收起；两个区域的展开状态互不影响，展开/收起不会保存配置或改变 Embedding 的启用状态。
- 每个折叠区域保留原有测试连接、保存、API Key 安全确认、语义索引重建操作。
- PC 管理页端到端回归脚本检查默认收起、独立展开、无外层间隙及配置接口。

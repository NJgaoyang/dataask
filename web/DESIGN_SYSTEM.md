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

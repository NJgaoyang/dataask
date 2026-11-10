# DataAsk 系统管理 — PC 功能实装与验收

目标：系统管理不再是仅可看的管理功能卡片，而是每张卡片进入独立的、可加载/可保存/可报错/可重试的右侧模块页面。PC 左侧只保留「系统管理」一级导航。移动端小程序 HTML 和普通智能问数页面不改。

## 功能清单（管理员专用）

| 模块 | 实际操作 | 后端接口 |
| --- | --- | --- |
| 数据源配置 | 读取加密配置、编辑 StarRocks FE JDBC/只读账号/ADS 数据库、测试连接、保存并刷新表元数据 | GET /api/admin/settings；POST /api/admin/settings/starrocks/test；POST /api/admin/settings/starrocks |
| 对话模型 | 编辑 Base URL、模型名、API Key；测试 OpenAI-compatible Chat Completions；保存生效 | POST /api/admin/settings/ai/test；POST /api/admin/settings/ai |
| Embedding 模型 | 启用/关闭、Base URL/模型/维度/API Key、测试服务、保存、重建语义索引 | POST /api/admin/settings/embedding/test；POST /api/admin/settings/embedding；POST /api/admin/semantic/rebuild |
| ADS 表授权 | 从 StarRocks 读取真实表与列名、展开字段、逐表启用/取消授权、刷新列表 | GET /api/admin/tables；POST /api/admin/table-allow |
| 指标口径 | 下载 Excel 模板、上传解析/预览、确认导入、查看批次、按批次删除 | GET /api/admin/metrics/template；POST /api/admin/metrics/preview；POST /api/admin/metrics/import；DELETE /api/admin/metrics/batches/{id} |
| 用户管理 | 查看/创建账号、编辑显示名称及角色、管理员重置密码；防止自降权和移除最后管理员 | GET/POST /api/admin/users；PUT /api/admin/users/{username}；POST /api/admin/users/{username}/reset-password |
| 配置状态 | 查看数据源/对话模型/Embedding 是否配置完整、手动刷新，并进入详细配置页 | GET /api/admin/status；GET /api/admin/settings |

表单、权限和成功反馈使用真实 REST 调用。显示「已配置」只表示必要配置完整，不等于实际联通；具体连通性由对应的「测试连接」验证。

## 关键修复

之前部分配置卡片点击时先加载配置，若接口失败就不跳页面，让人误认为不能点击。现在先切换到右侧页面，再独立加载；加载失败在页面顶部显示错误和「重试加载」，不会停留在原卡片。

- `web/src/App.vue`：每个模块独立路由状态，加载状态/错误/重试，表授权刷新，用户编辑和重置密码。
- `web/src/admin-functional-pc.css`：完整配置表单、状态列表、用户表格与操作表单的正式 PC 样式。
- `src/main/java/com/dataask/admin/AdminController.java`：用户编辑及密码重置 REST API，保持管理员安全限制。
- `src/test/java/com/dataask/admin/AdminUserManagementTest.java`：角色变更、最后管理员、自降权、密码策略测试。
- `web/scripts/admin-ui-smoke.mjs`：真实 Chromium 运行前端，对所有 7 个管理模块实际点击、模拟 REST 返回并检查 API 请求。
- `.github/workflows/build.yml`：加入 Chromium 测试与 UI 截图，之后才运行 Java 验证与打包。

## 生产部署前验收

1. 使用管理员账号登录，进入系统管理。7 个功能卡片都能切到右侧独立页面；切回后仍可再次打开，加载失败出现重试按钮。
2. 数据源中填写真实 StarRocks **只读**账号，点击「测试连接」后再保存；只有能连通并有 ADS 元数据，才开放数据表。
3. 模型配置填入阿里云百炼实际 Base URL、Model、API Key，测试后保存；Embedding 模型还需根据实际模型维度重建索引。
4. ADS 授权开关应在 MySQL 元数据中生效；对已授权的 ADS 表，跨表 JOIN 无需再配置人工规则；仍需关注聚合粒度和指标口径。
5. 上传真实 Excel 指标口径，先预览再导入，并在列表里核对与删除测试批次。
6. 创建测试普通用户，验证其完全没有系统管理入口且直接调用 `/api/admin/**` 返回 403。管理员编辑该用户角色/重置密码；旧 JWT 应失效并要求重新登录。
7. 实机观察 1366×768、1920×1080 PC；确认数据字段、按钮和下拉菜单没有被截断。

**自动化浏览器测试使用模拟的后端 HTTP 数据**，证明页面能够交互并发送正确请求；不能代替你服务器的 MySQL、StarRocks、Qwen 密钥连通性与真实业务联调。自动化 Java 单元测试验证新用户管理 API 的逻辑与角色限制。

## 本地打包

```bash
git switch dev20261109-miniapp-v2
git pull origin dev20261109-miniapp-v2
bash package.sh
```

产物 `release/dataask-0.1.0.tar.gz`。访问 `/build-info.json`，确认 `uiLayout` 为 `desktop-chart-studio-v9`，且 `buildSha` 与当前分支一致。原数据、账户和历史会话保留，不需要删除 MySQL 表或重建数据库。


## 2026-11-10 — 系统管理打开时报 `密码数据无效` 的修复

症状：`GET /api/admin/settings` 或 `GET /api/admin/status` 返回 500，后端日志经过
`RuntimeConfigService.adminView -> ai() -> decrypt()`，内部提示 `密码数据无效`。

原因与修复：

- 旧代码在 AES-GCM 密文长度不足 **29** 字节时报错，但一个**合法的加密空字符串**长度恰为 **28** 字节（12B IV + 16B Tag）。之前执行“清除原有 API Key”会写入这样的密文，随后无法重新读取。
- 已将长度校验修正为 **不足 28 字节**才拒绝。此修复无需手动更新或删除已有 MySQL 配置。
- `/api/admin/status` 和 `/api/admin/settings` 改为逐个检查保存的凭证，即使只有一项真正损坏，也不会阻断其他系统管理入口。接口返回 `*CredentialUnreadable` 布尔诊断字段而非密钥原文。
- 管理员可以在对应模块重新填写新的数据库密码或 API Key 覆盖不可解密的凭证；**显式填写新凭证不会再尝试先解密旧凭证**。
- 新增了针对 28 字节空密文、正常密文、损坏密文、加密密钥轮换、显式重填凭证的 Java 回归测试。

安全检查：不要向聊天、工单或日志复制 `da_app_settings.setting_value`、`DATAASK_JWT_SECRET` 或 API Key。若要核对历史存储是否为 28 字节的空密文，可以在 MySQL **只查询长度**（不回显敏感值）：

```sql
SELECT setting_key,
       CASE
         WHEN setting_value LIKE 'v1:%'
           THEN LENGTH(FROM_BASE64(SUBSTRING(setting_value, 4)))
         ELSE NULL
       END AS encrypted_bytes
FROM da_app_settings
WHERE setting_key IN ('ai.apiKey','embedding.apiKey','sr.password');
```

若 `encrypted_bytes=28`，符合本次空密文边界错误。若密文并非 28 字节仍无法解密，需检查**发布前后是否更换过 `DATAASK_JWT_SECRET`**，或凭证是否被损坏；优先恢复原密钥（从安全备份）或通过新页面重新填写实际的密码/API Key。不要直接删除 `da_app_settings` 表、用户或历史会话。

修复代码：`RuntimeConfigService.java`、`RuntimeConfigCryptoTest.java`、PC 管理页面凭证错误提示。修复已在 GitHub CI 执行测试，具体真实环境的密钥与数据库状态仍需部署后验证。

## 2026-11-10 — 自动跨表关联、对话模型测试修复

- 移除「表关联规则」管理页面和接口，系统管理首页现在保留七个实际功能模块。
- 智能问数不再调用 `JoinRuleService` / `JoinSafetyGuard`，也不要求提前批准关联键或映射。只要涉及的 ADS 表均已开放，就允许单条只读 SELECT 中跨表 JOIN。
- `SqlGuard` 仍然检查单条只读 SQL 与引用表白名单，不允许访问非授权表，不会放开写入操作。
- 从新部署版本的 `schema.sql` 移除旧手动关联规则表创建语句；已有表与记录不会自动删除。
- 对话模型测试界面：开启「清除原有 API Key」时不再发送可能产生误导性 HTTP 401 的测试请求；清除操作保存前需要二次确认。实际接口返回 HTTP 401 时给出明确的认证错误说明。
- PC 系统管理子页面的「返回系统管理」按钮改为显眼的浅蓝按钮，高度至少 43px。

`mvn test` 中加入已授权多表 JOIN 放行、未授权表 JOIN 拒绝及清除 API Key 时拒绝测试的回归检查。

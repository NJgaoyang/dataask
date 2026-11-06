# DataAsk · 数问

独立的企业经营数据问答 MVP，供领导使用手机浏览器 / 企业内部 H5 页面自然语言查询 StarRocks ADS 数据，支持连续追问和基本图表。**与 DataLens、DataForge、Datart 无代码或数据库依赖。**

> 状态：第一版源码骨架已经提交；需要按本文配置实际 MySQL、StarRocks、AI 模型后联调。尚未完成真实 ADS 指标口径验收、微信/钉钉免登和按人员的数据行级权限校验，**不可直接作为生产系统向领导开放**。

## 技术栈

- Java **21**、Spring Boot **3.5.7**、Spring Security、Spring JDBC
- MySQL **8**：用户、授权表、会话、SQL 和查询记录
- StarRocks：**外部 ADS 数据源**；不复制数据到 MySQL
- Vue 3 + Vant + ECharts：单页移动端 H5
- OpenAI 兼容的 `/chat/completions` 模型接口
- 部署：独立 JAR，附 Linux `tar.gz` 打包和启停脚本；不依赖 Docker、Redis 或 Nginx

## V0.1 已实现功能

1. 首次管理员账号初始化、JWT 登录、账号创建和会话归属校验。
2. 自动从 StarRocks `information_schema.COLUMNS` 读取 ADS 表字段及注释。
3. 管理员选择允许 AI 查询的 ADS 表，默认无授权表。
4. 提问 → AI 生成 JSON 查询计划 → JSqlParser 只读 SQL / 表白名单验证 → StarRocks 查询。
5. 展示指标、表格、柱状/折线/饼图；保存会话；点击“按城市看 / 按门店看 / 查看近7天趋势”进行多轮追问（沿用上一条问题及 SQL 作为 AI 上下文）。
6. Java 21 + Web GitHub Actions 构建校验。

**注意：** 当前连续追问通过上下文提示词实现，尚未做结构化、可证明保留日期/维度的查询规划器。自然语言 SQL 仍需要与 ADS 正式报表逐项对账。暂未实现 SSE 流式返回、微信小程序免登、钉钉免登、行级权限策略及生产级审计。

## 服务器环境

- JDK 21
- Maven 3.9+
- Node.js 22（只用于打包；运行时不需要）
- MySQL 8（预建 `dataask` 库、提供独立元数据库账号）
- 已开放可访问的 StarRocks FE MySQL 协议端口（通常是 9030）
- 可访问的 OpenAI 兼容模型 API

首次创建 MySQL 库（按实际情况调整账户及密码）：

```sql
CREATE DATABASE IF NOT EXISTS dataask CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

在 MySQL 中为 DataAsk 提供该库的建表、读写权限；不要让 DataAsk 使用业务库超级管理员账号。

## 本地启动

```bash
git clone https://github.com/NJgaoyang/dataask.git
cd dataask
export DATAASK_MYSQL_URL='jdbc:mysql://127.0.0.1:3306/dataask?useUnicode=true&characterEncoding=utf8'
export DATAASK_MYSQL_USER='dataask'
export DATAASK_MYSQL_PASSWORD='替换为实际密码'
export DATAASK_JWT_SECRET="$(openssl rand -hex 32)"
export DATAASK_ADMIN_PASSWORD='首次启动管理员强密码至少10位'
export DATAASK_STARROCKS_JDBC_URL='jdbc:mysql://starrocks-fe:9030/ads?useUnicode=true&characterEncoding=utf8'
export DATAASK_STARROCKS_USER='只读账号'
export DATAASK_STARROCKS_PASSWORD='替换为实际密码'
export DATAASK_STARROCKS_SCHEMA='ads'
export DATAASK_AI_BASE_URL='https://your-model-api.example/v1'
export DATAASK_AI_API_KEY='替换为API Key'
export DATAASK_AI_MODEL='替换为模型名'
mvn spring-boot:run
```

另开终端运行前端开发服务器：

```bash
cd web
npm install
npm run dev
```

访问 `http://127.0.0.1:5173`。初次登录账号为 `admin`，密码为环境变量 `DATAASK_ADMIN_PASSWORD` 指定的值。首次启动时自动建表，不会覆盖已有记录。

管理员先进入“系统管理”，查看 ADS 表字段注释，并开放经过核对的表，再测试“昨天的营业额是多少”。如果字段注释存在多种营业额口径，AI 必须提示确认口径，不能自行猜测。

## Linux TAR 打包和直接运行

```bash
bash scripts/package.sh
# 生成 release/dataask-0.1.0.tar.gz
tar -xzf release/dataask-0.1.0.tar.gz
cd release/dataask-0.1.0
vi conf/application.yml
chmod 600 conf/application.yml
bash bin/start.sh
tail -f logs/dataask.log
bash bin/stop.sh
```

后端已将构建好的前端静态页面一起打包进 JAR，部署后直接访问 `http://服务器IP:8087`。

**微信小程序 WebView / 钉钉企业内部应用正式接入时**，需要使用 HTTPS、对应平台域名和免登流程。当前是账号密码登录的 H5 内测版，请不要把 HTTP 明文登录页面暴露到公网。

## API 摘要

| API | 说明 |
| --- | --- |
| `POST /api/auth/login` | 登录，获取 Bearer Token |
| `GET /api/auth/me` | 当前身份 |
| `GET /api/chat/sessions` | 当前用户的会话 |
| `GET /api/chat/sessions/{id}/messages` | 恢复会话消息 |
| `POST /api/chat/ask` | 提问或带 `sourceMessageId` 追问 |
| `GET /api/admin/status` | 配置状态 |
| `GET /api/admin/tables` | 读取 StarRocks ADS 元数据 |
| `POST /api/admin/table-allow` | 对指定 ADS 表授权/禁用 |
| `GET /api/admin/users` / `POST /api/admin/users` | 管理内部账号 |
| `GET /api/health` | 健康探针 |

## 数据安全与后续增强

目前 SQL 有单条 SELECT、语法解析、ADS 表授权、最大 200 行、20 秒查询超时等保护，但**仍必须配合 StarRocks 最小 SELECT 权限账号及网络隔离使用**。MVP 是表级授权，并非按员工、部门、城市的数据行级权限管理。

下一阶段必须补齐：真实 ADS 口径校验、行级权限、企业微信/小程序和钉钉 SSO、服务端 SQL 执行审计、SQL 资源组和限流、上下文结构化解析与趋势下钻一致性测试。

## 常见启动失败：Unable to start embedded Tomcat

如果日志显示 `ApplicationContextException: Unable to start web server`，请继续查看 **最后一层 `Caused by`**，不能仅凭 Tomcat 外层异常判定为端口占用。

- **JWT 密钥为空/不足 32 字节**：可在 `conf/application.yml` 的 `dataask.jwt-secret` 填写 `openssl rand -hex 32` 生成的密钥，或者使用新版 `bin/start.sh`。新版脚本会在没有配置 JWT 密钥时首次生成 `conf/.dataask-jwt-secret`（权限 600），之后复用此文件；注意备份，切勿提交仓库。
- **首次管理员密码缺失**：初始化空库时，`dataask.admin-password` 必须至少 10 个字符；后续重启不会覆盖已有管理员密码。
- **MySQL 无法连接**：检查 `dataask` 库是否存在、账号权限及 `spring.datasource` 参数。
- **端口冲突**：`ss -lntp | grep ':8087'`，若端口被占用，可通过 `DATAASK_PORT` 更换端口。

诊断命令：

```bash
grep -n 'Caused by:' logs/dataask.log | tail -n 15
tail -n 120 logs/dataask.log
ss -lntp | grep ':8087'
```

如果使用的是之前打包的 TAR，修改仓库中的启动脚本不会自动更新旧 TAR；需要重新运行 `bash scripts/package.sh` 并部署新包。也可以直接按照上面步骤配置 JWT 密钥后重启，无需重新打包。


## 旧版模型配置故障排查（历史说明；新版已支持 Web 配置）

- 系统管理的“AI 模型”行可以点击，进入“AI 模型配置检查”。新版本可在 Web 中配置并保存 Key、Base URL 和模型名，旧版本只能读取运行中服务的配置文件。
- 完整可用状态需要 `dataask.ai.api-key`、`dataask.ai.base-url` 和 `dataask.ai.model` **三项同时存在**；只配置前两项，会显示“检查模型名称”。
- Linux TAR 部署优先读取解压目录 `conf/application.yml`。这个外部文件中的空值也会覆盖 JAR 自带 `application.yml` 的非空值，因此要检查**实际运行目录**的配置，而不只是 Git 仓库源码里的文件。
- 若使用 `${DATAASK_AI_API_KEY:}`、`${DATAASK_AI_BASE_URL:}` 等占位符，必须给启动进程设置相应环境变量。
- 修改 `conf/application.yml` 中的模型配置后，应通过 `bash bin/stop.sh`、`bash bin/start.sh` 重启；页面的“测试连接”能够给出 HTTP 状态错误，不会暴露 Key。
- StarRocks 的“未配置”也是可点击入口，可以填写 FE JDBC 地址、执行连接测试并保存；管理员开放 ADS 表后，用户才能问数。
- 由于前端资源包含在 JAR 中，**更新仓库代码后需要重新执行 `bash scripts/package.sh` 并部署新 TAR**，否则旧页面仍然无法点击。


## 城市简称与 ADS 实际值自动对齐（已实现的轻量语义层）

为了避免用户说“西安”但 ADS 字段存储“西安市”而导致空结果，后端现在含有
\`EntityValueResolver\`。

1. 仅检查管理员开放的 ADS 表，识别名称、字段注释中明确表示城市名称的文本列。
2. 对这些列按需执行 **SELECT DISTINCT ... LIMIT 500**，最多检查 12 个城市字段，每列查询超时 8 秒，并在内存中缓存 15 分钟。
3. 依据实际取出的城市名称生成短名称映射。例如检测到“西安市”以后，识别“西安”。
4. 只把与当前用户问题匹配的标准值和来源字段发送给大模型，提示其使用真实维度值；若大模型仍生成 \`city_name = '西安'\` 等明确等值条件，服务端对确认过的城市列进行有范围的修正，随后执行原有 SQL 白名单校验。
5. 问数结果会在成功命中标准值时标注“西安 → 西安市”。这不依赖向量数据库或 Embedding 模型。

**限制与安全：** 这是面向中文“城市名称”的轻量 MVP，不覆盖所有城市别名、区县、门店、业务术语或拼音；存在同名或歧义的数据值必须先由业务确认。字段值采样对大型明细表可能有成本，生产前应优先使用维表/汇总表或提前维护缓存并压测。只允许 StarRocks 的只读账号查询，尚未实现行级权限隔离。未来可增加实体别名词典、指标语义模型、Embedding 表/字段召回、Reranker、错问 SQL 示例库，但 Embedding 不应代替数据值精确校验或业务口径审核。

相关源码：
- \`src/main/java/com/dataask/semantic/EntityValueResolver.java\`
- \`src/main/java/com/dataask/data/StarRocksGateway.java\`
- \`src/main/java/com/dataask/chat/ChatController.java\`
- \`src/test/java/com/dataask/semantic/EntityValueResolverTest.java\`


## 指标 Excel、结果表格与 ADS 多表关联（最新版本）

### 指标口径 Excel

管理员进入 **系统管理 → 指标口径 · Excel → 上传 Excel**。支持 .xlsx/.xls、最多 5 MB，导入前预览前 10 条，确认后写入 MySQL，支持导入历史和按批次删除。点击“下载模板”可以获得规范格式。Excel 必须至少包括“指标名称”和“指标口径”；可选“指标别名、ADS表名、来源字段、时间字段、分析维度、备注”。AI 在生成 SQL 时会参考已上传口径，但不直接执行 Excel 中的指令。

### 中文列名与固定高度表格

表格最多占用约 **320px** 高度，在内部上下/左右滚动，表头固定；后端仍限制最多返回 200 行。结果列名优先使用 AI 返回的中文业务标签，然后查找已授权 ADS 字段的中文注释和已知业务字段。无法确定含义时会显示“字段N”并保留原始技术列名作为鼠标悬停提示，避免误译。

ECharts 已调整为多色图例、横向排行条、渐变趋势图、环形图。在合适的两列数据场景可自动生成排行/趋势图。图表仅显示前 8～12 项以保证手机可读性；完整查询结果显示在下方表格区域。

### 多张 ADS 汇总表关联

系统管理中新增“**ADS 跨表关联规则**”，可配置已授权的两张 ADS 表、主表与关联表的关联键（支持多个字段，以英文逗号分隔）、一对一/多对一类型和至少 8 字的业务粒度说明。

规则保存在 MySQL；AI 可使用审核过的关联规则。多表 SQL 在执行前会检查是否仅使用批准的 JOIN 等值关联键，拒绝不完整的关联条件、无审批关系和隐式关联。**管理员仍需核实 ADS 汇总表在关联粒度上是否真正唯一；仅审核键名无法证明不会重复计数。** 跨不同粒度的多个事实表建议先预聚合，避免乘法放大。

### 部署升级

仓库代码提交在 main 分支。应用的前端资源在 JAR 内，更新后需重新打包并部署：

    git pull origin main
    bash scripts/package.sh

升级时保留原有部署目录的 conf/application.yml、JWT 密钥和 MySQL 数据库，停止旧服务并替换新版 JAR；首次运行 schema.sql 将以 CREATE TABLE IF NOT EXISTS 新增指标和关联规则表，无需清空已有数据。新版本优先读取 Web 管理页面保存的模型配置；原 application.yml 保留为对话模型的兼容默认值。

这仍是 MVP：上线供领导使用前需要验证实际 ADS 数据、指标口径、用户行级权限及关联键唯一性。


## PC / H5 自适应及指标口径显示（2026-11）

### 问数结果的动态中文名称

- 结果标题和指标列名优先来自**用户本轮提问的业务指标词**；数据库真实字段名只负责计算，不负责决定业务展示名。
- 时间和指标名称分别处理：用户问“前一天的总金额”，即使 ADS 物理字段注释是“当日金额”，结果名称也显示“总金额”，时间标签单独显示“前一天”。
- 对昨天、昨日、前一天和前天等常用日期，SQL 必须包含对应绝对日期；生成 SQL 和提问日期不一致时拒绝作为可信答案。
- 如果无法确认业务口径、模型没有返回合法查询计划，或生成 SQL 违反只读/授权/关联限制，回答统一为 **对不起，当前问题不支持，请找管理员“许愿”。**
- 数据库、网络、AI 服务不可用属于系统错误，仍返回明确的配置或服务异常，避免误报成业务不支持。
- 完整结果仍来自 StarRocks，指标展示名与查询数据是两条独立的校验路径；指标值必须与公司 ADS 官方口径核对，不因显示名称变化而修改 SQL 计算。

### PC 的 WorkBuddy 风格工作台

桌面宽屏（宽度 >= 1024px）采用三栏布局：

1. 左侧：产品标识、新建对话、历史搜索、个人与管理入口。
2. 中间：AI 对话、结果卡片、连续追问和固定底部输入栏。
3. 右侧：数据结果详情，可切换概览/完整表格，也可收起。

手机和平板使用响应式布局。微信小程序 WebView、钉钉 H5 应用和手机浏览器共用一套 Vue 3 页面，窄屏自动切换单栏，不增加独立前端。**此版本仅完成响应式 H5；微信/钉钉免登接入、正式 HTTPS 域名和移动端实机兼容测试仍需独立实施。**

### 更新

本次改动已提交 main，重新拉取、打包和部署才能看到新 PC 界面和动态指标标题：

    git pull origin main
    bash scripts/package.sh

部署更新时必须保留原来的 conf/application.yml、JWT 密钥和 MySQL 数据，不要用空白配置模板覆盖真实密钥。


## 最新：Web 模型管理、Embedding 语义索引与 PC 新版可见性

### 一、模型密钥全部在 Web 配置

管理员打开 **系统管理**，分别点击 **对话模型** 和 **Embedding 模型**。

- 对话模型：Base URL、模型名称、API Key 可以直接在网页填写、测试、保存，立即生效。
- Embedding：可启用模型、填写 Base URL、模型名称、API Key（本地免密可留空）和向量维度；默认使用阿里云 qwen3.7-text-embedding-flash（1024 维）。
- Embedding 保存后必须点击 **重建语义索引** 才开始参与问数；当前最多索引 120 条已授权 ADS 表元数据与 Excel 指标描述，每批 20 条。
- 语义检索失败时，问数仍使用 ADS 字段注释及 Excel 指标定义回退；不会把向量结果当作业务口径的唯一真相。
- Key 由后端加密存储到 MySQL 8，前端保存后不再明文回显。原 JWT 加密密钥需要长期保留，否则旧的数据源密钥和模型密钥无法解密。

### 二、为什么 PC 没看到新布局

前端静态资源打包在 Spring Boot JAR 里。只执行 git pull 不会更换服务器上运行的 JAR。

新版桌面浏览器视口宽度 >= 1024px 时，会显示左侧历史会话、中间 AI 对话、右侧数据结果三栏；手机、小程序和钉钉 H5 保持单栏。

在源码目录执行：

    git pull origin main
    bash scripts/package.sh

假设现有安装目录为 /opt/dataask（请换成你的真实路径），建议执行：

    bash scripts/runtime/upgrade.sh "$PWD/release/dataask-0.1.0.tar.gz" /opt/dataask

升级脚本仅替换旧安装目录的 JAR 与启停脚本，保留原 conf/application.yml、JWT 密钥与 MySQL 数据库。服务启动后检查：

    curl -s http://127.0.0.1:8087/build-info.json

新版本应该返回 buildSha、builtAt、uiLayout 为 desktop-three-pane、modelManagement 为 web 的 JSON，并在 PC 左侧底部显示构建号。返回 404 说明运行的可能是旧 JAR 或访问了错误的服务器。

浏览器可尝试 Ctrl+Shift+R，或用无痕窗口排除旧缓存；新版本已为 HTML 和构建信息添加无缓存响应头。

### 三、验收边界

这次提交已由 GitHub CI 检查，但还没有在你的实际服务器和真实 StarRocks 中进行连通性、浏览器和微信/钉钉实机验证。企业免登、行级数据权限和指标口径对账仍需独立验收。


### 默认配置、阿里云 Embedding 和部署验证（2026-11）

**服务默认端口 8087**。已有 conf/application.yml 配置了端口时，以现有配置为准。

- 对话模型默认地址：https://api.deepseek.com；模型名：deepseek-flash；API Key 在系统管理 Web 页面填写。
- Embedding 默认地址：https://dashscope.aliyuncs.com/api/v1；模型：qwen3.7-text-embedding-flash；维度：1024。
- Embedding 默认关闭，需要在 Web 模型管理中填写 Key、启用、测试连接、保存后执行“重建语义索引”。如果之前已保存其他模型，该配置优先于新默认值。
- DashScope /api/v1 使用原生同步接口 /services/embeddings/text-embedding/text-embedding；OpenAI 兼容地址仍使用 /embeddings。DataAsk 会根据 URL 自动选择正确的请求与响应格式。
- StarRocks FE 的 jdbc:starrocks://HOST:9030 简写会适配到 MySQL Connector/J 可以使用的 jdbc:mysql://HOST:9030/ads。

请保留已有的服务器 conf/application.yml、JWT 密钥、MySQL 数据，不要将真实密码和 API Key 提交到 GitHub。最新版本可用以下地址核对：

    curl -fsS http://127.0.0.1:8087/build-info.json

如果网页还是旧界面，请先确认已使用新 JAR 启动，再打开构建信息核对 buildSha，并强制刷新浏览器。

# DataAsk · 数问

面向企业管理者的独立智能问数应用：直接询问经营数据，查询外接 StarRocks ADS 表，展示中文指标、表格和图表。Java 21 / Spring Boot 3.5.7 / MySQL 8 / Vue 3。**与 DataLens、DataForge 无运行时或数据库依赖；表字段选择只参考 DataForge 的交互方式。**

## 当前配置方式

业务配置统一存入 MySQL。管理员登录后进入 **系统管理 · MySQL 配置中心**：

| 模块 | 可配置内容 |
| --- | --- |
| 模型与连接 | 对话模型、Embedding 模型的 Base URL / 模型名 / API Key；StarRocks FE 连接、账号、密码；测试连接与保存 |
| 数据库 / 表 / 字段 | 选择真实数据库、搜索并勾选表、逐字段选择、确认保存；表列表10条/页，字段区域固定高度滚动 |
| 表关联 | 选择已开放表，维护关联键、一对一/多对一及统计粒度说明 |
| 指标口径 | 上传 .xlsx / .xls，预览后导入，保留批次并可删除 |
| 账号管理 | 创建管理员及普通内部账号 |

配置保存后立即生效，不需要重新打包或重启。切换数据库不会覆盖其他数据库的已保存选择。未选择的字段不进入问数元数据，也不能用于 SELECT、筛选、关联或聚合。新增物理字段不会自动开放。取消表字段或切换模型会清除对应旧向量索引，需要重新构建。

**启动前必须提供元数据库 MySQL 的连接信息**：程序需要先连上该库才能读取其余配置，所以这项引导连接以及 HTTP 端口保留在 `conf/application.yml` 或环境变量中。模型、StarRocks 分析连接、表字段、系统签名密钥等不再要求人工写进配置文件。

默认预填值（模型是否可调用以服务商及当前账号的连接测试为准）：

| 项目 | 初始值 |
| --- | --- |
| 服务端口 | `8087` |
| 对话模型 Base URL / 模型 | `https://api.deepseek.com` / `deepseek-flash` |
| Embedding Base URL / 模型 | `https://dashscope.aliyuncs.com/api/v1` / `qwen3.7-text-embedding-flash` |
| Embedding 维度 / 启用 | `1024` / 默认关闭 |

DashScope `/api/v1` 自动使用原生文本向量请求协议；其他兼容地址使用 `/embeddings`。管理员在网页中填写各自 Key，测试、保存，再重建语义索引。没有 Key 的已知云端模型不会显示为已配置；本地无密钥兼容服务可留空。

## 构建与首次安装

构建机器需要 **Java 21、Maven 3.9+、Node.js 22或更高版本、npm、tar、unzip、sha256sum**。运行机器需要 Java 21、MySQL 8，以及 Linux 的 bash、flock、curl、unzip；不需要 Node、Redis、Nginx 或 Docker。

```bash
bash package.sh
# 或 bash scripts/package.sh
# mvn clean package 也会自动构建 Vue 前端并嵌入 JAR，不再单独复用旧静态文件。
```

产物：`release/dataask-0.1.0-<构建提交号>.tar.gz`，同时提供兼容名称 `release/dataask-0.1.0.tar.gz` 和版本化 SHA256 文件。

先在 MySQL 创建元数据库：

```sql
CREATE DATABASE IF NOT EXISTS dataask CHARACTER SET utf8mb4;
```

元数据库账号需要该库的建表、修改表结构和读写权限。分析数据源账号只需相应 ADS 的读取权限。

```bash
tar -xzf dataask-0.1.0.tar.gz
cd dataask-0.1.0
vi conf/application.yml
# 只填写 MySQL 引导连接及服务端口。
export DATAASK_ADMIN_PASSWORD='安装时自行指定的初始密码'
bash bin/start.sh
bash bin/status.sh
```

通过 `http://服务器IP:8087` 访问。全新数据库的管理员账号是 `admin`；未指定初始密码时，启动日志会仅在初始化时输出一次自动生成的密码。已有账号不会因升级或重新设置该环境变量而重置。

管理员按顺序配置：**模型与连接 → 保存 StarRocks → 数据库 / 表 / 字段 → 选择数据库 → 选择表与字段 → 确认保存**。日期、城市、指标及 JOIN 所需字段也需要勾选。普通用户不需要接触这些配置，只进入问数工作台。

## 旧安装升级与“发布后没变化”排查

升级前备份元数据库。**首次从旧版迁移时保留原 `conf/application.yml` 和 `conf/.dataask-jwt-secret`（存在时）**，不要更换旧 JWT 密钥；系统需要它读取原有加密配置。第一次迁移只补齐缺失配置，不覆盖已经通过网页保存的值。迁移后，以 MySQL 为唯一业务配置来源，旧 YAML 模型字段不再覆盖网页配置。

旧版只有整表授权，没有明确字段清单：升级后请在新选择器中确认一次表和字段并保存，系统不会自动扩大字段范围。更换分析连接会撤销旧连接的表授权、关联规则及向量索引。

在源码目录更新并打包后，使用**新源码中的升级脚本**，第二个参数是实际运行安装目录，而不是源码目录：

```bash
git switch main
git pull --ff-only origin main
bash package.sh

# /opt/dataask 仅为示例，替换为现有安装目录。
bash scripts/runtime/upgrade.sh \
  "$PWD/release/dataask-0.1.0.tar.gz" \
  /opt/dataask

bash /opt/dataask/bin/status.sh
curl -fsS http://127.0.0.1:8087/api/build-info
```

升级只替换 JAR 和脚本，保留配置文件、用户和 MySQL 数据。脚本等待旧进程真正退出，备份原程序，原子替换文件，再检查**新进程 PID 与构建版本一致**后才报告启动成功。启动失败会尝试恢复原程序，不会回滚或删除数据库数据。

版本诊断接口 `/api/build-info` 返回：

```json
{
  "buildSha": "实际源码提交号",
  "uiLayout": "desktop-three-pane",
  "configStorage": "mysql",
  "catalogVersion": "table-column-selector-v1",
  "pid": 12345,
  "startedAt": "实际进程启动时间"
}
```

页面仍不变化时，先核对实际安装目录、端口和该接口，不要只看打包成功日志。管理页同时显示前端与后端版本，二者不同会提示刷新。手工覆盖 JAR 后直接执行 start，不会自动替换已经运行的旧进程，请使用 upgrade。

## 问数、语义与界面

PC 宽屏使用历史会话 / 对话 / 数据结果三栏工作台；手机使用单列对话和自适应管理面板。结果表格、字段选择和指标文档预览均有独立滚动区域。

中文列名根据当前问题的业务含义展示。例如“前一天的总金额”可以使用注释为“当日金额”的物理字段，但显示名称仍为“总金额”，时间落实为日期筛选。无法支持的问题统一返回：`对不起当前问题不支持，请找管理员"许愿"`。

城市别名从已选字段的真实维度值建立映射，例如“西安”匹配“西安市”；歧义映射不自动猜测。Embedding 增强表和指标口径检索，不负责保证业务计算正确。

跨表查询必须有管理员批准的 JOIN 键与粒度规则。当前字段校验支持直接 SELECT、常用聚合、CASE、日期函数和批准的 JOIN；暂不放行无法完整校验字段范围的 CTE、子查询、窗口函数或 `SELECT *`。接口结果最多200行，查询有超时限制。多表聚合仍需核对事实粒度，不能把各表汇总值相乘。

连续追问按钮携带 `sourceMessageId`，在同一会话中沿用上一轮问题和 SQL 上下文。当前不是结构化语义规划器，正式上线前需要对典型指标、时间范围和连续追问与数仓报表逐项对账。

## API 与验证

主要接口：`POST /api/auth/login`、`POST /api/chat/ask`、`GET /api/admin/settings`、`POST /api/admin/settings/{ai|embedding|starrocks}`、对应 `/test`、`GET /api/admin/catalog/databases`、`GET /api/admin/catalog/tables?database=...`、`POST /api/admin/catalog/selection`、`POST /api/admin/semantic/rebuild`。

`POST /api/admin/table-allow` 旧整表入口已停用，返回410，引导使用明确表字段选择。目录保存包含 sourceKey/revision，冲突返回409，避免旧页面覆盖新连接或新选择。

CI 在隔离 MySQL 8 上构建完整 TAR，并测试首次启动、网页登录配置、表字段保存、多库选择、问数 SQL、Mock Embedding 索引失效、重启持久化、原生升级及 Chromium PC/手机页面。模型使用本地确定性响应，分析库使用 MySQL 协议测试数据，**不能替代用户实际 StarRocks、云模型、业务口径及微信/钉钉容器的联调验收**。

微信小程序 / 钉钉内嵌应用目前提供自适应 H5，平台免登、平台域名配置和按人员的行级权限尚未实现。图表为问数结果展示；独立图表收藏与按人共享仍需后续迭代，不能与已有会话保存混为一谈。

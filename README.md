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

后端已将构建好的前端静态页面一起打包进 JAR，部署后直接访问 `http://服务器IP:8080`。

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
- **端口冲突**：`ss -lntp | grep ':8080'`，若端口被占用，可通过 `DATAASK_PORT` 更换端口。

诊断命令：

```bash
grep -n 'Caused by:' logs/dataask.log | tail -n 15
tail -n 120 logs/dataask.log
ss -lntp | grep ':8080'
```

如果使用的是之前打包的 TAR，修改仓库中的启动脚本不会自动更新旧 TAR；需要重新运行 `bash scripts/package.sh` 并部署新包。也可以直接按照上面步骤配置 JWT 密钥后重启，无需重新打包。

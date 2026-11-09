# DataAsk 智能问数小程序 V2 · 可独立部署的完整分支

本分支从 main 完整继承 Spring Boot 3.5.7 / Java 21 / MySQL 8 / StarRocks / AI 问数能力和 PC 管理页面，新增与 `dataask-miniapp-prototype-v2.html` 同风格的高清 H5 小程序页面与数据接口。**非纯原型：页面必须登录并调用真实后端，没有写死营业额、订单数。**

## 工程与入口

- PC 管理端：`http://服务器IP:8087/`
- V2 小程序 H5 页面：`http://服务器IP:8087/miniapp/index.html`
- 健康检查：`http://服务器IP:8087/api/health`
- 版本检查：`http://服务器IP:8087/build-info.json`
- 源页面：`web/public/miniapp/index.html`，随 Vite 构建一起内置于 Spring Boot JAR。
- 小程序当前交付物为**适合手机与企业内嵌 WebView 的 H5**；它尚不是可直接提交微信审核的原生 `wx` 小程序工程。正式微信发布需要申请合规 HTTPS 域名和业务域名并完成微信授权/登录适配。

## 单独打包

打包机器需要 Java **21**、Maven **3.9+**、Node.js **22**、npm、git、tar；不需要 Docker、Redis 或 Nginx。

```bash
git clone -b dev20261109-miniapp-v2 https://github.com/NJgaoyang/dataask.git
cd dataask
bash package.sh
# 产物 release/dataask-0.1.0.tar.gz
```

该包**同时**包含 PC 管理端、V2 H5 小程序、Java API、配置模板、start/stop/upgrade 脚本，独立运行，不依赖其它 DataAsk 分支上的代码。不要直接将源码中的 HTML 文件部署到另一个服务器并期望它能查询数据。

## Linux 发布

```bash
mkdir -p /opt/dataask && tar -xzf release/dataask-0.1.0.tar.gz -C /opt/dataask
cd /opt/dataask/dataask-0.1.0
vi conf/application.yml
# 至少配置 MySQL 8 的库、账号、密码，首次启动 ADMIN_PASSWORD、StarRocks 连接
export DATAASK_ADMIN_PASSWORD='在这里设置至少10位强密码'  # 仅首次无账号初始化时需要
bash bin/start.sh
curl -f http://127.0.0.1:8087/api/health
bash bin/stop.sh
```

首次启动时 `schema.sql` 自动创建系统数据表（需要建表权限）；JWT 签名密钥在首次启动时由脚本生成并落于 `conf/.dataask-jwt-secret`，请妥善备份。首次管理员账号 `admin`，密码由 `DATAASK_ADMIN_PASSWORD` 设置，不预设可公开的默认密码。MySQL 元数据与 StarRocks 分离；StarRocks 只允许管理员在 PC 管理端批准的 ADS 表进行只读查询。

生产中建议将数据库账户和 API Key 等机密通过环境变量或保护的配置文件注入。修改模型/API Key、ADS 表权限与指标定义，请在 PC 管理端完成。小程序不会直接持有云模型密钥和 StarRocks 凭证。

### AI 模型与指标

小程序沿用现有 `/api/chat/ask`，由后台调用已配置的 Qwen（阿里云百炼兼容 OpenAI Chat Completions 的模型）或其他兼容模型。可在现有 PC 管理端设置 Base URL、Model、API Key；密钥绝不可放入前端。实际业务指标要先上传指标口径并开放对应 ADS 表，否则明确报错或回复“当前问题不支持，请找管理员‘许愿’”，不会返回伪造数字。

## 小程序 API（复用原有 JWT）

| 功能 | 方法 | 路径 |
|---|---|---|
| 登录 | POST | `/api/auth/login` |
| 当前用户/推荐问题 | GET | `/api/mini/bootstrap` |
| 智能问数/连续追问 | POST | `/api/chat/ask` |
| 最近会话 | GET | `/api/chat/sessions` |
| 会话消息 | GET | `/api/chat/sessions/{id}/messages` |
| 删除自己的会话 | DELETE | `/api/mini/sessions/{id}` |
| 收藏生成过的图表 | POST | `/api/mini/charts` |
| 我的/分享给我/管理员全部 | GET | `/api/mini/charts?view=mine|shared|all` |
| 读取图表元信息（重新查询所需问题） | GET | `/api/mini/charts/{id}` |
| 更改可见范围 | PUT | `/api/mini/charts/{id}/share` |
| 取消收藏 | DELETE | `/api/mini/charts/{id}` |

共享配置：`{"scope":"PRIVATE|ALL|SELECTED","users":["zhangsan"]}`，`users` 必须是已存在系统成员的**用户名**，SELECTED 最多 20 人。创建收藏只能引用本人会话中的已生成消息。普通用户仅能管理本人收藏；管理员可查看管理所有图表。只有被共享的对象可打开共享图表，图表打开时重新使用当前**查看者**权限执行问数。

前端仅保存会话令牌于当前页面会话的 `sessionStorage`，刷新仍可用，浏览器关闭失效；正式微信小程序应接入企业微信/钉钉等身份提供方并将登录态迁入官方安全储存方案。不要用演示数据生产发布。

## 验收

1. 运行 `bash package.sh`，确认 `release/dataask-0.1.0.tar.gz` 存在。
2. 核实 JAR 同时包含 `static/index.html` 和 `static/miniapp/index.html`。
3. 用管理员登录 PC 端配置 Qwen/StarRocks ADS 允许表，然后在手机页面使用同一账号登录。
4. 输入“昨日营业额是多少？”验证数据来自 StarRocks；试一次“按城市看”并检查会话连续追问。
5. 收藏结果，重新访问“我的图表”并点击“实时查询”以验证不是静态数据。
6. 用第二个真实账号验证 PRIVATE 不可见，ALL 可见，SELECTED 仅指定成员可见。
7. 验证独立 TAR 中的 `bin/start.sh`、`bin/stop.sh` 与 `conf/application.yml` 工作正常。

GitHub Actions 在 `dev20261109-miniapp-v2` push 时运行前端构建、Maven 单测以及 TAR 打包和静态资源嵌入检查。没有真实 MySQL/StarRocks/模型密钥时，CI 不代表端到端业务链路已验收。

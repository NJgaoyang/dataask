import { readFileSync } from 'node:fs'
import assert from 'node:assert/strict'

const read = path => readFileSync(path, 'utf8')
const vue = read('web/src/App.vue')
const base = read('web/src/style.css')
const system = read('web/src/pc-design-system.css')
const admin = read('web/src/pc-admin.css')
const lists = read('web/src/pc-lists.css')
const entry = read('web/src/main.js')
const miniapp = read('web/public/miniapp/index.html')
const audit = read('web/src/components/AdminAuditLog.vue')

const imports = [
  "import './style.css'",
  "import './pc-design-system.css'",
  "import './pc-admin.css'",
  "import './pc-lists.css'"
]
for (const name of imports) assert(entry.includes(name), `缺少样式入口：${name}`)
assert(!entry.includes('pc-redesign.css'), 'pc-redesign.css 已并入设计系统，入口不应再引用')
for (let i = 1; i < imports.length; i++) {
  assert(entry.indexOf(imports[i - 1]) < entry.indexOf(imports[i]), 'PC 样式层加载顺序错误')
}
for (const [name, css] of Object.entries({ system, admin, lists })) {
  assert(css.includes('@media (min-width:1024px)'), `${name} 必须限定为 PC 端`)
}
assert(base.includes('@media (max-width:1023px)'), '移动端样式入口丢失')
assert(!miniapp.includes('pc-redesign.css'), '小程序不应依赖 PC 样式')

for (const token of [
  '--da-sidebar-w:216px',
  '--da-topbar-h:48px',
  '--da-primary:#0064C8',
  '--da-chat-max:100%',
  '.consumer-pc .da-home-grid',
  '.consumer-pc .da-home-ask-row',
  '.consumer-pc .da-home-session-row',
  '.consumer-pc .ds-home{background:var(--da-surface)',
  '.consumer-pc .da-home-grid>.da-home-card:first-child',
  '.consumer-pc .login-brand',
  '.consumer-pc .answer-card',
  '.consumer-pc[data-theme="dark"]'
]) assert(system.includes(token), `PC 页面规范缺失：${token}`)
for (const token of [
  '.consumer-pc .tb-content-page>.da-record-list',
  '.consumer-pc .tb-admin-source-page .da-list-table',
  '.consumer-pc .da-list-table .da-check input[type=checkbox]'
]) assert(lists.includes(token), `PC 列表规范缺失：${token}`)
assert(admin.includes('.consumer-pc .tb-admin-overview'), 'PC 管理规范缺失：.consumer-pc .tb-admin-overview')

for (const token of [
  'class="desktop-main-nav"',
  'class="ds-home-intro"',
  'class="da-home-ask"',
  'class="da-home-quick-label"',
  'class="da-home-grid"',
  'aria-label="最近会话"',
  'aria-label="最近图表"',
  'class="da-account-menu"',
  'role="menuitem" aria-label="系统管理"',
  'endpoint="/chat/sessions/page"',
  'v-model="historyDate" type="date"',
  'selectable v-model:selected="historySelection"',
  'class="da-history-batch-delete"',
  "api.delete('/chat/sessions/'+encodeURIComponent(row.id))",
  "api.post('/chat/sessions/batch-delete', { ids })",
  'endpoint="/mini/charts/page"',
  'class="answer-card"',
  '<AnalysisPanel v-if="isDesktop',
  'class="composer-row da-composer"'
]) assert(vue.includes(token), `PC 交互结构缺失：${token}`)

assert(!vue.includes('ds-home-hero'), '工作台不应再保留营销 hero 区')
assert(!vue.includes('class="da-home-columns"'), '首页不应使用旧双列列表结构')
assert(vue.includes('小Q'), '回答者名称缺失')
// 审计日志时间筛选遵循阿里云日志页形态：预设下拉 + 自定义区间，切换即查询。
for (const token of [
  'aria-label="时间范围"',
  "['today','今天'],['yesterday','昨天'],['3d','近 3 天'],['7d','近 7 天'],['30d','近 30 天'],['custom','自定义区间'],['all','全部时间']",
  'function applyRange',
  'class="audit-querybar"',
  'class="audit-conditions"'
]) assert(audit.includes(token), `审计时间筛选契约缺失：${token}`)
assert(!audit.includes('class="audit-filters"'), '审计旧筛选表单应被查询栏替代')
assert(!audit.includes('重置为今日'), '审计按钮文案应为重置')
console.log('PC 页面结构、统一样式、管理入口与移动端隔离检查通过')

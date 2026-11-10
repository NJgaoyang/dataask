import { readFileSync, existsSync } from 'node:fs';
import assert from 'node:assert/strict';

const vue = readFileSync('web/src/App.vue','utf8');
const system = readFileSync('web/src/pc-design-system.css','utf8');
const admin = readFileSync('web/src/pc-admin.css','utf8');
const base = readFileSync('web/src/style.css','utf8');
const entry = readFileSync('web/src/main.js','utf8');
const mobile = readFileSync('web/public/miniapp/index.html','utf8');

// 1. Stylesheet entry order: mobile base -> desktop design system -> desktop admin.
const order = ["import './style.css'", "import './pc-design-system.css'", "import './pc-admin.css'"];
for (const token of order) assert(entry.includes(token), 'Stylesheet not imported: ' + token);
assert(entry.indexOf(order[0]) < entry.indexOf(order[1]) && entry.indexOf(order[1]) < entry.indexOf(order[2]),
  'Desktop stylesheets must load after the mobile base, admin last');
for (const legacy of [
  'quickbi-pc.css', 'tencentbi-pc.css', 'admin-compact-pc.css', 'pc-bluewhite.css',
  'reference-home-pc.css', 'pc-global-layout-v4.css', 'pc-layout-v5.css',
  'admin-functional-pc.css', 'visualization-admin-v9.css', 'composer-resize.css', 'clarification.css'
]) {
  assert(!existsSync('web/src/' + legacy), 'Legacy desktop stylesheet must be removed: ' + legacy);
  assert(!entry.includes(legacy), 'Legacy desktop stylesheet must not be imported: ' + legacy);
}

// 2. Design tokens are the single source of truth and only apply on desktop.
for (const token of [
  '--da-primary:#2f6bff', '--da-canvas:#f5f7fa', '--da-text:#1f2733', '--da-border:#e5e8ee',
  '--da-sidebar-w:232px', '--da-topbar-h:56px', '--da-chat-max:880px', '--da-fs-kpi:28px',
  '.app-shell.consumer-pc', 'grid-template-areas:"sidebar topbar" "sidebar content" "sidebar composer"',
  '.sidebar-collapsed', '.da-composer-bar', '.da-scope-chip', '.da-answer-scope', '.da-answer-insight',
  '.da-suggestion-grid', '.da-home-ask', '.da-home-stats', '.ds-home-shortcut-grid', '.ds-home-history-head',
  '.tb-chart-table', '.da-chart-detail-body', '.tb-history-list', '.login-card', '.da-password-dialog',
  '.da-composer-resize-handle', '.metric-clarification', 'tabular-nums'
]) {
  assert(system.includes(token), 'Design system rule missing: ' + token);
}
assert(system.trimStart().startsWith('/*') && system.includes('@media (min-width:1024px)'),
  'Design system must be desktop scoped');
assert((system.match(/linear-gradient\(/g) || []).length <= 1,
  'Desktop surfaces must stay flat (only the loading shimmer may use a gradient)');
assert(!vue.includes('ds-home-wave') && !vue.includes('ds-home-window'),
  'Illustrated workbench banner must be removed');
for (const token of [
  '.tb-admin-page', '.tb-admin-overview-grid', '.tb-admin-panel-card', '.tb-admin-config-fields',
  '.tb-user-editor', '.tb-user-reset', '.tb-admin-metrics-page', '.tb-config-status-list',
  '.tb-admin-load-error', '.admin-table', '.metric-preview', '.da-admin-config-grid'
]) {
  assert(admin.includes(token), 'Admin rule missing: ' + token);
}
assert(admin.includes('@media (min-width:1024px)'), 'Admin stylesheet must be desktop scoped');
assert(!/@media\s*\(min-width:\s*1024px\)/.test(base) && !/@media\s*\(min-width:\s*1500px\)/.test(base),
  'style.css must only hold the mobile/H5 base');
assert(base.includes('@media (max-width:1023px)') && base.includes('.metric-clarification'),
  'style.css must keep the mobile clarification + desktop element hiding rules');

// 3. Desktop template structure.
for (const token of [
  "'consumer-pc': isDesktop", "'sidebar-collapsed'", 'class="da-sidebar-collapse"', 'toggleSidebar',
  'class="desktop-main-nav"', 'tb-single-admin-nav', 'class="tb-header-breadcrumb"',
  'class="da-account-menu"', 'openPasswordChange', 'submitPasswordChange', 'desktopLogout', 'changePasswordOpen',
  'class="ds-home-intro"', '{{ greeting }}', 'class="da-home-ask"', 'askFromHome', 'class="da-home-stats"',
  '{{ sessions.length }}', '{{ savedCharts.length }}', '{{ sharedCharts.length }}', '{{ approvedDatasets.length }}',
  'ds-home-shortcut-grid', 'ds-home-history-head', 'ds-home-history-row', 'sessions.slice(0,5)', 'fullSessionTime(item.updatedAt)',
  'class="composer-row da-composer"', 'class="da-composer-bar"', 'v-model="selectedDataset"', 'class="da-suggestions"',
  'class="da-answer-scope"', 'answerScope(msg)', 'answer-summary da-answer-insight', 'da-answer-result', 'desktop-result-switch da-segmented',
  'class="da-followups-label"', 'da-composer-resize-handle', 'tb-chart-open', 'da-chart-view-buttons', 'tb-history-search',
  "desktopSection==='overview'", "desktopSection==='ask'", "desktopSection==='charts'", "desktopSection==='history'", 'desktopIsAdminSection'
]) {
  assert(vue.includes(token), 'Desktop template token missing: ' + token);
}
for (const removed of [
  'desktop-insights', 'resultPanelOpen', 'latestAnswer', 'resultTab', 'desktop-toggle-results',
  'ds-home-banner', 'ds-home-kicker', 'quickbi-pc', 'tencentbi-pc', 'desktop-no-result', 'sidebar-profile-card', 'qbi-welcome-brand'
]) {
  assert(!vue.includes(removed), 'Removed desktop structure still present: ' + removed);
}
assert((vue.match(/class="da-chart-metric"/g) || []).length === 1, 'Chart detail KPI block must not be duplicated');
assert(!vue.includes('张经理') && !vue.includes('2025-01-20 14:28'),
  'Home cannot contain fabricated user/data examples');

// 4. Mobile/miniapp isolation.
assert(mobile.includes('function resultChart(a)') && mobile.includes('mobile-result-row'),
  'Mobile visual and chart functionality must be kept intact');
assert(!mobile.includes('pc-design-system') && !mobile.includes('--da-primary'),
  'Miniapp must not depend on the desktop design system');

console.log('PC: design tokens, two-file desktop stylesheet, collapsible sidebar, inline answer cards, workbench stats, dataset scope and mobile isolation OK');

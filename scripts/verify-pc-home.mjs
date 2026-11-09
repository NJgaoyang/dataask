import { readFileSync } from 'node:fs';
import assert from 'node:assert/strict';

const vue = readFileSync('web/src/App.vue','utf8');
const css = readFileSync('web/src/reference-home-pc.css','utf8');
const entry = readFileSync('web/src/main.js','utf8');
const mobile = readFileSync('web/public/miniapp/index.html','utf8');
const expected = [
  'ds-home-intro', 'ds-home-banner', 'ds-home-banner-illustration',
  'ds-home-shortcut-grid', 'ds-home-history-head',
  'ds-home-history-row', 'fullSessionTime(item.updatedAt)'
];
for (const token of expected) {
  assert(vue.includes(token), 'Approved home UI component missing: ' + token);
}
for (const token of [
  'grid-template-columns:264px minmax(0,1fr)',
  '.ds-home-banner', '.ds-home-shortcut-grid',
  '.ds-home-history-table', '.ds-home-history-head'
]) {
  assert(css.includes(token), 'Approved layout rule missing: ' + token);
}
assert(entry.includes("import './reference-home-pc.css'"),
  'Reference layout stylesheet not imported');
assert(entry.indexOf("import './reference-home-pc.css'") >
  entry.indexOf("import './pc-bluewhite.css'"),
  'Reference layout must have final desktop CSS precedence');
assert(vue.includes("sessions.slice(0,5)"), 'Recent questions must use actual session data');
assert(!vue.includes('张经理') && !vue.includes('2025-01-20 14:28'),
  'Home cannot contain fabricated user/data examples');
assert(mobile.includes('function resultChart(a)') && mobile.includes('mobile-result-row'),
  'Mobile visual and chart functionality must be kept intact');
const globalLayout = readFileSync('web/src/pc-global-layout-v4.css','utf8');
const allDesktopSections = [
  "desktopSection==='overview'",
  "desktopSection==='ask'",
  "desktopSection==='charts'",
  "desktopSection==='history'",
  "desktopIsAdminSection"
];
for(const section of allDesktopSections){
  assert(vue.includes(section),'Desktop section unavailable: '+section);
}
for(const rule of [
  'grid-area:sidebar', 'grid-row:1 / -1', 'height:100dvh',
  'linear-gradient(155deg,#11285f', 'sidebar-bottom',
  '.tb-page-body', '.tb-page-heading', '.tb-admin-heading',
  '.tb-admin-panel-intro', '.ds-home-intro', '.qbi-welcome-brand',
  '.conversation', '.tb-history-search'
]){
  assert(globalLayout.includes(rule),'Global PC layout rule missing: '+rule);
}
assert(globalLayout.includes('@media (min-width:1024px)'),
  'Global layout must not affect mobile devices');
assert(entry.includes("import './pc-global-layout-v4.css'"),
  'Global PC layout stylesheet missing');
assert(entry.indexOf("import './pc-global-layout-v4.css'") >
  entry.indexOf("import './reference-home-pc.css'"),
  'Global PC geometry must load after all legacy PC styles');

console.log('Approved PC workbench, full-height global sidebar, compact headers on every PC section, CSS precedence and mobile isolation OK');

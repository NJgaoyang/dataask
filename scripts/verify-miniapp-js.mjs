import { readFileSync } from 'node:fs';
import { Script, runInNewContext } from 'node:vm';
import assert from 'node:assert/strict';

const html = readFileSync('web/public/miniapp/index.html','utf8');
const inline = html.match(/<script>([\s\S]*?)<\/script>/i)?.[1];
if (!inline) throw new Error('Miniapp inline application script missing');
new Script(inline, { filename:'miniapp-inline.js' });
for (const url of ['/api/auth/login','/api/chat/ask','/api/mini/bootstrap','/api/mini/charts']) {
  assert(html.includes(url),'Missing live API integration: '+url);
}
assert(!html.includes('8,463,520')&&!html.includes('数据为交互原型演示'),'Hard-coded business data detected');
assert(!html.includes('data-action="export"')&&!html.includes('exportCSV(')&&!html.includes('.download='),'Mobile downloads must be unavailable');
assert(!html.includes('data-action="mic"') && !html.includes('data-action="config"'),
  'Mobile must never show microphone recording or model/data configuration');
for(const token of [
  "function chartDetailPage()",
  "state.view==='chart-detail'?chartDetailPage()",
  "/api/mini/charts/'+encodeURIComponent(id)+'/view",
  "/api/mini/charts/'+encodeURIComponent(id)+'/refresh",
  "function refreshSaved(id)"
]) assert(html.includes(token),'Mobile saved-chart detail missing: '+token);
assert(!html.includes('await openChat(c.question)'),
  'Opening saved chart must not jump back to a conversation');


const initializer = /startApp\(\);\s*\}\)\(\);\s*$/;
assert(initializer.test(inline),'Miniapp bootstrap pattern changed: chart smoke tests need updating');
const probe = inline.replace(initializer,
  'globalThis.__charts = {resultChart, resultTable, chartable, pieAllowed, answerCard, setType: (id,type) => {state.chartStyles[id]=type}, setMode: (id,mode) => {state.chartModes[id]=mode}}; })();');
const context = { sessionStorage: {getItem:() => null},console };
runInNewContext(probe,context,{timeout:2000});
const fn=context.__charts;
assert(fn,'Chart functions missing');
const a={
  id:'smoke-chart-1', chartType:'bar',
  title:'昨日各城市订单量排行', metricLabel:'订单量',
  columns:['city','total_orders'],
  rows:Array.from({length:21},(_,i)=>({city:'城市'+(i+1),total_orders:262-i*8})),
  columnLabels:{city:'城市',total_orders:'订单数'},
  summary:'已从 StarRocks 查询到 21 行真实数据。可以点击快捷追问继续分析。',
  followUps:[]
};
assert(fn.chartable(a),'21-city result should be chartable');
let plot=fn.resultChart(a);
assert(plot.includes('<svg')&&plot.includes('<rect'),'Bar chart SVG missing');
assert(plot.includes('城市1')&&plot.includes('城市21'),'21 rows not fully rendered');
assert(!plot.includes('下载'),'Unexpected download affordance');
let card=fn.answerCard({type:'answer',answer:a});
assert(card.includes('chart-plot'),'Result card must display graphical plot, not plain numbers');
assert(card.includes('mobile-result-row'),'Chart results must use the mobile full-width grid layout');
assert(card.includes('<div class="chart-viewport"'),'Chart result missing fixed-size scrolling viewport');
const fixedViewportCss = html.match(/\.mobile-result-row \.chart-viewport\s*\{([^}]*)\}/)?.[1] || '';
assert(/height:\s*clamp\(/.test(fixedViewportCss),'Chart viewport must use a fixed responsive height');
assert(/overflow-y:\s*auto/.test(fixedViewportCss),'Long chart rows must scroll inside the chart viewport');
assert(/overscroll-behavior:\s*contain/.test(fixedViewportCss),'Scroll should not chain to the whole page');
fn.setMode(a.id,'table');
const tableCard=fn.answerCard({type:'answer',answer:a});
assert(tableCard.includes('class="chart-viewport"') && tableCard.includes('class="table-data"'),
  'Chart and table modes must share the same fixed-height viewport');
assert(tableCard.includes('mobile-chart-types'),
  'Chart style toolbar must remain present in table mode so the outer card height stays fixed');
fn.setMode(a.id,'chart');
assert(html.includes('.mobile-result-row .chart-card') && html.includes('grid-column:1 / -1'),
  'Mobile result card must span the avatar and answer columns');
assert(!card.includes('data-action="export"'),'Result card exposes download');
assert(html.includes('area.scrollTop=scroll'),'Chart switch should preserve the outer chat position');
fn.setType(a.id,'line');
assert(fn.resultChart(a).includes('<path'),'Line chart missing');
fn.setType(a.id,'pie');
assert(fn.pieAllowed(a),'Positive-value pie chart expected');
assert(fn.resultChart(a).includes('<circle'),'Pie chart missing');
const table=fn.resultTable(a);
assert(table.includes('城市21')&&table.includes('订单数'),'Full table missing rows/Chinese labels');
const clarificationAnswer={
  id:'clarify-1',question:'查看近7天趋势',title:'请确认查询指标',
  summary:'你想查看哪项业务数据？请选择一个指标继续查询。',
  unsupported:true,rows:[],columns:[],
  followUps:['查看近7天订单量趋势','查看近7天用户数趋势']
};
const clarificationCard=fn.answerCard({type:'answer',answer:clarificationAnswer});
assert(clarificationCard.includes('你想查看哪项指标？'),'Mobile clarification heading missing');
assert(clarificationCard.includes('查看近7天订单量趋势') &&
  clarificationCard.includes('查看近7天用户数趋势'), 'Missing grounded metric candidates');
assert(clarificationCard.includes('data-action="follow"') &&
  clarificationCard.includes('data-mid="clarify-1"'),'Clarification choices must be clickable');
assert(!clarificationCard.includes('chart-plot'),'Unsupported clarification must not display a fake chart');
assert(html.includes('sourceId||state.lastMessageId'),
  'Clarification choice must reference the correct source response');
console.log('Miniapp: chart/table same fixed-height viewport, internal scroll, stable chat scroll, 21 rows, labels, no download OK');

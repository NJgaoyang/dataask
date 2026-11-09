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

const initializer = /startApp\(\);\s*\}\)\(\);\s*$/;
assert(initializer.test(inline),'Miniapp bootstrap pattern changed: chart smoke tests need updating');
const probe = inline.replace(initializer,
  'globalThis.__charts = {resultChart, resultTable, chartable, pieAllowed, answerCard, setType: (id,type) => {state.chartStyles[id]=type}}; })();');
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
assert(html.includes('.mobile-result-row .chart-card') && html.includes('grid-column:1 / -1'),
  'Mobile result card must span the avatar and answer columns');
assert(!card.includes('data-action="export"'),'Result card exposes download');
fn.setType(a.id,'line');
assert(fn.resultChart(a).includes('<path'),'Line chart missing');
fn.setType(a.id,'pie');
assert(fn.pieAllowed(a),'Positive-value pie chart expected');
assert(fn.resultChart(a).includes('<circle'),'Pie chart missing');
const table=fn.resultTable(a);
assert(table.includes('城市21')&&table.includes('订单数'),'Full table missing rows/Chinese labels');
console.log('Miniapp: syntax, API, all 21 chart rows, bar/line/pie, table and no-download checks OK');

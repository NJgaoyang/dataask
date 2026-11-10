// Visual check of the PC design system: screenshots of the six desktop pages
// (login / workbench / ask empty / ask with answer / my charts / admin) plus an H5 regression,
// against a mocked backend. Usage: npm run preview, then `node scripts/pc-screens.mjs`.
import { chromium } from 'playwright';
import { mkdir } from 'node:fs/promises';

const base = process.env.DATAASK_UI_URL || 'http://127.0.0.1:4173';
const out = process.env.DATAASK_SHOTS || '/tmp/dataask-shots';
await mkdir(out, { recursive: true });

const sessions = [
  { id:'s1', title:'昨天的营业额是多少？', updatedAt:'2026-11-10 09:12' },
  { id:'s2', title:'昨天各城市的订单量排行', updatedAt:'2026-11-09 17:40' },
  { id:'s3', title:'最近7天的营收趋势', updatedAt:'2026-11-08 11:05' },
];
const chart = {
  id:'chart-001', ownerUsername:'admin', sourceMessageId:'msg-001',
  question:'昨天各城市的订单量排行', title:'昨日各城市订单量排行', chartType:'bar',
  shareScope:'PRIVATE', dataset:'ads.city_orders', updatedAt:'2026-11-10 10:30'
};
const answer = {
  id:'msg-001', question:'昨天各城市的订单量排行', sessionId:'s2',
  title:'昨日各城市订单量排行', summary:'昨日订单量最高的城市为西安市（188 单），前三城市合计 449 单，占全部订单的 62%。',
  chartType:'bar', columns:['city','order_cnt'], columnLabels:{ city:'城市', order_cnt:'订单量' },
  rows:[{city:'西安市',order_cnt:188},{city:'苏州市',order_cnt:156},{city:'武汉市',order_cnt:105},{city:'成都市',order_cnt:98},{city:'杭州市',order_cnt:76}],
  unsupported:false, followUps:['对比前一天的订单量', '西安市近 7 天订单趋势'], metricLabel:'订单量', timeLabel:'昨日', dataset:'ads.city_orders'
};
const status = {
  starrocksConfigured:true, schema:'ads', aiConfigured:true, model:'qwen-plus', aiKeyConfigured:true, aiUrlConfigured:true,
  embeddingConfigured:true, embeddingModel:'text-embedding-v3'
};
const settings = {
  starrocks:{ jdbcUrl:'jdbc:mysql://127.0.0.1:9030/ads', username:'readonly', schema:'ads', passwordSet:true },
  ai:{ baseUrl:'https://dashscope.aliyuncs.com/compatible-mode/v1', model:'qwen-plus', apiKeySet:true },
  embedding:{ baseUrl:'https://dashscope.aliyuncs.com/api/v1', model:'text-embedding-v3', enabled:true, dimensions:1024, apiKeySet:true }
};

async function mock(route) {
  const req = route.request(), url = new URL(req.url()), path = url.pathname, method = req.method();
  const ok = json => route.fulfill({ status:200, json });
  if (path === '/api/auth/login' && method === 'POST') return ok({ token:'t', username:'admin', displayName:'系统管理员', role:'ADMIN' });
  if (path === '/api/auth/me') return ok({ username:'admin', role:'ADMIN', displayName:'系统管理员' });
  if (path === '/api/chat/sessions') return ok(sessions);
  if (path === '/api/chat/datasets') return ok(['ads.city_orders', 'ads.sales']);
  if (path === '/api/chat/ask' && method === 'POST') return ok(answer);
  if (path === '/api/mini/charts') return ok(url.searchParams.get('view') === 'shared' ? [] : [chart]);
  if (path === '/api/mini/charts/chart-001/view') return ok({ chart, answer, cached:true });
  if (path === '/api/mini/charts/chart-001/refresh' && method === 'POST') return ok({ chart, answer:{ ...answer, id:'transient-chart-answer' }, cached:false });
  if (path === '/api/admin/status') return ok(status);
  if (path === '/api/admin/settings') return ok(settings);
  if (path === '/api/admin/semantic/status') return ok({ count:2, indexedModel:'text-embedding-v3', configured:true });
  if (path === '/api/admin/tables') return ok([{ name:'ads.city_orders', enabled:true, columns:[{ name:'city', type:'varchar', comment:'城市' }] }]);
  if (path === '/api/admin/metrics') return ok({ total:0, batches:[], definitions:[] });
  if (path === '/api/admin/users') return ok([{ username:'admin', displayName:'系统管理员', role:'ADMIN' }]);
  return ok({});
}

const browser = await chromium.launch({ headless:true, args:['--no-sandbox'] });
const errors = [];
async function shoot(page, name) { await page.waitForTimeout(400); await page.screenshot({ path:`${out}/${name}.png`, fullPage:false }); }

try {
  for (const [tag, viewport] of [['1280', { width:1280, height:800 }], ['1920', { width:1920, height:1080 }]]) {
    // Login page (no token)
    const anon = await browser.newContext({ viewport });
    const login = await anon.newPage();
    login.on('pageerror', e => errors.push(tag + ' login: ' + e.message));
    await login.route('**/api/**', mock);
    await login.goto(base, { waitUntil:'networkidle' });
    await shoot(login, `${tag}-1-login`);
    await anon.close();

    const ctx = await browser.newContext({ viewport });
    await ctx.addInitScript(() => {
      sessionStorage.setItem('dataask_token', 'ui-test-token');
      sessionStorage.setItem('dataask_user', JSON.stringify({ username:'admin', displayName:'系统管理员', role:'ADMIN' }));
      localStorage.setItem('da_sidebar', '0');
    });
    const page = await ctx.newPage();
    page.on('pageerror', e => errors.push(tag + ': ' + e.message));
    await page.route('**/api/**', mock);
    await page.goto(base, { waitUntil:'networkidle' });
    await page.locator('.da-home-stats').waitFor();
    await shoot(page, `${tag}-2-workbench`);

    await page.locator('.desktop-main-nav button').filter({ hasText:'智能问数' }).click();
    await page.locator('.da-suggestions').waitFor();
    await shoot(page, `${tag}-3-ask-empty`);

    await page.locator('.da-suggestion-grid button').first().click();
    await page.locator('.answer-card').waitFor();
    await page.waitForTimeout(800);
    await shoot(page, `${tag}-4-ask-answer`);
    await page.locator('.conversation').evaluate(el => { el.scrollTop = 0; });
    await shoot(page, `${tag}-4b-ask-answer-top`);

    await page.locator('.desktop-main-nav button').filter({ hasText:'我的图表' }).click();
    await page.locator('.tb-chart-open').first().waitFor();
    await shoot(page, `${tag}-5-charts`);
    await page.locator('.tb-chart-open').first().click();
    await page.locator('.da-chart-detail h1').waitFor();
    await page.waitForTimeout(800);
    await shoot(page, `${tag}-5b-chart-detail`);

    await page.locator('.desktop-main-nav button').filter({ hasText:'历史会话' }).click();
    await page.locator('.tb-history-list').waitFor();
    await shoot(page, `${tag}-5c-history`);

    await page.locator('.tb-single-admin-nav button').click();
    await page.locator('.tb-admin-overview-grid').waitFor();
    await shoot(page, `${tag}-6-admin`);
    await page.locator('.tb-admin-overview-grid button').filter({ hasText:'数据源配置' }).click();
    await page.locator('.tb-admin-page h1').waitFor();
    await shoot(page, `${tag}-6b-admin-source`);
    await page.locator('.tb-admin-overview-grid, .tb-admin-back').first();
    await page.locator('.tb-single-admin-nav button').click();
    await page.locator('.tb-admin-overview-grid button').filter({ hasText:'用户管理' }).click();
    await page.locator('.tb-admin-users-page, .tb-admin-page').first().waitFor();
    await shoot(page, `${tag}-6c-admin-users`);

    // Collapsed sidebar state
    await page.locator('.desktop-main-nav button').filter({ hasText:'工作台' }).click();
    await page.locator('.da-sidebar-collapse').click();
    await shoot(page, `${tag}-7-collapsed`);
    await ctx.close();
  }

  // H5 regression (390x844): single column shell, conversation + composer visible.
  const mobile = await browser.newContext({ viewport:{ width:390, height:844 }, isMobile:true, hasTouch:true });
  await mobile.addInitScript(() => {
    sessionStorage.setItem('dataask_token', 'ui-test-token');
    sessionStorage.setItem('dataask_user', JSON.stringify({ username:'admin', displayName:'系统管理员', role:'ADMIN' }));
  });
  const h5 = await mobile.newPage();
  h5.on('pageerror', e => errors.push('h5: ' + e.message));
  await h5.route('**/api/**', mock);
  await h5.goto(base, { waitUntil:'networkidle' });
  const h5Layout = await h5.evaluate(() => {
    const shell = document.querySelector('.app-shell');
    const cs = getComputedStyle(shell);
    return {
      consumerPc: shell.classList.contains('consumer-pc'),
      columns: cs.gridTemplateColumns, display: cs.display,
      conversation: !!document.querySelector('.conversation'), composer: !!document.querySelector('.composer'),
      sidebarHidden: !document.querySelector('.sidebar') || getComputedStyle(document.querySelector('.sidebar')).display === 'none'
    };
  });
  console.log('H5 layout', JSON.stringify(h5Layout));
  await shoot(h5, 'h5-390');
  await mobile.close();
} finally {
  await browser.close();
}
if (errors.length) { console.error('Page errors:\n' + errors.join('\n')); process.exit(1); }
console.log('Screenshots written to ' + out);

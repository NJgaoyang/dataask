import assert from 'node:assert/strict';
import {mkdir,readFile} from 'node:fs/promises';
import {chromium} from 'playwright';
import {analysisAnswer} from './analysis-fixture.mjs';

const base=process.env.DATAASK_UI_URL||'http://127.0.0.1:4175';
const out='artifacts/compact-layout';
await mkdir(out,{recursive:true});
const charts=['按城市看','近7天趋势','昨日各城市的订单量排行'].map((title,i)=>({id:'chart-'+i,title,question:title,
  chartType:['table','line','bar'][i],ownerUsername:'admin',shareScope:'PRIVATE',updatedAt:'2026-11-10T05:57:00'}));
const trend={...analysisAnswer,title:'近7天趋势',question:'查看近7天趋势',chartType:'line',columns:['day','total'],
  columnLabels:{day:'日期',total:'订单量'},semantic:null,execution:null,
  rows:[70,66,73,96,60,80,79].map((total,i)=>({day:`2026-11-${String(i+3).padStart(2,'0')}`,total})),summary:'本次返回 7 行数据。'};
const auditRows=Array.from({length:38},(_,i)=>({id:`00000000-0000-0000-0000-${String(i+1).padStart(12,'0')}`,
  requestId:`10000000-0000-0000-0000-${String(i+1).padStart(12,'0')}`,username:'admin',actorRole:'ADMIN',module:'AUDIT',
  action:i%2?'查看查询 SQL 审计':'查看操作审计',target:i%2?'/api/admin/audit/queries':'/api/admin/audit/logs',
  path:i%2?'/api/admin/audit/queries':'/api/admin/audit/logs',method:'GET',detail:'',status:i?'SUCCESS':'RUNNING',
  httpStatus:i?200:null,durationMs:i?13:null,occurredAt:'2026-11-10T06:51:13Z'}));
async function backend(route) {
  const req=route.request(),u=new URL(req.url()),p=u.pathname,ok=json=>route.fulfill({json});
  if(p==='/api/auth/me')return ok({username:'admin',displayName:'系统管理员',role:'ADMIN'});
  if(p==='/api/chat/sessions')return ok([{id:'session-1',title:'昨天营业额是多少？',updatedAt:'2026-11-10T09:30:00'}]);
  if(p==='/api/chat/datasets')return ok(['ads.orders']);
  if(p==='/api/chat/ask')return ok({...analysisAnswer,question:req.postDataJSON().question});
  if(p==='/api/mini/charts/page')return ok({items:u.searchParams.get('view')==='shared'?[]:charts,total:u.searchParams.get('view')==='shared'?0:charts.length});
  if(p==='/api/admin/users/page')return ok({items:[{username:'admin',displayName:'系统管理员',role:'ADMIN'},{username:'alice',displayName:'业务用户',role:'USER'}],total:2});
  if(p==='/api/admin/datasources')return ok({items:[{id:'source-1',name:'经营数据源',jdbcUrl:'jdbc:mysql://localhost:9030/ads',schema:'ads',username:'readonly',active:true}],total:1});
  if(p==='/api/mini/charts')return ok(u.searchParams.get('view')==='shared'?[]:charts);
  if(p==='/api/mini/charts/chart-1/view')return ok({chart:charts[1],answer:trend,cached:true});
  if(p==='/api/mini/charts/chart-1/refresh')return route.fulfill({status:422,json:{message:'当前无法刷新该图表，请检查指标口径、ADS 表授权与模型配置'}});
  if(p==='/api/admin/status')return ok({starrocksConfigured:true,aiConfigured:true,embeddingConfigured:true});
  if(p==='/api/admin/settings')return ok({starrocks:{jdbcUrl:'jdbc:mysql://localhost:9030/ads',username:'readonly',schema:'ads'},ai:{baseUrl:'https://example.com/v1',model:'shared-model'},embedding:{enabled:false}});
  if(p==='/api/admin/users')return ok([{username:'admin',displayName:'系统管理员',role:'ADMIN'},{username:'alice',displayName:'业务用户',role:'USER'}]);
  if(p==='/api/admin/metrics')return ok({total:0,batches:[],definitions:[]});
  if(p==='/api/admin/tables')return ok([]);
  if(p==='/api/admin/audit/options')return ok({today:'2026-11-10',usernames:['admin','alice']});
  if(p==='/api/admin/audit/logs')return ok({items:auditRows.slice(0,20),total:38,page:1,pageSize:20});
  if(p==='/api/admin/audit/queries')return ok({items:[],total:0,page:1,pageSize:20});
  return ok({success:true});
}
const browser=await chromium.launch({headless:true,args:['--no-sandbox']});
const errors=[];
function trackErrors(page) {
  page.on('pageerror',e=>errors.push(e.message));
  page.on('console',message=>{
    if(message.type()==='error'&&!/Failed to load resource:.*status of 422/.test(message.text())) errors.push(message.text());
  });
}
async function capture(page,width,name,headingSelector) {
  await page.locator(headingSelector).waitFor();
  await page.mouse.move(width-8,8);
  const frame=await page.locator(headingSelector).boundingBox();
  assert(frame.y>=48 && frame.y<400,`${name}: heading stays inside the content frame`);
  const metrics=await page.evaluate(()=>{
    const rail=document.querySelector('.desktop-sidebar').getBoundingClientRect();
    const main=document.querySelector('main');
    return {rail:rail.width,railRight:rail.right,mainX:main.getBoundingClientRect().x,padding:getComputedStyle(main).padding,
      main:main.clientWidth,overflow:document.documentElement.scrollWidth>innerWidth,
      mainOverflow:main.scrollWidth>main.clientWidth+1,text:document.querySelector('.app-shell').innerText,
      headingFont:getComputedStyle(document.querySelector('main h1,main h2')).fontSize};
  });
  assert(Math.abs(metrics.rail-216)<2,`${width}: sidebar keeps the fixed enterprise rail width`);
  assert(Math.abs(metrics.main-(width-metrics.rail))<2,`${width}: content fills remaining width`);
  assert(Math.abs(metrics.railRight-metrics.mainX)<1,`${name}: main must touch the navigation divider`);
  assert(!metrics.overflow&&!metrics.mainOverflow,`${name}: horizontal overflow is confined to internal tables`);
  assert(parseFloat(metrics.headingFont)>=18 && parseFloat(metrics.headingFont)<=44,`${name}: readable title size`);
  assert.doesNotMatch(metrics.text,/仅查询管理员|用自然语言提问，系统会|通用问法示例|查看已收藏或他人共享|记录所有用户的服务端|接入说明|敏感凭证不会显示明文|管理员可创建用户/);
  await page.screenshot({path:`${out}/${width}-${name}.png`,animations:'disabled'});
}
try {
  for(const width of [2160,1920,1440,1280,1024]) {
    const ctx=await browser.newContext({viewport:{width,height:width===2160?1187:900},locale:'zh-CN'});
    await ctx.addInitScript(()=>{sessionStorage.setItem('dataask_token','layout-test');sessionStorage.setItem('dataask_user',JSON.stringify({username:'admin',role:'ADMIN',displayName:'系统管理员'}))});
    const page=await ctx.newPage();trackErrors(page);await page.route('**/api/**',backend);
    await page.goto(base,{waitUntil:'networkidle'});
    await capture(page,width,'workbench','.ds-home-intro');
    await page.locator('.sidebar-new').click();
    await capture(page,width,'ask','.welcome');
    const box=await page.locator('.composer-row').boundingBox();
    assert(box.y<=180&&box.width<=width-216+2,'Question input fills the workspace width');
    await page.locator('.desktop-main-nav button').filter({hasText:'我的图表'}).click();
    await capture(page,width,'charts','.tb-page-heading');
    const workspace=await page.locator('.tb-chart-workspace').boundingBox();
    assert(workspace.width<=width-216-32+2,'Chart workspace fits the shared content frame');
    assert.equal(await page.locator('.tb-page-heading p').count(),1);
    await page.locator('.tb-chart-name-link').filter({hasText:'近7天趋势'}).click();
    await page.locator('.da-chart-detail-error').waitFor();
    await page.locator('.da-chart-detail canvas').waitFor();
    await capture(page,width,'chart-detail','.da-chart-detail-heading');
    const backStyle=await page.locator('.da-chart-back').evaluate(el=>({color:getComputedStyle(el).color,border:getComputedStyle(el).borderTopStyle,height:el.getBoundingClientRect().height}));
    assert.equal(backStyle.border,'solid');assert.equal(backStyle.height,32);
    await page.locator('.da-chart-back').focus();await page.keyboard.press('Enter');
    assert.equal(await page.locator('.da-chart-detail').count(),0,'Back button must work with the keyboard');
    await page.locator('.desktop-main-nav button').filter({hasText:'历史会话'}).click();
    await capture(page,width,'history','.tb-page-heading');
    await page.locator('.da-account-trigger').click();await page.getByRole('menuitem',{name:'系统管理'}).click();
    await capture(page,width,'admin','.tb-admin-heading');
    for(const label of ['用户管理','审计日志','数据源配置']) {
      if(label!=='用户管理')await page.locator('.tb-breadcrumb-admin').click();
      await page.locator('.tb-admin-overview-grid button').filter({hasText:label}).click();
      await page.locator('.tb-admin-loading').waitFor({state:'hidden'});
      await capture(page,width,label==="用户管理"?'users':label==='审计日志'?'audit':'source','.tb-admin-heading');
    }
    if(width===1440) {
      await page.locator('.da-account-trigger').click();await page.getByRole('menuitem',{name:'个性化',exact:true}).click();
      await page.getByRole('button',{name:/深色.*黑色界面/}).click();
      await page.locator('.da-preferences-popup .van-popup__close-icon').click();await page.locator('.da-preferences-popup').waitFor({state:'hidden'});
      await capture(page,width,'source-dark','.tb-admin-heading');
    }
    await ctx.close();
  }
  assert.deepEqual(errors,[]);
  // Reference attachments are local task inputs, not required in CI.
  if(process.env.DATAASK_COMPARE==='1') {
    // The supplied 72px topbar corresponds to 48 CSS px at DPR 1.5.
    // Match that density instead of comparing a 2160 CSS px desktop to a 1440 CSS px reference.
    const matched=await browser.newContext({viewport:{width:1440,height:791},deviceScaleFactor:1.5,locale:'zh-CN'});
    await matched.addInitScript(()=>{sessionStorage.setItem('dataask_token','layout-test');sessionStorage.setItem('dataask_user',JSON.stringify({username:'admin',role:'ADMIN',displayName:'系统管理员'}))});
    const matchedPage=await matched.newPage();trackErrors(matchedPage);await matchedPage.route('**/api/**',backend);await matchedPage.goto(base,{waitUntil:'networkidle'});
    await matchedPage.locator('.desktop-main-nav button').filter({hasText:'我的图表'}).click();await matchedPage.locator('.tb-chart-workspace').waitFor();
    await matchedPage.locator('.tb-chart-name-link').filter({hasText:'近7天趋势'}).click();
    await matchedPage.locator('.da-chart-detail-error').waitFor();await matchedPage.locator('.da-chart-detail canvas').waitFor();
    await matchedPage.mouse.move(1432,8);
    await matchedPage.screenshot({path:`${out}/reference-density-detail.png`,animations:'disabled'});
    await matchedPage.locator('.da-account-trigger').click();await matchedPage.getByRole('menuitem',{name:'系统管理'}).click();
    await matchedPage.locator('.tb-admin-overview-grid button').filter({hasText:'审计日志'}).click();
    await matchedPage.locator('.audit-querybar').waitFor();await matchedPage.mouse.move(1432,8);
    await matchedPage.screenshot({path:`${out}/reference-density-audit.png`,animations:'disabled'});await matched.close();
    const refs=[
      ['图表详情','/root/.codex/attachments/a640d077-f333-430e-bef8-613c3387507d/codex-clipboard-4a53b404-e648-4afd-807f-3346316e7545.png',`${out}/reference-density-detail.png`],
      ['审计日志','/root/.codex/attachments/7db80b76-b7be-45c7-996c-8a0325f664d0/codex-clipboard-ab289b7d-02cd-4394-aa4a-ec36ec785055.png',`${out}/reference-density-audit.png`]
    ];
    const compare=await browser.newPage({viewport:{width:2160,height:1300}});
    let html='<style>*{box-sizing:border-box}body{margin:0;background:#e5e8ee;font:16px sans-serif}section{display:grid;grid-template-columns:1fr 1fr;gap:8px}figure{margin:0;background:white}figcaption{height:32px;padding:6px 16px}img{display:block;width:100%;height:auto}</style>';
    for(const [name,source,after] of refs){
      html+='<section>';
      for(const [title,path] of [['原图标注',source],['调整后',after]])html+=`<figure><figcaption>${name} · ${title}</figcaption><img src="data:image/png;base64,${(await readFile(path)).toString('base64')}"></figure>`;
      html+='</section>';
    }
    await compare.setContent(html);await compare.locator('img').evaluateAll(imgs=>Promise.all(imgs.map(img=>img.decode())));
    await compare.screenshot({path:`${out}/edge-comparison.png`,fullPage:true});
    await compare.setViewportSize({width:2160,height:530});
    await compare.addStyleTag({content:'section{margin-bottom:12px}figure{height:250px;overflow:hidden}img{width:2160px;max-width:none}'});
    await compare.screenshot({path:`${out}/edge-heading-comparison.png`,fullPage:true});
    await compare.close();
  }
  assert.deepEqual(errors,[]);
  console.log('PC layout: five desktop widths, bounded sidebar, centered workbench, consistent content frames, navigation, user/audit/source pages and dark appearance passed.');
} finally {await browser.close()}

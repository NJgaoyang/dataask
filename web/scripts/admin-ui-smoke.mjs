import { chromium } from 'playwright';
import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';

const base = process.env.DATAASK_UI_URL || 'http://127.0.0.1:4173';
const calls = [];
const status = {
  starrocksConfigured:true, schema:'ads', aiConfigured:true,
  model:'qwen-plus', aiKeyConfigured:true, aiUrlConfigured:true,
  embeddingConfigured:true, embeddingModel:'text-embedding-v3'
};
const settings = {
  starrocks:{jdbcUrl:'jdbc:mysql://127.0.0.1:9030/ads',username:'readonly',schema:'ads',passwordSet:true},
  ai:{baseUrl:'https://dashscope.aliyuncs.com/compatible-mode/v1',model:'qwen-plus',apiKeySet:true},
  embedding:{baseUrl:'https://dashscope.aliyuncs.com/api/v1',model:'text-embedding-v3',enabled:true,dimensions:1024,apiKeySet:true}
};
let tables = [
  {name:'ads.city_orders',enabled:true,columns:[{name:'city',type:'varchar',comment:'城市'},{name:'order_cnt',type:'bigint',comment:'订单数'}]},
  {name:'ads.sales',enabled:true,columns:[{name:'city',type:'varchar',comment:'城市'},{name:'amount',type:'decimal',comment:'销售额'}]}
];
let users = [
  {username:'admin',displayName:'系统管理员',role:'ADMIN'},
  {username:'alice',displayName:'Alice',role:'USER'}
];
let metrics = {total:0,batches:[],definitions:[]};
let failNextAdminSettings = false;

async function mockBackend(route) {
  const req = route.request();
  const url = new URL(req.url());
  const path = url.pathname;
  const method = req.method();
  calls.push(method+' '+path);
  const ok = payload => route.fulfill({status:200,json:payload});
  const fail = () => route.fulfill({status:404,json:{message:'Mock endpoint missing: '+method+' '+path}});
  if(path==='/api/auth/me') return ok({username:'admin',role:'ADMIN'});
  if(path==='/api/chat/sessions') return ok([]);
  if(path==='/api/chat/datasets') return ok(['ads.city_orders','ads.sales']);
  if(path==='/api/mini/charts') return ok([]);
  if(path==='/api/admin/status') return ok(status);
  if(path==='/api/admin/settings') {
    if(failNextAdminSettings){
      failNextAdminSettings=false;
      return route.fulfill({status:503,json:{message:'配置服务暂时不可用'}});
    }
    return ok(settings);
  }
  if(path==='/api/admin/semantic/status') return ok({count:2,indexedModel:'text-embedding-v3',configured:true});
  if(path==='/api/admin/semantic/rebuild' && method==='POST') return ok({message:'语义索引已重建'});
  if(path==='/api/admin/tables' && method==='GET') return ok(tables);
  if(path==='/api/admin/table-allow' && method==='POST') {
    const body=req.postDataJSON();tables=tables.map(t=>t.name===body.tableName?{...t,enabled:body.enabled}:t);return ok({success:true});
  }
  if(path==='/api/admin/metrics' && method==='GET') return ok(metrics);
  if(path==='/api/admin/metrics/preview' && method==='POST')
    return ok({filename:'demo-metrics.xlsx',count:1,samples:[{metricName:'营业额',definition:'销售净额',adsTable:'ads.sales'}],notices:[]});
  if(path==='/api/admin/metrics/import' && method==='POST') {
    metrics={total:1,batches:[{id:'batch1',filename:'demo-metrics.xlsx',count:1,createdAt:'2026-11-09'}],definitions:[]};
    return ok({success:true,message:'导入成功'});
  }
  if(path==='/api/admin/metrics/template' && method==='GET')
    return route.fulfill({status:200,body:'template-stub',headers:{'content-type':'application/octet-stream'}});
  if(path.startsWith('/api/admin/metrics/batches/') && method==='DELETE') {
    metrics={total:0,batches:[],definitions:[]};return ok({success:true});
  }
  if(path==='/api/admin/users' && method==='GET') return ok(users);
  if(path==='/api/admin/users' && method==='POST') {
    const body=req.postDataJSON();users.push({username:body.username,displayName:body.displayName,role:body.role});return ok({success:true});
  }
  const userMatch=path.match(/^\/api\/admin\/users\/([A-Za-z0-9_-]+)(\/reset-password)?$/);
  if(userMatch && method==='PUT'){
    const body=req.postDataJSON();
    users=users.map(u=>u.username===userMatch[1]?{...u,displayName:body.displayName,role:body.role}:u);
    return ok({success:true,message:'用户信息已更新'});
  }
  if(userMatch && method==='POST')return ok({success:true,message:'密码已重置'});
  const settingsMatch=path.match(/^\/api\/admin\/settings\/(starrocks|ai|embedding)(\/test)?$/);
  if(settingsMatch && method==='POST')return ok({success:true,message:settingsMatch[2]?'连接测试成功':'配置已保存'});
  return fail();
}

async function visible(locator,label){
  await locator.first().waitFor({state:'visible',timeout:10000});
  assert(await locator.first().isVisible(),label+' not visible');
}
async function clickTile(page,label) {
  await page.locator('.tb-single-admin-nav button').click();
  await visible(page.locator('.tb-admin-overview-grid button').filter({hasText:label}),label+' tile');
  await page.locator('.tb-admin-overview-grid button').filter({hasText:label}).click();
  await visible(page.locator('.tb-admin-page h1'),label+' heading');
}
async function requireCall(methodPath){
  for(let i=0;i<90;i++){
    if(calls.includes(methodPath))return;
    await new Promise(resolve=>setTimeout(resolve,50));
  }
  throw new Error('Expected backend request not observed: '+methodPath+'\nObserved: '+calls.join(', '));
}
let debugPage=null;
const browser=await chromium.launch({headless:true,args:['--no-sandbox']});
try {
  const ctx=await browser.newContext({viewport:{width:1366,height:768}});
  await ctx.addInitScript(() => {
    sessionStorage.setItem('dataask_token','ui-test-token');
    sessionStorage.setItem('dataask_user',JSON.stringify({username:'admin',displayName:'系统管理员',role:'ADMIN'}));
  });
  const page=await ctx.newPage();
  debugPage=page;
  const errors=[];
  page.on('pageerror', e => errors.push(e.message));
  await page.route('**/api/**',mockBackend);
  await page.goto(base,{waitUntil:'networkidle'});

  await clickTile(page,'数据源配置');
  await visible(page.locator('input[placeholder*="jdbc:mysql"]'),'StarRocks form');
  await page.getByRole('button',{name:'测试连接'}).click();
  await page.getByRole('button',{name:'保存配置'}).click();

  await clickTile(page,'对话模型');
  await visible(page.locator('input[placeholder="https://api.example.com/v1"]'),'AI URL');
  await page.getByRole('button',{name:'测试连接'}).click();
  // Prevent accidental 401 when the operator toggles "clear stored API Key".
  const modelClear=page.locator('.tb-admin-config-fields .van-switch').first();
  await modelClear.click();
  const beforeTestCalls=calls.filter(x=>x==='POST /api/admin/settings/ai/test').length;
  await page.getByRole('button',{name:'测试连接'}).click();
  await visible(page.locator('.tb-admin-config-fields .config-feedback'),'Clear API key guidance');
  assert((await page.locator('.tb-admin-config-fields .config-feedback').innerText()).includes('已开启'),
    'The UI must explain why key-clearing makes model tests unauthenticated');
  assert.equal(calls.filter(x=>x==='POST /api/admin/settings/ai/test').length,beforeTestCalls,
    'Clear-key model test must not call provider without saved authentication');
  await modelClear.click();
  await page.getByRole('button',{name:'保存配置'}).click();

  await clickTile(page,'Embedding 模型');
  await visible(page.locator('input[placeholder="qwen3.7-text-embedding-flash"]'),'Embedding model');
  await page.getByRole('button',{name:'测试连接'}).click();
  await page.getByRole('button',{name:'保存配置'}).click();
  await page.getByRole('button',{name:'重建语义索引'}).click();

  await clickTile(page,'ADS 表授权');
  await visible(page.locator('.admin-table'),'ADS tables');
  const tableSwitch=page.locator('.admin-table .van-switch').first();
  await tableSwitch.click();
  await requireCall('POST /api/admin/table-allow');
  // Restore the authorization so subsequent tests retain their original grants.
  await tableSwitch.click();
  for(let i=0;i<80 && calls.filter(x=>x==='POST /api/admin/table-allow').length<2;i++)
    await new Promise(resolve=>setTimeout(resolve,40));
  assert.equal(tables.filter(t=>t.enabled).length,2,'Restoring table permission failed');

  await clickTile(page,'指标口径');
  await visible(page.locator('.tb-admin-metrics-page'),'Metric page');
  await page.locator('.tb-admin-metrics-page input[type=file]').setInputFiles({
    name:'demo-metrics.xlsx',mimeType:'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    buffer:Buffer.from('mocked spreadsheet')});
  await visible(page.locator('.metric-preview'),'Excel preview');
  await page.getByRole('button',{name:/确认导入/}).click();
  await requireCall('POST /api/admin/metrics/import');

  await clickTile(page,'用户管理');
  await visible(page.locator('.tb-user-row'),'User listing');
  await page.locator('.tb-user-row').filter({hasText:'alice'}).getByRole('button',{name:/编辑/}).click();
  await visible(page.locator('.tb-user-editor'),'User editor');
  await page.locator('.tb-user-editor input[placeholder="请输入姓名"]').fill('Alice Zhang');
  await page.getByRole('button',{name:'保存用户信息'}).click();
  await requireCall('PUT /api/admin/users/alice');
  await page.locator('.tb-user-row').filter({hasText:'alice'}).getByRole('button',{name:/编辑/}).click();
  await page.locator('.tb-user-reset input[type=password]').fill('new-password-123');
  await page.locator('.tb-user-reset').getByRole('button',{name:'重置密码',exact:true}).click();
  await page.getByRole('dialog').getByRole('button',{name:'确认'}).click();
  await requireCall('POST /api/admin/users/alice/reset-password');

  await clickTile(page,'配置状态');
  await visible(page.locator('.tb-config-status-list'),'Configuration status page');
  await page.getByRole('button',{name:/刷新状态/}).click();

  // Regression: an API failure must not block navigation into a module.
  await page.locator('.tb-single-admin-nav button').click();
  await visible(page.locator('.tb-admin-overview-grid'),'Admin overview');
  await page.waitForTimeout(200);
  failNextAdminSettings = true;
  await page.locator('.tb-admin-overview-grid button').filter({hasText:'数据源配置'}).click();
  await visible(page.locator('.tb-admin-load-error'),'Configuration load error');
  await page.getByRole('button',{name:'重试加载'}).click();
  await page.locator('.tb-admin-load-error').waitFor({state:'hidden',timeout:10000});
  await visible(page.locator('input[placeholder*="jdbc:mysql"]'),'Configuration retry success');

  await mkdir('artifacts',{recursive:true});
  await page.screenshot({path:'artifacts/admin-desktop.png',fullPage:true});
  assert.equal(errors.length,0,'Unexpected browser runtime errors: '+errors.join(' | '));
  await ctx.close();

  const ctxUser=await browser.newContext({viewport:{width:1366,height:768}});
  await ctxUser.addInitScript(()=>{
    sessionStorage.setItem('dataask_token','other-token');
    sessionStorage.setItem('dataask_user',JSON.stringify({username:'alice',displayName:'Alice',role:'USER'}));
  });
  const userPage=await ctxUser.newPage();
  await userPage.route('**/api/**',async route=>{
    const url=new URL(route.request().url());
    if(url.pathname==='/api/auth/me')return route.fulfill({json:{username:'alice',role:'USER'}});
    if(url.pathname==='/api/chat/sessions'||url.pathname==='/api/mini/charts'||url.pathname==='/api/chat/datasets')
      return route.fulfill({json:[]});
    return route.fulfill({status:403,json:{message:'Forbidden'}});
  });
  await userPage.goto(base,{waitUntil:'networkidle'});
  assert.equal(await userPage.locator('.tb-single-admin-nav').count(),0,'Regular users must not see admin modules');
  await ctxUser.close();
  console.log('PASS: seven admin tiles, real HTTP requests, role restrictions, editor and password reset, desktop browser');
} catch(e) {
  if(debugPage && !debugPage.isClosed()) {
    await mkdir('artifacts',{recursive:true});
    await debugPage.screenshot({path:'artifacts/admin-desktop-failure.png',fullPage:true}).catch(()=>{});
    console.error('Browser UI state at failure:',await debugPage.locator('body').innerText().then(x=>x.slice(0,3500)).catch(()=>'')); 
  }
  throw e;
} finally {
  await browser.close();
}

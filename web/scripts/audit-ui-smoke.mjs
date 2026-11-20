import {chromium} from 'playwright';
import assert from 'node:assert/strict';
import {mkdir} from 'node:fs/promises';
const base = process.env.DATAASK_UI_URL || 'http://127.0.0.1:4173';
const calls = [], deletions = [], errors = [];
let failNext = false;
let records = Array.from({length:26},(_,i)=>({
  id:`00000000-0000-0000-0000-${String(i+1).padStart(12,'0')}`,
  requestId:`10000000-0000-0000-0000-${String(i+1).padStart(12,'0')}`,
  username:i%2?'bob':'alice',module:'CHAT',action:'智能问数',target:'订单排行',detail:'按城市查看订单量',method:'POST',path:'/api/chat/ask',
  occurredAt:'2026-11-10T01:30:00Z',status:'SUCCESS',httpStatus:200,durationMs:160,source:'CHAT',question:'昨天各城市订单量',rowCount:5
}));
const sql = 'SELECT city, SUM(order_cnt) AS total FROM ads.orders GROUP BY city';
async function backend(route) {
  const request=route.request(), url=new URL(request.url()), path=url.pathname, params=Object.fromEntries(url.searchParams);
  calls.push({path,params,method:request.method()});
  const ok=json=>route.fulfill({status:200,json});
  if(path==='/api/auth/me') return ok({username:'admin',role:'ADMIN',displayName:'系统管理员'});
  if(path==='/api/admin/status') return ok({});
  if(path==='/api/chat/sessions'||path==='/api/chat/datasets'||path==='/api/mini/charts') return ok([]);
  if(path==='/api/admin/audit/options') return ok({today:'2026-11-10',usernames:['admin','alice','bob']});
  if(path==='/api/admin/audit/logs'||path==='/api/admin/audit/queries') {
    if(failNext) {failNext=false;return route.fulfill({status:503,json:{message:'审计数据暂时不可用'}})}
    let items=records.filter(r=>(!params.username||r.username===params.username)&&(!params.keyword||r.question.includes(params.keyword)));
    const page=Number(params.page||1),size=Number(params.pageSize||20),total=items.length;
    items=items.slice((page-1)*size,page*size);
    return ok({items,total,page,pageSize:size});
  }
  if(path.endsWith('/batch-delete')) {
    const ids=request.postDataJSON().ids;deletions.push(ids);records=records.filter(r=>!ids.includes(r.id));
    return ok({deleted:ids.length});
  }
  if(path.startsWith('/api/admin/audit/queries/')) return ok({sql,sessionId:'session-1'});
  return ok({success:true});
}
const browser=await chromium.launch({headless:true,args:['--no-sandbox']});
try {
  const context=await browser.newContext({viewport:{width:1440,height:1000},locale:'zh-CN'});
  await context.addInitScript(()=>{
    sessionStorage.setItem('dataask_token','test-admin');
    sessionStorage.setItem('dataask_user',JSON.stringify({username:'admin',role:'ADMIN',displayName:'系统管理员'}));
  });
  const page=await context.newPage();page.on('pageerror',e=>errors.push(e.message));
  await page.route('**/api/**',backend);await page.goto(base,{waitUntil:'networkidle'});
  await page.locator('.da-account-trigger').click();await page.getByRole('menuitem',{name:'系统管理'}).click();
  await page.locator('.tb-admin-overview-grid button').filter({hasText:'审计日志'}).click();
  const panel=page.getByRole('region',{name:'管理员审计日志'});
  await page.waitForFunction(()=>document.querySelector('.audit-toolbar')?.textContent.includes('共 26 条'));
  const rangeSelect=panel.getByLabel('时间范围');
  assert.equal(await rangeSelect.inputValue(),'today','默认时间范围为今天');
  assert.equal(calls.find(c=>c.path==='/api/admin/audit/logs').params.dateFrom,'2026-11-10');
  assert.equal(await panel.locator('tbody tr').count(),20);
  assert.match(await panel.locator('tbody tr').first().innerText(),/09:30:00/);
  await panel.getByRole('button',{name:'下一页',exact:true}).click();
  await page.waitForFunction(()=>document.querySelector('.audit-pagination')?.textContent.includes('第 2 / 2 页'));
  assert.equal(await panel.locator('tbody tr').count(),6);
  await panel.getByLabel('操作账号',{exact:true}).fill('alice');
  await panel.getByLabel('关键词',{exact:true}).fill('订单');
  await panel.getByRole('button',{name:'查询',exact:true}).click();
  await page.waitForFunction(()=>document.querySelector('.audit-toolbar')?.textContent.includes('共 13 条'));
  assert.match(await panel.locator('.audit-pagination').innerText(),/第 1 \/ 1 页/);
  assert.equal(calls.filter(c=>c.path==='/api/admin/audit/logs').at(-1).params.username,'alice');
  // 时间范围一键切换立即查询（阿里云日志页行为），日期参数由前端按今天推算
  await rangeSelect.selectOption('7d');
  await page.waitForFunction(()=>!document.querySelector('.audit-table-wrap')?.matches('[aria-busy="true"]'));
  assert.equal(calls.filter(c=>c.path==='/api/admin/audit/logs').at(-1).params.dateFrom,'2026-11-04');
  assert.equal(calls.filter(c=>c.path==='/api/admin/audit/logs').at(-1).params.dateTo,'2026-11-10');
  await rangeSelect.selectOption('yesterday');
  await page.waitForFunction(()=>!document.querySelector('.audit-table-wrap')?.matches('[aria-busy="true"]'));
  assert.equal(calls.filter(c=>c.path==='/api/admin/audit/logs').at(-1).params.dateFrom,'2026-11-09');
  assert.equal(calls.filter(c=>c.path==='/api/admin/audit/logs').at(-1).params.dateTo,'2026-11-09');
  await rangeSelect.selectOption('custom');
  await panel.getByLabel('开始日期',{exact:true}).fill('2026-11-05');
  await page.waitForFunction(()=>!document.querySelector('.audit-table-wrap')?.matches('[aria-busy="true"]'));
  assert.equal(calls.filter(c=>c.path==='/api/admin/audit/logs').at(-1).params.dateFrom,'2026-11-05');
  await panel.getByLabel('结束日期',{exact:true}).fill('2026-11-09');
  await page.waitForFunction(()=>!document.querySelector('.audit-table-wrap')?.matches('[aria-busy="true"]'));
  assert.equal(calls.filter(c=>c.path==='/api/admin/audit/logs').at(-1).params.dateTo,'2026-11-09');
  await panel.getByRole('button',{name:'重置',exact:true}).click();
  await page.waitForFunction(()=>document.querySelector('.audit-toolbar')?.textContent.includes('共 26 条'));
  assert.equal(await rangeSelect.inputValue(),'today','重置回到今天');
  await panel.getByRole('tab',{name:'SQL 查询记录',exact:true}).click();
  await panel.getByRole('button',{name:'查看 SQL',exact:true}).first().waitFor();
  assert.equal(calls.filter(c=>/^\/api\/admin\/audit\/queries\//.test(c.path)).length,0,'SQL must load only on demand');
  await panel.getByRole('button',{name:'查看 SQL',exact:true}).first().click();
  await panel.locator('.audit-sql pre').waitFor();assert.equal(await panel.locator('.audit-sql pre').innerText(),sql);
  await panel.getByRole('button',{name:'收起 SQL'}).click();
  await panel.getByRole('checkbox',{name:'选择本页全部记录'}).check();
  await panel.getByRole('button',{name:'批量删除',exact:true}).click();
  await page.locator('.van-dialog__cancel').click();assert.equal(deletions.length,0,'Cancel must not delete');
  await panel.getByRole('button',{name:'批量删除',exact:true}).click();
  await page.locator('.van-dialog__confirm').click();
  await page.waitForFunction(()=>document.querySelector('.audit-toolbar')?.textContent.includes('共 6 条'));
  assert.equal(deletions[0].length,20);assert.deepEqual(deletions[0],Array.from({length:20},(_,i)=>`00000000-0000-0000-0000-${String(i+1).padStart(12,'0')}`));
  assert.equal(await panel.getByRole('button',{name:'批量删除',exact:true}).isDisabled(),true);
  await rangeSelect.selectOption('all');
  await page.waitForFunction(()=>!document.querySelector('.audit-table-wrap')?.matches('[aria-busy="true"]'));
  assert.equal(calls.filter(c=>c.path==='/api/admin/audit/queries').at(-1).params.allDates,'true');
  assert.equal(calls.filter(c=>c.path==='/api/admin/audit/queries').at(-1).params.dateFrom,'');
  failNext=true;await panel.getByRole('button',{name:'查询',exact:true}).click();
  await panel.getByRole('alert').waitFor();assert.match(await panel.getByRole('alert').innerText(),/审计数据暂时不可用/);
  await panel.getByRole('button',{name:'重试',exact:true}).click();
  await page.waitForFunction(()=>!document.querySelector('.audit-error'));
  await panel.getByRole('button',{name:'重置'}).click();
  await panel.getByRole('button',{name:'查看 SQL',exact:true}).first().click();
  await panel.locator('.audit-sql pre').waitFor();
  await page.locator('.van-toast').waitFor({state:'hidden'});
  await mkdir('artifacts',{recursive:true});await page.screenshot({path:'artifacts/admin-audit-light.png',fullPage:true});
  await page.locator('.da-account-trigger').click();await page.getByRole('menuitem',{name:'个性化',exact:true}).click();
  await page.getByRole('button',{name:/深色 黑色界面/}).click();
  await page.locator('.da-preferences-popup .van-popup__close-icon').click();
  await page.locator('.da-preferences-popup').waitFor({state:'hidden'});
  await page.screenshot({path:'artifacts/admin-audit-dark.png',fullPage:true});
  for (const width of [1280,1024]) {
    await page.setViewportSize({width,height:1000});
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false,'No page-level horizontal overflow');
    await page.screenshot({path:`artifacts/admin-audit-dark-${width}.png`,fullPage:true});
  }
  assert.deepEqual(errors,[]);
  await context.close();
  // Server-verified ordinary role wins over a forged administrator cached in sessionStorage.
  const userContext=await browser.newContext({viewport:{width:1280,height:900}});
  await userContext.addInitScript(()=>{sessionStorage.setItem('dataask_token','test-alice');sessionStorage.setItem('dataask_user',JSON.stringify({username:'alice',role:'ADMIN'}))});
  const userPage=await userContext.newPage();
  await userPage.route('**/api/**',route=>new URL(route.request().url()).pathname==='/api/auth/me'?route.fulfill({json:{username:'alice',role:'USER'}}):backend(route));
  await userPage.goto(base,{waitUntil:'networkidle'});await userPage.locator('.da-account-trigger').click();assert.equal(await userPage.getByRole('menuitem',{name:'系统管理'}).count(),0);
  await userContext.close();
  console.log('Audit UI: Aliyun-style time range presets + custom range, today default, Shanghai time, combined search, pagination, admin role, on-demand SQL, batch-delete confirmation/cancel, all dates, retry and screenshots passed.');
} finally {await browser.close()}

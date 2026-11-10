import assert from 'node:assert/strict';
import { chromium } from 'playwright';
import { mkdir } from 'node:fs/promises';

const base=process.env.DATAASK_UI_URL || 'http://127.0.0.1:4173';
const calls=[];
const chart={
  id:'chart-001',ownerUsername:'admin',sourceMessageId:'msg-001',
  question:'请帮我查看昨天各城市订单量排行？',
  title:'昨日各城市订单量排行',chartType:'bar',
  shareScope:'PRIVATE',dataset:'',updatedAt:'2026-11-10 10:30'
};
const saved={
  id:'msg-001',question:chart.question,sessionId:'original-session',
  title:'城市订单量',summary:'从 ADS 读取真实数据',
  chartType:'bar',columns:['city','order_cnt'],
  columnLabels:{city:'城市',order_cnt:'订单量'},
  rows:[{city:'西安市',order_cnt:188},{city:'苏州市',order_cnt:156},{city:'武汉市',order_cnt:105}],
  unsupported:false,followUps:[],metricLabel:'订单量',timeLabel:'昨日',dataset:''
};
const refreshed={...saved,id:'transient-chart-answer',sessionId:'no-history',
  rows:[{city:'西安市',order_cnt:259},{city:'苏州市',order_cnt:175},{city:'武汉市',order_cnt:113}]};
async function mock(route){
  const req=route.request(),u=new URL(req.url()),path=u.pathname,method=req.method();
  calls.push(method+' '+path);
  const reply=data=>route.fulfill({status:200,json:data});
  if(path==='/api/auth/me')return reply({username:'admin',role:'ADMIN',displayName:'系统管理员'});
  if(path==='/api/mini/bootstrap')return reply({user:{username:'admin',display_name:'系统管理员',role:'ADMIN'},suggestions:[]});
  if(path==='/api/chat/sessions')return reply([]);
  if(path==='/api/chat/datasets')return reply(['ads.orders']);
  if(path==='/api/mini/charts')return reply(u.searchParams.get('view')==='shared'?[]:[chart]);
  if(path==='/api/mini/charts/chart-001/view')return reply({chart,answer:saved,cached:true});
  if(path==='/api/mini/charts/chart-001/refresh'&&method==='POST')return reply({chart,answer:refreshed,cached:false});
  if(path==='/api/chat/ask')return route.fulfill({status:501,json:{message:'Chart detail wrongly triggered a chat request'}});
  return route.fulfill({status:404,json:{message:'Not mocked: '+method+' '+path}});
}
const browser=await chromium.launch({headless:true,args:['--no-sandbox']});
try{
  await mkdir('artifacts',{recursive:true});
  const pc=await browser.newContext({viewport:{width:1440,height:900}});
  await pc.addInitScript(()=>{
    sessionStorage.setItem('dataask_token','demo-token');
    sessionStorage.setItem('dataask_user',JSON.stringify({
      username:'admin',displayName:'系统管理员',role:'ADMIN'}));
  });
  const page=await pc.newPage();const errors=[];
  page.on('pageerror',err=>errors.push(err.message));
  await page.route('**/api/**',mock);
  await page.goto(base,{waitUntil:'networkidle'});
  await page.locator('.desktop-main-nav button').filter({hasText:'我的图表'}).click();
  await page.locator('.tb-chart-open').first().click();
  await page.locator('.da-chart-detail h1').waitFor({state:'visible'});
  assert((await page.locator('.da-chart-detail h1').innerText()).includes('昨日各城市订单量排行'));
  assert.equal(await page.locator('.conversation').count(),0,
    'Opening a saved chart must not navigate to any conversation');
  await page.waitForFunction(()=>document.querySelector('.da-chart-current')!==null,{timeout:10000}).catch(()=>{});
  await page.locator('.da-chart-view-buttons button').filter({hasText:'表格'}).click();
  await page.getByText('259',{exact:true}).first().waitFor({state:'visible'});
  assert(calls.includes('POST /api/mini/charts/chart-001/refresh'),
    'Saved chart must refresh live data using dedicated endpoint');
  assert(!calls.some(c=>c==='POST /api/chat/ask'),'Chart detail should not create chat sessions');
  await page.screenshot({path:'artifacts/chart-detail-pc.png',fullPage:true});
  assert.equal(errors.length,0,errors.join(' | '));
  await pc.close();

  const mobile=await browser.newContext({viewport:{width:390,height:844},isMobile:true,deviceScaleFactor:3,hasTouch:true});
  await mobile.addInitScript(()=>sessionStorage.setItem('dataask_token','demo-token'));
  const mp=await mobile.newPage();const mErrors=[];
  mp.on('pageerror',e=>mErrors.push(e.message));
  await mp.route('**/api/**',mock);
  await mp.goto(base+'/miniapp/index.html',{waitUntil:'networkidle'});
  await mp.locator('.tabbar [data-action="tab"][data-view="charts"]').click();
  await mp.locator('[data-action="open-chart"][data-id="chart-001"]').click();
  await mp.locator('.chart-detail-page h2').waitFor({state:'visible'});
  await mp.waitForFunction(()=>document.querySelector('.chart-detail-current')!==null,{timeout:10000}).catch(()=>{});
  assert((await mp.locator('.chart-detail-page h2').innerText()).includes('昨日各城市订单量排行'));
  assert.equal(await mp.locator('[data-action="mic"]').count(),0,'Microphone must not exist');
  assert.equal(await mp.locator('[data-action="config"]').count(),0,'Model settings must not exist');
  await mp.locator('.chart-detail-card [data-action="chart-mode"][data-mode="table"]').click();
  await mp.getByText('259',{exact:true}).first().waitFor({state:'visible'});
  await mp.locator('.chart-detail-card [data-action="chart-mode"][data-mode="chart"]').click();
  await mp.locator('.chart-detail-card .chart-svg').waitFor({state:'visible'});
  await mp.screenshot({path:'artifacts/chart-detail-mobile.png',fullPage:true});
  assert.equal(mErrors.length,0,mErrors.join(' | '));
  await mobile.close();
  assert(!calls.some(c=>c==='POST /api/chat/ask'),'Mobile chart detail must not call chat');
  console.log('Chart detail PC/mobile: real view + refresh, no chat navigation, chart/table, no voice/config/download OK');
}finally{await browser.close()}

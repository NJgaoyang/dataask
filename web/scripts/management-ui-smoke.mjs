import assert from 'node:assert/strict';
import {mkdir} from 'node:fs/promises';
import {chromium} from 'playwright';
import {analysisAnswer} from './analysis-fixture.mjs';

const base=process.env.DATAASK_UI_URL || 'http://127.0.0.1:4175';
const out='artifacts/management';
await mkdir(out,{recursive:true});
const calls=[],errors=[];
let sessions=Array.from({length:65},(_,i)=>({id:'s'+i,title:'会话问题 '+(i+1),updatedAt:'2026-11-10T09:30:00'}));
const charts=Array.from({length:24},(_,i)=>({id:'c'+i,title:'城市订单分析 '+(i+1),question:'各城市订单量 '+(i+1),ownerUsername:'admin',chartType:i%2?'bar':'line',updatedAt:'2026-11-10T09:30:00'}));
let sources=Array.from({length:21},(_,i)=>({id:'ds'+i,name:'数据源 '+(i+1),jdbcUrl:`jdbc:mysql://fe${i+1}:9030/ads`,schema:'ads',username:'readonly',description:'',active:i===0}));
let users=Array.from({length:23},(_,i)=>({username:i?'user'+i:'admin',displayName:i?'用户 '+i:'系统管理员',role:i?'USER':'ADMIN',createdAt:'2026-11-01T00:00:00'}));
function paged(list,url){const q=(url.searchParams.get('keyword') || '').toLowerCase(),page=Number(url.searchParams.get('page') || 1),size=Number(url.searchParams.get('pageSize') || 10);let rows=list.filter(r=>Object.values(r).some(v=>String(v).toLowerCase().includes(q)));const type=url.searchParams.get('type');if(type&&type!=='ALL')rows=rows.filter(r=>r.chartType===type);return {items:rows.slice((page-1)*size,page*size),total:rows.length,page,pageSize:size}}
async function backend(route){
  const req=route.request(),u=new URL(req.url()),p=u.pathname,m=req.method(),ok=json=>route.fulfill({json});
  calls.push({p,m,query:Object.fromEntries(u.searchParams),body:req.postData()});
  if(p==='/api/auth/me')return ok({username:'admin',displayName:'系统管理员',role:'ADMIN'});
  if(p==='/api/chat/sessions')return ok(sessions.slice(0,50));
  if(p==='/api/chat/sessions/page')return ok(paged(sessions,u));
  const sd=p.match(/^\/api\/chat\/sessions\/([^/]+)$/);
  if(sd&&m==='DELETE'){sessions=sessions.filter(x=>x.id!==sd[1]);return ok({deleted:1})}
  if(p==='/api/chat/sessions/batch-delete'&&m==='POST'){const ids=req.postDataJSON().ids;sessions=sessions.filter(x=>!ids.includes(x.id));return ok({deleted:ids.length})}
  if(p==='/api/chat/datasets')return ok(['ads.orders']);
  if(p==='/api/chat/ask')return ok({...analysisAnswer,question:req.postDataJSON().question,chartType:'table',rows:Array.from({length:23},(_,i)=>({city:'城市 '+(i+1),order_cnt:i+100}))});
  if(p==='/api/mini/charts')return ok(u.searchParams.get('view')==='shared'?[]:charts);
  if(p==='/api/mini/charts/page')return ok(paged(u.searchParams.get('view')==='shared'?[]:charts,u));
  if(p==='/api/admin/status')return ok({starrocksConfigured:true,aiConfigured:true,embeddingConfigured:true});
  if(p==='/api/admin/settings')return ok({starrocks:{schema:'ads'},ai:{baseUrl:'https://model.example.com/v1',model:'qwen-plus',apiKeySet:true},embedding:{baseUrl:'https://embedding.example.com/v1',model:'embedding',enabled:true,dimensions:1024}});
  if(p==='/api/admin/users'&&m==='GET')return ok(users);
  if(p==='/api/admin/users/page')return ok(paged(users,u));
  if(p==='/api/admin/users'&&m==='POST'){const body=req.postDataJSON();users=[...users,{...body,password:undefined}];return ok({success:true})}
  if(p.match(/^\/api\/admin\/users\/[^/]+$/)&&m==='PUT'){const username=p.split('/').at(-1);users=users.map(x=>x.username===username?{...x,...req.postDataJSON()}:x);return ok({success:true})}
  if(p.endsWith('/reset-password'))return ok({success:true});
  if(p==='/api/admin/datasources'&&m==='GET')return ok(paged(sources,u));
  if(p==='/api/admin/datasources'&&m==='POST'){sources=[{...req.postDataJSON(),password:undefined,id:'new-source',active:false},...sources];return ok({success:true,id:'new-source'})}
  const ds=p.match(/^\/api\/admin\/datasources\/([^/]+)(?:\/(test|activate))?$/);
  if(ds){if(ds[2]==='activate')sources=sources.map(x=>({...x,active:x.id===ds[1]}));else if(m==='PUT')sources=sources.map(x=>x.id===ds[1]?{...x,...req.postDataJSON(),password:undefined}:x);else if(m==='DELETE')sources=sources.filter(x=>x.id!==ds[1]);return ok({success:true})}
  if(p==='/api/admin/datasources/test')return ok({success:true});
  if(p==='/api/admin/tables')return ok(Array.from({length:23},(_,i)=>({name:'ads.orders_'+i,enabled:true,columns:[]})));
  if(p==='/api/admin/semantic/status')return ok({count:0});
  if(p==='/api/admin/metrics')return ok({total:0,batches:[],metrics:[]});
  if(p.includes('/metrics/')&&p.endsWith('/page'))return ok({items:[],total:0});
  return ok({success:true});
}
const browser=await chromium.launch({headless:true,args:['--no-sandbox']});
async function tile(page,label){
  if(!await page.locator('.da-admin-workspace-popup:visible').count()){
    await page.locator('.da-account-trigger').click();
    await page.getByRole('menuitem',{name:'系统管理'}).click();
  }
  await page.locator('.da-admin-workspace-nav button').filter({hasText:label}).click();
  await page.locator('.tb-admin-loading').waitFor({state:'hidden'});
}
async function snap(page,name){
  await page.evaluate(async()=>{
    await new Promise(requestAnimationFrame);await new Promise(requestAnimationFrame);
    await Promise.all(document.getAnimations().filter(a=>a.effect?.getTiming().iterations!==Infinity).map(a=>a.finished.catch(()=>{})));
  });
  await page.screenshot({path:`${out}/${name}.png`,animations:'disabled',style:'.van-toast{visibility:hidden!important}'});
}
try{
  const ctx=await browser.newContext({viewport:{width:1440,height:1000},locale:'zh-CN'});
  await ctx.addInitScript(()=>{sessionStorage.setItem('dataask_token','management-test');sessionStorage.setItem('dataask_user',JSON.stringify({username:'admin',displayName:'系统管理员',role:'ADMIN'}))});
  const page=await ctx.newPage();page.on('pageerror',e=>errors.push(e.message));await page.route('**/api/**',backend);await page.goto(base,{waitUntil:'networkidle'});
  assert.equal(await page.locator('.desktop-main-nav button').filter({hasText:'工作台'}).count(),0,
    'Smart Q&A must not expose a separate workbench');
  assert.match(await page.locator('.desktop-main-nav button').filter({hasText:'新建任务'}).getAttribute('class'),/active/,
    'New task is the authenticated default Q&A entry');
  assert.equal(await page.locator('.ds-home-intro,.tb-page-heading,.tb-admin-heading,.da-chart-detail-heading').count(),0,
    'Global PC workspaces must not render extra title/description banners');
  const composerHeight=(await page.locator('.da-home-question-box').boundingBox()).height;
  assert(composerHeight>=120,'Smart Q&A landing input must remain comfortably tappable');await snap(page,'smart-qa-default');
  assert.equal(await page.locator('.sidebar-new').count(),0,'Sidebar must not expose a separate new-question button');
  await page.locator('.desktop-main-nav button').filter({hasText:'新建任务'}).click();
  assert((await page.locator('.da-home-question-box').boundingBox()).height>=120,'Smart Q&A navigation returns to the landing page');
  await tile(page,'数据源配置');const sourceList=page.getByRole('region',{name:'数据源',exact:true});
  await sourceList.waitFor();
  const sourceOuter=await page.locator('.tb-admin-page').boundingBox();
  const sourceCard=await page.locator('.tb-admin-source-page').boundingBox();
  assert(sourceOuter && sourceCard && Math.abs(sourceOuter.x-sourceCard.x)<2 &&
    Math.abs(sourceOuter.width-sourceCard.width)<18,'Data source page must be full width without outer card gaps');
  await page.locator('.da-list-table tbody tr').filter({hasText:'数据源 1'}).first().waitFor();
  assert.equal(await sourceList.locator('tbody tr').count(),10);
  const controlHeights=await sourceList.locator('.da-list-toolbar button').evaluateAll(items=>items.map(el=>Math.round(el.getBoundingClientRect().height)));
  assert(controlHeights.every(h=>h===32),'Console list actions should all have 32px height');
  const filterHeight=await sourceList.getByRole('textbox',{name:'搜索数据源',exact:true}).evaluate(el=>Math.round(el.getBoundingClientRect().height));
  assert.equal(filterHeight,32,'List search inputs should align with buttons');
  await sourceList.getByRole('button',{name:'下一页',exact:true}).click();
  await sourceList.locator('tbody tr').filter({hasText:'数据源 11'}).waitFor();assert.equal(await sourceList.locator('tbody tr').first().locator('td').first().innerText(),'11');
  await snap(page,'sources-page-2');
  await sourceList.getByRole('button',{name:'新增数据源',exact:true}).click();let modal=page.locator('.da-crud-modal:visible');
  assert.equal(await modal.getByLabel('名称',{exact:true}).inputValue(),'');await modal.getByLabel('名称',{exact:true}).fill('经营数据仓库');await modal.getByLabel('JDBC 地址').fill('jdbc:mysql://warehouse:9030/ads');await modal.getByLabel('用户名',{exact:true}).fill('reader');await modal.getByLabel('密码',{exact:true}).fill('private-secret-123');await snap(page,'source-create');
  await modal.getByRole('button',{name:'测试连接',exact:true}).click();await modal.getByRole('status').waitFor();
  await modal.getByRole('button',{name:'保存',exact:true}).click();await modal.waitFor({state:'hidden'});
  assert(sources.find(x=>x.id==='ds0').active,'Saving must not change active source');
  await sourceList.getByRole('textbox',{name:'搜索数据源',exact:true}).fill('经营数据仓库');await sourceList.getByRole('button',{name:'查询',exact:true}).click();
  const source=sourceList.locator('tbody tr').filter({hasText:'经营数据仓库'});await source.waitFor();assert.equal(await source.locator('td').first().innerText(),'1','Search resets page');
  await source.getByRole('button',{name:'编辑',exact:true}).click();assert.equal(await modal.getByLabel('密码',{exact:true}).inputValue(),'','Passwords never return to browser');await snap(page,'source-edit');await modal.getByRole('button',{name:'取消',exact:true}).click();
  await source.getByRole('button',{name:'启用',exact:true}).click();await page.locator('.van-dialog').getByRole('button',{name:'取消',exact:true}).click();assert(!sources.find(x=>x.id==='new-source').active);
  await source.getByRole('button',{name:'启用',exact:true}).click();await page.locator('.van-dialog').getByRole('button',{name:'确认',exact:true}).click();await source.getByText('当前启用',{exact:true}).waitFor();assert.equal(await source.getByRole('button',{name:'删除',exact:true}).isDisabled(),true);await snap(page,'source-active');
  await sourceList.getByRole('button',{name:'重置',exact:true}).click();
  const inactive=sourceList.getByRole('row').filter({has:page.getByRole('cell',{name:'数据源 1',exact:true})});await inactive.waitFor();
  await inactive.getByRole('button',{name:'删除',exact:true}).click();await page.locator('.van-dialog').getByRole('button',{name:'取消',exact:true}).click();assert(sources.some(x=>x.id==='ds0'));
  await inactive.getByRole('button',{name:'删除',exact:true}).click();await page.locator('.van-dialog').getByRole('button',{name:'确认',exact:true}).click();await inactive.waitFor({state:'hidden'});assert(!sources.some(x=>x.id==='ds0'));
  await tile(page,'用户管理');const userList=page.getByRole('region',{name:'用户',exact:true});await userList.locator('tbody tr').filter({hasText:'user1'}).first().waitFor();
  await userList.getByRole('button',{name:'下一页',exact:true}).click();await userList.locator('tbody tr').filter({hasText:'user10'}).waitFor();assert.equal(await userList.locator('tbody tr').first().locator('td').first().innerText(),'11');await snap(page,'users-page-2');
  await userList.getByRole('button',{name:'新增用户',exact:true}).click();await modal.getByLabel('账号',{exact:true}).fill('new_user');await modal.getByLabel('姓名',{exact:true}).fill('测试用户');await modal.getByLabel('初始密码',{exact:true}).fill('strong-password-123');await snap(page,'user-create');await modal.getByRole('button',{name:'保存',exact:true}).click();await modal.waitFor({state:'hidden'});
  await userList.getByRole('textbox',{name:'搜索用户',exact:true}).fill('new_user');await userList.getByRole('button',{name:'查询',exact:true}).click();const newUser=userList.locator('tbody tr').filter({hasText:'new_user'});await newUser.waitFor();await newUser.getByRole('button',{name:'编辑',exact:true}).click();assert(await modal.getByLabel('账号',{exact:true}).isDisabled());await modal.getByLabel('姓名',{exact:true}).fill('已编辑用户');await modal.getByRole('button',{name:'保存',exact:true}).click();await modal.waitFor({state:'hidden'});await newUser.getByText('已编辑用户',{exact:true}).waitFor();
  await newUser.getByRole('button',{name:'重置密码',exact:true}).click();await modal.getByLabel('新密码',{exact:true}).fill('changed-password-123');await snap(page,'user-reset');await modal.getByRole('button',{name:'保存',exact:true}).click();await page.locator('.van-dialog').getByRole('button',{name:'确认',exact:true}).click();await modal.waitFor({state:'hidden'});
  await page.getByRole('button',{name:'返回 DataAsk',exact:true}).click();await page.locator('.da-admin-workspace-popup').waitFor({state:'hidden'});
  await page.locator('.desktop-main-nav button').filter({hasText:'历史会话'}).click();const history=page.getByRole('region',{name:'历史会话',exact:true});
  const historyPage=await page.locator('.tb-content-page').boundingBox();
  const historyList=await history.boundingBox();
  assert(historyPage && historyList && Math.abs(historyPage.x-historyList.x)<2 &&
    Math.abs(historyPage.width-historyList.width)<18,'History page must use full content width');await history.getByText('共 65 条',{exact:true}).waitFor();for(let i=0;i<6;i++){await history.getByRole('button',{name:'下一页',exact:true}).click();await history.getByText(`第 ${i+2} / 7 页`,{exact:true}).waitFor()}assert.equal(await history.locator('tbody tr').count(),5);assert.equal(await history.locator('tbody tr').first().locator('td').nth(1).innerText(),'61');await snap(page,'history-last-page');
  assert.equal(await history.locator('.da-history-batch-delete').isDisabled(),true);
  await history.locator('tbody tr').nth(0).getByRole('checkbox').check();await history.locator('tbody tr').nth(1).getByRole('checkbox').check();
  await history.getByRole('button',{name:/批量删除（2）/}).click();await page.locator('.van-dialog').getByRole('button',{name:'取消',exact:true}).click();assert.equal(await history.locator('tbody tr').count(),5);
  await history.getByRole('button',{name:/批量删除（2）/}).click();await page.locator('.van-dialog').getByRole('button',{name:'确认',exact:true}).click();
  await history.getByText('共 63 条',{exact:true}).waitFor();assert.equal(await history.locator('tbody tr').count(),3);assert.equal(await history.locator('.da-history-batch-delete').isDisabled(),true);
  await history.locator('tbody tr').first().getByRole('button',{name:'删除',exact:true}).click();await page.locator('.van-dialog').getByRole('button',{name:'取消',exact:true}).click();assert.equal(await history.locator('tbody tr').count(),3);
  await history.locator('tbody tr').first().getByRole('button',{name:'删除',exact:true}).click();await page.locator('.van-dialog').getByRole('button',{name:'确认',exact:true}).click();
  await history.getByText('共 62 条',{exact:true}).waitFor();assert.equal(await history.locator('tbody tr').count(),2);await snap(page,'history-after-delete');
  await page.locator('.desktop-main-nav button').filter({hasText:'我的图表'}).click();const mine=page.getByRole('region',{name:'我的图表',exact:true});
  const chartPage=await page.locator('.tb-charts-page').boundingBox();
  assert.equal(await page.locator('.tb-chart-tabs button').count(),2,'Charts workspace has mine/shared tabs');
  await page.locator('.tb-chart-tabs button').filter({hasText:'共享给我的'}).click();
  const workspace=await page.locator('.tb-chart-workspace').boundingBox();
  assert(chartPage && workspace && Math.abs(chartPage.x-workspace.x)<2 &&
    Math.abs(chartPage.width-workspace.width)<18,'Chart workspace must have no outer gutters');await mine.getByText('共 24 条',{exact:true}).waitFor();await mine.getByRole('button',{name:'下一页',exact:true}).click();await mine.getByText('第 2 / 3 页',{exact:true}).waitFor();assert.equal(await mine.locator('tbody tr').first().locator('td').first().innerText(),'11');await snap(page,'charts-page-2');
  const beforeFilter=calls.filter(c=>c.p==='/api/mini/charts/page').length;
  await page.getByRole('combobox',{name:'图表类型筛选',exact:true}).selectOption('line');
  assert.equal(calls.filter(c=>c.p==='/api/mini/charts/page').length,beforeFilter,'Draft filters should not query until 查询 is clicked');
  await page.locator('.tb-chart-toolbar').getByRole('button',{name:'查询',exact:true}).click();
  await mine.getByText('共 12 条',{exact:true}).waitFor();
  await mine.getByText('第 1 / 2 页',{exact:true}).waitFor();
  assert.equal(await mine.locator('tbody tr').first().locator('td').first().innerText(),'1');
  await page.locator('.tb-chart-toolbar').getByRole('button',{name:'重置',exact:true}).click();
  await mine.getByText('共 24 条',{exact:true}).waitFor();
  await page.locator('.desktop-main-nav button').filter({hasText:'新建任务'}).click();await page.locator('.da-home-question-box textarea').fill('昨天各城市的订单量');await page.locator('.da-home-question-box textarea').press('Enter');
  const answer=page.locator('.answer-card');await answer.getByText('共 23 条',{exact:true}).waitFor();await answer.getByRole('button',{name:'下一页',exact:true}).click();assert.equal(await answer.locator('.result-table tbody tr').first().locator('td').first().innerText(),'11');await snap(page,'query-results-page-2');
  await tile(page,'ADS 表授权');await page.getByRole('button',{name:'下一页',exact:true}).click();await page.getByText('第 2 / 3 页',{exact:true}).waitFor();assert.equal(await page.locator('.admin-table .da-sequence').first().innerText(),'11');
  await tile(page,'数据源配置');await page.getByRole('button',{name:'新增数据源',exact:true}).click();
  await page.evaluate(()=>document.querySelector('.consumer-pc').setAttribute('data-theme','dark'));await snap(page,'source-create-dark');await modal.getByRole('button',{name:'取消',exact:true}).click();
  for(const width of [1024,1280,1920]){await page.setViewportSize({width,height:900});assert(await page.locator('main').evaluate(el=>el.scrollWidth<=el.clientWidth+1));await snap(page,`sources-${width}`)}
  assert.deepEqual(errors,[]);assert(calls.some(c=>c.p==='/api/admin/users/new_user/reset-password'));console.log('PASS: Smart Q&A default entry, server paging beyond 50 sessions, source create/test/edit/activate/cancel, user create/edit/reset, indexed pagination, dark mode and screenshots');await ctx.close();
}finally{await browser.close()}

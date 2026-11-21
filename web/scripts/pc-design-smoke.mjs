import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { chromium } from 'playwright';
import { analysisAnswer } from './analysis-fixture.mjs';

const base = process.env.DATAASK_UI_URL || 'http://127.0.0.1:4175';
const out = process.env.DATAASK_SHOTS || 'artifacts/pc-redesign';
await mkdir(out, { recursive:true });
const errors = [];
let mode = 'normal', releasePending;
const calls = [];
const names = {admin:'高阳',alice:'Alice'};
let profileFailure = false;
async function backend(route) {
  const req = route.request(), path = new URL(req.url()).pathname;
  const username = req.headers().authorization === 'Bearer test-alice' ? 'alice' : 'admin';
  const role = username === 'admin' ? 'ADMIN' : 'USER';
  calls.push(req.method() + ' ' + path);
  const ok = json => route.fulfill({ status:200, json });
  if (path === '/api/auth/me') return ok({ username,displayName:names[username],role });
  if (path === '/api/auth/login') {
    const user = req.postDataJSON().username;
    return ok({ token:'test-'+user,username:user,displayName:names[user],role:user==='admin'?'ADMIN':'USER' });
  }
  if (path === '/api/auth/profile') {
    if (req.method() === 'PUT') { names[username] = req.postDataJSON().displayName; return ok({username,displayName:names[username]}); }
    if (profileFailure) return route.fulfill({status:503,json:{message:'资料暂时不可用'}});
    return ok({username,displayName:names[username],role,createdAt:'2026-11-01 10:00:00'});
  }
  if (path === '/api/chat/datasets') return ok(['ads.city_orders']);
  if (path === '/api/chat/sessions') return ok([{id:'s2',title:analysisAnswer.question,updatedAt:'2026-11-10 09:12'}]);
  if (path.endsWith('/messages')) return ok([{...analysisAnswer,semantic:null,execution:null}]);
  if (path === '/api/chat/ask') {
    if (mode === 'pending') await new Promise(resolve => { releasePending = resolve; });
    const input = req.postDataJSON();
    assert.equal(input.dataset,null,'Removing the scope control must not leave an invisible dataset restriction');
    return ok(mode === 'unsupported' ? {...analysisAnswer,id:'unsupported',unsupported:true,rows:[],semantic:null,sql:null,
      title:'请确认查询指标',summary:'请选择要分析的指标。',followUps:['查看订单量'],
      execution:{durationMs:150,steps:[{key:'planning',label:'查询规划',status:'needs_input',durationMs:150,detail:'等待确认指标'}]}}
      : {...analysisAnswer,id:crypto.randomUUID(),question:input.question});
  }
  if (path === '/api/chat/cancel') return ok({cancelled:true});
  if (path === '/api/mini/charts') return ok([]);
  return ok({});
}
const browser = await chromium.launch({headless:true,args:['--no-sandbox']});
async function snap(page, name) {
  await page.evaluate(async()=>{
    await Promise.all(document.getAnimations().filter(a=>a.effect?.getTiming().iterations!==Infinity)
      .map(a=>a.finished.catch(()=>{})));
  });
  await page.screenshot({path:`${out}/${name}.png`});
}
try {
  for (const width of [1440,1280,1920,1024]) {
    const height = width === 1440 ? 1024 : width === 1920 ? 1080 : 800;
    const context = await browser.newContext({viewport:{width,height},reducedMotion:'reduce'});
    await context.addInitScript(() => {
      sessionStorage.setItem('dataask_token','ui-test-token');
      sessionStorage.setItem('dataask_user',JSON.stringify({username:'admin',displayName:'高阳',role:'ADMIN'}));
    });
    const page = await context.newPage();
    page.on('pageerror', error => errors.push(error.message));
    await page.route('**/api/**',backend);
    await page.goto(base,{waitUntil:'networkidle'});
    await page.locator('.conversation').waitFor();
    await page.locator('.da-home-question-box').waitFor();
    await page.locator('.da-ask-hero').waitFor();
    assert.equal(await page.locator('.topbar').count(),0,
      'New-task landing page must not render the DataAsk breadcrumb header');
    const landingTop=await page.locator('.conversation').boundingBox();
    assert(landingTop && landingTop.y<=1,
      'New-task content must start at the top without an empty header row');
    assert.equal(await page.locator('.da-empty-ask-home').count(),1,'Authenticated New task must render the WorkBuddy-style landing page');
    const landingGeometry = await page.evaluate(() => {
      const conversation=document.querySelector('.conversation');
      const field=document.querySelector('.da-home-question-box textarea');
      if (!conversation || !field) return null;
      const main=conversation.getBoundingClientRect();
      const input=field.getBoundingClientRect();
      const style=getComputedStyle(conversation);
      const visibleAtPoint=document.elementFromPoint(input.left+input.width/2,input.top+Math.min(18,input.height/2));
      return { mainHeight:main.height, mainBottom:main.bottom, inputTop:input.top, inputBottom:input.bottom,
        overflowY:style.overflowY, inputHit:visibleAtPoint===field };
    });
    assert(landingGeometry && landingGeometry.mainHeight>280,
      'New-task content container must have visible height (legacy CSS used height:0)');
    assert(landingGeometry.inputTop<landingGeometry.mainBottom && landingGeometry.inputBottom>0,
      'New-task input must not be clipped by its parent container');
    assert(landingGeometry.overflowY!=='hidden' && landingGeometry.inputHit,
      'New-task input must actually be visible and clickable');
    await page.getByRole('textbox',{name:'输入经营数据问题'}).click();
    assert.equal(await page.locator('.da-ask-hero p').count(),0,'Smart Q&A home keeps only the task title');
    assert.equal(await page.locator('.da-suggestions-label small').count(),0,'Common questions heading has no helper copy');
    assert.equal(await page.locator('.da-suggestion-grid button').count(),8,'Smart Q&A home exposes eight compact quick questions');
    const entryBox=await page.locator('.da-home-question-box').boundingBox();
    assert(entryBox && entryBox.height>=136,'Smart Q&A input area stays visually dominant');
    assert(entryBox && entryBox.width<=1042 && entryBox.width>=Math.min(720,width-280),
      'Smart Q&A entry is centered and bounded instead of spanning the full canvas');
    assert.equal(await page.locator('.desktop-main-nav button').filter({hasText:'工作台'}).count(),0,
      'Smart Q&A has no extra workbench navigation layer');
    const askNav=page.locator('.desktop-main-nav button').filter({hasText:'新建任务'});
    assert.match(await askNav.getAttribute('class'),/active/,'Authenticated desktop exposes New task as the default Q&A entry');
    assert.equal(await page.getByRole('button',{name:'查看全部会话'}).count(),0,'Recent section should contain recent questions only');
    const rail = await page.locator('.desktop-sidebar').boundingBox();
    assert(Math.abs(rail.width - 216)<2,'Sidebar keeps the 216px default width');
    const resizeEdge=page.locator('.da-sidebar-resizer');
    const resizeBox=await resizeEdge.boundingBox();
    assert(resizeBox && resizeBox.width<=10,'Sidebar resize edge stays visually unobtrusive');
    await page.mouse.move(resizeBox.x+resizeBox.width/2,resizeBox.y+180);
    await page.mouse.down();
    await page.mouse.move(resizeBox.x+56,resizeBox.y+180,{steps:4});
    await page.mouse.up();
    const resizedRail=await page.locator('.desktop-sidebar').boundingBox();
    assert(resizedRail.width>250 && resizedRail.width<=360,'Sidebar can be widened by dragging its edge');
    await resizeEdge.focus();
    await page.keyboard.press('ArrowLeft');
    assert((await page.locator('.desktop-sidebar').boundingBox()).width<resizedRail.width,
      'Sidebar width can also be adjusted without a visible drag handle');
    const account = await page.locator('.da-account-trigger').boundingBox();
    assert(account.x < 232 && account.y > height - 150,'Account must remain at bottom-left');
    await snap(page,`${width}-ask-home`);
    await page.getByRole('textbox',{name:'输入经营数据问题'}).fill(analysisAnswer.question);
    await page.getByRole('button',{name:'发送问题'}).click();
    await page.locator('.topbar').waitFor();
    const processPanel = page.locator('.da-analysis-panel');
    await processPanel.waitFor();
    assert.equal(await page.locator('.da-analysis-steps li').count(),6);
    assert.match(await processPanel.locator('.da-analysis-card').first().innerText(),/分析维度[\s\S]*城市[\s\S]*计算指标[\s\S]*订单量/);
    assert.match(await processPanel.locator('.da-analysis-sql').innerText(),/SELECT city[\s\S]*ads\.city_orders/);
    assert.equal(await page.locator('.da-analysis-summary').count(),1);
    assert.match(await page.locator('.da-analysis-summary').innerText(),/西安市的订单量最高，为 188/,'Actual summary must not be mistaken for generic system narration');
    await page.locator('.conversation').evaluate(el=>{el.scrollTop=0});
    await page.locator('.conversation .chart-view canvas').waitFor();
    await snap(page,`${width}-answer-process`);
    assert.match(await processPanel.locator('.da-analysis-card').first().innerText(),/2026-11-09/);
    await processPanel.getByRole('button',{name:'关闭分析过程'}).click();
    assert.equal(await processPanel.count(),0);
    await page.getByRole('button',{name:/查看分析过程/}).click();
    await processPanel.waitFor();
    await page.locator('.da-analysis-summary').scrollIntoViewIfNeeded();
    await snap(page,`${width}-summary`);
    await processPanel.getByRole('button',{name:'关闭分析过程'}).click();
    await page.getByRole('button',{name:'表格',exact:true}).click();
    assert.equal(await page.locator('.result-table tbody tr').count(),5);
    await page.getByRole('button',{name:'图表',exact:true}).click();
    assert.equal(await page.locator('.result-table').count(),0);
    await page.locator('.desktop-answer-tools button').filter({hasText:'全屏查看'}).click();
    await page.locator('.da-result-fullscreen').waitFor();
    assert.match(await page.locator('.da-result-fullscreen-dialog').innerText(),/问数结果[\s\S]*各城市订单量排行/);
    if (width === 1440) await snap(page,'1440-result-fullscreen');
    await page.getByRole('button',{name:'关闭全屏结果'}).click();
    await page.locator('.desktop-answer-tools button').filter({hasText:'追加提问'}).click();
    assert.match(await page.locator('.da-followup-context').innerText(),/正在追加提问/);
    await page.getByRole('button',{name:'退出追加提问'}).click();

    await page.getByRole('button',{name:'结果有帮助'}).click();
    assert.match(await page.getByRole('button',{name:'结果有帮助'}).getAttribute('class'),/active/);
    assert.equal(await page.locator('.da-sidebar-collapse,.da-scope-chip,.da-composer-scope').count(),0);
    assert.equal(await page.locator('.da-account-trigger>.van-icon').count(),0,'Remove the account chevron');
    await page.locator('.da-account-trigger').click();
    assert.equal(await page.getByRole('menuitem',{name:'退出登录'}).isVisible(),true);
    assert.equal(await page.getByRole('menuitem').count(),5,'Personalization, profile, password, system management and logout');
    const menu = await page.locator('.da-account-dropdown').boundingBox();
    assert(menu.x >= 0 && menu.x + menu.width <= width && menu.y >= 0 && menu.y + menu.height <= height,'Account menu must fit viewport');
    if (width === 1440) {
      await snap(page,'1440-account-menu-light');
      await page.locator('.da-account-dropdown').screenshot({path:`${out}/account-menu-light-detail.png`});
    }
    await page.locator('.answer-heading').click();
    assert.equal(await page.getByRole('menu',{name:'账号选项'}).count(),0,'Outside click dismisses account menu');
    await page.locator('.da-account-trigger').click();
    await page.keyboard.press('End');
    assert.equal(await page.getByRole('menuitem',{name:'退出登录'}).evaluate(el=>el===document.activeElement),true);
    await page.keyboard.press('Escape');
    assert.equal(await page.locator('.da-account-trigger').evaluate(el=>el===document.activeElement),true);
    assert(await page.evaluate(()=>document.documentElement.scrollWidth <= innerWidth),'No horizontal viewport overflow');

    if (width === 1440) {
      await page.getByRole('button',{name:/查看分析过程/}).click();
      await processPanel.waitFor();
      // Theme choice actually repaints charts/dialogs and persists per account across reloads.
      await page.locator('.da-account-trigger').click();
      await page.getByRole('menuitem',{name:/个性化/}).click();
      await page.getByRole('button',{name:/深色.*黑色界面/}).click();
      await page.waitForFunction(()=>document.documentElement.dataset.dataaskTheme==='dark');
      assert.equal(await page.locator('.da-analysis-panel').evaluate(el=>getComputedStyle(el).backgroundColor),
        await page.locator('.topbar').evaluate(el=>getComputedStyle(el).backgroundColor));
      await snap(page,'1440-personalization-dark');
      await page.locator('.da-preferences-popup:visible').screenshot({path:`${out}/personalization-dark-detail.png`});
      await page.getByRole('button',{name:/浅色.*白色界面/}).click();
      await page.waitForFunction(()=>document.documentElement.dataset.dataaskTheme==='light');
      assert.equal(await page.locator('.da-analysis-panel').evaluate(el=>getComputedStyle(el).backgroundColor),'rgb(255, 255, 255)');
      await snap(page,'1440-personalization-light');
      await page.locator('.da-preferences-popup:visible').screenshot({path:`${out}/personalization-light-detail.png`});
      await page.getByRole('button',{name:/深色.*黑色界面/}).click();
      await page.keyboard.press('Escape');
      await page.locator('.da-preferences-popup').first().waitFor({state:'hidden'});
      await page.locator('.conversation').evaluate(el=>{el.scrollTop=0});
      await snap(page,'1440-answer-dark');
      await page.locator('.da-account-trigger').click();
      await snap(page,'1440-account-menu-dark');
      await page.locator('.da-account-dropdown').screenshot({path:`${out}/account-menu-dark-detail.png`});
      await page.keyboard.press('Escape');
      await snap(page,'1440-execution-dark');
      await page.reload({waitUntil:'networkidle'});
      await page.waitForFunction(()=>document.documentElement.dataset.dataaskTheme==='dark');
      await page.locator('.da-account-trigger').click();
      profileFailure = true;
      await page.getByRole('menuitem',{name:'个人资料',exact:true}).click();
      await page.getByText('资料暂时不可用',{exact:true}).waitFor();
      profileFailure = false;
      await page.getByRole('button',{name:'重新加载',exact:true}).click();
      const displayName = page.locator('.da-profile-dialog input[placeholder="请输入显示名称"]');
      await displayName.waitFor();
      await displayName.fill('高阳（数据分析）');
      await snap(page,'1440-profile-dark');
      await page.getByRole('button',{name:'保存资料',exact:true}).click();
      await page.locator('.da-profile-dialog').waitFor({state:'hidden'});
      assert.match(await page.locator('.da-account-label').innerText(),/高阳（数据分析）/);
      assert(calls.includes('PUT /api/auth/profile'));
      await page.reload({waitUntil:'networkidle'});
      await page.locator('.da-account-label b').getByText('高阳（数据分析）',{exact:true}).waitFor();
      await page.locator('.da-account-trigger').click();
      await page.getByRole('menuitem',{name:'系统管理',exact:true}).click();
      await page.locator('.tb-admin-overview-grid').waitFor();
      assert.equal(await page.locator('.tb-admin-overview-grid button').filter({hasText:'角色权限'}).count(),1,
        'System management exposes role and permission administration');
      await snap(page,'1440-admin-dark');
      await page.locator('.tb-admin-overview-grid button').filter({hasText:'数据源配置'}).click();
      await page.getByRole('button',{name:'新增数据源',exact:true}).click();
      await page.locator('.da-crud-modal:visible').waitFor();
      assert.equal(await page.locator('.da-crud-modal:visible').evaluate(el=>getComputedStyle(el).backgroundColor),
        await page.locator('.topbar').evaluate(el=>getComputedStyle(el).backgroundColor));
      await snap(page,'1440-admin-source-dark');
      await page.locator('.da-crud-modal:visible').getByRole('button',{name:'取消',exact:true}).click();
      await page.getByRole('button',{name:'返回 DataAsk',exact:true}).click();
      await page.locator('.da-admin-workspace-popup').waitFor({state:'hidden'});
      // Old records must not fabricate semantic results or past durations.
      await page.locator('.da-sidebar-session-open').first().click();
      assert.equal(await page.locator('.da-analysis-panel').count(),0);
      await page.locator('.da-session-more').first().click();
      assert.equal(await page.getByRole('menuitem',{name:'删除会话'}).isVisible(),true);
      const deleteResponse=page.waitForResponse(r=>r.request().method()==='DELETE'&&r.url().includes('/api/chat/sessions/s2'));
      await page.getByRole('menuitem',{name:'删除会话'}).click();
      await page.locator('.van-dialog').getByRole('button',{name:'确认',exact:true}).click();
      await deleteResponse;
      assert(calls.includes('DELETE /api/chat/sessions/s2'),'Recent-session menu must delete through the existing API');
      await page.getByRole('button',{name:/查看分析过程/}).click();
      await page.locator('.da-analysis-panel').waitFor();
      assert.equal(await page.locator('.da-analysis-steps').count(),0);
      await page.locator('.desktop-main-nav button').filter({hasText:'新建任务'}).click();
      assert.equal(await page.locator('.topbar').count(),0,
        'Returning to New task must hide the conversation breadcrumb again');
      assert((await page.locator('.conversation').boundingBox())?.height>280,
        'Clicking New task after a conversation must restore the visible landing page');
      await page.getByRole('textbox',{name:'输入经营数据问题'}).click();
      assert.equal(await page.locator('.conversation .welcome').count(),0,
        'PC question page must not render the redundant welcome banner');
      const composer = await page.locator('.da-home-question-box').boundingBox();
      assert(composer && composer.y<360,'Empty Smart Q&A page should expose the main input immediately');
      assert(composer.width<=980,'Landing input must stay centered instead of filling the workspace');
      assert.doesNotMatch(await page.locator('.app-shell').innerText(),/仅查询管理员|通用问法示例|用自然语言提问，系统会/);
      await snap(page,`${width}-ask-compact`);
      await page.locator('.desktop-main-nav button').filter({hasText:'我的图表'}).click();
      assert.equal(await page.locator('.topbar').count(),0,'Chart list must not have a redundant global breadcrumb header');
      const chartsMain = await page.locator('.tb-charts-page').boundingBox();
      assert(chartsMain && chartsMain.y<=1,'Chart list must start directly at the top of its workspace');
      await page.locator('.tb-chart-tabs').waitFor();
      await page.locator('.desktop-main-nav button').filter({hasText:'新建任务'}).click();
      assert.equal(await page.locator('.topbar').count(),0,'Returning to New task keeps the breadcrumb hidden');

      mode = 'unsupported';
      await page.locator('.da-home-question-box textarea').fill('查看近7天趋势');
      await page.locator('.da-home-question-box textarea').press('Enter');
      await page.locator('.metric-clarification').waitFor();
      const composerEdge=page.locator('.da-composer-resize-edge');
      const edgeBox=await composerEdge.boundingBox();
      assert(edgeBox && edgeBox.height<=10,'Conversation composer resize edge stays visually hidden');

      assert.equal(await page.locator('.da-analysis-card').count(),0);
      assert.match(await page.locator('.da-analysis-steps').innerText(),/等待确认指标/);
      await page.locator('.desktop-main-nav button').filter({hasText:'新建任务'}).click();
      mode = 'pending';
      await page.locator('.da-home-question-box textarea').fill('昨天订单量');
      await page.locator('.da-home-question-box textarea').press('Enter');
      await page.getByText('正在分析',{exact:true}).waitFor();
      assert.equal(await page.locator('.da-analysis-steps').count(),0,'Never invent in-flight completed stages');
      await page.getByRole('button',{name:'停止查询',exact:true}).click();
      await page.getByText('查询已停止',{exact:true}).waitFor();
      releasePending?.();
      mode = 'normal';
      await page.locator('.da-account-trigger').click();
      await page.getByRole('menuitem',{name:'修改密码'}).click();
      await page.locator('.da-password-dialog').waitFor();
      await page.getByRole('button',{name:'取消',exact:true}).click();
      await page.locator('.da-account-trigger').click();
      await page.getByRole('menuitem',{name:'退出登录'}).click();
      await page.locator('.login-card').waitFor();
      assert.equal(await page.evaluate(()=>sessionStorage.getItem('dataask_token')),null);
      const loginHeadline=(await page.locator('.login-brand-copy>strong').innerText()).replace(/\s+/g,' ');
      assert.match(loginHeadline,/用自然语言/);
      assert.match(loginHeadline,/数据触手可问/);
      assert.equal(await page.getByText('自然语言问数',{exact:true}).count(),1);
      assert.equal(await page.getByText('图表共享协作',{exact:true}).count(),1);
      const loginGrid=await page.locator('.login-screen').evaluate(el=>getComputedStyle(el).gridTemplateColumns);
      assert.match(loginGrid,/px .*px/,'Desktop login should keep a two-column layout');
      const loginCardBox=await page.locator('.login-card').boundingBox();
      assert(loginCardBox && loginCardBox.width>=400 && loginCardBox.width<=500,'Login card should match the approved visual proportions');
      assert.equal(await page.locator('svg.login-brand-hero').count(),1,'Desktop login uses a transparent inline SVG analytics scene');
      assert.equal(await page.locator('.login-topbar').count(),0,'Login page must not show the internal-system security banner');
      assert.equal(await page.locator('.login-brand-hero image').count(),0,'The hero must not contain a raster rectangle');
      assert((await page.locator('.login-brand-hero defs').count())===1,'The SVG hero keeps gradients and shadows inside the transparent scene');
      assert.equal(await page.locator('.login-brand-hero g[transform*="rotate(7 170 190)"]').count(),1,
        'The main analytics window must lean left, not right');
      assert.equal(await page.locator('.login-card-corner').count(),1,'Login card uses the vector top-right arc decoration');
      const cornerPosition=await page.locator('.login-card-corner').evaluate(el=>getComputedStyle(el).position);
      assert.equal(cornerPosition,'absolute','Card corner decoration must not occupy form layout space');
      const loginButton=page.locator('.login-card .van-button');
      await loginButton.waitFor({state:'visible'});
      const cardBox=await page.locator('.login-card').boundingBox();
      const loginButtonBox=await loginButton.boundingBox();
      assert(cardBox && loginButtonBox && loginButtonBox.y+loginButtonBox.height <= cardBox.y+cardBox.height,
        'Login button must remain fully visible inside the card');
      assert.equal(await page.locator('.login-background-waves').count(),1,'Login bottom uses the vector wave layers');
      const hero=await page.locator('.login-brand-hero').boundingBox();
      const card=await page.locator('.login-card').boundingBox();
      const viewport=page.viewportSize();
      assert(hero && card && viewport);
      assert(Math.abs(hero.x/viewport.width-0.3655)<0.03,'Hero horizontal position follows the approved reference');
      assert(Math.abs(card.x/viewport.width-0.646)<0.04,'Login card horizontal position follows the reference');
      assert(Math.abs(card.y/viewport.height-0.162)<0.04,'Login card vertical position follows the reference');
      assert.equal(await page.evaluate(()=>document.documentElement.dataset.dataaskTheme),undefined);
      assert(calls.includes('POST /api/chat/cancel'));
      await page.locator('input[autocomplete="username"]').fill('alice');
      await page.locator('input[autocomplete="current-password"]').fill('example-password');
      const passwordFocus=await page.locator('input[autocomplete="current-password"]').evaluate(el=>getComputedStyle(el));
      assert.equal(passwordFocus.outlineStyle,'none','Native password input must not draw an inner focus outline');
      assert.equal(passwordFocus.borderLeftWidth,'0px','Password input must not draw the left separator line');
      assert.equal(passwordFocus.borderRightWidth,'0px','Password input must not draw the right separator line');
      await page.getByRole('button',{name:'登录',exact:true}).click();
      await page.locator('.conversation').waitFor();await page.locator('.da-home-question-box').waitFor();
      assert.equal(await page.evaluate(()=>document.documentElement.dataset.dataaskTheme),'light','A different user must not inherit admin theme');
      await page.locator('.da-account-trigger').click();
      await page.getByRole('menuitem',{name:'退出登录'}).click();
      await page.locator('.login-card').waitFor();
      await page.locator('input[autocomplete="username"]').fill('admin');
      await page.locator('input[autocomplete="current-password"]').fill('example-password');
      await page.getByRole('button',{name:'登录',exact:true}).click();
      await page.locator('.conversation').waitFor();await page.locator('.da-home-question-box').waitFor();
      assert.equal(await page.evaluate(()=>document.documentElement.dataset.dataaskTheme),'dark','Returning account should restore its own preference');
      // Returning to narrow H5 removes desktop dark styling even with the preference saved.
      await page.setViewportSize({width:390,height:844});
      await page.waitForFunction(()=>!document.documentElement.dataset.dataaskTheme);
      assert.equal(await page.locator('.app-shell').getAttribute('data-theme'),null);
    }
    await context.close();
  }
  const mobile = await browser.newContext({viewport:{width:390,height:844},isMobile:true,hasTouch:true});
  await mobile.addInitScript(()=>{
    sessionStorage.setItem('dataask_token','test');
    sessionStorage.setItem('dataask_user',JSON.stringify({username:'admin',role:'ADMIN'}));
  });
  const page = await mobile.newPage();
  page.on('pageerror',error=>errors.push(error.message));
  await page.route('**/api/**',backend);
  await page.goto(base,{waitUntil:'networkidle'});
  assert.equal(await page.locator('.desktop-sidebar').isVisible(),false);
  assert.equal(await page.locator('.da-query-explanation').count(),0);
  assert.equal(await page.locator('.composer textarea').isVisible(),true);
  await snap(page,'390-mobile');
  await mobile.close();
  assert.deepEqual(errors,[],'No browser runtime errors');
  console.log('PC redesign: 4 widths, semantic trace, scope-control removal, account menu keyboard/outside behavior, theme repaint + persistence + per-user isolation, profile save/reload/error, password/logout and mobile isolation passed. Screenshots: '+out);
} finally {
  releasePending?.();
  await browser.close();
}

import { chromium } from 'playwright'
import assert from 'node:assert/strict'
import { mkdir } from 'node:fs/promises'
import { resolve } from 'node:path'
const base = process.env.DATAASK_TEST_BASE
const password = process.env.DATAASK_TEST_ADMIN_PASSWORD
if (!base?.startsWith('http://127.0.0.1:') || !password) throw new Error('Only isolated local integration fixtures are supported')
const output = resolve('target/installation-test')
await mkdir(output, { recursive: true })
const browser = await chromium.launch({ headless: true })
const errors = []
async function login(page) {
  page.on('pageerror', error => errors.push(error.message))
  await page.goto(base)
  await page.locator('input[placeholder="请输入密码"]').fill(password)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await page.locator('.conversation').waitFor()
}
async function selectTablesTab(page) {
  await page.locator('.admin-tabs').getByRole('button', { name: '数据库 / 表 / 字段', exact: true }).click()
  await page.locator('#catalog-db').waitFor()
  await page.waitForFunction(() => [...document.querySelectorAll('#catalog-db option')].some(o => o.value === 'ads_ci'))
  await page.locator('#catalog-db').selectOption('ads_ci')
  const next = page.locator('.catalog-actions').getByRole('button', { name: '下一步', exact: true })
  await next.click()
  await page.getByRole('textbox', { name: '搜索表名或注释', exact: true }).waitFor()
  const items = await page.locator('.catalog-table-item').count()
  assert.equal(items, 10, '来源表应为10条/页')
  await page.getByRole('textbox', { name: '搜索表名或注释', exact: true }).fill('revenue_daily')
  await page.locator('.catalog-table-item > button').click()
  await page.getByRole('checkbox', { name: '选择字段amount', exact: true }).waitFor()
  assert.equal(await page.getByRole('checkbox', { name: '选择字段secret_amount', exact: true }).isChecked(), false)
}
try {
  const desktop = await browser.newContext({ viewport: { width: 1440, height: 960 }, locale: 'zh-CN' })
  const page = await desktop.newPage()
  await login(page)
  assert.equal(await page.locator('.desktop-sidebar').isVisible(), true)
  await page.locator('.sidebar-bottom').getByRole('button', { name: '系统管理', exact: true }).click()
  await page.getByText('系统管理 · MySQL 配置中心', { exact: true }).waitFor()
  await selectTablesTab(page)
  const fields = page.getByRole('checkbox', { name: '选择字段amount', exact: true })
  assert.equal(await fields.isChecked(), true)
  await fields.uncheck()
  await page.locator('.catalog-actions').getByRole('button', { name: '下一步', exact: true }).click()
  const saved = page.waitForResponse(r => r.url().endsWith('/api/admin/catalog/selection') && r.request().method() === 'POST')
  await page.getByRole('button', { name: '确认保存并生效', exact: true }).click()
  assert.equal((await saved).status(), 200)
  const selection = await page.evaluate(async () => {
    const response = await fetch('/api/admin/catalog/databases', { headers: { Authorization: 'Bearer ' + sessionStorage.getItem('dataask_token') } })
    return (await response.json()).selection
  })
  assert.deepEqual(selection['ads_ci.revenue_daily'], ['dt', 'city_name'])
  await page.getByRole('button', { name: '上一步', exact: true }).click()
  await fields.check()
  await page.locator('.catalog-actions').getByRole('button', { name: '下一步', exact: true }).click()
  const restored = page.waitForResponse(r => r.url().endsWith('/api/admin/catalog/selection') && r.request().method() === 'POST')
  await page.getByRole('button', { name: '确认保存并生效', exact: true }).click()
  assert.equal((await restored).status(), 200)
  await page.getByRole('button', { name: '上一步', exact: true }).click()
  const bounds = await page.locator('.config-center').boundingBox()
  assert.ok(bounds.height < 960 && bounds.width <= 1440)
  assert.ok(await page.locator('.catalog-fields-scroll').evaluate(el => getComputedStyle(el).overflowY === 'auto' && el.clientHeight <= 400))
  await page.screenshot({ path: resolve(output, 'desktop-catalog.png') })
  console.log('PASS UI: desktop three-pane, 10-row pagination, column selection/save, fixed scroll window')
  await page.locator('.admin-tabs').getByRole('button', { name: '模型与连接', exact: true }).click()
  await page.getByRole('button', { name: /对话模型/ }).click()
  await page.getByText('对话模型管理', { exact: true }).waitFor()
  await page.locator('.config-panel input').first().waitFor()
  assert.equal(await page.locator('.config-panel input').first().inputValue(), (await page.evaluate(async () => {
    const response = await fetch('/api/admin/settings', { headers: { Authorization: 'Bearer ' + sessionStorage.getItem('dataask_token') } })
    return (await response.json()).ai.baseUrl
  })))
  console.log('PASS UI: model setting entry opens and loads saved MySQL values')
  await desktop.close()
  const mobile = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, deviceScaleFactor: 1, hasTouch: true, locale: 'zh-CN' })
  const phone = await mobile.newPage()
  await login(phone)
  assert.equal(await phone.locator('.desktop-sidebar').isVisible(), false)
  await phone.locator('.top-actions button[title="管理员"]').click()
  await selectTablesTab(phone)
  assert.equal(await phone.getByRole('checkbox', { name: '选择字段amount', exact: true }).isChecked(), true)
  assert.ok(await phone.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1), '手机页面不应整体横向溢出')
  await phone.screenshot({ path: resolve(output, 'mobile-catalog.png'), fullPage: true })
  console.log('PASS UI: mobile layout and persisted table-column configuration')
  await mobile.close()
  assert.deepEqual(errors, [], '浏览器不应出现未处理的页面异常')
} finally {
  await browser.close()
}

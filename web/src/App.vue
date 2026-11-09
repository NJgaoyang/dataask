<script setup>
import { computed, onMounted, onBeforeUnmount, ref, nextTick } from 'vue'
import axios from 'axios'
import { showToast, showFailToast, showSuccessToast, showConfirmDialog } from 'vant'
import AnswerChart from './components/AnswerChart.vue'

const api = axios.create({ baseURL: '/api', timeout: 65000 })
api.interceptors.request.use(config => {
  const token = sessionStorage.getItem('dataask_token')
  if (token) config.headers.Authorization = 'Bearer ' + token
  return config
})
api.interceptors.response.use(r => r, error => {
  if (error.response?.status === 401 && !String(error.config?.url || '').includes('/auth/login')) {
    sessionStorage.removeItem('dataask_token')
    sessionStorage.removeItem('dataask_user')
    loggedIn.value = false
  }
  return Promise.reject(error)
})
const loggedIn = ref(!!sessionStorage.getItem('dataask_token'))
const me = ref(JSON.parse(sessionStorage.getItem('dataask_user') || '{}'))
const loginForm = ref({ username: 'admin', password: '' })
const loggingIn = ref(false)
const question = ref('')
const busy = ref(false)
const pendingQuestion = ref('')
const chartsVisible = ref(false)
const savedCharts = ref([])
const sharingVisible = ref(false)
const sharingChart = ref(null)
const shareScope = ref('PRIVATE')
const shareMembers = ref('')
let activeController = null
let activeRequestId = ''
let askSequence = 0
const messages = ref([])
const sessionId = ref('')
const historyVisible = ref(false)
const sessions = ref([])
const sessionSearch = ref('')
const resultPanelOpen = ref(true)
const resultTab = ref('overview')
const visibleSessions = computed(() => {
  const keyword = sessionSearch.value.trim().toLowerCase()
  return keyword ? sessions.value.filter(s => s.title.toLowerCase().includes(keyword)) : sessions.value
})
const activeSessionTitle = computed(() =>
  sessions.value.find(s => s.id === sessionId.value)?.title || '新对话')
const latestAnswer = computed(() =>
  [...messages.value].reverse().find(m => m.rows?.length || m.unsupported) || null)
const adminVisible = ref(false)
const status = ref({})
const configVisible = ref(false)
const configKind = ref('starrocks')
const isDesktop = ref(false)
const buildSha = __DATAASK_BUILD_SHA__
let desktopMedia
const aiForm = ref({ baseUrl:'', model:'', apiKey:'', clearKey:false })
const embeddingForm = ref({
  baseUrl:'https://dashscope.aliyuncs.com/api/v1', model:'qwen3.7-text-embedding-flash', apiKey:'', clearKey:false,
  enabled:false, dimensions:1024
})
const semanticStatus = ref({ count: 0, indexedModel: '', configured: false })
const semanticBusy = ref(false)
const configBusy = ref(false)
const configTestBusy = ref(false)
const configTestMessage = ref('')
const settingsData = ref({ starrocks: {}, ai: {} })
const srForm = ref({ jdbcUrl: '', username: '', password: '', schema: 'ads' })
const allowedTables = ref([])
const expandedTable = ref('')
const users = ref([])
const joinRules = ref([])
const joinSaving = ref(false)
const joinForm = ref({ leftTable: '', rightTable: '', leftKeys: '', rightKeys: '', cardinality: 'ONE_TO_ONE', grainNote: '' })
const metricFileInput = ref(null)
const selectedMetricFile = ref(null)
const metricPreview = ref(null)
const metricPreviewBusy = ref(false)
const metricImportBusy = ref(false)
const metricInfo = ref({ total: 0, batches: [], metrics: [] })
const metricError = ref('')
const newUser = ref({ username: '', displayName: '', password: '', role: 'USER' })
const footerRef = ref(null)
const quickQuestions = ['昨天的营业额是多少？', '昨天各城市的订单量排行', '最近7天的营收趋势', '本月各城市销售额对比']
const isAdmin = computed(() => me.value.role === 'ADMIN')

function errorText(e) { return e?.response?.data?.message || e?.message || '请求失败' }
function scrollEnd() { nextTick(() => footerRef.value?.scrollIntoView({ behavior: 'smooth', block: 'end' })) }
async function login() {
  if (!loginForm.value.username || !loginForm.value.password) return showToast('请输入账号和密码')
  loggingIn.value = true
  try {
    const { data } = await api.post('/auth/login', loginForm.value)
    sessionStorage.setItem('dataask_token', data.token)
    const user = { username: data.username, displayName: data.displayName, role: data.role }
    sessionStorage.setItem('dataask_user', JSON.stringify(user))
    me.value = user; loggedIn.value = true; loginForm.value.password = ''
    await loadSessions()
  } catch (e) { showFailToast(errorText(e)) }
  finally { loggingIn.value = false }
}
function logout() {
  sessionStorage.removeItem('dataask_token')
  sessionStorage.removeItem('dataask_user')
  loggedIn.value = false
  messages.value = []; sessionId.value = ''; historyVisible.value = false; adminVisible.value = false
}
async function loadSessions() {
  try { sessions.value = (await api.get('/chat/sessions')).data }
  catch (e) { showFailToast(errorText(e)) }
}
async function openSession(item) {
  try {
    messages.value = (await api.get('/chat/sessions/' + item.id + '/messages')).data
    sessionId.value = item.id
    historyVisible.value = false
    scrollEnd()
  } catch (e) { showFailToast(errorText(e)) }
}
function newConversation() {
  if (busy.value) stopAsk()
  sessionId.value = ''; messages.value = []; historyVisible.value = false
}
async function openSavedCharts() {
  try {
    const { data } = await api.get('/mini/charts?view=mine')
    savedCharts.value = data
    chartsVisible.value = true
  } catch (e) { showFailToast(errorText(e)) }
}
async function revisitChart(chart) {
  chartsVisible.value = false
  newConversation()
  await ask(chart.question)
}
async function saveChart(msg) {
  try {
    const { data } = await api.post('/mini/charts', { sourceMessageId: msg.id, title: msg.title })
    showSuccessToast('图表已收藏')
    return data
  } catch (e) { showFailToast(errorText(e)); return null }
}
async function shareChart(msg) {
  const chart = await saveChart(msg)
  if (!chart) return
  sharingChart.value = chart
  shareScope.value = chart.shareScope || 'PRIVATE'
  shareMembers.value = ''
  sharingVisible.value = true
}
async function applyShare() {
  if (!sharingChart.value) return
  const users = shareScope.value === 'SELECTED'
    ? shareMembers.value.split(/[,，]/).map(x => x.trim()).filter(Boolean) : []
  try {
    await api.put('/mini/charts/' + sharingChart.value.id + '/share', { scope: shareScope.value, users })
    sharingVisible.value = false
    showSuccessToast('共享权限已保存')
  } catch (e) { showFailToast(errorText(e)) }
}
function stopAsk() {
  if (!busy.value) return
  const id = activeRequestId
  activeController?.abort()
  askSequence++
  busy.value = false
  pendingQuestion.value = ''
  activeRequestId = ''
  activeController = null
  if (id) api.post('/chat/cancel', { requestId: id }).catch(() => {})
  showToast('已停止本次查询')
}
async function ask(text = question.value, sourceMessageId = null) {
  text = (text || '').trim()
  if (!text || busy.value) return
  if (text.length > 800) return showToast('提问不能超过800字')
  const sequence = ++askSequence
  const controller = new AbortController()
  const requestId = globalThis.crypto?.randomUUID?.() || ('pc-' + Date.now() + '-' + Math.random().toString(36).slice(2))
  activeController = controller
  activeRequestId = requestId
  busy.value = true
  pendingQuestion.value = text
  question.value = ''
  scrollEnd()
  try {
    const { data } = await api.post('/chat/ask', {
      question: text, sessionId: sessionId.value || null, sourceMessageId, requestId
    }, { signal: controller.signal })
    if (sequence !== askSequence || controller.signal.aborted) return
    sessionId.value = data.sessionId
    messages.value.push(data)
    await loadSessions()
    scrollEnd()
  } catch (e) {
    if (sequence !== askSequence || controller.signal.aborted || axios.isCancel(e)) return
    question.value = text
    showFailToast(errorText(e))
  } finally {
    if (sequence === askSequence) {
      busy.value = false
      pendingQuestion.value = ''
      activeController = null
      activeRequestId = ''
      scrollEnd()
    }
  }
}
async function refreshAdminStatus() {
  const [s, cfg] = await Promise.all([api.get('/admin/status'), api.get('/admin/settings')])
  status.value = s.data
  settingsData.value = cfg.data
}
async function reloadTables() {
  if (!status.value.starrocksConfigured) { allowedTables.value = []; return }
  try { allowedTables.value = (await api.get('/admin/tables')).data }
  catch (e) { allowedTables.value = []; showFailToast('读取 ADS 表失败：' + errorText(e)) }
}
async function openAdmin() {
  adminVisible.value = true
  try { await refreshAdminStatus() }
  catch (e) { showFailToast('读取配置失败：' + errorText(e)) }
  try { users.value = (await api.get('/admin/users')).data }
  catch (e) { showFailToast('读取账号失败：' + errorText(e)) }
  await Promise.all([reloadTables(), reloadMetrics(), reloadJoinRules(), reloadSemanticStatus()])
}
async function reloadSemanticStatus() {
  try { semanticStatus.value = (await api.get('/admin/semantic/status')).data }
  catch (e) { semanticStatus.value = { count: 0 }; }
}
async function rebuildSemanticIndex() {
  if (semanticBusy.value) return
  semanticBusy.value = true
  configTestMessage.value = ''
  try {
    const { data } = await api.post('/admin/semantic/rebuild', {}, { timeout: 180000 })
    configTestMessage.value = data.message
    showSuccessToast(data.message || '索引已重建')
    await reloadSemanticStatus()
  } catch(e) {
    configTestMessage.value = errorText(e)
    showFailToast(configTestMessage.value)
  } finally { semanticBusy.value = false }
}
async function reloadJoinRules() {
  try { joinRules.value = (await api.get('/admin/join-rules')).data }
  catch (e) { showFailToast('读取关联规则失败：' + errorText(e)) }
}
async function saveJoinRule() {
  if (joinSaving.value) return
  joinSaving.value = true
  try {
    await api.post('/admin/join-rules', joinForm.value)
    showSuccessToast('关联规则已保存')
    joinForm.value = { leftTable:'', rightTable:'', leftKeys:'', rightKeys:'', cardinality:'ONE_TO_ONE', grainNote:'' }
    await reloadJoinRules()
  } catch (e) { showFailToast(errorText(e)) }
  finally { joinSaving.value = false }
}
async function removeJoinRule(rule) {
  try {
    await showConfirmDialog({
      title: '删除关联规则',
      message: '确定移除 ' + rule.leftTable + ' 与 ' + rule.rightTable + ' 的跨表关系吗？'
    })
    await api.delete('/admin/join-rules/' + rule.id)
    await reloadJoinRules()
    showSuccessToast('关联规则已删除')
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') showFailToast(errorText(e))
  }
}
async function reloadMetrics() {
  try { metricInfo.value = (await api.get('/admin/metrics')).data }
  catch (e) { showFailToast('读取指标口径失败：' + errorText(e)) }
}
function chooseMetricFile() { metricFileInput.value?.click() }
async function selectMetricFile(event) {
  const file = event.target.files?.[0]
  if (!file) return
  metricPreview.value = null
  metricError.value = ''
  selectedMetricFile.value = file
  if (!/\.xlsx?$/i.test(file.name)) { metricError.value = '请选择 .xlsx 或 .xls 文件'; return }
  if (file.size > 5 * 1024 * 1024) { metricError.value = 'Excel 文件不能超过 5 MB'; return }
  metricPreviewBusy.value = true
  try {
    const body = new FormData()
    body.append('file', file)
    metricPreview.value = (await api.post('/admin/metrics/preview', body)).data
  } catch (e) { metricError.value = errorText(e) }
  finally { metricPreviewBusy.value = false; event.target.value = '' }
}
async function importMetrics() {
  if (!selectedMetricFile.value || !metricPreview.value || metricImportBusy.value) return
  metricImportBusy.value = true
  metricError.value = ''
  try {
    const body = new FormData()
    body.append('file', selectedMetricFile.value)
    const { data } = await api.post('/admin/metrics/import', body)
    showSuccessToast(data.message || '指标已导入')
    selectedMetricFile.value = null
    metricPreview.value = null
    await reloadMetrics()
  } catch (e) { metricError.value = errorText(e); showFailToast(metricError.value) }
  finally { metricImportBusy.value = false }
}
async function downloadMetricTemplate() {
  try {
    const response = await api.get('/admin/metrics/template', { responseType: 'blob' })
    const url = URL.createObjectURL(response.data)
    const a = document.createElement('a')
    a.href = url
    a.download = 'DataAsk-指标口径模板.xlsx'
    document.body.appendChild(a)
    a.click()
    a.remove()
    setTimeout(() => URL.revokeObjectURL(url), 2000)
  } catch (e) { showFailToast('模板下载失败：' + errorText(e)) }
}
async function deleteMetricBatch(item) {
  try {
    await showConfirmDialog({
      title: '删除指标口径',
      message: '确认删除“' + item.filename + '”导入的 ' + item.count + ' 条指标？'
    })
    await api.delete('/admin/metrics/batches/' + item.id)
    await reloadMetrics()
    showSuccessToast('该批次已删除')
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') showFailToast(errorText(e))
  }
}
function columnLabel(message, column, index) {
  const labels = message?.columnLabels || {}
  if (labels[column]) return labels[column]
  const direct = {
    city: '城市', city_name: '城市', city_name_std: '城市',
    province: '省份', province_name: '省份', store: '门店',
    store_name: '门店名称', date: '日期', dt: '业务日期', biz_date: '业务日期',
    month: '月份', month_dt: '月份', order_cnt: '订单量', order_count: '订单数',
    total_orders: '订单总数', total_order: '订单总数', user_cnt: '用户数',
    total_users: '用户总数', pay_amount: '实付金额', amount: '金额',
    total_amount: '总金额', revenue: '营业额', total_revenue: '营业额',
    net_revenue: '净收入', refund_amount: '退款金额',
  }
  const key = String(column || '').toLowerCase().replace(/[`"]/g,'')
  if (/[一-龥]/.test(column)) return column
  if (direct[key]) return direct[key]
  if (/^(total|sum|count)_/.test(key)) {
    const suffix = key.replace(/^(total|sum|count)_/,'')
    if (direct[suffix]) return '合计' + direct[suffix]
  }
  return '字段' + (index + 1)
}
async function showConfig(kind) {
  configKind.value = kind
  configTestMessage.value = ''
  try {
    await refreshAdminStatus()
    const sr = settingsData.value.starrocks || {}
    const ai = settingsData.value.ai || {}
    const embedding = settingsData.value.embedding || {}
    srForm.value = { jdbcUrl: sr.jdbcUrl || '', username: sr.username || '',
      password: '', schema: sr.schema || 'ads' }
    aiForm.value = { baseUrl: ai.baseUrl || '', model: ai.model || '', apiKey:'', clearKey:false }
    embeddingForm.value = {
      baseUrl: embedding.baseUrl || 'https://dashscope.aliyuncs.com/api/v1', model: embedding.model || 'qwen3.7-text-embedding-flash',
      apiKey:'', clearKey:false, enabled: !!embedding.enabled,
      dimensions: embedding.dimensions || 1024
    }
    if (kind === 'embedding') await reloadSemanticStatus()
    configVisible.value = true
  } catch (e) { showFailToast(errorText(e)) }
}
function modelPayload(kind) {
  return kind === 'ai' ? aiForm.value : embeddingForm.value
}
async function testConfig() {
  if (configTestBusy.value) return
  configTestBusy.value = true
  configTestMessage.value = ''
  try {
    const kind = configKind.value
    let endpoint = '/admin/settings/starrocks/test'
    if (kind === 'ai') endpoint = '/admin/settings/ai/test'
    else if (kind === 'embedding') endpoint = '/admin/settings/embedding/test'
    const payload = kind === 'starrocks' ? srForm.value : modelPayload(kind)
    const { data } = await api.post(endpoint, payload, { timeout: 45000 })
    configTestMessage.value = data.message || '连接成功'
    showSuccessToast(configTestMessage.value)
  } catch (e) {
    configTestMessage.value = errorText(e)
    showFailToast(configTestMessage.value)
  } finally { configTestBusy.value = false }
}
async function saveConfig() {
  if (configBusy.value) return
  configBusy.value = true
  configTestMessage.value = ''
  try {
    const kind = configKind.value
    let endpoint = '/admin/settings/starrocks'
    if (kind === 'ai') endpoint = '/admin/settings/ai'
    else if (kind === 'embedding') endpoint = '/admin/settings/embedding'
    const payload = kind === 'starrocks' ? srForm.value : modelPayload(kind)
    const { data } = await api.post(endpoint, payload)
    if (kind === 'ai') { aiForm.value.apiKey = ''; aiForm.value.clearKey = false }
    if (kind === 'embedding') { embeddingForm.value.apiKey = ''; embeddingForm.value.clearKey = false }
    if (kind === 'starrocks') {
      await reloadTables()
      configVisible.value = false
    }
    await refreshAdminStatus()
    if (kind === 'embedding') await reloadSemanticStatus()
    configTestMessage.value = data.message || '配置已保存'
    showSuccessToast(configTestMessage.value)
  } catch (e) { showFailToast(errorText(e)) }
  finally { configBusy.value = false }
}
async function toggleAllowed(table) {
  try {
    await api.post('/admin/table-allow', { tableName: table.name, enabled: table.enabled })
    showSuccessToast(table.enabled ? '已授权' : '已取消授权')
  } catch (e) { table.enabled = !table.enabled; showFailToast(errorText(e)) }
}
async function addUser() {
  try {
    await api.post('/admin/users', newUser.value)
    users.value = (await api.get('/admin/users')).data
    newUser.value = { username:'', displayName:'', password:'', role:'USER' }
    showSuccessToast('用户创建成功')
  } catch (e) { showFailToast(errorText(e)) }
}
function pretty(v) {
  if (v === null || v === undefined) return '—'
  if (typeof v === 'number') return new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 }).format(v)
  return String(v)
}
onMounted(() => {
  desktopMedia = window.matchMedia('(min-width: 1024px)')
  const update = () => { isDesktop.value = desktopMedia.matches }
  update()
  desktopMedia.addEventListener('change', update)
  window.__dataAskDesktopListener = update
  if (loggedIn.value) loadSessions()
})
onBeforeUnmount(() => desktopMedia?.removeEventListener('change', window.__dataAskDesktopListener))
</script>

<template>
  <div class="app-shell" :class="{ 'desktop-no-result': !resultPanelOpen }">
    <div v-if="!loggedIn" class="login-screen">
      <div class="login-logo">问</div>
      <h1>DataAsk 数问</h1>
      <p>面向企业管理者的智能经营数据助手</p>
      <div class="login-card">
        <h2>登录到数问</h2>
        <van-field v-model="loginForm.username" label="账号" placeholder="请输入账号" autocomplete="username"/>
        <van-field v-model="loginForm.password" label="密码" placeholder="请输入密码" type="password" autocomplete="current-password" @keyup.enter="login"/>
        <van-button type="primary" block :loading="loggingIn" @click="login">登录</van-button>
        <p class="login-hint">首次启动由管理员配置初始密码，系统不会使用公开默认密码。</p>
      </div>
    </div>
    <template v-else>
      <aside class="desktop-sidebar" aria-label="历史对话侧边栏">
        <div class="sidebar-brand">
          <div class="brand-icon">问</div>
          <div class="brand-title"><strong>DataAsk 智能问数</strong><small>企业经营数据助手</small></div>
        </div>
        <button class="sidebar-new" type="button" @click="newConversation">
          <van-icon name="plus" size="15"/> 新建对话
        </button>
        <label class="sidebar-search">
          <van-icon name="search" color="#8997a8"/>
          <input v-model="sessionSearch" placeholder="搜索历史对话" aria-label="搜索历史对话"/>
        </label>
        <nav class="desktop-main-nav" aria-label="PC 功能导航">
          <button type="button" class="active" @click="newConversation"><van-icon name="chat-o"/> 智能问数</button>
          <button type="button" @click="openSavedCharts"><van-icon name="chart-trending-o"/> 我的图表</button>
          <button type="button" @click="historyVisible = true"><van-icon name="clock-o"/> 历史会话</button>
          <button v-if="isAdmin" type="button" @click="openAdmin"><van-icon name="records-o"/> 指标口径</button>
          <button v-if="isAdmin" type="button" @click="showConfig('starrocks')"><van-icon name="cluster-o"/> 数据连接</button>
          <button v-if="isAdmin" type="button" @click="showConfig('ai')"><van-icon name="setting-o"/> 模型配置</button>
        </nav>
        <div class="sidebar-label">最近的对话</div>
        <nav class="sidebar-sessions">
          <button v-for="item in visibleSessions" :key="item.id"
            class="sidebar-session" :class="{ active: item.id === sessionId }"
            type="button" @click="openSession(item)" :title="item.title">
            <van-icon name="chat-o" size="15"/><span>{{ item.title }}</span>
          </button>
          <p v-if="!visibleSessions.length" class="sidebar-empty">暂无对话记录</p>
        </nav>
        <div class="sidebar-bottom">
          <p class="sidebar-build">工作台版本 {{ buildSha }}</p>
          <button type="button" v-if="isAdmin" @click="openAdmin">
            <van-icon name="setting-o"/> 系统管理
          </button>
          <div class="sidebar-profile">
            <span class="sidebar-avatar">{{ (me.displayName || me.username || '用').slice(0,1) }}</span>
            <span class="sidebar-profile-name">{{ me.displayName || me.username }}</span>
            <button type="button" class="sidebar-logout" @click="logout" title="退出登录">
              <van-icon name="sign" size="16"/>
            </button>
          </div>
        </div>
      </aside>
      <header class="topbar">
        <div class="brand">
          <div class="brand-icon">问</div>
          <div class="brand-title"><strong class="mobile-brand">数问</strong>
            <strong class="desktop-current-session">{{ activeSessionTitle }}</strong>
            <small>经营数据助手</small></div>
        </div>
        <div class="top-actions">
          <button class="desktop-toggle-results" type="button"
            :title="resultPanelOpen ? '隐藏结果面板' : '显示结果面板'"
            @click="resultPanelOpen = !resultPanelOpen">
            <van-icon name="apps-o" size="19"/>
          </button>
          <button title="历史对话" @click="historyVisible = true"><van-icon name="clock-o" size="21"/></button>
          <button v-if="isAdmin" title="管理员" @click="openAdmin"><van-icon name="setting-o" size="21"/></button>
          <button title="新对话" @click="newConversation"><van-icon name="add-o" size="21"/></button>
        </div>
      </header>
      <main class="conversation">
        <section v-if="!messages.length" class="welcome">
          <div class="welcome-icon"><van-icon name="chart-trending-o" size="27"/></div>
          <h2>今天想了解哪些经营数据？</h2>
          <p>直接提问，系统会根据已授权的 ADS 数据计算真实结果。</p>
          <div class="quick-grid">
            <button v-for="item in quickQuestions" :key="item" :disabled="busy" @click="ask(item)">
              {{ item }} <van-icon name="arrow" />
            </button>
          </div>
          <p class="data-notice"><van-icon name="shield-o"/> 仅查询管理员开放的 ADS 表，结果来自真实数据库</p>
        </section>
        <section v-for="msg in messages" :key="msg.id" class="turn">
          <div class="user-bubble">{{ msg.question }}</div>
          <div class="answer-card">
            <div class="answer-heading"><span class="answer-mark">数</span><b>{{ msg.title }}</b>
              <span v-if="msg.timeLabel" class="answer-time">{{ msg.timeLabel }}</span>
            </div>
            <p class="answer-summary" :class="{ 'unsupported-text': msg.unsupported }">{{ msg.summary }}</p>
            <template v-if="msg.rows?.length">
              <div v-if="msg.chartType === 'metric' && msg.rows.length === 1" class="metric-data">
                <div v-for="col in msg.columns" :key="col">
                  <small :title="col">{{ columnLabel(msg, col, msg.columns.indexOf(col)) }}</small><strong>{{ pretty(msg.rows[0][col]) }}</strong>
                </div>
              </div>
              <AnswerChart v-if="['bar','line','pie'].includes(msg.chartType) && msg.rows.length > 1" :message="msg"/>
              <div class="result-table-scroll" role="region" tabindex="0" aria-label="数据结果表格，可独立上下滚动">
                <table class="result-table">
                  <thead><tr><th v-for="(col, idx) in msg.columns" :key="col" :title="col">{{ columnLabel(msg, col, idx) }}</th></tr></thead>
                  <tbody><tr v-for="(row, idx) in msg.rows" :key="idx">
                    <td v-for="col in msg.columns" :key="col">{{ pretty(row[col]) }}</td>
                  </tr></tbody>
                </table>
              </div>
              <p class="table-foot">当前显示 {{ msg.rows.length }} 行，表格区域可独立上下滚动（最多200行）。</p>
            </template>
            <div v-if="isDesktop && !msg.unsupported && msg.rows?.length" class="desktop-answer-tools">
              <button type="button" @click="saveChart(msg)"><van-icon name="star-o"/> 收藏</button>
              <button type="button" @click="shareChart(msg)"><van-icon name="share-o"/> 分享</button>
            </div>
            <div v-if="msg.followUps?.length" class="followups">
              <button v-for="item in msg.followUps" :key="item" :disabled="busy"
                @click="ask(item, msg.id)">{{ item }} <van-icon name="arrow" size="10"/></button>
            </div>
          </div>
        </section>
        <template v-if="busy && isDesktop">
          <div class="turn desktop-pending-turn"><div class="user-bubble">{{ pendingQuestion }}</div>
            <div class="loading-bubble"><span class="answer-mark">D</span><van-loading size="18px"/> 正在分析问题、生成 SQL 并查询数据…
              <button type="button" @click="stopAsk" title="停止查询" class="desktop-inline-stop" aria-label="停止查询"><span class="stop-square"></span></button>
            </div>
          </div>
        </template>
        <div v-else-if="busy" class="loading-bubble"><van-loading size="16px"/> 正在理解问题并查询 ADS 数据...</div>
        <div ref="footerRef" class="end-marker"></div>
      </main>
      <aside class="desktop-insights" aria-label="数据结果面板">
        <div class="insights-top">
          <strong>数据结果</strong>
          <button type="button" title="收起结果面板" @click="resultPanelOpen = false"><van-icon name="cross"/></button>
        </div>
        <div class="insights-tabs">
          <button type="button" :class="{ active: resultTab === 'overview' }"
            @click="resultTab='overview'">概览</button>
          <button type="button" :class="{ active: resultTab === 'table' }"
            @click="resultTab='table'">数据</button>
        </div>
        <div class="insights-content">
          <div v-if="!latestAnswer" class="insights-empty">
            <div class="insights-empty-icon"><van-icon name="chart-trending-o" size="23"/></div>
            <strong>从一个经营问题开始</strong>
            <p>查询结果会出现在这里，方便在对话时对照查看。</p>
          </div>
          <template v-else>
            <div class="insights-summary">
              <div class="insights-meta">本轮分析 <span v-if="latestAnswer.timeLabel">· {{ latestAnswer.timeLabel }}</span></div>
              <h3>{{ latestAnswer.metricLabel || latestAnswer.title }}</h3>
              <p :class="{ 'unsupported-text': latestAnswer.unsupported }">{{ latestAnswer.summary }}</p>
            </div>
            <template v-if="!latestAnswer.unsupported">
              <template v-if="resultTab === 'overview'">
                <div v-if="latestAnswer.rows?.length === 1" class="insights-metric-list">
                  <div v-for="(col, idx) in latestAnswer.columns" :key="col" class="insights-metric">
                    <small>{{ columnLabel(latestAnswer, col, idx) }}</small>
                    <strong>{{ pretty(latestAnswer.rows[0][col]) }}</strong>
                  </div>
                </div>
                <AnswerChart v-if="['bar','line','pie'].includes(latestAnswer.chartType) && latestAnswer.rows?.length > 1" :message="latestAnswer"/>
                <div v-if="latestAnswer.rows?.length > 1" class="insights-mini-summary">
                  共 {{ latestAnswer.rows.length }} 行结果，点击「数据」查看完整表格
                </div>
              </template>
              <div v-if="resultTab === 'table' && latestAnswer.rows?.length" class="insights-table-wrap">
                <table class="result-table">
                  <thead><tr><th v-for="(col, idx) in latestAnswer.columns" :key="col">{{ columnLabel(latestAnswer, col, idx) }}</th></tr></thead>
                  <tbody><tr v-for="(row, idx) in latestAnswer.rows" :key="idx">
                    <td v-for="col in latestAnswer.columns" :key="col">{{ pretty(row[col]) }}</td>
                  </tr></tbody>
                </table>
              </div>
            </template>
          </template>
        </div>
      </aside>
      <footer class="composer">
        <div class="composer-row">
          <textarea v-model="question" :rows="isDesktop ? 3 : 1" maxlength="800"
            :placeholder="isDesktop ? '请输入你的问题，例如：昨天的营业额是多少？' : '问问经营数据，例如：昨日营业额'"
            @keydown.enter.exact.prevent="ask()"></textarea>
          <button v-if="isDesktop && busy" class="send-btn stop-btn" type="button"
            title="停止查询" aria-label="停止查询" @click="stopAsk"><span class="stop-square"></span></button>
          <button v-else class="send-btn" type="button" :disabled="busy || !question.trim()"
            aria-label="发送问题" @click="ask()">
            <svg v-if="isDesktop" width="23" height="23" viewBox="0 0 24 24" fill="none"
              stroke="currentColor" stroke-width="2.5" stroke-linecap="round"
              stroke-linejoin="round" aria-hidden="true"><path d="M12 19V5M5 12l7-7 7 7"/></svg>
            <van-icon v-else name="arrow-up" size="20"/>
          </button>
        </div>
        <small>DataAsk · 仅供内部经营分析 · 以实际业务口径为准</small>
      </footer>
      <van-popup v-model:show="chartsVisible" :position="isDesktop ? 'center' : 'bottom'" round
        :style="{ width: isDesktop ? 'min(680px,90vw)' : '100%', height: isDesktop ? 'min(650px,82vh)' : '75%' }">
        <div class="drawer">
          <div class="drawer-header"><b>我的图表</b><van-icon name="cross" @click="chartsVisible=false"/></div>
          <p class="muted">收藏保存查询口径，点击图表会重新查询最新业务数据。</p>
          <div v-for="item in savedCharts" :key="item.id" class="session-item" @click="revisitChart(item)">
            <van-icon name="chart-trending-o"/><span>{{ item.title }}</span><van-icon name="arrow"/>
          </div>
          <p v-if="!savedCharts.length" class="muted">暂时没有收藏的图表</p>
        </div>
      </van-popup>
      <van-popup v-model:show="sharingVisible" :position="isDesktop ? 'center' : 'bottom'" round
        :style="{ width: isDesktop ? 'min(500px,90vw)' : '100%' }">
        <div class="drawer share-dialog">
          <div class="drawer-header"><b>共享图表</b><van-icon name="cross" @click="sharingVisible=false"/></div>
          <label for="pc-share-scope">共享范围</label>
          <select id="pc-share-scope" v-model="shareScope" class="join-select">
            <option value="PRIVATE">仅自己可见</option>
            <option value="ALL">所有系统用户</option>
            <option value="SELECTED">指定成员</option>
          </select>
          <label v-if="shareScope==='SELECTED'" for="pc-share-users">成员用户名（英文逗号分隔）</label>
          <input v-if="shareScope==='SELECTED'" id="pc-share-users" v-model="shareMembers" class="join-select"
            placeholder="例如：zhangsan,lisi"/>
          <van-button type="primary" block @click="applyShare">保存共享权限</van-button>
        </div>
      </van-popup>
      <van-popup v-model:show="historyVisible" position="left" :style="{ width: '85%', maxWidth: '340px', height: '100%' }">
        <div class="drawer">
          <div class="drawer-header"><b>历史对话</b><van-icon name="cross" @click="historyVisible=false"/></div>
          <button class="drawer-new" @click="newConversation"><van-icon name="plus"/> 开启新对话</button>
          <div v-for="item in sessions" :key="item.id" class="session-item" @click="openSession(item)">
            <van-icon name="chat-o"/><span>{{ item.title }}</span>
          </div>
          <p v-if="!sessions.length" class="muted">暂无历史会话</p>
          <div class="drawer-bottom"><span>{{ me.displayName || me.username }}</span><button @click="logout">退出登录</button></div>
        </div>
      </van-popup>
      <van-popup v-model:show="adminVisible" :position="isDesktop ? 'center' : 'bottom'" round
        :style="{ width: isDesktop ? 'min(920px, 92vw)' : '100%', height: isDesktop ? 'min(780px, 88vh)' : '88%' }">
        <div class="admin-panel">
          <div class="drawer-header"><b>系统管理</b><van-icon name="cross" @click="adminVisible=false"/></div>
          <button type="button" class="status-row status-action" @click="showConfig('starrocks')">
            <span>StarRocks ADS</span>
            <span class="status-value"><strong>{{ status.starrocksConfigured ? '已配置' : '未配置' }}</strong><van-icon name="arrow"/></span>
          </button>
          <button type="button" class="status-row status-action" @click="showConfig('ai')">
            <span>对话模型</span>
            <span class="status-value"><strong>{{ status.aiConfigured ? status.model : (status.aiKeyConfigured && status.aiUrlConfigured ? '检查模型名称' : '检查配置') }}</strong><van-icon name="arrow"/></span>
          </button>
          <button type="button" class="status-row status-action" @click="showConfig('embedding')">
            <span>Embedding 模型</span>
            <span class="status-value">
              <strong>{{ status.embeddingConfigured ? status.embeddingModel : '未启用 / 未配置' }}</strong>
              <van-icon name="arrow"/>
            </span>
          </button>
          <div class="admin-build-status">DataAsk 工作台 · 版本 {{ buildSha }} · PC宽屏使用三栏布局</div>
          <h3>开放给 AI 的 ADS 表</h3>
          <p class="muted">默认不开放任何表。仅授权通过审核的经营数据表。</p>
          <div v-for="table in allowedTables" :key="table.name" class="admin-table">
            <div class="table-head">
              <span @click="expandedTable = expandedTable === table.name ? '' : table.name">
                <van-icon name="arrow-down"/> {{ table.name }}
              </span>
              <van-switch v-model="table.enabled" size="19px" @change="toggleAllowed(table)"/>
            </div>
            <div v-if="expandedTable === table.name" class="columns-list">
              <div v-for="col in table.columns" :key="col.name"><b>{{ col.name }}</b> {{ col.type }} <span>{{ col.comment }}</span></div>
            </div>
          </div>
          <p v-if="!allowedTables.length" class="muted">没有读取到 ADS 表，请检查连接配置。</p>
          <section class="metric-admin join-admin">
            <h3>ADS 跨表关联规则</h3>
            <p class="muted">多张 ADS 汇总表只有明确关联键和业务粒度才能 JOIN。系统不允许未经配置的跨表关联，以免统计金额重复。</p>
            <div v-for="rule in joinRules" :key="rule.id" class="metric-batch">
              <div class="metric-batch-details">
                <b>{{ rule.leftTable }} → {{ rule.rightTable }}</b>
                <small>关联键：{{ rule.leftKeys.join(', ') }} = {{ rule.rightKeys.join(', ') }}</small>
                <small>{{ rule.cardinality === 'ONE_TO_ONE' ? '一对一' : '多对一' }} · {{ rule.grainNote }}</small>
              </div>
              <button type="button" class="metric-delete" :aria-label="'删除关联规则'+rule.id" @click="removeJoinRule(rule)">
                <van-icon name="delete-o" size="18"/>
              </button>
            </div>
            <div class="join-editor">
              <label>主表（已授权）</label>
              <select v-model="joinForm.leftTable" class="join-select">
                <option disabled value="">选择 ADS 主表</option>
                <option v-for="item in allowedTables.filter(t => t.enabled)" :key="item.name" :value="item.name">{{ item.name }}</option>
              </select>
              <label>关联表（已授权）</label>
              <select v-model="joinForm.rightTable" class="join-select">
                <option disabled value="">选择关联表</option>
                <option v-for="item in allowedTables.filter(t => t.enabled)" :key="item.name" :value="item.name">{{ item.name }}</option>
              </select>
              <van-field v-model="joinForm.leftKeys" label="主表关联键" label-width="90px" placeholder="city_name,dt"/>
              <van-field v-model="joinForm.rightKeys" label="关联表关联键" label-width="90px" placeholder="city,dt"/>
              <van-field label="关联关系" label-width="90px">
                <template #input>
                  <van-radio-group v-model="joinForm.cardinality" direction="horizontal">
                    <van-radio name="ONE_TO_ONE">一对一</van-radio>
                    <van-radio name="MANY_TO_ONE">多对一</van-radio>
                  </van-radio-group>
                </template>
              </van-field>
              <van-field v-model="joinForm.grainNote" label="粒度说明" label-width="90px"
                type="textarea" rows="2" maxlength="800" autosize placeholder="例如：两表均按天和城市唯一汇总，city+dt 构成唯一键"/>
              <van-button type="primary" block plain :loading="joinSaving" @click="saveJoinRule">保存关联规则</van-button>
              <p class="muted">请先在数据仓库核对唯一性；跨表关联不能代替事实表口径验证。默认禁止多对多关联。</p>
            </div>
          </section>
          <section class="metric-admin">
            <div class="metric-section-title">
              <h3>指标口径 · Excel</h3>
              <p class="muted">上传现有的指标定义，AI 将依据确认的业务口径理解 ADS 数据。当前已导入 {{ metricInfo.total || 0 }} 条。</p>
            </div>
            <input ref="metricFileInput" class="file-input-hidden" type="file"
              accept=".xlsx,.xls" @change="selectMetricFile" aria-label="选择指标口径Excel文件"/>
            <div class="metric-action-row">
              <van-button size="small" type="primary" icon="plus" plain @click="chooseMetricFile">上传 Excel</van-button>
              <van-button size="small" plain icon="down" @click="downloadMetricTemplate">下载模板</van-button>
            </div>
            <p class="muted">支持 .xlsx / .xls，最大 5 MB；至少包含“指标名称”和“指标口径”两列。</p>
            <div v-if="metricPreviewBusy" class="metric-upload-state">
              <van-loading size="16px"/> 正在解析 Excel ...
            </div>
            <p v-if="metricError" class="metric-error">{{ metricError }}</p>
            <div v-if="metricPreview" class="metric-preview">
              <div class="metric-preview-heading">
                <b>{{ metricPreview.filename }}</b><span>识别到 {{ metricPreview.count }} 条</span>
              </div>
              <p v-for="notice in metricPreview.notices" :key="notice" class="metric-note">{{ notice }}</p>
              <div class="metric-preview-table" tabindex="0" role="region" aria-label="指标预览表格">
                <table>
                  <thead><tr><th>指标名称</th><th>业务口径</th><th>ADS 表</th></tr></thead>
                  <tbody><tr v-for="(item, idx) in metricPreview.samples" :key="idx">
                    <td>{{ item.metricName }}</td><td>{{ item.definition }}</td><td>{{ item.adsTable || '—' }}</td>
                  </tr></tbody>
                </table>
              </div>
              <van-button type="primary" block :loading="metricImportBusy" @click="importMetrics">
                确认导入 {{ metricPreview.count }} 条指标
              </van-button>
            </div>
            <div v-if="metricInfo.batches?.length" class="metric-batches">
              <h4>导入历史</h4>
              <div v-for="batch in metricInfo.batches" :key="batch.id" class="metric-batch">
                <div class="metric-batch-details"><b>{{ batch.filename }}</b>
                  <small>{{ batch.count }} 条 · {{ batch.createdAt }}</small></div>
                <button type="button" class="metric-delete" :aria-label="'删除'+batch.filename"
                  @click="deleteMetricBatch(batch)"><van-icon name="delete-o" size="18"/></button>
              </div>
            </div>
            <p v-else class="muted">尚未上传指标口径文件。</p>
            <div v-if="metricInfo.metrics?.length" class="metric-definition-list">
              <h4>最近导入的指标</h4>
              <div v-for="item in metricInfo.metrics.slice(0, 8)" :key="item.id" class="metric-definition-item">
                <strong>{{ item.metricName }}</strong>
                <p>{{ item.definition }}</p>
              </div>
            </div>
          </section>
          <h3>内部账号管理</h3>
          <div v-for="user in users" :key="user.username" class="status-row">
            <span>{{ user.displayName }}（{{ user.username }}）</span><small>{{ user.role }}</small>
          </div>
          <van-field v-model="newUser.username" label="新账号" placeholder="英文账号" />
          <van-field v-model="newUser.displayName" label="姓名" placeholder="显示名称" />
          <van-field v-model="newUser.password" label="密码" type="password" placeholder="至少10位" />
          <van-field label="角色"><template #input><van-radio-group v-model="newUser.role" direction="horizontal">
            <van-radio name="USER">普通用户</van-radio><van-radio name="ADMIN">管理员</van-radio>
          </van-radio-group></template></van-field>
          <van-button type="primary" block plain @click="addUser">创建账号</van-button>
          <p class="muted">AI 配置直接使用服务器 application.yml，不会在浏览器回显密钥；StarRocks 配置可通过上方入口测试和保存。</p>
        </div>
      </van-popup>
      <van-popup v-model:show="configVisible" :position="isDesktop ? 'center' : 'bottom'" round
        :style="{ width: isDesktop ? 'min(620px, 92vw)' : '100%', height: isDesktop ? 'min(740px, 85vh)' : '80%' }">
        <div class="config-panel">
          <div class="drawer-header">
            <b>{{ configKind === 'starrocks' ? 'StarRocks ADS 数据源'
              : configKind === 'ai' ? '对话模型管理' : 'Embedding 模型管理' }}</b>
            <van-icon name="cross" @click="configVisible = false"/>
          </div>
          <template v-if="configKind === 'starrocks'">
            <p class="muted">配置 StarRocks FE 只读账号，测试连接成功后保存并立即读取 ADS 字段注释。</p>
            <van-field v-model="srForm.jdbcUrl" label="JDBC 地址" label-width="85px" placeholder="jdbc:mysql://192.168.1.10:9030/ads"/>
            <van-field v-model="srForm.schema" label="ADS 数据库" label-width="85px" placeholder="ads"/>
            <van-field v-model="srForm.username" label="用户名" label-width="85px" placeholder="只读用户名"/>
            <van-field v-model="srForm.password" label="密码" label-width="85px" type="password"
              :placeholder="settingsData.starrocks?.passwordSet ? '已配置，留空不修改' : '请输入密码'"/>
            <p class="config-note">数据库密码由后端保存，留空会保留现有密码。</p>
          </template>
          <template v-else-if="configKind === 'ai'">
            <p class="muted">在这里直接配置用于自然语言分析和 SQL 生成的大模型。保存后无需重启服务。</p>
            <van-field v-model="aiForm.baseUrl" label="Base URL" label-width="95px" placeholder="https://api.example.com/v1"/>
            <van-field v-model="aiForm.model" label="模型名称" label-width="95px" placeholder="输入服务商模型 ID"/>
            <van-field v-model="aiForm.apiKey" label="API Key" label-width="95px" type="password"
              autocomplete="new-password" :placeholder="settingsData.ai?.apiKeySet ? '已设置 · 留空不修改' : '填写 API Key'"/>
            <van-cell title="清除原有 API Key" label="仅在主动更换为无密钥的本地模型时勾选">
              <template #right-icon><van-switch v-model="aiForm.clearKey" size="20px"/></template>
            </van-cell>
            <p class="config-note">API Key 从此页面提交给后端保存，编辑时不会明文回显。</p>
          </template>
          <template v-else>
            <p class="muted">用于 ADS 表、字段注释和 Excel 指标口径语义匹配。默认阿里云百炼 DashScope 原生 API；也支持 OpenAI 兼容 /embeddings 接口。</p>
            <van-cell title="启用 Embedding"><template #right-icon>
              <van-switch v-model="embeddingForm.enabled" size="20px"/>
            </template></van-cell>
            <van-field v-model="embeddingForm.baseUrl" label="Base URL" label-width="95px"
              placeholder="https://dashscope.aliyuncs.com/api/v1"/>
            <van-field v-model="embeddingForm.model" label="模型名称" label-width="95px"
              placeholder="qwen3.7-text-embedding-flash"/>
            <van-field v-model.number="embeddingForm.dimensions" label="向量维度" label-width="95px"
              type="number" placeholder="1024"/>
            <van-field v-model="embeddingForm.apiKey" label="API Key" label-width="95px" type="password"
              autocomplete="new-password" :placeholder="settingsData.embedding?.apiKeySet ? '已设置 · 留空不修改' : '本地无密钥服务可留空'"/>
            <van-cell title="清除原有 API Key"><template #right-icon>
              <van-switch v-model="embeddingForm.clearKey" size="20px"/>
            </template></van-cell>
            <div class="semantic-status">
              <strong>语义索引</strong>
              <span>{{ semanticStatus.count || 0 }} 条 · {{ semanticStatus.indexedModel || '尚未构建' }}</span>
            </div>
            <p class="config-note">保存并测试模型后，点击“重建语义索引”。仅向模型发送授权 ADS 的字段描述及指标口径，不上传业务明细数据。</p>
          </template>
          <p v-if="configTestMessage" class="config-feedback">{{ configTestMessage }}</p>
          <div class="config-actions">
            <van-button block plain type="primary" :loading="configTestBusy"
              :disabled="configBusy || semanticBusy" @click="testConfig">测试连接</van-button>
            <van-button block type="primary" :loading="configBusy"
              :disabled="configTestBusy || semanticBusy" @click="saveConfig">保存配置</van-button>
            <van-button v-if="configKind==='embedding' && status.embeddingConfigured" block
              plain type="primary" :loading="semanticBusy"
              :disabled="configBusy || configTestBusy" @click="rebuildSemanticIndex">重建语义索引</van-button>
          </div>
        </div>
      </van-popup>
    </template>
  </div>
</template>

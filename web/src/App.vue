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
// Stored profile is display-only. Permissions are resolved from the authenticated server.
const me = ref(JSON.parse(sessionStorage.getItem('dataask_user') || '{}'))
const verifiedRole = ref('')
const loginForm = ref({ username: 'admin', password: '' })
const loggingIn = ref(false)
// Desktop account actions live in the top-right global header, not the sidebar.
const accountMenuOpen = ref(false)
const accountMenuRef = ref(null)
const changePasswordOpen = ref(false)
const changePasswordBusy = ref(false)
const passwordForm = ref({ oldPassword:'', newPassword:'', confirmPassword:'' })
function handleAccountOutsideClick(event) {
  if (accountMenuOpen.value && !accountMenuRef.value?.contains(event.target)) {
    accountMenuOpen.value = false
  }
}
function openPasswordChange() {
  accountMenuOpen.value = false
  passwordForm.value = { oldPassword:'', newPassword:'', confirmPassword:'' }
  changePasswordOpen.value = true
}
function closePasswordChange() {
  if (changePasswordBusy.value) return
  changePasswordOpen.value = false
  passwordForm.value = { oldPassword:'', newPassword:'', confirmPassword:'' }
}
async function submitPasswordChange() {
  if (changePasswordBusy.value) return
  const {oldPassword,newPassword,confirmPassword} = passwordForm.value
  if (!oldPassword) return showFailToast('请输入当前密码')
  if (newPassword.length < 10 || newPassword.length > 128)
    return showFailToast('新密码需为10到128位')
  if (newPassword === oldPassword) return showFailToast('新密码不能与当前密码相同')
  if (newPassword !== confirmPassword) return showFailToast('两次输入的新密码不一致')
  changePasswordBusy.value = true
  try {
    await api.post('/auth/change-password', { oldPassword, newPassword })
    changePasswordOpen.value = false
    passwordForm.value = { oldPassword:'', newPassword:'', confirmPassword:'' }
    logout()
    showSuccessToast('密码已更新，请使用新密码重新登录')
  } catch(e) {
    showFailToast(errorText(e))
  } finally {
    changePasswordBusy.value = false
  }
}
function desktopLogout() {
  accountMenuOpen.value = false
  logout()
}
const question = ref('')
const busy = ref(false)
const pendingQuestion = ref('')
// Desktop-only UI state. The miniapp HTML and narrow-screen template stay untouched.
const desktopFeedback = ref(null)
const answerViews = ref({})
const desktopQuestionRef = ref(null)
const chartsVisible = ref(false)
const savedCharts = ref([])
const sharedCharts = ref([])
const chartsBusy = ref(false)
// Saving a chart creates a visualization asset, not another chat.
const activeChart = ref(null)
const activeChartAnswer = ref(null)
const activeChartBusy = ref(false)
const activeChartRefreshing = ref(false)
const activeChartError = ref('')
const activeChartCached = ref(true)
const activeChartMode = ref('chart')
const activeChartStyle = ref('')
const activeChartCanPlot = computed(() =>
  !!activeChartAnswer.value && ['bar','line','pie'].includes(activeChartAnswer.value.chartType) &&
  (activeChartAnswer.value.rows?.length || 0) > 1)
const activeChartIsMetric = computed(() => activeChartAnswer.value?.chartType === 'metric' &&
  activeChartAnswer.value?.rows?.length === 1)
function closeChartDetail() {
  activeChart.value = null
  activeChartAnswer.value = null
  activeChartError.value = ''
  activeChartMode.value = 'chart'
}
async function loadChartDetail(chart) {
  if (!chart?.id || activeChartBusy.value) return
  activeChart.value = chart
  activeChartAnswer.value = null
  activeChartBusy.value = true
  activeChartError.value = ''
  activeChartMode.value = 'chart'
  activeChartStyle.value = chart.chartType || 'bar'
  if (isDesktop.value) desktopSection.value = 'charts'
  else chartsVisible.value = true
  try {
    const {data} = await api.get('/mini/charts/'+encodeURIComponent(chart.id)+'/view')
    activeChart.value = data.chart || chart
    activeChartAnswer.value = data.answer
    activeChartCached.value = !!data.cached
    activeChartMode.value = activeChartCanPlot.value ? 'chart' : activeChartIsMetric.value ? 'summary' : 'table'
    activeChartStyle.value = data.answer?.chartType || chart.chartType || 'bar'
  } catch(e) {
    activeChartError.value = errorText(e)
  } finally { activeChartBusy.value = false }
  if (activeChartAnswer.value) refreshChartDetail()
}
async function refreshChartDetail() {
  if (!activeChart.value?.id || activeChartRefreshing.value) return
  const id=activeChart.value.id
  activeChartRefreshing.value = true
  activeChartError.value = ''
  try {
    const {data} = await api.post('/mini/charts/'+encodeURIComponent(id)+'/refresh')
    if (activeChart.value?.id !== id) return
    activeChart.value = data.chart || activeChart.value
    activeChartAnswer.value = data.answer
    activeChartCached.value = false
    activeChartStyle.value = data.answer?.chartType || 'bar'
    activeChartMode.value = activeChartCanPlot.value ? 'chart' : activeChartIsMetric.value ? 'summary' : 'table'
  } catch(e) {
    if (activeChart.value?.id === id)
      activeChartError.value = '实时更新失败：'+errorText(e)+
        (activeChartAnswer.value ? '（当前保留上次已授权查询结果）' : '')
  } finally { activeChartRefreshing.value = false }
}
function openChartShare(chart) {
  if (!chart) return
  sharingChart.value=chart
  shareScope.value=chart.shareScope || 'PRIVATE'
  shareMembers.value=''
  sharingVisible.value=true
}
async function deleteSavedChart(chart) {
  if (!chart?.id) return
  try { await showConfirmDialog({title:'取消收藏',message:'确定取消收藏“'+chart.title+'”吗？'}) }
  catch {return}
  try {
    await api.delete('/mini/charts/'+encodeURIComponent(chart.id))
    if (activeChart.value?.id===chart.id) closeChartDetail()
    await loadSavedCharts()
    showSuccessToast('已取消收藏')
  } catch(e) { showFailToast(errorText(e)) }
}
const chartSearch = ref('')
const chartTypeFilter = ref('ALL')
const chartSortOrder = ref('recent')
const chartTypeOptions = computed(() => {
  const types = [...new Set([...savedCharts.value, ...sharedCharts.value]
    .map(item => String(item.chartType || '').trim())
    .filter(Boolean))]
  return types.sort((a,b) => a.localeCompare(b))
})
function filterChartItems(items) {
  const keyword = chartSearch.value.trim().toLowerCase()
  return [...items].filter(item => {
    const matchesText = !keyword ||
      [item.title, item.question, item.ownerUsername].some(
        value => String(value || '').toLowerCase().includes(keyword))
    const matchesType = chartTypeFilter.value === 'ALL' ||
      String(item.chartType || '') === chartTypeFilter.value
    return matchesText && matchesType
  }).sort((a,b) => {
    const at = new Date(a.updatedAt || a.createdAt || 0).getTime() || 0
    const bt = new Date(b.updatedAt || b.createdAt || 0).getTime() || 0
    return chartSortOrder.value === 'oldest' ? at-bt : bt-at
  })
}
const filteredSavedCharts = computed(() => filterChartItems(savedCharts.value))
const filteredSharedCharts = computed(() => filterChartItems(sharedCharts.value))
function chartTypeLabel(type) {
  return ({line:'折线图', bar:'柱状图', pie:'饼图', metric:'指标卡', table:'数据表'})[type] || type || '图表'
}
function isRedundantSummary(msg) {
  if (!msg?.rows?.length || msg.unsupported) return false
  const summary = String(msg.summary || '').replace(/\s+/g,' ').trim()
  if (!summary) return true
  // Suppress generic system narration, never hide actual calculated business insights.
  return /^(已从\s*StarRocks|已查询到|查询到|已返回|已获取|数据查询成功|本次查询返回)/i.test(summary) &&
    /(\d+\s*行|条数据|真实数据|查询结果|点击下方|继续分析|来自)/.test(summary)
}

const desktopSection = ref('overview')
const adminExpanded = ref(false)
const desktopAdminSections = ['admin-overview','admin-source','admin-model','admin-embedding','admin-tables','admin-metrics','admin-users','admin-status']
const adminSectionBusy = ref(false)
const adminSectionError = ref('')
let adminLoadSequence = 0
const userBusy = ref(false)
const userEditVisible = ref(false)
const userEditSaving = ref(false)
const userEditForm = ref({ username:'', displayName:'', role:'USER', password:'' })
const unreadableConfigParts = computed(() => [
  status.value.starrocksCredentialUnreadable ? 'StarRocks 数据源密码' : null,
  status.value.aiCredentialUnreadable ? '对话模型 API Key' : null,
  status.value.embeddingCredentialUnreadable ? 'Embedding API Key' : null,
].filter(Boolean))
const configuredStatusRows = computed(() => [
  { key:'数据源', configured:!!status.value.starrocksConfigured,
    detail:status.value.starrocksCredentialUnreadable ? '已保存密码无法解密，请重新填写' : (status.value.schema || '未选择 ADS 数据库'), section:'admin-source' },
  { key:'对话模型', configured:!!status.value.aiConfigured,
    detail:status.value.aiCredentialUnreadable ? '已保存 API Key 无法解密，请重新填写' : (status.value.model || '尚未配置模型'), section:'admin-model' },
  { key:'Embedding', configured:!!status.value.embeddingConfigured,
    detail:status.value.embeddingCredentialUnreadable ? '已保存 API Key 无法解密，请重新填写' : (status.value.embeddingModel || '尚未启用'), section:'admin-embedding' },
])

const desktopSectionTitle = computed(() => ({
  overview:'工作台',ask:'智能问数',charts:'我的图表',history:'历史会话',
  'admin-overview':'系统管理','admin-source':'数据源配置','admin-model':'对话模型',
  'admin-embedding':'Embedding 模型','admin-tables':'ADS 表授权',
  'admin-metrics':'指标口径','admin-users':'用户管理',
  'admin-status':'配置状态'
})[desktopSection.value] || '工作台')
const desktopIsAdminSection = computed(() => desktopSection.value.startsWith('admin-'))
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
function fullSessionTime(value) {
  if (!value) return '—'
  const raw = String(value).replace('T',' ')
  const normalized = raw.match(/^(\d{4}-\d{2}-\d{2}) (\d{2}:\d{2})/)
  if (normalized) return normalized[1] + ' ' + normalized[2]
  const timestamp = new Date(value)
  return Number.isNaN(timestamp.getTime()) ? raw.slice(0,16) : timestamp.toLocaleString('zh-CN',{hour12:false})
}
function sessionTimeLabel(updatedAt) {
  if (!updatedAt) return ''
  const text = String(updatedAt)
  const date = text.slice(0, 10)
  const time = text.slice(11, 16)
  const today = new Date()
  const todayDate = [today.getFullYear(), String(today.getMonth() + 1).padStart(2, '0'), String(today.getDate()).padStart(2, '0')].join('-')
  if (date === todayDate) return '今天 ' + time
  const yesterday = new Date(today.getFullYear(), today.getMonth(), today.getDate() - 1)
  const yesterdayDate = [yesterday.getFullYear(), String(yesterday.getMonth() + 1).padStart(2, '0'), String(yesterday.getDate()).padStart(2, '0')].join('-')
  if (date === yesterdayDate) return '昨天 ' + time
  return date && time ? date.slice(5) + ' ' + time : text.slice(0, 16)
}
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
// Quick BI-inspired desktop querying. A dataset selection is enforced by the server.
const approvedDatasets = ref([])
const selectedDataset = ref('')
const chartStyles = ref({})
const supportedChartTypes = ['bar','line','pie']
function pieSupported(msg) {
  const columns = msg.columns || [], rows = msg.rows || []
  const numeric = columns.filter(key => rows.some(row =>
    row[key] !== null && row[key] !== '' && Number.isFinite(Number(row[key]))))
  const measures = numeric.filter(key => /count|cnt|amount|orders|revenue|total|sum|num|income|sales|金额|收入|数量|订单/i.test(key))
  const measure = measures[0] || numeric[numeric.length - 1]
  if (!measure || rows.length < 2) return false
  const values = rows.slice(0, 8).map(row => Number(row[measure]))
  return values.every(value => Number.isFinite(value) && value >= 0) && values.some(value => value > 0)
}
function chartStyle(msg) {
  const requested = chartStyles.value[msg.id] || msg.chartType
  return requested === 'pie' && !pieSupported(msg) ? 'bar' : requested
}
function setChartStyle(msg, type) {
  if (!supportedChartTypes.includes(type) || !msg.rows?.length || (type === 'pie' && !pieSupported(msg))) return
  chartStyles.value = { ...chartStyles.value, [msg.id]: type }
  setResultView(msg, 'chart')
}
async function loadApprovedDatasets() {
  if (!loggedIn.value) { approvedDatasets.value = []; return }
  try {
    const { data } = await api.get('/chat/datasets')
    approvedDatasets.value = Array.isArray(data) ? data : []
    if (selectedDataset.value && !approvedDatasets.value.includes(selectedDataset.value)) selectedDataset.value = ''
  } catch(e) {
    approvedDatasets.value = []
    if (isDesktop.value) showFailToast('读取可用数据失败：'+errorText(e))
  }
}
const isAdmin = computed(() => loggedIn.value && verifiedRole.value === 'ADMIN')
function requireAdmin() {
  if (isAdmin.value) return true
  showFailToast('仅系统管理员可以管理模型、数据源和系统配置')
  return false
}
async function refreshAuthenticatedRole() {
  if (!sessionStorage.getItem('dataask_token')) {
    verifiedRole.value = ''
    return
  }
  verifiedRole.value = ''
  adminVisible.value = false
  configVisible.value = false
  try {
    const { data } = await api.get('/auth/me')
    const username = String(data.username || '')
    const role = data.role === 'ADMIN' ? 'ADMIN' : 'USER'
    // Do not trust a cached role, even when somebody alters sessionStorage manually.
    const displayName = me.value.username === username ? (me.value.displayName || username) : username
    me.value = { username, displayName, role }
    sessionStorage.setItem('dataask_user', JSON.stringify(me.value))
    verifiedRole.value = role
  } catch (e) {
    verifiedRole.value = ''
    if (e.response?.status === 401) {
      sessionStorage.removeItem('dataask_token')
      sessionStorage.removeItem('dataask_user')
      loggedIn.value = false
      me.value = {}
    }
  }
}
function resultView(msg) {
  const explicit = answerViews.value[msg.id]
  if (explicit) return explicit
  if (msg.chartType === 'metric' && msg.rows?.length === 1) return 'summary'
  return ['line', 'bar', 'pie'].includes(msg.chartType) && msg.rows?.length > 1 ? 'chart' : 'table'
}
function setResultView(msg, view) { answerViews.value = { ...answerViews.value, [msg.id]: view } }
function focusDesktopQuestion() { nextTick(() => { if (isDesktop.value) desktopQuestionRef.value?.focus() }) }
async function copyAnswer(msg) {
  const data = [msg.title, msg.summary,
    ...(msg.rows || []).slice(0, 30).map(row => (msg.columns || []).map(c => `${msg.columnLabels?.[c] || c}: ${row[c] ?? '—'}`).join('；'))
  ].filter(Boolean).join('\n')
  try {
    if (navigator.clipboard?.writeText) await navigator.clipboard.writeText(data)
    else {
      const el = document.createElement('textarea')
      el.value = data; el.style.position = 'fixed'; el.style.left = '-9999px'
      document.body.appendChild(el); el.select()
      if (!document.execCommand('copy')) throw new Error('浏览器禁止复制')
      el.remove()
    }
    showSuccessToast('结果已复制')
  } catch { showFailToast('复制失败，请检查浏览器剪贴板权限') }
}

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
    verifiedRole.value = data.role === 'ADMIN' ? 'ADMIN' : 'USER'
    me.value = user; loggedIn.value = true; loginForm.value.password = ''
    await Promise.all([loadSessions(), loadApprovedDatasets(), loadSavedCharts()])
    if (isDesktop.value) desktopSection.value = 'overview'
  } catch (e) { showFailToast(errorText(e)) }
  finally { loggingIn.value = false }
}
function logout() {
  if (isDesktop.value && busy.value) stopAsk()
  sessionStorage.removeItem('dataask_token')
  sessionStorage.removeItem('dataask_user')
  loggedIn.value = false
  verifiedRole.value = ''
  configVisible.value = false
  accountMenuOpen.value = false
  changePasswordOpen.value = false
  passwordForm.value = { oldPassword:'', newPassword:'', confirmPassword:'' }
  messages.value = []; sessionId.value = ''; historyVisible.value = false; adminVisible.value = false
  desktopFeedback.value = null
  desktopSection.value = 'overview'
  adminSectionError.value = ''
  adminSectionBusy.value = false
  adminExpanded.value = false
  savedCharts.value = []
  sharedCharts.value = []
  selectedDataset.value = ''
  approvedDatasets.value = []
}
async function loadSessions() {
  try { sessions.value = (await api.get('/chat/sessions')).data }
  catch (e) { showFailToast(errorText(e)) }
}
function switchDesktopSection(section) {
  const normal = ['overview','ask','charts','history'].includes(section)
  if (!normal && !(desktopAdminSections.includes(section) && isAdmin.value)) return
  if (isDesktop.value && busy.value && section !== 'ask') stopAsk()
  desktopSection.value = section
  if (desktopAdminSections.includes(section)) adminExpanded.value = true
  if (section === 'charts') { closeChartDetail(); loadSavedCharts() }
  if (section === 'history') loadSessions()
  if (section === 'ask') focusDesktopQuestion()
}
async function loadSavedCharts() {
  if (!loggedIn.value) return
  chartsBusy.value = true
  try {
    const [mine, shared] = await Promise.all([
      api.get('/mini/charts?view=mine'),
      api.get('/mini/charts?view=shared')
    ])
    savedCharts.value = Array.isArray(mine.data) ? mine.data : []
    sharedCharts.value = Array.isArray(shared.data) ? shared.data : []
  } catch (e) { showFailToast(errorText(e)) }
  finally { chartsBusy.value = false }
}
async function openSession(item) {
  if (isDesktop.value && busy.value) stopAsk()
  try {
    messages.value = (await api.get('/chat/sessions/' + item.id + '/messages')).data
    sessionId.value = item.id
    if (isDesktop.value) desktopSection.value = 'ask'
    const lastDataset = messages.value.at(-1)?.dataset || ''
    selectedDataset.value = lastDataset
    historyVisible.value = false
    desktopFeedback.value = null
    scrollEnd()
  } catch (e) { showFailToast(errorText(e)) }
}
function newConversation() {
  if (busy.value) stopAsk()
  sessionId.value = ''; messages.value = []; historyVisible.value = false
  if (isDesktop.value) {
    desktopSection.value = 'ask'
    // The desktop dataset chooser is intentionally hidden; a new conversation uses all approved tables.
    selectedDataset.value = ''
  }
  desktopFeedback.value = null
  focusDesktopQuestion()
}
async function openSavedCharts() {
  if (isDesktop.value) {
    switchDesktopSection('charts')
    return
  }
  try {
    const { data } = await api.get('/mini/charts?view=mine')
    savedCharts.value = data
    chartsVisible.value = true
  } catch (e) { showFailToast(errorText(e)) }
}
async function revisitChart(chart) {
  await loadChartDetail(chart)
}
async function saveChart(msg) {
  try {
    const { data } = await api.post('/mini/charts', { sourceMessageId: msg.id, title: msg.question })
    showSuccessToast('图表已收藏')
    if (isDesktop.value) loadSavedCharts()
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
  if (isDesktop.value) desktopFeedback.value = { type: 'stopped', question: pendingQuestion.value, message: '本次查询已手动停止，没有生成新的分析结果。' }
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
  if (isDesktop.value) desktopSection.value = 'ask'
  if (text.length > 800) return showToast('提问不能超过800字')
  const sequence = ++askSequence
  const controller = new AbortController()
  const requestId = globalThis.crypto?.randomUUID?.() || ('pc-' + Date.now() + '-' + Math.random().toString(36).slice(2))
  activeController = controller
  activeRequestId = requestId
  busy.value = true
  pendingQuestion.value = text
  if (isDesktop.value) desktopFeedback.value = null
  question.value = ''
  scrollEnd()
  try {
    const { data } = await api.post('/chat/ask', {
      question: text, sessionId: sessionId.value || null, sourceMessageId, requestId,
      dataset: isDesktop.value ? selectedDataset.value : null
    }, { signal: controller.signal })
    if (sequence !== askSequence || controller.signal.aborted) return
    sessionId.value = data.sessionId
    messages.value.push(data)
    await loadSessions()
    scrollEnd()
  } catch (e) {
    if (sequence !== askSequence || controller.signal.aborted || axios.isCancel(e)) return
    if (isDesktop.value) {
      desktopFeedback.value = { type: 'error', question: text, message: errorText(e) }
    } else {
      question.value = text
      showFailToast(errorText(e))
    }
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
function hydrateConfigForms() {
  const sr = settingsData.value.starrocks || {}
  const ai = settingsData.value.ai || {}
  const embedding = settingsData.value.embedding || {}
  srForm.value = {
    jdbcUrl: sr.jdbcUrl || '', username: sr.username || '',
    password: '', schema: sr.schema || 'ads'
  }
  aiForm.value = { baseUrl: ai.baseUrl || '', model: ai.model || '', apiKey:'', clearKey:false }
  embeddingForm.value = {
    baseUrl: embedding.baseUrl || 'https://dashscope.aliyuncs.com/api/v1',
    model: embedding.model || 'qwen3.7-text-embedding-flash',
    apiKey:'', clearKey:false, enabled:!!embedding.enabled,
    dimensions:embedding.dimensions || 1024
  }
}
async function loadAdminSectionData(section) {
  const request = ++adminLoadSequence
  adminSectionBusy.value = true
  adminSectionError.value = ''
  try {
    if (['admin-overview','admin-status'].includes(section)) {
      await refreshAdminStatus()
    } else if (['admin-source','admin-model','admin-embedding'].includes(section)) {
      await refreshAdminStatus()
      hydrateConfigForms()
      if (section === 'admin-embedding') {
        semanticStatus.value = (await api.get('/admin/semantic/status')).data
      }
    } else if (section === 'admin-tables') {
      await refreshAdminStatus()
      if (status.value.starrocksConfigured) {
        allowedTables.value = (await api.get('/admin/tables')).data
      } else {
        allowedTables.value = []
        adminSectionError.value = '尚未配置 StarRocks 数据源，请先在数据源配置中连接 ADS 数据库。'
      }
    } else if (section === 'admin-metrics') {
      metricInfo.value = (await api.get('/admin/metrics')).data
    } else if (section === 'admin-users') {
      users.value = (await api.get('/admin/users')).data
    }
  } catch (e) {
    if (request === adminLoadSequence) {
      adminSectionError.value = '读取“' + desktopSectionTitle.value + '”失败：' + errorText(e)
      showFailToast(adminSectionError.value)
    }
  } finally {
    if (request === adminLoadSequence) adminSectionBusy.value = false
  }
}
async function openAdmin() {
  // Model/data-source management is a desktop-only experience.
  if (!isDesktop.value) return
  if (!requireAdmin()) return
  if (isDesktop.value) {
    adminVisible.value = false
    switchDesktopSection('admin-overview')
    await loadAdminSectionData('admin-overview')
    return
  }
  adminVisible.value = true
  try {
    await refreshAdminStatus()
    users.value = (await api.get('/admin/users')).data
    await Promise.all([reloadTables(),reloadMetrics(),reloadSemanticStatus()])
  } catch(e) { showFailToast('读取系统管理失败：'+errorText(e)) }
}
function toggleAdminNavigation() {
  if (!isAdmin.value) return
  if (!adminExpanded.value) { openAdmin(); return }
  adminExpanded.value = false
  if (desktopIsAdminSection.value) switchDesktopSection('overview')
}
async function refreshAdminOverview() {
  if (!requireAdmin()) return
  await loadAdminSectionData('admin-status')
  if (!adminSectionError.value) showSuccessToast('配置状态已更新')
}
async function openAdminSection(section) {
  if (!requireAdmin() || !desktopAdminSections.includes(section)) return
  if (section === 'admin-source') configKind.value = 'starrocks'
  if (section === 'admin-model') configKind.value = 'ai'
  if (section === 'admin-embedding') configKind.value = 'embedding'
  configTestMessage.value = ''
  configVisible.value = false
  // Navigate first. A failed API must not make the entire module appear unclickable.
  switchDesktopSection(section)
  await loadAdminSectionData(section)
}

async function reloadSemanticStatus() {
  try { semanticStatus.value = (await api.get('/admin/semantic/status')).data }
  catch (e) { semanticStatus.value = { count: 0 }; }
}
async function rebuildSemanticIndex() {
  if (!requireAdmin()) return
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
  if (!requireAdmin()) return
  if (isDesktop.value) {
    const section = kind === 'starrocks' ? 'admin-source' : kind === 'ai' ? 'admin-model' : 'admin-embedding'
    await openAdminSection(section)
    return
  }
  configKind.value = kind
  configTestMessage.value = ''
  try {
    await refreshAdminStatus()
    hydrateConfigForms()
    if (kind === 'embedding') await reloadSemanticStatus()
    configVisible.value = true
  } catch (e) { showFailToast('加载配置失败：' + errorText(e)) }
}
function modelPayload(kind) {
  return kind === 'ai' ? aiForm.value : embeddingForm.value
}
async function testConfig() {
  if (!requireAdmin()) return
  if (configTestBusy.value) return
  configTestBusy.value = true
  configTestMessage.value = ''
  try {
    const kind = configKind.value
    if (kind==='ai' && aiForm.value.clearKey) {
      configTestMessage.value = '已开启“清除原有 API Key”，测试请求将不带原密钥。请先关闭该开关，再测试已保存的模型连接。'
      showFailToast(configTestMessage.value)
      return
    }
    if (kind==='embedding' && embeddingForm.value.clearKey) {
      configTestMessage.value = '已开启“清除原有 API Key”。请先关闭开关再测试已保存的 Embedding 模型连接。'
      showFailToast(configTestMessage.value)
      return
    }
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
  if (!requireAdmin()) return
  if (configBusy.value) return
  configBusy.value = true
  configTestMessage.value = ''
  try {
    const kind = configKind.value
    let endpoint = '/admin/settings/starrocks'
    if (kind === 'ai') endpoint = '/admin/settings/ai'
    else if (kind === 'embedding') endpoint = '/admin/settings/embedding'
    const payload = kind === 'starrocks' ? srForm.value : modelPayload(kind)
    if ((kind==='ai' || kind==='embedding') && payload.clearKey) {
      try {
        await showConfirmDialog({
          title:'确认清除 API Key',
          message:'清除后将不再使用当前已保存的 API Key。若模型服务要求认证，后续请求会返回 HTTP 401。确定继续吗？',
          confirmButtonText:'确认清除', cancelButtonText:'保留密钥'
        })
      } catch { return }
    }
    const { data } = await api.post(endpoint, payload)
    if (kind === 'ai') { aiForm.value.apiKey = ''; aiForm.value.clearKey = false }
    if (kind === 'embedding') { embeddingForm.value.apiKey = ''; embeddingForm.value.clearKey = false }
    if (kind === 'starrocks') {
      await refreshAdminStatus()
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
  if (!requireAdmin()) { table.enabled = !table.enabled; return }
  try {
    await api.post('/admin/table-allow', { tableName: table.name, enabled: table.enabled })
    showSuccessToast(table.enabled ? '已授权' : '已取消授权')
  } catch (e) { table.enabled = !table.enabled; showFailToast(errorText(e)) }
}
async function addUser() {
  if (!requireAdmin() || userBusy.value) return
  const request = {
    username:newUser.value.username.trim(),
    displayName:newUser.value.displayName.trim(),
    password:newUser.value.password,
    role:newUser.value.role
  }
  if (!/^[A-Za-z0-9_-]{3,64}$/.test(request.username)) return showFailToast('账号应为3至64位字母、数字、_ 或 -')
  if (!request.displayName || request.displayName.length>80) return showFailToast('姓名长度应为1至80字符')
  if (request.password.length<10 || request.password.length>128) return showFailToast('初始密码需为10至128位')
  userBusy.value = true
  try {
    await api.post('/admin/users', request)
    users.value = (await api.get('/admin/users')).data
    newUser.value = { username:'', displayName:'', password:'', role:'USER' }
    showSuccessToast('用户创建成功')
  } catch (e) { showFailToast(errorText(e)) }
  finally { userBusy.value = false }
}
function editUser(user) {
  if (!requireAdmin()) return
  userEditForm.value = {
    username:user.username, displayName:user.displayName,
    role:user.role, password:''
  }
  userEditVisible.value = true
  nextTick(() => document.querySelector('.tb-user-editor')?.scrollIntoView({
    behavior:'smooth', block:'nearest'
  }))
}
function cancelUserEdit() {
  if (userEditSaving.value) return
  userEditVisible.value = false
  userEditForm.value = { username:'',displayName:'',role:'USER',password:'' }
}
async function saveUserProfile() {
  if (!requireAdmin() || userEditSaving.value || !userEditVisible.value) return
  const value=userEditForm.value
  const name=value.displayName.trim()
  if (!name || name.length>80) return showFailToast('姓名长度需要1至80位')
  userEditSaving.value = true
  try {
    await api.put('/admin/users/'+encodeURIComponent(value.username),{
      displayName:name, role:value.role
    })
    users.value = (await api.get('/admin/users')).data
    showSuccessToast('用户信息已保存')
    userEditVisible.value = false
    userEditForm.value = { username:'', displayName:'', role:'USER', password:'' }
  } catch(e) { showFailToast('保存用户失败：'+errorText(e)) }
  finally { userEditSaving.value=false }
}
async function resetUserPassword() {
  if (!requireAdmin() || userEditSaving.value || !userEditVisible.value) return
  const value=userEditForm.value
  if (value.password.length<10 || value.password.length>128)
    return showFailToast('重置密码需为10至128位')
  try {
    await showConfirmDialog({
      title:'确认重置密码',
      message:'确定为“'+value.username+'”重置密码吗？该用户的现有登录令牌将失效。'
    })
  } catch { return }
  userEditSaving.value = true
  try {
    await api.post('/admin/users/'+encodeURIComponent(value.username)+'/reset-password',
      { password:value.password })
    userEditForm.value.password=''
    showSuccessToast('密码已重置，该用户需要重新登录')
  } catch(e) { showFailToast('密码重置失败：'+errorText(e)) }
  finally { userEditSaving.value=false }
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
  document.addEventListener('click', handleAccountOutsideClick)
  if (loggedIn.value) {
    refreshAuthenticatedRole()
    loadSessions()
    loadApprovedDatasets()
    loadSavedCharts()
  }
})
onBeforeUnmount(() => {
  desktopMedia?.removeEventListener('change', window.__dataAskDesktopListener)
  document.removeEventListener('click', handleAccountOutsideClick)
})
</script>

<template>
  <div class="app-shell" :class="{ 'desktop-no-result': !resultPanelOpen, 'consumer-pc': isDesktop, 'quickbi-pc': isDesktop, 'tencentbi-pc': isDesktop, 'consumer-home': isDesktop && desktopSection==='ask' && !messages.length && !busy && !desktopFeedback }">
    <div v-if="!loggedIn" class="login-screen">
      <div class="login-logo">问</div>
      <h1>{{ isDesktop ? 'DataAsk' : 'DataAsk 数问' }}</h1>
      <p>{{ isDesktop ? '和数据聊聊天，让每个问题都有答案。' : '面向企业管理者的智能经营数据助手' }}</p>
      <div class="login-card">
        <h2>{{ isDesktop ? '欢迎回来' : '登录到数问' }}</h2>
        <van-field v-model="loginForm.username" label="账号" placeholder="请输入账号" autocomplete="username"/>
        <van-field v-model="loginForm.password" label="密码" placeholder="请输入密码" type="password" autocomplete="current-password" @keyup.enter="login"/>
        <van-button type="primary" block :loading="loggingIn" @click="login">登录</van-button>
        <p class="login-hint">首次启动由管理员配置初始密码，系统不会使用公开默认密码。</p>
      </div>
    </div>
    <template v-else>
      <aside class="desktop-sidebar" aria-label="DataAsk 功能导航">
        <div class="sidebar-brand">
          <div class="brand-icon">问</div>
          <div class="brand-title"><strong>DataAsk</strong><small>智能商业分析平台</small></div>
        </div>
        <div class="tb-nav-category">工作空间</div>
        <nav class="desktop-main-nav" aria-label="工作空间导航">
          <button type="button" :class="{active:desktopSection==='overview'}" @click="switchDesktopSection('overview')"><van-icon name="home-o"/> 工作台</button>
          <button type="button" :class="{active:desktopSection==='ask'}" @click="switchDesktopSection('ask')"><van-icon name="chat-o"/> 智能问数</button>
          <button type="button" :class="{active:desktopSection==='charts'}" @click="switchDesktopSection('charts')"><van-icon name="chart-trending-o"/> 我的图表</button>
          <button type="button" :class="{active:desktopSection==='history'}" @click="switchDesktopSection('history')"><van-icon name="clock-o"/> 历史会话</button>
        </nav>
        <nav v-if="isAdmin" class="desktop-main-nav tb-single-admin-nav" aria-label="管理员功能">
          <button type="button" :class="{active:desktopIsAdminSection}" @click="openAdmin">
            <van-icon name="setting-o"/> 系统管理
          </button>
        </nav>
        <div class="tb-sidebar-fill"></div>
      </aside>
      <header class="topbar">
        <div v-if="isDesktop" class="tb-header-breadcrumb">
          <span>DataAsk</span><van-icon name="arrow" size="11"/>
          <template v-if="desktopIsAdminSection && desktopSection!=='admin-overview'">
            <button type="button" class="tb-breadcrumb-admin" @click="openAdminSection('admin-overview')">系统管理</button>
            <van-icon name="arrow" size="11"/>
          </template>
          <strong>{{ desktopSectionTitle }}</strong>
        </div>
        <div v-else class="brand">
          <div class="brand-icon">问</div>
          <div class="brand-title"><strong class="mobile-brand">数问</strong>
            <strong class="desktop-current-session">{{ activeSessionTitle }}</strong>
            <small>经营数据助手</small></div>
        </div>
        <div v-if="isDesktop" class="da-account-menu" ref="accountMenuRef">
          <button type="button" class="da-account-trigger" :aria-expanded="accountMenuOpen"
            aria-haspopup="menu" aria-label="当前用户菜单" @click.stop="accountMenuOpen=!accountMenuOpen">
            <span class="da-account-avatar">{{ (me.displayName || me.username || '用').slice(0,1) }}</span>
            <span class="da-account-label">{{ me.displayName || me.username }}</span>
            <van-icon name="arrow-down" size="12"/>
          </button>
          <div v-if="accountMenuOpen" class="da-account-dropdown" role="menu" @click.stop>
            <div class="da-account-dropdown-heading">
              <strong>{{ me.displayName || me.username }}</strong>
              <small>{{ isAdmin ? '系统管理员' : '普通用户' }} · {{ me.username }}</small>
            </div>
            <button type="button" role="menuitem" @click="openPasswordChange">
              <van-icon name="lock"/> 修改密码
            </button>
            <button type="button" role="menuitem" @click="desktopLogout">
              <van-icon name="sign"/> 退出登录
            </button>
          </div>
        </div>
        <div v-if="!isDesktop" class="top-actions">
          <button class="desktop-toggle-results" type="button"
            :title="resultPanelOpen ? '隐藏结果面板' : '显示结果面板'" @click="resultPanelOpen = !resultPanelOpen">
            <van-icon name="apps-o" size="19"/>
          </button>
          <button title="历史对话" @click="historyVisible = true"><van-icon name="clock-o" size="21"/></button>
          <button title="新对话" @click="newConversation"><van-icon name="add-o" size="21"/></button>
        </div>
      </header>
      <main v-if="isDesktop && isAdmin && desktopIsAdminSection" class="tb-page-body tb-admin-page">
        <div class="tb-admin-heading">
          <div class="tb-admin-heading-copy">
            <button v-if="desktopSection!=='admin-overview'" type="button" class="tb-admin-back"
              @click="openAdminSection('admin-overview')"><van-icon name="arrow-left"/> 返回系统管理</button>
            <div v-else class="tb-admin-location"><van-icon name="setting-o"/> 系统管理</div>
            <h1>{{ desktopSectionTitle }}</h1>
            <p>{{ desktopSection==='admin-overview' ? '平台配置与访问权限集中管理' : '配置仅管理员可修改，保存后按原后端规则生效' }}</p>
          </div>
          <span class="tb-admin-heading-badge"><van-icon name="shield-o"/> 管理员权限</span>
        </div>
        <div v-if="unreadableConfigParts.length && !adminSectionBusy" class="tb-admin-credential-warning" role="alert">
          <van-icon name="warning-o" size="18"/>
          <span>以下已保存凭证无法解密：{{ unreadableConfigParts.join('、') }}。
            请优先确认服务器的 DATAASK_JWT_SECRET 是否曾更换；若已无法恢复原密钥，可进入对应模块重新填写凭证并保存。
            其他设置不会因此被删除。</span>
        </div>
        <div v-if="adminSectionBusy" class="tb-admin-loading" role="status">
          <van-loading size="18px"/> 正在加载 {{ desktopSectionTitle }}…
        </div>
        <div v-if="adminSectionError && !adminSectionBusy" class="tb-admin-load-error" role="alert">
          <van-icon name="warning-o" size="18"/>
          <span>{{ adminSectionError }}</span>
          <button type="button" @click="loadAdminSectionData(desktopSection)">重试加载</button>
        </div>
        <div v-if="desktopSection==='admin-overview'" class="tb-admin-overview">
          <div class="tb-admin-overview-grid">
            <button type="button" @click="openAdminSection('admin-source')">
              <span class="tb-admin-overview-icon icon-source"><van-icon name="cluster-o" size="21"/></span>
              <strong>数据源配置</strong><small>{{ status.starrocksConfigured ? 'StarRocks 已配置' : '配置 StarRocks 数据源' }}</small>
              <van-icon name="arrow" class="tb-admin-overview-arrow"/>
            </button>
            <button type="button" @click="openAdminSection('admin-model')">
              <span class="tb-admin-overview-icon icon-model"><van-icon name="chat-o" size="21"/></span>
              <strong>对话模型</strong><small>{{ status.aiConfigured ? (status.model || '模型已配置') : '大模型与 API Key' }}</small>
              <van-icon name="arrow" class="tb-admin-overview-arrow"/>
            </button>
            <button type="button" @click="openAdminSection('admin-embedding')">
              <span class="tb-admin-overview-icon icon-embedding"><van-icon name="setting-o" size="21"/></span>
              <strong>Embedding 模型</strong><small>{{ status.embeddingConfigured ? '语义模型已启用' : '语义识别和索引' }}</small>
              <van-icon name="arrow" class="tb-admin-overview-arrow"/>
            </button>
            <button type="button" @click="openAdminSection('admin-tables')">
              <span class="tb-admin-overview-icon icon-tables"><van-icon name="records-o" size="21"/></span>
              <strong>ADS 表授权</strong><small>控制可查询的数据表</small>
              <van-icon name="arrow" class="tb-admin-overview-arrow"/>
            </button>
            <button type="button" @click="openAdminSection('admin-metrics')">
              <span class="tb-admin-overview-icon icon-metrics"><van-icon name="description" size="21"/></span>
              <strong>指标口径</strong><small>维护业务指标定义</small>
              <van-icon name="arrow" class="tb-admin-overview-arrow"/>
            </button>
            <button type="button" @click="openAdminSection('admin-users')">
              <span class="tb-admin-overview-icon icon-users"><van-icon name="friends-o" size="21"/></span>
              <strong>用户管理</strong><small>用户账号与角色</small>
              <van-icon name="arrow" class="tb-admin-overview-arrow"/>
            </button>
            <button type="button" @click="openAdminSection('admin-status')">
              <span class="tb-admin-overview-icon icon-status"><van-icon name="chart-trending-o" size="21"/></span>
              <strong>配置状态</strong><small>刷新数据源、模型配置状态</small>
              <van-icon name="replay" class="tb-admin-overview-arrow"/>
            </button>
          </div>
        </div>
        <div v-else-if="desktopSection==='admin-status'" class="tb-admin-panel-card tb-admin-status-page">
          <div class="tb-admin-panel-intro tb-admin-panel-intro-action">
            <div><strong>平台连接与配置状态</strong>
              <p>检查模型与数据源是否已完成配置，点击条目进入对应设置。</p></div>
            <button type="button" class="tb-admin-plain-action" @click="refreshAdminOverview"
              :disabled="adminSectionBusy"><van-icon name="replay"/> 刷新状态</button>
          </div>
          <div class="tb-config-status-list">
            <button v-for="item in configuredStatusRows" :key="item.key" type="button"
              @click="openAdminSection(item.section)">
              <span class="tb-config-status-icon"><van-icon :name="item.configured ? 'passed' : 'warning-o'"/></span>
              <span class="tb-config-status-copy"><strong>{{ item.key }}</strong><small>{{ item.detail }}</small></span>
              <span class="tb-config-state" :class="{ok:item.configured}">
                {{ item.configured ? '已配置' : '未配置' }}
              </span>
              <van-icon name="arrow"/>
            </button>
          </div>
          <p class="muted">这里展示的是配置完整性，是否能够真正连接或调用，请进入对应模块点击“测试连接”。</p>
        </div>
        <div v-else-if="['admin-source','admin-model','admin-embedding'].includes(desktopSection)"
          class="tb-admin-panel-card tb-admin-config-page">
          <div class="da-admin-config-intro">
            <div class="da-admin-config-icon">
              <van-icon :name="configKind==='starrocks' ? 'cluster-o' : configKind==='ai' ? 'chat-o' : 'setting-o'" size="23"/>
            </div>
            <div class="da-admin-config-heading">
              <strong>{{ configKind==='starrocks' ? 'StarRocks 数据连接' : configKind==='ai' ? 'AI 对话模型' : 'Embedding 语义服务' }}</strong>
              <p>{{ configKind==='starrocks' ? '连接已授权的 ADS 数据表，为智能问数提供可信的经营数据' :
                configKind==='ai' ? '统一管理自然语言理解、SQL 生成与业务数据分析的模型' :
                '管理字段语义匹配、指标口径检索与向量索引服务' }}</p>
            </div>
            <span class="da-admin-status-badge" :class="{ok:configKind==='starrocks' ? status.starrocksConfigured : configKind==='ai' ? status.aiConfigured : status.embeddingConfigured}">
              <van-icon :name="configKind==='starrocks' ? (status.starrocksConfigured ? 'passed' : 'info-o') :
                configKind==='ai' ? (status.aiConfigured ? 'passed' : 'info-o') :
                (status.embeddingConfigured ? 'passed' : 'info-o')"/>
              {{ (configKind==='starrocks' ? status.starrocksConfigured : configKind==='ai' ? status.aiConfigured : status.embeddingConfigured) ? '已配置' : '待配置' }}
            </span>
          </div>
          <div class="da-admin-config-grid">
            <section class="da-admin-config-main" aria-label="连接配置表单">
              <div class="da-admin-config-form-title">
                <strong>{{ configKind==='starrocks' ? '数据库连接参数' : '模型接入参数' }}</strong>
                <small>必填信息通过服务端校验，敏感凭证不会显示明文</small>
              </div>
              <div class="tb-admin-config-fields">
            <template v-if="configKind === 'starrocks'">
            <p class="muted">配置 StarRocks FE 只读账号，测试连接成功后保存并立即读取 ADS 字段注释。</p>
            <van-field v-model="srForm.jdbcUrl" label="JDBC 地址" label-width="85px" placeholder="jdbc:mysql://192.168.1.10:9030/ads"/>
            <van-field v-model="srForm.schema" label="ADS 数据库" label-width="85px" placeholder="ads"/>
            <van-field v-model="srForm.username" label="用户名" label-width="85px" placeholder="只读用户名"/>
            <van-field v-model="srForm.password" label="密码" label-width="85px" type="password"
              :placeholder="settingsData.starrocks?.passwordSet ? '已配置，留空不修改' : '请输入密码'"/>
            <p v-if="settingsData.starrocks?.passwordUnreadable" class="tb-admin-credential-warning">
              <van-icon name="warning-o"/> 已保存的数据库密码无法解密，请在上方填写新的数据库密码后保存。
            </p>
            <p class="config-note">数据库密码由后端保存，留空会保留现有密码。</p>
          </template>
          <template v-else-if="configKind === 'ai'">
            <p class="muted">在这里直接配置用于自然语言分析和 SQL 生成的大模型。保存后无需重启服务。</p>
            <van-field v-model="aiForm.baseUrl" label="Base URL" label-width="95px" placeholder="https://api.example.com/v1"/>
            <van-field v-model="aiForm.model" label="模型名称" label-width="95px" placeholder="输入服务商模型 ID"/>
            <van-field v-model="aiForm.apiKey" label="API Key" label-width="95px" type="password"
              autocomplete="new-password" :placeholder="settingsData.ai?.apiKeySet ? '已设置 · 留空不修改' : '填写 API Key'"/>
            <van-cell title="清除原有 API Key" label="仅无密钥本地模型才需要开启；开启后测试不会携带原密钥">
              <template #right-icon><van-switch v-model="aiForm.clearKey" size="20px"/></template>
            </van-cell>
            <p v-if="aiForm.clearKey" class="tb-admin-credential-warning">
              <van-icon name="warning-o"/> 当前已勾选清除 API Key。关闭开关即可测试已保存的 Key；保存清除操作前将再次确认。
            </p>
            <p v-if="settingsData.ai?.apiKeyUnreadable" class="tb-admin-credential-warning">
              <van-icon name="warning-o"/> 已保存的 API Key 无法解密，请重新填写有效 API Key 后保存；不要留空。
            </p>
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
            <p v-if="settingsData.embedding?.apiKeyUnreadable" class="tb-admin-credential-warning">
              <van-icon name="warning-o"/> 已保存的 Embedding API Key 无法解密，请重新填写后保存。
            </p>
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
            </section>
            <aside class="da-admin-config-aside" aria-label="配置帮助">
              <h3><van-icon name="info-o"/> 接入说明</h3>
              <template v-if="configKind==='starrocks'">
                <div class="da-admin-guide-step"><span>01</span><div><b>使用只读账号</b><p>为 StarRocks 创建专用只读用户，避免使用 root 或其他高权限账号。</p></div></div>
                <div class="da-admin-guide-step"><span>02</span><div><b>确认 ADS 数据库</b><p>填写 FE MySQL 协议地址与目标 ADS 库，字段注释用于智能问数语义理解。</p></div></div>
                <div class="da-admin-guide-step"><span>03</span><div><b>测试后保存</b><p>先验证数据库连接，再保存；需要读取哪些表请进入 ADS 表授权设置。</p></div></div>
              </template>
              <template v-else-if="configKind==='ai'">
                <div class="da-admin-guide-step"><span>01</span><div><b>使用兼容 API</b><p>填写服务商 API 地址和模型 ID，确保支持 Chat Completions 接口。</p></div></div>
                <div class="da-admin-guide-step"><span>02</span><div><b>保护模型密钥</b><p>API Key 由后端加密保存。留空表示保留当前密钥，明确清除时需二次确认。</p></div></div>
                <div class="da-admin-guide-step"><span>03</span><div><b>移动端自动生效</b><p>小程序和 PC 共用这份服务端模型配置，无须在手机端重复配置。</p></div></div>
              </template>
              <template v-else>
                <div class="da-admin-guide-step"><span>01</span><div><b>选择语义模型</b><p>使用服务商实际支持的 Embedding 模型，并确认向量维度。</p></div></div>
                <div class="da-admin-guide-step"><span>02</span><div><b>测试接入</b><p>检查 Base URL、模型名、API Key 和网络访问权限。</p></div></div>
                <div class="da-admin-guide-step"><span>03</span><div><b>重建索引</b><p>更换模型或维度后，保存设置并重建语义索引。</p></div></div>
              </template>
              <div class="da-admin-config-security"><van-icon name="shield-o"/> 仅管理员可修改配置，普通用户与小程序不展示此入口。</div>
            </aside>
          </div>
        </div>
        <div v-else-if="desktopSection==='admin-tables'" class="tb-admin-panel-card">
          <div class="tb-admin-panel-intro tb-admin-panel-intro-action">
            <div><strong>ADS 表授权</strong><p>仅开启业务审核通过的 ADS 表。关闭授权后，普通用户将无法使用该表问数。</p></div>
            <div class="tb-admin-table-actions">
              <button type="button" class="tb-admin-plain-action" :disabled="adminSectionBusy"
                @click="loadAdminSectionData('admin-tables')"><van-icon name="replay"/> 刷新表列表</button>
              <button type="button" class="tb-admin-plain-action" @click="openAdminSection('admin-source')">
                <van-icon name="cluster-o"/> 数据源配置
              </button>
            </div>
          </div>
          <h3>开放给 AI 的 ADS 表</h3>
          <p class="muted">默认不开放任何表。仅授权通过审核的经营数据表。</p>
          <div v-for="table in allowedTables" :key="table.name" class="admin-table">
            <div class="table-head">
              <button type="button" class="tb-table-expand"
                :aria-expanded="expandedTable === table.name"
                @click="expandedTable = expandedTable === table.name ? '' : table.name">
                <van-icon :name="expandedTable === table.name ? 'arrow-up' : 'arrow-down'"/> {{ table.name }}
              </button>
              <van-switch v-model="table.enabled" size="19px" @change="toggleAllowed(table)"/>
            </div>
            <div v-if="expandedTable === table.name" class="columns-list">
              <div v-for="col in table.columns" :key="col.name"><b>{{ col.name }}</b> {{ col.type }} <span>{{ col.comment }}</span></div>
            </div>
          </div>
          <p v-if="!allowedTables.length" class="muted">没有读取到 ADS 表，请检查连接配置。</p>
        </div>
        <div v-else-if="desktopSection==='admin-metrics'" class="tb-admin-panel-card tb-admin-metrics-page">
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
        </div>
        <div v-else-if="desktopSection==='admin-users'" class="tb-admin-panel-card tb-admin-users-page">
          <div class="tb-admin-panel-intro">
            <strong>用户与权限管理</strong>
            <p>管理员可创建用户、编辑显示名称与角色、重置密码。普通用户不能操作这里的配置。</p>
          </div>
          <div class="tb-users-table">
            <div class="tb-user-head"><span>用户</span><span>角色</span><span>操作</span></div>
            <div v-for="user in users" :key="user.username" class="tb-user-row">
              <span class="tb-user-identity">
                <span class="tb-user-avatar">{{ (user.displayName || user.username || '用').slice(0,1) }}</span>
                <span><strong>{{ user.displayName || user.username }}</strong><small>{{ user.username }}</small></span>
              </span>
              <span class="tb-user-role">{{ user.role==='ADMIN' ? '管理员' : '普通用户' }}</span>
              <button type="button" class="tb-user-edit" @click="editUser(user)">
                <van-icon name="edit"/> 编辑 / 重置密码
              </button>
            </div>
            <div v-if="!users.length && !adminSectionBusy" class="tb-admin-empty">
              暂无用户信息，请点击“重试加载”检查接口。
            </div>
          </div>
          <div v-if="userEditVisible" class="tb-user-editor">
            <div class="tb-user-editor-title">
              <strong>编辑用户 · {{ userEditForm.username }}</strong>
              <button type="button" :disabled="userEditSaving" @click="cancelUserEdit"><van-icon name="cross"/> 关闭</button>
            </div>
            <van-field v-model="userEditForm.displayName" label="显示名称" placeholder="请输入姓名"/>
            <van-field label="用户角色"><template #input>
              <van-radio-group v-model="userEditForm.role" direction="horizontal">
                <van-radio name="USER">普通用户</van-radio>
                <van-radio name="ADMIN">管理员</van-radio>
              </van-radio-group>
            </template></van-field>
            <div class="tb-user-action-row">
              <van-button type="primary" :loading="userEditSaving" @click="saveUserProfile">保存用户信息</van-button>
            </div>
            <div class="tb-user-reset">
              <strong>重置登录密码</strong>
              <p>仅由管理员执行，新密码需至少10位；重置后该账号需要重新登录。</p>
              <van-field v-model="userEditForm.password" label="新密码"
                type="password" autocomplete="new-password" placeholder="10–128 位"/>
              <van-button plain type="danger" :loading="userEditSaving" @click="resetUserPassword">重置密码</van-button>
            </div>
          </div>
          <div class="tb-user-create">
            <h3>创建新账号</h3>
            <van-field v-model="newUser.username" label="登录账号" placeholder="3–64 位英文账号" />
            <van-field v-model="newUser.displayName" label="显示名称" placeholder="用户姓名" />
            <van-field v-model="newUser.password" label="初始密码" type="password"
              autocomplete="new-password" placeholder="至少10位" />
            <van-field label="角色"><template #input>
              <van-radio-group v-model="newUser.role" direction="horizontal">
                <van-radio name="USER">普通用户</van-radio>
                <van-radio name="ADMIN">管理员</van-radio>
              </van-radio-group>
            </template></van-field>
            <van-button type="primary" :loading="userBusy" @click="addUser">创建账号</van-button>
          </div>
        </div>
      </main>
      <main v-if="isDesktop && desktopSection==='overview'" class="tb-page-body tb-overview ds-home" aria-label="DataAsk 工作台">
        <header class="ds-home-intro">
          <div class="ds-home-heading-copy">
            <p class="ds-home-kicker"><span aria-hidden="true"></span> WELCOME TO DATAASK</p>
            <h1>工作台</h1>
            <p>基于自然语言的智能数据分析平台，让数据查询与分析更简单、更高效。</p>
          </div>
        </header>
        <section class="ds-home-banner" aria-label="欢迎使用 DataAsk">
          <div class="ds-home-banner-copy">
            <h2>欢迎使用 <span>DataAsk</span></h2>
            <p>用自然语言提问，快速获取数据、图表与业务洞察，助力高效决策。</p>
            <button type="button" class="ds-home-cta" @click="switchDesktopSection('ask')">
              <van-icon name="magic-stick" size="17"/> 进入智能问数 <van-icon name="arrow" size="17"/>
            </button>
          </div>
          <svg class="ds-home-banner-illustration" viewBox="0 0 510 213" role="img" aria-label="数据分析图表示意">
            <defs>
              <linearGradient id="ds-home-wave" x1="0" y1="0" x2="1" y2="1">
                <stop offset="0" stop-color="#b5d7ff" stop-opacity=".04"/>
                <stop offset="1" stop-color="#70a8fa" stop-opacity=".43"/>
              </linearGradient>
              <linearGradient id="ds-home-window" x1="0" y1="0" x2="1" y2="1">
                <stop offset="0" stop-color="#f9fcff"/>
                <stop offset="1" stop-color="#c7deff"/>
              </linearGradient>
              <linearGradient id="ds-home-window-bar" x1="0" y1="1" x2="0" y2="0">
                <stop offset="0" stop-color="#4d8bef"/><stop offset="1" stop-color="#a2cafa"/>
              </linearGradient>
              <filter id="ds-home-shadow" x="-35%" y="-40%" width="170%" height="190%">
                <feDropShadow dx="0" dy="10" stdDeviation="11" flood-color="#5390e6" flood-opacity=".18"/>
              </filter>
            </defs>
            <path d="M0 188 Q128 105 242 160 T510 99 L510 213 L0 213 Z" fill="url(#ds-home-wave)" opacity=".55"/>
            <ellipse cx="314" cy="193" rx="165" ry="16" fill="#83b1f8" opacity=".12"/>
            <g transform="translate(188 15) rotate(-3 132 87)" filter="url(#ds-home-shadow)">
              <rect x="0" y="0" width="275" height="168" rx="16" fill="url(#ds-home-window)" stroke="#fff" stroke-width="3"/>
              <rect x="0" y="0" width="275" height="28" rx="14" fill="#7caaf1"/>
              <path d="M0 18 L275 18 L275 29 L0 29Z" fill="#7caaf1"/>
              <circle cx="15" cy="14" r="4" fill="#fff" opacity=".9"/>
              <circle cx="29" cy="14" r="4" fill="#fff" opacity=".7"/>
              <circle cx="43" cy="14" r="4" fill="#fff" opacity=".6"/>
              <rect x="17" y="42" width="146" height="108" rx="9" fill="#fff" opacity=".83"/>
              <line x1="29" y1="126" x2="154" y2="126" stroke="#cbdcf6" stroke-width="1.7"/>
              <rect x="37" y="104" width="19" height="22" rx="4" fill="url(#ds-home-window-bar)"/>
              <rect x="64" y="90" width="19" height="36" rx="4" fill="url(#ds-home-window-bar)"/>
              <rect x="91" y="71" width="19" height="55" rx="4" fill="url(#ds-home-window-bar)"/>
              <rect x="118" y="57" width="19" height="69" rx="4" fill="url(#ds-home-window-bar)"/>
              <rect x="175" y="43" width="82" height="67" rx="11" fill="#f4f8ff"/>
              <circle cx="214" cy="76" r="23" fill="none" stroke="#bed9fd" stroke-width="11"/>
              <path d="M214 53 A23 23 0 0 1 237 76 L214 76Z" fill="#2e7ce9"/>
              <path d="M214 76 L237 76 A23 23 0 0 1 206 98 Z" fill="#6aa8fa"/>
              <rect x="178" y="122" width="71" height="7" rx="3.5" fill="#aacaf4"/>
              <rect x="178" y="137" width="50" height="6" rx="3" fill="#c1d8f7"/>
            </g>
            <g transform="translate(112 100) rotate(5 53 37)" filter="url(#ds-home-shadow)">
              <rect x="0" y="0" width="110" height="70" rx="15" fill="#fff" stroke="#d8e8fd" stroke-width="2"/>
              <path d="M24 70 L34 70 L29 82Z" fill="#fff"/>
              <path d="M21 41 L36 23 L49 33 L64 15" stroke="#378cf7" stroke-width="4" fill="none" stroke-linecap="round" stroke-linejoin="round"/>
              <circle cx="64" cy="15" r="4" fill="#5aa2ff"/>
              <rect x="21" y="51" width="62" height="5" rx="2.5" fill="#bed6fa"/>
            </g>
            <g fill="#fff" stroke="#fff" stroke-width="1.7" opacity=".97">
              <path d="M469 36 l6 12 12 6 -12 6 -6 12 -6 -12 -12 -6 12 -6Z"/>
              <path d="M91 47 l3 7 7 3 -7 3 -3 7 -3 -7 -7 -3 7 -3Z" opacity=".75"/>
            </g>
          </svg>
        </section>
        <section class="ds-home-shortcuts" aria-labelledby="ds-shortcut-label">
          <div class="ds-home-section-title"><h3 id="ds-shortcut-label">快捷入口</h3></div>
          <div class="ds-home-shortcut-grid">
            <button type="button" class="ds-home-shortcut" @click="switchDesktopSection('ask')">
              <span class="ds-shortcut-icon ds-shortcut-ask"><van-icon name="chat-o" size="25"/></span>
              <span class="ds-shortcut-content"><strong>智能问数</strong><small>用自然语言提问，获取数据分析结果</small></span>
              <van-icon name="arrow" class="ds-shortcut-chevron" size="17"/>
            </button>
            <button type="button" class="ds-home-shortcut" @click="switchDesktopSection('charts')">
              <span class="ds-shortcut-icon ds-shortcut-chart"><van-icon name="chart-trending-o" size="25"/></span>
              <span class="ds-shortcut-content"><strong>我的图表</strong><small>查看和管理已创建的图表及分析看板</small></span>
              <van-icon name="arrow" class="ds-shortcut-chevron" size="17"/>
            </button>
            <button type="button" class="ds-home-shortcut" @click="switchDesktopSection('history')">
              <span class="ds-shortcut-icon ds-shortcut-history"><van-icon name="clock-o" size="25"/></span>
              <span class="ds-shortcut-content"><strong>历史会话</strong><small>查看过往提问记录，快速复用分析</small></span>
              <van-icon name="arrow" class="ds-shortcut-chevron" size="17"/>
            </button>
          </div>
        </section>
        <section class="ds-home-recent" aria-labelledby="ds-recent-label">
          <div class="ds-home-section-title ds-home-recent-title">
            <h3 id="ds-recent-label">最近提问</h3>
            <button type="button" @click="switchDesktopSection('history')">查看更多 <van-icon name="arrow" size="14"/></button>
          </div>
          <div class="ds-home-history-table">
            <div class="ds-home-history-head"><span>问数内容</span><span>提问时间</span><span></span></div>
            <button v-for="item in sessions.slice(0,5)" :key="item.id" type="button" class="ds-home-history-row" @click="openSession(item)">
              <span class="ds-history-question"><van-icon name="chat-o" size="18"/><span>{{ item.title }}</span></span>
              <time>{{ fullSessionTime(item.updatedAt) }}</time>
              <van-icon name="arrow" size="15" class="ds-history-arrow"/>
            </button>
            <div v-if="!sessions.length" class="ds-home-history-empty">暂无历史问数。选择“智能问数”即可开始探索。</div>
          </div>
        </section>
      </main>
      <main v-if="isDesktop && desktopSection==='charts'" class="tb-page-body tb-content-page tb-charts-page">
        <template v-if="activeChart">
          <div class="da-chart-detail">
            <div class="da-chart-detail-heading">
              <div>
                <button type="button" class="da-chart-back" @click="closeChartDetail">
                  <van-icon name="arrow-left"/> 返回我的图表
                </button>
                <h1>{{ activeChart.title }}</h1>
                <p>{{ activeChart.question }}</p>
              </div>
              <div class="da-chart-detail-actions">
                <span v-if="activeChartRefreshing" class="da-chart-refreshing"><van-loading size="14px"/> 正在更新数据…</span>
                <span v-else-if="activeChartCached" class="da-chart-cached">上次保存结果</span>
                <span v-else class="da-chart-current">实时更新成功</span>
                <button type="button" :disabled="activeChartBusy || activeChartRefreshing"
                  @click="refreshChartDetail"><van-icon name="replay"/> 刷新数据</button>
                <button v-if="activeChart.ownerUsername===me.username || isAdmin"
                  type="button" @click="openChartShare(activeChart)"><van-icon name="share-o"/> 共享</button>
                <button v-if="activeChart.ownerUsername===me.username || isAdmin"
                  type="button" @click="deleteSavedChart(activeChart)"><van-icon name="delete-o"/> 取消收藏</button>
              </div>
            </div>
            <div v-if="activeChartError" class="da-chart-detail-error" role="alert">
              <van-icon name="warning-o"/> {{ activeChartError }}
            </div>
            <div v-if="activeChartBusy" class="da-chart-detail-loading">
              <van-loading size="24px"/> 正在读取图表…
            </div>
            <div v-else-if="activeChartAnswer" class="da-chart-detail-body">
              <div class="da-chart-detail-bar">
                <div><strong>数据分析结果</strong>
                  <small>本次结果 {{ activeChartAnswer.rows?.length || 0 }} 行</small></div>
                <div class="da-chart-view-buttons">
                  <button v-if="activeChartIsMetric" type="button"
                    :class="{active:activeChartMode==='summary'}" @click="activeChartMode='summary'">指标</button>
                  <button v-if="activeChartCanPlot" type="button"
                    :class="{active:activeChartMode==='chart'}" @click="activeChartMode='chart'">图表</button>
                  <button type="button" :class="{active:activeChartMode==='table'}"
                    @click="activeChartMode='table'">表格</button>
                </div>
              </div>
              <div v-if="activeChartCanPlot && activeChartMode==='chart'" class="da-chart-style-switch">
                <span>切换展示</span>
                <button v-for="type in ['bar','line',...(pieSupported(activeChartAnswer)?['pie']:[])]"
                  :key="type" type="button" :class="{active:activeChartStyle===type}"
                  @click="activeChartStyle=type">{{ {bar:'柱状图',line:'折线图',pie:'饼图'}[type] }}</button>
              </div>
              <div v-if="activeChartIsMetric && activeChartMode==='summary'"
                class="da-chart-metric">
                <div v-for="(col,index) in activeChartAnswer.columns" :key="col">
                  <small>{{ columnLabel(activeChartAnswer,col,index) }}</small>
                  <strong>{{ pretty(activeChartAnswer.rows[0][col]) }}</strong>
                </div>
              </div>
              <div v-if="activeChartIsMetric && activeChartMode==='summary'"
                class="da-chart-metric">
                <div v-for="(col,index) in activeChartAnswer.columns" :key="col">
                  <small>{{ columnLabel(activeChartAnswer,col,index) }}</small>
                  <strong>{{ pretty(activeChartAnswer.rows[0][col]) }}</strong>
                </div>
              </div>
              <AnswerChart v-if="activeChartCanPlot && activeChartMode==='chart'"
                :message="activeChartAnswer" :chart-type-override="activeChartStyle"
                :desktop-expanded="true"/>
              <div v-if="activeChartMode==='table' || (!activeChartCanPlot && !activeChartIsMetric)"
                class="result-table-scroll da-chart-detail-table" role="region" tabindex="0">
                <table class="result-table"><thead><tr>
                  <th v-for="(col,idx) in activeChartAnswer.columns" :key="col">
                    {{ columnLabel(activeChartAnswer,col,idx) }}</th>
                </tr></thead><tbody>
                  <tr v-for="(row,i) in activeChartAnswer.rows" :key="i">
                    <td v-for="col in activeChartAnswer.columns" :key="col">{{ pretty(row[col]) }}</td>
                  </tr>
                </tbody></table>
              </div>
              <div class="da-chart-detail-footer">
                <span><van-icon name="shield-o"/> 数据来自当前授权的 ADS 表</span>
                <span>展示实际查询结果，不会创建新的聊天会话</span>
              </div>
            </div>
          </div>
        </template>
        <template v-else>
        <div class="tb-page-heading">
          <div><h1>我的图表</h1><p>查看已收藏或他人共享的分析，重新查询即可获取最新数据。</p></div>
          <button type="button" class="tb-secondary-action" :disabled="chartsBusy" @click="loadSavedCharts">
            <van-icon name="replay"/> 刷新数据
          </button>
        </div>
        <div class="tb-chart-workspace">
          <div class="tb-chart-toolbar">
            <label class="tb-chart-search">
              <van-icon name="search" size="17"/>
              <input v-model="chartSearch" placeholder="搜索图表名称或问数问题" aria-label="搜索图表"/>
            </label>
            <label class="tb-chart-select-label">
              <span>类型</span>
              <select v-model="chartTypeFilter" aria-label="图表类型筛选">
                <option value="ALL">全部类型</option>
                <option v-for="type in chartTypeOptions" :key="type" :value="type">{{ chartTypeLabel(type) }}</option>
              </select>
            </label>
            <label class="tb-chart-select-label">
              <span>排序</span>
              <select v-model="chartSortOrder" aria-label="更新时间排序">
                <option value="recent">最近更新</option>
                <option value="oldest">最早更新</option>
              </select>
            </label>
          </div>
          <section class="tb-chart-section">
            <div class="tb-chart-section-title">
              <h2><van-icon name="star-o"/> 我收藏的图表 <span>{{ filteredSavedCharts.length }}</span></h2>
              <small>点击图表可按当前授权重新查询</small>
            </div>
            <div class="tb-chart-table-wrap" v-if="filteredSavedCharts.length">
              <table class="tb-chart-table">
                <thead><tr><th>图表名称</th><th>自然语言问题</th><th>图表类型</th><th>更新时间</th><th>操作</th></tr></thead>
                <tbody>
                  <tr v-for="item in filteredSavedCharts" :key="item.id">
                    <td class="tb-chart-name-cell"><div class="tb-chart-name-inner">
                      <span class="tb-chart-row-icon"><van-icon name="chart-trending-o" size="18"/></span>
                      <button type="button" :title="item.title" @click="revisitChart(item)">{{ item.title }}</button>
                    </div></td>
                    <td class="tb-chart-question-cell" :title="item.question">{{ item.question }}</td>
                    <td><span class="tb-chart-type-label">{{ chartTypeLabel(item.chartType) }}</span></td>
                    <td class="tb-chart-date">{{ sessionTimeLabel(item.updatedAt || item.createdAt) }}</td>
                    <td><button type="button" class="tb-chart-open" @click="revisitChart(item)">查看 <van-icon name="arrow"/></button></td>
                  </tr>
                </tbody>
              </table>
            </div>
            <div v-else class="tb-chart-table-empty">
              <van-icon name="chart-trending-o" size="28"/>
              <strong>{{ chartsBusy ? '正在加载…' : chartSearch || chartTypeFilter!=='ALL' ? '没有匹配的图表' : '还没有收藏图表' }}</strong>
              <p>在智能问数结果中点击“收藏”，就可以随时重新查看。</p>
              <button v-if="!chartSearch && chartTypeFilter==='ALL' && !chartsBusy" type="button" @click="switchDesktopSection('ask')">去智能问数</button>
            </div>
          </section>
          <section class="tb-chart-section">
            <div class="tb-chart-section-title">
              <h2><van-icon name="friends-o"/> 共享给我的图表 <span>{{ filteredSharedCharts.length }}</span></h2>
            </div>
            <div class="tb-chart-table-wrap" v-if="filteredSharedCharts.length">
              <table class="tb-chart-table">
                <thead><tr><th>图表名称</th><th>自然语言问题</th><th>图表类型</th><th>共享来源</th><th>更新时间</th><th>操作</th></tr></thead>
                <tbody>
                  <tr v-for="item in filteredSharedCharts" :key="item.id">
                    <td class="tb-chart-name-cell"><div class="tb-chart-name-inner">
                      <span class="tb-chart-row-icon tb-chart-row-shared"><van-icon name="share-o" size="18"/></span>
                      <button type="button" :title="item.title" @click="revisitChart(item)">{{ item.title }}</button>
                    </div></td>
                    <td class="tb-chart-question-cell" :title="item.question">{{ item.question }}</td>
                    <td><span class="tb-chart-type-label">{{ chartTypeLabel(item.chartType) }}</span></td>
                    <td class="tb-chart-owner">{{ item.ownerUsername }}</td>
                    <td class="tb-chart-date">{{ sessionTimeLabel(item.updatedAt || item.createdAt) }}</td>
                    <td><button type="button" class="tb-chart-open" @click="revisitChart(item)">查看 <van-icon name="arrow"/></button></td>
                  </tr>
                </tbody>
              </table>
            </div>
            <div v-else class="tb-chart-table-empty tb-shared-empty">
              <van-icon name="share-o" size="25"/>
              <strong>{{ chartSearch || chartTypeFilter!=='ALL' ? '没有匹配的共享图表' : '暂无共享给我的图表' }}</strong>
            </div>
          </section>
        </div>
        </template>
      </main>
      <main v-if="isDesktop && desktopSection==='history'" class="tb-page-body tb-content-page">
        <div class="tb-page-heading">
          <div><h1>历史会话</h1><p>找回之前的分析，继续追问业务数据。</p></div>
          <button type="button" class="tb-secondary-action" @click="loadSessions"><van-icon name="replay"/> 刷新</button>
        </div>
        <label class="tb-history-search"><van-icon name="search" size="18"/>
          <input v-model="sessionSearch" aria-label="搜索会话" placeholder="搜索之前问过的问题"/>
        </label>
        <div class="tb-recent-list tb-history-list">
          <button v-for="item in visibleSessions" :key="item.id" type="button" @click="openSession(item)">
            <van-icon name="chat-o" class="tb-recent-icon"/>
            <span>{{ item.title }}</span><small>{{ sessionTimeLabel(item.updatedAt) }}</small>
            <van-icon name="arrow"/>
          </button>
          <div v-if="!visibleSessions.length" class="tb-empty-state">
            <van-icon name="clock-o" size="40"/>
            <h3>{{ sessionSearch ? '没有找到相关会话' : '还没有历史会话' }}</h3>
            <p>从智能问数开始，你的问题会自动保存在这里。</p>
            <button type="button" @click="switchDesktopSection('ask')">开始问数</button>
          </div>
        </div>
      </main>
      <main v-if="!isDesktop || desktopSection==='ask'" class="conversation">
        <section v-if="!messages.length && (!isDesktop || (!busy && !desktopFeedback))" class="welcome">
          <template v-if="isDesktop">
            <div class="qbi-welcome-brand">
              <span class="qbi-welcome-orb" aria-hidden="true"><van-icon name="chart-trending-o" size="29"/></span>
              <span>DataAsk 智能问数</span>
            </div>
            <h2>你好，今天想分析哪些数据？</h2>
            <p class="qbi-welcome-description">用自然语言提问，即可获得经营数据的分析结果。</p>
            <div class="qbi-question-heading">
              <span><van-icon name="fire-o"/> 你可以这样问</span>
              <small>通用问法示例，实际可查询内容取决于指标口径</small>
            </div>
            <div class="quick-grid qbi-quick-grid">
              <button v-for="(item, idx) in quickQuestions" :key="item" :disabled="busy"
                type="button" @click="ask(item)">
                <span class="qbi-quick-icon"><van-icon :name="['chart-trending-o','location-o','bar-chart-o','balance-o'][idx]" size="19"/></span>
                <span class="qbi-quick-title">{{ item }}</span>
                <van-icon name="arrow" class="qbi-quick-arrow" size="14"/>
              </button>
            </div>
          </template>
          <template v-else>
            <div class="welcome-icon"><van-icon name="chart-trending-o" size="27"/></div>
            <h2>今天想了解哪些经营数据？</h2>
            <p>直接提问，系统会根据已授权的 ADS 数据计算真实结果。</p>
            <div class="quick-grid">
              <button v-for="item in quickQuestions" :key="item" :disabled="busy" @click="ask(item)">
                {{ item }} <van-icon name="arrow" />
              </button>
            </div>
            <p class="data-notice"><van-icon name="shield-o"/> 仅查询管理员开放的 ADS 表，结果来自真实数据库</p>
          </template>
        </section>
        <section v-for="msg in messages" :key="msg.id" class="turn">
          <div class="user-bubble">{{ msg.question }}</div>
          <div class="answer-card" :class="{ 'answer-needs-clarification': msg.unsupported && msg.followUps?.length }">
            <div class="answer-heading"><span class="answer-mark">{{ isDesktop ? 'Q' : '数' }}</span><b>{{ msg.title }}</b>
              <span v-if="msg.timeLabel" class="answer-time">{{ msg.timeLabel }}</span>
            </div>
            <p v-if="!isDesktop || !msg.rows?.length || !isRedundantSummary(msg)"
              class="answer-summary" :class="{ 'unsupported-text': msg.unsupported }">{{ msg.summary }}</p>
            <template v-if="msg.rows?.length">
              <div v-if="isDesktop" class="desktop-result-header">
                <div class="desktop-result-heading"><van-icon name="chart-trending-o"/> 查询结果 <span>共 {{ msg.rows.length }} 行</span></div>
                <div class="desktop-result-switch" role="group" :aria-label="'图表视图切换：'+msg.title">
                  <button v-if="msg.chartType === 'metric' && msg.rows.length === 1" type="button" :class="{active:resultView(msg)==='summary'}" @click="setResultView(msg,'summary')">指标</button>
                  <button v-if="['bar','line','pie'].includes(msg.chartType) && msg.rows.length > 1" type="button"
                    :class="{active:resultView(msg)==='chart'}" @click="setResultView(msg,'chart')">图表</button>
                  <button type="button" :class="{active:resultView(msg)==='table'}" @click="setResultView(msg,'table')">表格</button>
                </div>
              </div>
              <div v-if="msg.chartType === 'metric' && msg.rows.length === 1 && (!isDesktop || resultView(msg)==='summary')" class="metric-data">
                <div v-for="col in msg.columns" :key="col">
                  <small :title="col">{{ columnLabel(msg, col, msg.columns.indexOf(col)) }}</small><strong>{{ pretty(msg.rows[0][col]) }}</strong>
                </div>
              </div>
              <div v-if="isDesktop && ['bar','line','pie'].includes(msg.chartType) && msg.rows.length > 1 && resultView(msg)==='chart'" class="qbi-chart-type">
                <span>图表类型</span>
                <button type="button" :class="{active:chartStyle(msg)==='bar'}" @click="setChartStyle(msg,'bar')">柱状图</button>
                <button type="button" :class="{active:chartStyle(msg)==='line'}" @click="setChartStyle(msg,'line')">折线图</button>
                <button v-if="pieSupported(msg)" type="button" :class="{active:chartStyle(msg)==='pie'}" @click="setChartStyle(msg,'pie')">饼图</button>
              </div>
              <AnswerChart v-if="['bar','line','pie'].includes(msg.chartType) && msg.rows.length > 1 && (!isDesktop || resultView(msg)==='chart')"
                :message="msg" :desktop-expanded="isDesktop" :chart-type-override="isDesktop ? chartStyle(msg) : ''"/>
              <div v-if="!isDesktop || resultView(msg)==='table'" class="result-table-scroll" role="region" tabindex="0" aria-label="数据结果表格，可独立上下滚动">
                <table class="result-table">
                  <thead><tr><th v-for="(col, idx) in msg.columns" :key="col" :title="col">{{ columnLabel(msg, col, idx) }}</th></tr></thead>
                  <tbody><tr v-for="(row, idx) in msg.rows" :key="idx">
                    <td v-for="col in msg.columns" :key="col">{{ pretty(row[col]) }}</td>
                  </tr></tbody>
                </table>
              </div>
              <p v-if="!isDesktop || resultView(msg)==='table'" class="table-foot">当前显示 {{ msg.rows.length }} 行，表格区域可独立上下滚动（最多200行）。</p>
            </template>
            <div v-if="msg.unsupported && msg.followUps?.length" class="metric-clarification" aria-label="请选择查询指标">
              <div class="metric-clarification-heading">
                <van-icon name="question-o" size="18"/>
                <strong>你想查看哪项指标？</strong>
              </div>
              <p>以下选项根据当前已授权的数据字段生成，点击即可继续查询：</p>
              <div class="metric-clarification-options">
                <button v-for="item in msg.followUps" :key="item" type="button" :disabled="busy"
                  @click="ask(item, msg.id)">
                  {{ item }} <van-icon name="arrow" size="12"/>
                </button>
              </div>
              <small>也可以在输入框中说明需要的具体指标。</small>
            </div>
            <div v-if="isDesktop" class="desktop-answer-tools">
              <button type="button" @click="copyAnswer(msg)"><van-icon name="description"/> 复制结果</button>
              <button v-if="!msg.unsupported && msg.rows?.length" type="button" @click="saveChart(msg)"><van-icon name="star-o"/> 收藏</button>
              <button v-if="!msg.unsupported && msg.rows?.length" type="button" @click="shareChart(msg)"><van-icon name="share-o"/> 分享</button>
              <button type="button" :disabled="busy" @click="ask(msg.question)"><van-icon name="replay"/> 重新查询</button>
            </div>
            <div v-if="!msg.unsupported && msg.followUps?.length" class="followups">
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
        <section v-if="isDesktop && !busy && desktopFeedback" class="turn desktop-feedback-turn" aria-live="polite">
          <div class="user-bubble">{{ desktopFeedback.question }}</div>
          <div class="desktop-feedback-card" :class="'feedback-'+desktopFeedback.type">
            <van-icon :name="desktopFeedback.type==='stopped' ? 'pause-circle-o' : 'warning-o'" size="20"/>
            <div class="desktop-feedback-content">
              <strong>{{ desktopFeedback.type==='stopped' ? '查询已停止' : '查询未完成' }}</strong>
              <p>{{ desktopFeedback.message }}</p>
            </div>
            <button type="button" :disabled="busy" @click="ask(desktopFeedback.question)">
              <van-icon name="replay"/> 重新尝试
            </button>
          </div>
        </section>
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
      <footer v-if="!isDesktop || desktopSection==='ask'" class="composer">
        <div class="composer-row">
          <textarea ref="desktopQuestionRef" v-model="question" :rows="isDesktop ? (messages.length ? 1 : 3) : 1" maxlength="800"
            :placeholder="isDesktop ? '例如：昨天各城市的销售额是多少？' : '问问经营数据，例如：昨日营业额'"
            aria-label="输入经营数据问题" @keydown.enter.exact.prevent="ask()"></textarea>
          <div v-if="isDesktop" class="desktop-composer-hints">
            <span>Enter 发送 · Shift + Enter 换行</span>
            <span :class="{ 'near-limit': question.length > 700 }">{{ question.length }}/800</span>
          </div>
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
      <van-popup v-if="isDesktop" v-model:show="changePasswordOpen"
        position="center" round :closeable="!changePasswordBusy" :close-on-click-overlay="!changePasswordBusy"
        :style="{width:'min(430px,calc(100vw - 36px))'}"
        @closed="closePasswordChange">
        <div class="da-password-dialog">
          <h2>修改密码</h2>
          <p>为保障账号安全，请验证当前密码。修改后需要重新登录。</p>
          <van-field v-model="passwordForm.oldPassword" type="password"
            label="当前密码" placeholder="请输入当前密码" autocomplete="current-password"/>
          <van-field v-model="passwordForm.newPassword" type="password"
            label="新密码" placeholder="请输入10到128位的新密码" autocomplete="new-password"/>
          <van-field v-model="passwordForm.confirmPassword" type="password"
            label="确认密码" placeholder="再次输入新密码" autocomplete="new-password"
            @keyup.enter="submitPasswordChange"/>
          <div class="da-password-actions">
            <van-button size="normal" :disabled="changePasswordBusy" @click="closePasswordChange">取消</van-button>
            <van-button type="primary" size="normal" :loading="changePasswordBusy"
              @click="submitPasswordChange">确认修改</van-button>
          </div>
        </div>
      </van-popup>
      <van-popup v-if="!isDesktop" v-model:show="chartsVisible" position="bottom" round
        :style="{ width:'100%', height:'86%' }" @closed="closeChartDetail">
        <div class="drawer da-narrow-charts">
          <div class="drawer-header">
            <b>{{ activeChart ? activeChart.title : '我的图表' }}</b>
            <van-icon name="cross" @click="chartsVisible=false"/>
          </div>
          <template v-if="activeChart">
            <button type="button" class="da-chart-back" @click="closeChartDetail"><van-icon name="arrow-left"/> 返回图表列表</button>
            <div class="da-narrow-detail-actions">
              <button @click="refreshChartDetail" :disabled="activeChartRefreshing"><van-icon name="replay"/> 刷新数据</button>
              <button v-if="activeChart.ownerUsername===me.username || isAdmin"
                @click="openChartShare(activeChart)"><van-icon name="share-o"/> 共享</button>
            </div>
            <p v-if="activeChartError" class="da-chart-detail-error">{{ activeChartError }}</p>
            <van-loading v-if="activeChartBusy" size="22px">正在打开图表…</van-loading>
            <template v-else-if="activeChartAnswer">
              <p class="muted">{{ activeChart.question }} · {{ activeChartCached ? '保存结果' : '实时结果' }}</p>
              <div class="da-chart-view-buttons">
                <button v-if="activeChartIsMetric" :class="{active:activeChartMode==='summary'}" @click="activeChartMode='summary'">指标</button>
                <button v-if="activeChartCanPlot" :class="{active:activeChartMode==='chart'}" @click="activeChartMode='chart'">图表</button>
                <button :class="{active:activeChartMode==='table'}" @click="activeChartMode='table'">表格</button>
              </div>
              <AnswerChart v-if="activeChartCanPlot && activeChartMode==='chart'"
                :message="activeChartAnswer" :chart-type-override="activeChartStyle"/>
              <div v-if="activeChartMode==='table' || (!activeChartCanPlot && !activeChartIsMetric)" class="result-table-scroll da-chart-detail-table">
                <table class="result-table"><thead><tr>
                  <th v-for="(col,idx) in activeChartAnswer.columns" :key="col">{{ columnLabel(activeChartAnswer,col,idx) }}</th>
                </tr></thead><tbody><tr v-for="(row,i) in activeChartAnswer.rows" :key="i">
                  <td v-for="col in activeChartAnswer.columns" :key="col">{{ pretty(row[col]) }}</td>
                </tr></tbody></table>
              </div>
            </template>
          </template>
          <template v-else>
            <p class="muted">点击收藏即可直接查看图表，无需返回对话。</p>
            <div v-for="item in savedCharts" :key="item.id" class="session-item" @click="revisitChart(item)">
              <van-icon name="chart-trending-o"/><span>{{ item.title }}</span><van-icon name="arrow"/>
            </div>
            <div v-for="item in sharedCharts" :key="item.id" class="session-item" @click="revisitChart(item)">
              <van-icon name="share-o"/><span>{{ item.title }}</span><van-icon name="arrow"/>
            </div>
            <p v-if="!savedCharts.length && !sharedCharts.length" class="muted">暂时没有收藏或共享的图表</p>
          </template>
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
      <van-popup v-if="isAdmin && !isDesktop" v-model:show="adminVisible" :position="isDesktop ? 'center' : 'bottom'" round
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
      <van-popup v-if="isAdmin && !isDesktop" v-model:show="configVisible" :position="isDesktop ? 'center' : 'bottom'" round
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
            <van-cell title="清除原有 API Key" label="仅无密钥本地模型才需要开启；开启后测试不会携带原密钥">
              <template #right-icon><van-switch v-model="aiForm.clearKey" size="20px"/></template>
            </van-cell>
            <p v-if="aiForm.clearKey" class="tb-admin-credential-warning">
              <van-icon name="warning-o"/> 当前已勾选清除 API Key。关闭开关即可测试已保存的 Key；保存清除操作前将再次确认。
            </p>
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

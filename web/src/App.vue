<script setup>
import { computed, onMounted, onBeforeUnmount, ref, nextTick, watch } from 'vue'
import axios from 'axios'
import { showToast, showFailToast, showSuccessToast, showConfirmDialog } from 'vant'
import AnswerChart from './components/AnswerChart.vue'
import AnalysisPanel from './components/AnalysisPanel.vue'
import QueryExplanation from './components/QueryExplanation.vue'
import AdminAuditLog from './components/AdminAuditLog.vue'
import AdminDataSources from './components/AdminDataSources.vue'
import AdminUsers from './components/AdminUsers.vue'
import AdminRoles from './components/AdminRoles.vue'
import AdminMetrics from './components/AdminMetrics.vue'
import ModelConfigCenter from './components/ModelConfigCenter.vue'
import RecordList from './components/RecordList.vue'
import ListPagination from './components/ListPagination.vue'
import ResultTable from './components/ResultTable.vue'
import { recordClientEvent, recordLogout } from './audit'

let authEpoch = 0
const api = axios.create({ baseURL: '/api', timeout: 65000 })
api.interceptors.request.use(config => {
  const token = sessionStorage.getItem('dataask_token')
  if (token) config.headers.Authorization = 'Bearer ' + token
  return config
})
api.interceptors.response.use(r => r, error => {
  if (error.response?.status === 401 && !String(error.config?.url || '').includes('/auth/login')) {
    const sentAuthorization = error.config?.headers?.Authorization ||
      error.config?.headers?.get?.('Authorization') || ''
    const currentToken = sessionStorage.getItem('dataask_token')
    const currentAuthorization = currentToken ? 'Bearer ' + currentToken : ''
    // A late response from the previous account must never invalidate a newer login.
    if (sentAuthorization !== currentAuthorization) return Promise.reject(error)
    authEpoch++
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
const verifiedPermissions = ref([])
const cityPermissionScope = ref('ALL')
const loginForm = ref({ username: 'admin', password: '' })
const loggingIn = ref(false)
const showPassword = ref(false)
// Desktop account actions live at the foot of the navigation rail.
const accountMenuOpen = ref(false)
const accountMenuRef = ref(null)
const recentSessionMenuId = ref('')
const personalizationOpen = ref(false)
const theme = ref('light')
const profileOpen = ref(false)
const profileLoading = ref(false)
const profileSaving = ref(false)
const profileError = ref('')
const profileForm = ref({displayName:'',createdAt:''})
const cityPermissions = ref([])
const cityPermissionError = ref('')
const cityPermissionLoading = ref(false)

async function loadCityPermissions() {
  if (!sessionStorage.getItem('dataask_token')) return
  const epoch=authEpoch, token=sessionStorage.getItem('dataask_token')
  cityPermissionLoading.value=true
  cityPermissionError.value=''
  try {
    const {data}=await api.get('/auth/city-permissions')
    if(epoch!==authEpoch || token!==sessionStorage.getItem('dataask_token')) return
    cityPermissions.value=Array.isArray(data.cities) ? data.cities : []
    cityPermissionScope.value=data.scope==='CITY'?'CITY':'ALL'
  } catch(e) {
    if(epoch!==authEpoch || token!==sessionStorage.getItem('dataask_token')) return
    cityPermissions.value=[]
    cityPermissionScope.value='ALL'
    cityPermissionError.value=errorText(e)
  } finally {
    if(epoch===authEpoch) cityPermissionLoading.value=false
  }
}
let profileSequence = 0
const changePasswordOpen = ref(false)
const changePasswordBusy = ref(false)
const passwordForm = ref({ oldPassword:'', newPassword:'', confirmPassword:'' })
function handleAccountOutsideClick(event) {
  if (accountMenuOpen.value && !accountMenuRef.value?.contains(event.target)) {
    accountMenuOpen.value = false
  }
  const target = event.target instanceof Element ? event.target : null
  if (recentSessionMenuId.value && !target?.closest('.da-recent-menu')) {
    recentSessionMenuId.value = ''
  }
}
function toggleRecentSessionMenu(id) {
  recentSessionMenuId.value = recentSessionMenuId.value === id ? '' : id
  accountMenuOpen.value = false
}
async function deleteRecentSession(item) {
  recentSessionMenuId.value = ''
  await deleteSession(item)
}
function closeAccountMenu(returnFocus = false) {
  accountMenuOpen.value = false
  if (returnFocus) nextTick(() => accountMenuRef.value?.querySelector('.da-account-trigger')?.focus())
}
function toggleAccountMenu() {
  accountMenuOpen.value = !accountMenuOpen.value
  if (accountMenuOpen.value) nextTick(() => accountMenuRef.value?.querySelector('[role="menuitem"]')?.focus())
}
function accountMenuKeydown(event) {
  if (event.key === 'Escape') { event.preventDefault(); closeAccountMenu(true); return }
  if (!accountMenuOpen.value || !['ArrowDown','ArrowUp','Home','End'].includes(event.key)) return
  event.preventDefault()
  const items = [...accountMenuRef.value.querySelectorAll('[role="menuitem"]')]
  const index = items.indexOf(document.activeElement)
  const next = event.key === 'Home' ? 0 : event.key === 'End' ? items.length-1 :
    (index + (event.key === 'ArrowDown' ? 1 : -1) + items.length) % items.length
  items[next]?.focus()
}
function openPersonalization() { closeAccountMenu(); personalizationOpen.value = true }
function chooseTheme(value) {
  theme.value = value === 'dark' ? 'dark' : 'light'
  recordClientEvent('THEME_CHANGE',theme.value)
  try { localStorage.setItem('dataask_theme_' + me.value.username, theme.value) }
  catch { showToast('主题已切换，当前浏览器无法保存偏好') }
}
async function openProfile() {
  closeAccountMenu()
  profileOpen.value = true
  profileForm.value = {displayName:me.value.displayName || '',createdAt:''}
  profileError.value = ''
  profileLoading.value = true
  const epoch = authEpoch, username = me.value.username, sequence = ++profileSequence
  try {
    const {data} = await api.get('/auth/profile')
    if (epoch !== authEpoch || sequence !== profileSequence || username !== me.value.username || !profileOpen.value) return
    profileForm.value = {displayName:data.displayName || '',createdAt:data.createdAt || ''}
  } catch(e) {
    if (epoch === authEpoch && sequence === profileSequence) profileError.value = errorText(e)
  } finally {
    if (epoch === authEpoch && sequence === profileSequence) profileLoading.value = false
  }
}
function closeProfile() {
  if (profileSaving.value) return
  profileOpen.value = false
  profileSequence++
  profileLoading.value = false
}
async function saveProfile() {
  if (profileSaving.value || profileLoading.value || profileError.value) return
  const displayName = profileForm.value.displayName.trim()
  if (!displayName || displayName.length > 100) return showFailToast('显示名称需为1到100个字符')
  const epoch = authEpoch, username = me.value.username
  profileSaving.value = true
  try {
    const {data} = await api.put('/auth/profile', {displayName})
    if (epoch !== authEpoch || username !== me.value.username || !loggedIn.value) return
    me.value = {...me.value,displayName:data.displayName}
    sessionStorage.setItem('dataask_user', JSON.stringify(me.value))
    profileOpen.value = false
    showSuccessToast('个人资料已保存')
  } catch(e) { if (epoch === authEpoch) showFailToast(errorText(e)) }
  finally { if (epoch === authEpoch) profileSaving.value = false }
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
    logout(false)
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
const showDesktopAskLanding = computed(() =>
  isDesktop.value &&
  desktopSection.value === 'ask' &&
  messages.value.length === 0 &&
  !busy.value &&
  !desktopFeedback.value
)
const analysisPanelOpen = ref(false)
const analysisMessage = ref(null)
const analysisQuestion = ref('')
const answerViews = ref({})
const followUpSource = ref(null)
const fullScreenMessage = ref(null)
const fullScreenView = ref('chart')
const answerFeedback = ref({})
const resultFullscreenOpen = computed({
  get: () => !!fullScreenMessage.value,
  set: value => { if (!value) fullScreenMessage.value = null }
})
const desktopQuestionRef = ref(null)

function openAnalysis(message) {
  if (!isDesktop.value) return
  analysisMessage.value = message || null
  analysisQuestion.value = message?.question || pendingQuestion.value || ''
  analysisPanelOpen.value = true
  if (message?.id) recordClientEvent('VIEW_PROCESS', message.id)
}
function closeAnalysis() {
  analysisPanelOpen.value = false
}
function startFollowUp(message) {
  if (!message || busy.value) return
  followUpSource.value = message
  question.value = ''
  focusDesktopQuestion()
  recordClientEvent('FOLLOW_UP_START', message.id)
}
function cancelFollowUp() { followUpSource.value = null }
function openFullScreen(message) {
  fullScreenMessage.value = message
  fullScreenView.value = ['bar','line','pie'].includes(message.chartType) && message.rows?.length > 1
    ? 'chart' : isSingleValue(message) ? 'summary' : 'table'
  recordClientEvent('FULLSCREEN_RESULT', message.id)
}
function closeFullScreen() { fullScreenMessage.value = null }
function voteAnswer(message, value) {
  const next = answerFeedback.value[message.id] === value ? '' : value
  answerFeedback.value = { ...answerFeedback.value, [message.id]: next }
  recordClientEvent(next === 'up' ? 'RESULT_LIKE' : next === 'down' ? 'RESULT_DISLIKE' : 'RESULT_FEEDBACK_CLEAR', message.id)
  showToast(next === 'up' ? '感谢你的认可' : next === 'down' ? '反馈已记录' : '已取消反馈')
}
const chartsVisible = ref(false)
const savedCharts = ref([])
const sharedCharts = ref([])
const chartsBusy = ref(false)
const chartsError = ref('')
const chartListRefresh = ref(0), historyListRefresh = ref(0), metricListRefresh = ref(0)
const historySelection = ref([])
const tablePage=ref(1), tableSize=ref(10), tableSearch=ref('')
const filteredTables=computed(()=>allowedTables.value.filter(t=>t.name.toLowerCase().includes(tableSearch.value.toLowerCase())))
const pagedTables=computed(()=>filteredTables.value.slice((tablePage.value-1)*tableSize.value,tablePage.value*tableSize.value))
watch(tableSearch,()=>{tablePage.value=1})
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
const chartView = ref('mine')
const chartSearch = ref('')
const chartSearchDraft = ref('')
const chartTypeFilter = ref('ALL')
const chartTypeDraft = ref('ALL')
const chartSortOrder = ref('recent')
const chartSortDraft = ref('recent')
function applyChartFilters() {
  const next=chartSearchDraft.value.trim()
  const changed=chartSearch.value!==next || chartTypeFilter.value!==chartTypeDraft.value || chartSortOrder.value!==chartSortDraft.value
  chartSearch.value=next
  chartTypeFilter.value=chartTypeDraft.value
  chartSortOrder.value=chartSortDraft.value
  if (!changed) chartListRefresh.value++ // refresh current filters explicitly
}
function resetChartFilters() {
  const changed=chartSearch.value!=='' || chartTypeFilter.value!=='ALL' || chartSortOrder.value!=='recent'
  chartSearchDraft.value=''
  chartTypeDraft.value='ALL'
  chartSortDraft.value='recent'
  chartSearch.value=''
  chartTypeFilter.value='ALL'
  chartSortOrder.value='recent'
  if (!changed) chartListRefresh.value++
}
const chartTypeOptions = ['bar','line','pie','metric','table']
function chartTypeLabel(type) {
  return ({line:'折线图', bar:'柱状图', pie:'饼图', metric:'指标卡', table:'数据表'})[type] || type || '图表'
}
function isRedundantSummary(msg) {
  if (!msg?.rows?.length || msg.unsupported) return false
  // Structured results carry an actual data summary, even when it starts with a row count.
  if (msg.semantic) return false
  const summary = String(msg.summary || '').replace(/\s+/g,' ').trim()
  if (!summary) return true
  // Suppress generic system narration, never hide actual calculated business insights.
  return /^(?:已从\s*StarRocks\s*查询到\s*\d+\s*行真实数据[。.]?\s*(?:可点击下方快捷追问继续分析[。.]?)?|(?:已查询到|查询到|已返回|已获取|本次查询返回)\s*\d+\s*(?:行|条)数据[。.]?)$/i.test(summary)
}

const desktopSection = ref('ask')
const adminExpanded = ref(false)
const adminWorkspaceOpen = ref(false)
const adminReturnSection = ref('ask')
const desktopAdminSections = ['admin-source','admin-model','admin-tables','admin-metrics','admin-users','admin-roles','admin-status','admin-audit']
const adminNavigationGroups = [
  { label:'管理', items:[
    {section:'admin-source',label:'数据源配置',icon:'cluster-o'},
    {section:'admin-model',label:'模型配置',icon:'chat-o'},
    {section:'admin-tables',label:'ADS 表授权',icon:'records-o'},
    {section:'admin-metrics',label:'指标口径',icon:'description'}
  ]},
  { label:'权限', items:[
    {section:'admin-users',label:'用户管理',icon:'friends-o'},
    {section:'admin-roles',label:'角色权限',icon:'shield-o'}
  ]},
  { label:'运维', items:[
    {section:'admin-status',label:'配置状态',icon:'chart-trending-o'},
    {section:'admin-audit',label:'审计日志',icon:'shield-o'}
  ]}
]
const adminSectionBusy = ref(false)
const modelConfigVisible = ref(false)
const modelCenterRef = ref(null)
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
  { key:'模型配置', configured:!!status.value.aiConfigured,
    detail:'对话：'+(status.value.model || '未配置')+' · Embedding：'+(status.value.embeddingModel || '未启用'),
    section:'admin-model' },
])

const desktopSectionTitle = computed(() => ({
  ask:'新建任务',charts:'我的图表',history:'历史会话',
  'admin-source':'数据源配置','admin-model':'模型配置',
  'admin-tables':'ADS 表授权',
  'admin-metrics':'指标口径','admin-users':'用户管理','admin-roles':'角色权限',
  'admin-status':'配置状态','admin-audit':'审计日志'
})[desktopSection.value] || '智能问数')
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
let conversationVersion = 0
const composerHeight = ref(148)
const sidebarWidth = ref(216)
let composerPointerDrag = null
let sidebarPointerDrag = null
function boundedComposerHeight(px) { return Math.max(94, Math.min(Math.round(window.innerHeight * 0.52), 430, Math.round(px))) }
function adjustComposerHeight(delta) {
  composerHeight.value = boundedComposerHeight(composerHeight.value + delta)
  localStorage.setItem('dataask_composer_height', String(composerHeight.value))
}
function moveComposerPointer(e) {
  if (!composerPointerDrag || e.pointerId !== composerPointerDrag.id) return
  composerHeight.value = boundedComposerHeight(composerPointerDrag.height + composerPointerDrag.y - e.clientY)
}
function finishComposerPointer() {
  if (!composerPointerDrag) return
  composerPointerDrag = null
  document.documentElement.classList.remove('da-composer-resizing')
  document.removeEventListener('pointermove', moveComposerPointer)
  document.removeEventListener('pointerup', finishComposerPointer)
  document.removeEventListener('pointercancel', finishComposerPointer)
  localStorage.setItem('dataask_composer_height', String(composerHeight.value))
}
function beginComposerDrag(e) {
  if (!isDesktop.value) return
  e.preventDefault()
  composerPointerDrag = { id:e.pointerId, y:e.clientY, height:composerHeight.value }
  document.documentElement.classList.add('da-composer-resizing')
  document.addEventListener('pointermove', moveComposerPointer)
  document.addEventListener('pointerup', finishComposerPointer)
  document.addEventListener('pointercancel', finishComposerPointer)
}
function boundedSidebarWidth(px) {
  return Math.max(176, Math.min(360, Math.round(px)))
}
function adjustSidebarWidth(delta) {
  sidebarWidth.value = boundedSidebarWidth(sidebarWidth.value + delta)
  localStorage.setItem('dataask_sidebar_width', String(sidebarWidth.value))
}
function moveSidebarPointer(e) {
  if (!sidebarPointerDrag || e.pointerId !== sidebarPointerDrag.id) return
  sidebarWidth.value = boundedSidebarWidth(sidebarPointerDrag.width + e.clientX - sidebarPointerDrag.x)
}
function finishSidebarPointer() {
  if (!sidebarPointerDrag) return
  sidebarPointerDrag = null
  document.documentElement.classList.remove('da-sidebar-resizing')
  document.removeEventListener('pointermove', moveSidebarPointer)
  document.removeEventListener('pointerup', finishSidebarPointer)
  document.removeEventListener('pointercancel', finishSidebarPointer)
  localStorage.setItem('dataask_sidebar_width', String(sidebarWidth.value))
}
function beginSidebarDrag(e) {
  if (!isDesktop.value) return
  e.preventDefault()
  sidebarPointerDrag = { id:e.pointerId, x:e.clientX, width:sidebarWidth.value }
  document.documentElement.classList.add('da-sidebar-resizing')
  document.addEventListener('pointermove', moveSidebarPointer)
  document.addEventListener('pointerup', finishSidebarPointer)
  document.addEventListener('pointercancel', finishSidebarPointer)
}
const historyVisible = ref(false)
const sessions = ref([])
const sessionSearch = ref('')
function currentDateInput() {
  const now = new Date()
  return [now.getFullYear(), String(now.getMonth() + 1).padStart(2, '0'), String(now.getDate()).padStart(2, '0')].join('-')
}
// PC history opens on today's conversations; an empty value switches back to all dates.
const historyDate = ref(currentDateInput())
const todayLabel = computed(() => {
  const now = new Date()
  const weekday = ['日','一','二','三','四','五','六'][now.getDay()]
  return `${now.getFullYear()} 年 ${now.getMonth() + 1} 月 ${now.getDate()} 日 星期${weekday}`
})
function answerScope(msg) {
  const period = msg.timeLabel || msg.periodLabel
  return [
    msg.metricLabel ? { key:'metric', label:'指标', value:msg.metricLabel } : null,
    period ? { key:'period', label:'周期', value:period } : null,
    msg.dataset ? { key:'dataset', label:'数据来源', value:msg.dataset } : null,
  ].filter(Boolean)
}
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
const adminVisible = ref(false)
const status = ref({})
const configVisible = ref(false)
const configKind = ref('starrocks')
const isDesktop = ref(false)
watch(() => [loggedIn.value, me.value.username], () => {
  try { theme.value = loggedIn.value && localStorage.getItem('dataask_theme_' + me.value.username) === 'dark' ? 'dark' : 'light' }
  catch { theme.value = 'light' }
}, {immediate:true})
watch([theme, isDesktop, loggedIn], () => {
  if (isDesktop.value && loggedIn.value) document.documentElement.dataset.dataaskTheme = theme.value
  else delete document.documentElement.dataset.dataaskTheme
  nextTick(() => window.dispatchEvent(new Event('dataask-themechange')))
}, {immediate:true})
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
const desktopQuickQuestions = [
  { type:'指标查询', icon:'chart-trending-o', question:'昨天的营业额是多少？' },
  { type:'城市排行', icon:'location-o', question:'昨天各城市的订单量排行' },
  { type:'趋势分析', icon:'bar-chart-o', question:'最近7天的营收趋势' },
  { type:'经营对比', icon:'balance-o', question:'本月各城市销售额对比' },
  { type:'用户增长', icon:'friends-o', question:'昨天新增用户数是多少？' },
  { type:'城市排行', icon:'cluster-o', question:'本月各城市新增用户排行' },
  { type:'订单趋势', icon:'clock-o', question:'最近30天订单量趋势' },
  { type:'销售排行', icon:'ascending', question:'昨天各城市销售额排行' }
]
const desktopAskCategories = ['指标查询','城市排行','趋势分析','经营对比','用户增长','销售排行']
function chooseAskCategory(type) {
  const item = desktopQuickQuestions.find(entry => entry.type === type)
  if (!item) return
  question.value = item.question
  focusDesktopQuestion()
}
// Quick BI-inspired desktop querying. A dataset selection is enforced by the server.
const approvedDatasets = ref([])
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
  } catch(e) {
    approvedDatasets.value = []
    if (isDesktop.value) showFailToast('读取可用数据失败：'+errorText(e))
  }
}
const isAdmin = computed(() => loggedIn.value && verifiedRole.value === 'ADMIN')
function authPermissions(data) {
  if (Array.isArray(data?.permissions)) return data.permissions.map(String)
  return data?.role === 'ADMIN' ? ['ASK','CHARTS','SYSTEM_ADMIN'] : ['ASK','CHARTS']
}
const hasPermission = code => verifiedPermissions.value.includes(code) || isAdmin.value
const canAsk = computed(() => loggedIn.value && hasPermission('ASK'))
const canCharts = computed(() => loggedIn.value && hasPermission('CHARTS'))
const canSystemAdmin = computed(() => loggedIn.value && hasPermission('SYSTEM_ADMIN'))
function requireAdmin() {
  if (canSystemAdmin.value) return true
  showFailToast('当前角色没有系统管理权限')
  return false
}
function defaultDesktopSection() {
  if (hasPermission('ASK')) return 'ask'
  if (hasPermission('CHARTS')) return 'charts'
  if (hasPermission('SYSTEM_ADMIN')) return 'admin-source'
  return 'ask'
}
async function refreshAuthenticatedRole() {
  if (!sessionStorage.getItem('dataask_token')) {
    verifiedRole.value = ''
    verifiedPermissions.value = []
    return
  }
  verifiedRole.value = ''
  adminVisible.value = false
  configVisible.value = false
  const epoch = authEpoch, token = sessionStorage.getItem('dataask_token')
  try {
    const { data } = await api.get('/auth/me')
    if (epoch !== authEpoch || token !== sessionStorage.getItem('dataask_token')) return
    const username = String(data.username || '')
    const role = String(data.role || '')
    const permissions = authPermissions(data)
    // Do not trust a cached role or permissions, even when somebody alters sessionStorage manually.
    const displayName = data.displayName || (me.value.username === username ? (me.value.displayName || username) : username)
    me.value = { username, displayName, role, roleName:data.roleName || role, permissions, dataScope:data.dataScope || 'ALL' }
    sessionStorage.setItem('dataask_user', JSON.stringify(me.value))
    verifiedRole.value = role
    verifiedPermissions.value = permissions
    cityPermissionScope.value = data.dataScope === 'CITY' ? 'CITY' : 'ALL'
  } catch (e) {
    if (epoch !== authEpoch || token !== sessionStorage.getItem('dataask_token')) return
    verifiedRole.value = ''
    verifiedPermissions.value = []
    if (e.response?.status === 401) {
      sessionStorage.removeItem('dataask_token')
      sessionStorage.removeItem('dataask_user')
      loggedIn.value = false
      me.value = {}
    }
  }
}
function resultView(msg) {
  if (isSingleValue(msg)) return 'summary'
  const explicit = answerViews.value[msg.id]
  if (explicit) return explicit
  return ['line', 'bar', 'pie'].includes(msg.chartType) && msg.rows?.length > 1 ? 'chart' : 'table'
}
function isSingleValue(msg) { return msg?.rows?.length === 1 && msg?.columns?.length === 1 }
function answerSummary(msg) {
  if (!msg?.unsupported || msg.followUps?.length) return msg?.summary || ''
  return `${msg?.summary || '当前问题暂时无法查询。'} 请补充具体指标、时间范围或分析维度，例如“昨天各城市的订单量”。`
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
    recordClientEvent('COPY_RESULT',msg.id)
  } catch { showFailToast('复制失败，请检查浏览器剪贴板权限') }
}

async function copyTraceId(msg) {
  try {
    if (!msg?.id) throw new Error('没有可复制的标识')
    await navigator.clipboard.writeText(msg.id)
    showSuccessToast('Trace ID 已复制')
    recordClientEvent('COPY_TRACE_ID', msg.id)
  } catch { showFailToast('复制失败，请检查浏览器剪贴板权限') }
}
function exportAnswer(msg) {
  if (!msg?.rows?.length || !msg.columns?.length) return showToast('当前没有可导出的数据')
  const safeCell = value => {
    let text = value === null || value === undefined ? '' : String(value)
    if (/^[=+\-@]/.test(text)) text = "'" + text
    return '"' + text.replaceAll('"','""') + '"'
  }
  const lines = [
    msg.columns.map(column => safeCell(msg.columnLabels?.[column] || column)).join(','),
    ...msg.rows.map(row => msg.columns.map(column => safeCell(row[column])).join(','))
  ]
  const blob = new Blob(['\ufeff' + lines.join('\r\n')], { type:'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = (msg.title || msg.question || '问数结果').replace(/[\\/:*?"<>|]/g,'_').slice(0,80) + '.csv'
  document.body.appendChild(link)
  link.click()
  link.remove()
  setTimeout(() => URL.revokeObjectURL(url), 2000)
  showSuccessToast('数据已导出')
  recordClientEvent('EXPORT_RESULT', msg.id)
}

function errorText(e) { return e?.response?.data?.message || e?.message || '请求失败' }
function scrollEnd() { nextTick(() => footerRef.value?.scrollIntoView({ behavior: 'smooth', block: 'end' })) }
async function login() {
  if (!loginForm.value.username || !loginForm.value.password) return showToast('请输入账号和密码')
  loggingIn.value = true
  try {
    const { data } = await api.post('/auth/login', loginForm.value)
    authEpoch++
    sessionStorage.setItem('dataask_token', data.token)
    const permissions = authPermissions(data)
    const user = { username:data.username, displayName:data.displayName, role:data.role,
      roleName:data.roleName || data.role, permissions, dataScope:data.dataScope || 'ALL' }
    sessionStorage.setItem('dataask_user', JSON.stringify(user))
    verifiedRole.value = String(data.role || '')
    verifiedPermissions.value = permissions
    cityPermissionScope.value = data.dataScope === 'CITY' ? 'CITY' : 'ALL'
    me.value = user; loggedIn.value = true; loginForm.value.password = ''
    await loadCityPermissions()
    await Promise.all([
      hasPermission('ASK') ? loadSessions() : Promise.resolve(),
      hasPermission('ASK') ? loadApprovedDatasets() : Promise.resolve(),
      hasPermission('CHARTS') ? loadSavedCharts() : Promise.resolve()
    ])
    if (isDesktop.value) desktopSection.value = defaultDesktopSection()
  } catch (e) { showFailToast(errorText(e)) }
  finally { loggingIn.value = false }
}
function logout(record = true) {
  if (record !== false) recordLogout()
  if (busy.value) stopAsk()
  authEpoch++
  conversationVersion++
  sessionStorage.removeItem('dataask_token')
  sessionStorage.removeItem('dataask_user')
  loggedIn.value = false
  verifiedRole.value = ''
  verifiedPermissions.value = []
  configVisible.value = false
  accountMenuOpen.value = false
  recentSessionMenuId.value = ''
  personalizationOpen.value = false
  profileOpen.value = false
  profileSequence++
  profileSaving.value = false
  profileLoading.value = false
  profileError.value = ''
  profileForm.value = {displayName:'',createdAt:''}
  cityPermissions.value = []
  cityPermissionScope.value = 'ALL'
  cityPermissionError.value = ''
  cityPermissionLoading.value = false
  changePasswordOpen.value = false
  passwordForm.value = { oldPassword:'', newPassword:'', confirmPassword:'' }
  messages.value = []; sessions.value = []; sessionId.value = ''; historyVisible.value = false; adminVisible.value = false; modelConfigVisible.value = false
  desktopFeedback.value = null
  analysisPanelOpen.value = false
  analysisMessage.value = null
  analysisQuestion.value = ''
  followUpSource.value = null
  fullScreenMessage.value = null
  answerFeedback.value = {}
  desktopSection.value = 'ask'
  adminSectionError.value = ''
  adminSectionBusy.value = false
  adminExpanded.value = false
  savedCharts.value = []
  sharedCharts.value = []
  approvedDatasets.value = []
}
async function deleteSession(row) {
  try {
    await showConfirmDialog({ title:'删除会话', message:'确定删除“'+row.title+'”？会话内的问数记录将一并删除，不可恢复。' })
  } catch { return }
  try {
    await api.delete('/chat/sessions/'+encodeURIComponent(row.id))
    historySelection.value = historySelection.value.filter(id => id !== row.id)
    afterSessionDelete([row.id])
    showSuccessToast('会话已删除')
  } catch(e) { showFailToast(errorText(e)) }
}
async function batchDeleteSessions() {
  const ids = [...historySelection.value]
  if (!ids.length) return
  try {
    await showConfirmDialog({ title:'批量删除会话', message:'确定删除选中的 '+ids.length+' 个会话？会话内的问数记录将一并删除，不可恢复。' })
  } catch { return }
  try {
    await api.post('/chat/sessions/batch-delete', { ids })
    historySelection.value = []
    afterSessionDelete(ids)
    showSuccessToast('已删除 '+ids.length+' 个会话')
  } catch(e) { showFailToast(errorText(e)) }
}
function afterSessionDelete(ids) {
  historyListRefresh.value++
  loadSessions()
  if (ids.includes(sessionId.value)) newConversation()
}
async function loadSessions() {
  const epoch = authEpoch
  const token = sessionStorage.getItem('dataask_token')
  const username = me.value.username
  try {
    const {data} = await api.get('/chat/sessions')
    if (epoch !== authEpoch || token !== sessionStorage.getItem('dataask_token') ||
        username !== me.value.username || !loggedIn.value) return
    sessions.value = data
  } catch (e) {
    if (epoch === authEpoch) showFailToast(errorText(e))
  }
}
let sharedHistoryRefreshPending = false
async function refreshSharedHistory() {
  if (!loggedIn.value || document.hidden || sharedHistoryRefreshPending) return
  const epoch = authEpoch
  const token = sessionStorage.getItem('dataask_token')
  const username = me.value.username
  sharedHistoryRefreshPending = true
  try {
    const {data:list} = await api.get('/chat/sessions')
    if (epoch !== authEpoch || token !== sessionStorage.getItem('dataask_token') ||
        username !== me.value.username || !loggedIn.value) return
    sessions.value = list
    const currentSession = sessionId.value
    const version = conversationVersion
    if (!currentSession || busy.value || (isDesktop.value && desktopSection.value !== 'ask')) return
    const {data:latest} = await api.get('/chat/sessions/' + encodeURIComponent(currentSession) + '/messages')
    if (epoch !== authEpoch || token !== sessionStorage.getItem('dataask_token') ||
        username !== me.value.username || !loggedIn.value || sessionId.value !== currentSession ||
        version !== conversationVersion || busy.value) return
    if (messages.value.map(m=>m.id).join('|') !== latest.map(m=>m.id).join('|')) {
      messages.value = latest
      scrollEnd()
    }
  } catch { /* Keep locally rendered conversations when disconnected. */ }
  finally { sharedHistoryRefreshPending = false }
}
function switchDesktopSection(section) {
  const normal = ['ask','charts','history'].includes(section)
  if (section==='ask' && !hasPermission('ASK')) return
  if (section==='charts' && !hasPermission('CHARTS')) return
  if (section==='history' && !hasPermission('ASK')) return
  if (!normal && !(desktopAdminSections.includes(section) && canSystemAdmin.value)) return
  if (desktopSection.value !== section) recordClientEvent('PAGE_VIEW',section)
  if (isDesktop.value && busy.value && section !== 'ask') stopAsk()
  configVisible.value=false
  if (section !== 'ask') { closeAnalysis(); closeFullScreen(); followUpSource.value = null }
  desktopSection.value = section
  if (desktopAdminSections.includes(section)) adminExpanded.value = true
  if (section === 'charts') closeChartDetail()
  if (section === 'history') loadSessions()
  if (section === 'ask') { focusDesktopQuestion(); if (sessionId.value) refreshSharedHistory() }
}
async function loadSavedCharts() {
  if (!loggedIn.value) return
  const epoch = authEpoch
  const token = sessionStorage.getItem('dataask_token')
  const username = me.value.username
  chartsBusy.value = true
  chartsError.value = ''
  try {
    const [mine, shared] = await Promise.all([
      api.get('/mini/charts?view=mine'),
      api.get('/mini/charts?view=shared')
    ])
    if (epoch !== authEpoch || token !== sessionStorage.getItem('dataask_token') ||
        username !== me.value.username || !loggedIn.value) return
    savedCharts.value = Array.isArray(mine.data) ? mine.data : []
    sharedCharts.value = Array.isArray(shared.data) ? shared.data : []
  } catch (e) { if (epoch === authEpoch) { chartsError.value = errorText(e); showFailToast(errorText(e)) } }
  finally { if (epoch === authEpoch) chartsBusy.value = false }
}
async function openSession(item) {
  recentSessionMenuId.value = ''
  if (busy.value) stopAsk()
  const version = ++conversationVersion
  try {
    const {data:history} = await api.get('/chat/sessions/' + encodeURIComponent(item.id) + '/messages')
    if (version !== conversationVersion) return
    messages.value = history
    sessionId.value = item.id
    if (isDesktop.value) desktopSection.value = 'ask'
    closeAnalysis()
    followUpSource.value = null
    closeFullScreen()
    historyVisible.value = false
    desktopFeedback.value = null
    scrollEnd()
  } catch (e) { if (version === conversationVersion) showFailToast(errorText(e)) }
}
function newConversation() {
  recentSessionMenuId.value = ''
  if (busy.value) stopAsk()
  conversationVersion++
  sessionId.value = ''; messages.value = []; question.value = ''; historyVisible.value = false
  if (isDesktop.value) {
    desktopSection.value = 'ask'
  }
  desktopFeedback.value = null
  closeAnalysis()
  analysisMessage.value = null
  analysisQuestion.value = ''
  followUpSource.value = null
  closeFullScreen()
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
  closeAnalysis()
  if (id) {
    const token = sessionStorage.getItem('dataask_token')
    api.post('/chat/cancel', { requestId: id }, token
      ? { headers: { Authorization: 'Bearer ' + token } }
      : undefined).catch(() => {})
  }
  showToast('已停止本次查询')
}
async function ask(text = question.value, sourceMessageId = undefined) {
  text = (text || '').trim()
  if (!text || busy.value) return
  if (isDesktop.value) desktopSection.value = 'ask'
  if (text.length > 800) return showToast('提问不能超过800字')
  const sequence = ++askSequence
  const version = conversationVersion
  const controller = new AbortController()
  const requestId = globalThis.crypto?.randomUUID?.() || ('pc-' + Date.now() + '-' + Math.random().toString(36).slice(2))
  const resolvedSourceMessageId = sourceMessageId === undefined ? followUpSource.value?.id || null : sourceMessageId
  activeController = controller
  activeRequestId = requestId
  busy.value = true
  pendingQuestion.value = text
  if (isDesktop.value) desktopFeedback.value = null
  if (isDesktop.value) {
    analysisMessage.value = null
    analysisQuestion.value = text
    analysisPanelOpen.value = true
  }
  question.value = ''
  scrollEnd()
  try {
    const { data } = await api.post('/chat/ask', {
      question: text, sessionId: sessionId.value || null, sourceMessageId: sessionId.value ? resolvedSourceMessageId : null, requestId,
      dataset: null
    }, { signal: controller.signal })
    if (sequence !== askSequence || version !== conversationVersion || controller.signal.aborted) return
    sessionId.value = data.sessionId
    messages.value.push(data)
    followUpSource.value = null
    if (isDesktop.value) {
      analysisMessage.value = data
      analysisQuestion.value = data.question || text
      analysisPanelOpen.value = true
    }
    await loadSessions()
    scrollEnd()
  } catch (e) {
    if (sequence !== askSequence || version !== conversationVersion || controller.signal.aborted || axios.isCancel(e)) return
    if (isDesktop.value) {
      desktopFeedback.value = { type: 'error', question: text, message: errorText(e) }
      closeAnalysis()
    } else {
      question.value = text
      showFailToast(errorText(e))
    }
  } finally {
    if (sequence === askSequence && version === conversationVersion) {
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
    if (section === 'admin-status') {
      await refreshAdminStatus()
    } else if (section === 'admin-source') {
      await refreshAdminStatus()
      hydrateConfigForms()
    } else if (section === 'admin-model') {
      await refreshAdminStatus()
    } else if (section === 'admin-tables') {
      await refreshAdminStatus()
      if (status.value.starrocksConfigured) {
        allowedTables.value = (await api.get('/admin/tables')).data
      } else {
        allowedTables.value = []
        adminSectionError.value = '尚未配置 StarRocks 数据源，请先在数据源配置中连接 ADS 数据库。'
      }
    } // User and metric list components request their own server-side pages.
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
  closeAccountMenu()
  if (!isDesktop.value || !requireAdmin()) return
  if (!desktopIsAdminSection.value) adminReturnSection.value = desktopSection.value
  adminVisible.value = false
  adminWorkspaceOpen.value = true
  switchDesktopSection('admin-source')
  await loadAdminSectionData('admin-source')
}
function closeAdminWorkspace() {
  adminWorkspaceOpen.value = false
  adminExpanded.value = false
  const target = ['ask','charts','history'].includes(adminReturnSection.value) ? adminReturnSection.value : 'ask'
  if (desktopIsAdminSection.value) switchDesktopSection(target)
}
function toggleAdminNavigation() {
  if (!canSystemAdmin.value) return
  if (adminWorkspaceOpen.value) { closeAdminWorkspace(); return }
  openAdmin()
}
async function refreshAdminOverview() {
  if (!requireAdmin()) return
  await loadAdminSectionData('admin-status')
  if (!adminSectionError.value) showSuccessToast('配置状态已更新')
}
async function openAdminSection(section) {
  if (section === 'admin-embedding') section = 'admin-model' // old navigation alias
  if (!requireAdmin() || !desktopAdminSections.includes(section)) return
  adminWorkspaceOpen.value = true
  if (section === 'admin-source') configKind.value = 'starrocks'
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
  if (isDesktop.value) { metricListRefresh.value++; return }
  try { metricInfo.value = (await api.get('/admin/metrics')).data; metricListRefresh.value++ }
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
    await openAdminSection(kind === 'starrocks' ? 'admin-source' : 'admin-model')
    return
  }
  if (kind !== 'starrocks') {
    modelConfigVisible.value = true
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
    if (isDesktop.value) configVisible.value=false
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
  const storedHeight = Number(localStorage.getItem('dataask_composer_height'))
  if (Number.isFinite(storedHeight) && storedHeight > 0) composerHeight.value = boundedComposerHeight(storedHeight)
  const storedSidebarWidth = Number(localStorage.getItem('dataask_sidebar_width'))
  if (Number.isFinite(storedSidebarWidth) && storedSidebarWidth > 0) sidebarWidth.value = boundedSidebarWidth(storedSidebarWidth)
  desktopMedia = window.matchMedia('(min-width: 1024px)')
  const update = () => { isDesktop.value = desktopMedia.matches }
  update()
  desktopMedia.addEventListener('change', update)
  window.__dataAskDesktopListener = update
  document.addEventListener('click', handleAccountOutsideClick)
  window.addEventListener('focus', refreshSharedHistory)
  document.addEventListener('visibilitychange', refreshSharedHistory)
  if (loggedIn.value) {
    void (async () => {
      await refreshAuthenticatedRole()
      if (!loggedIn.value) return
      if (isDesktop.value) desktopSection.value = defaultDesktopSection()
      await loadCityPermissions()
      await Promise.all([
        hasPermission('ASK') ? loadSessions() : Promise.resolve(),
        hasPermission('ASK') ? loadApprovedDatasets() : Promise.resolve(),
        hasPermission('CHARTS') ? loadSavedCharts() : Promise.resolve()
      ])
    })()
  }
})
onBeforeUnmount(() => {
  delete document.documentElement.dataset.dataaskTheme
  desktopMedia?.removeEventListener('change', window.__dataAskDesktopListener)
  document.removeEventListener('click', handleAccountOutsideClick)
  window.removeEventListener('focus', refreshSharedHistory)
  document.removeEventListener('visibilitychange', refreshSharedHistory)
  finishComposerPointer()
  finishSidebarPointer()
})
</script>

<template>
  <div class="app-shell" :data-theme="isDesktop ? theme : undefined"
    :style="isDesktop ? { '--da-sidebar-w': sidebarWidth + 'px' } : undefined"
    :class="{ 'consumer-pc': isDesktop, 'consumer-home': showDesktopAskLanding, 'analysis-panel-open': isDesktop && desktopSection==='ask' && analysisPanelOpen }">
    <div v-if="!loggedIn" class="login-screen">
      <svg v-if="isDesktop" class="login-background-waves" viewBox="0 0 1647 216" preserveAspectRatio="none" aria-hidden="true">
        <defs>
          <linearGradient id="login-wave-strong" x1="0" y1="0" x2="1" y2="0">
            <stop offset="0%" stop-color="#3f8df1"/>
            <stop offset="33%" stop-color="#86c4ff"/>
            <stop offset="63%" stop-color="#dceeff"/>
            <stop offset="100%" stop-color="#3890f2"/>
          </linearGradient>
          <linearGradient id="login-wave-soft" x1="0" y1="0" x2="1" y2="0">
            <stop offset="0%" stop-color="#eef7ff"/>
            <stop offset="48%" stop-color="#dcecff"/>
            <stop offset="100%" stop-color="#86c8ff"/>
          </linearGradient>
        </defs>
        <path d="M0 34 C86 46 112 81 204 78 C382 70 456 147 665 173 C914 204 1098 180 1260 134 C1422 88 1519 46 1647 25 L1647 216 L0 216 Z" fill="url(#login-wave-strong)"/>
        <path d="M0 62 C156 100 259 103 390 121 C580 147 747 204 970 182 C1169 162 1293 115 1428 114 C1513 113 1589 116 1647 115 L1647 216 L0 216 Z" fill="url(#login-wave-soft)" fill-opacity=".88"/>
        <path d="M0 154 C219 121 335 140 493 173 C690 215 864 220 1034 194 C1227 165 1428 125 1647 125 L1647 216 L0 216 Z" fill="#f8fbff" fill-opacity=".94"/>
      </svg>
      <aside v-if="isDesktop" class="login-brand">
        <div class="login-brand-top">
          <div class="login-brand-lockup">
            <div class="login-brand-mark" aria-hidden="true"><svg class="brand-glyph" viewBox="0 0 36 36"><rect x="1.5" y="1.5" width="33" height="33" rx="10" fill="#2f6bff"/><path d="M10 8.5h8.2c4.8 0 8 3.5 8 8s-3.2 8-8 8H10z" fill="#fff"/><path d="M13 12h5c2.4 0 4 1.6 4 4.5s-1.6 4.5-4 4.5h-5z" fill="#2f6bff"/><path d="M7 27l5-5 4 3 7-8" fill="none" stroke="#b9d6ff" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg></div>
            <span>DataAsk 数问</span>
          </div>
        </div>
        <div class="login-brand-copy">
          <span class="login-brand-eyebrow">企业经营数据智能问答平台</span>
          <strong>用自然语言，<br/>让 <em>数据触手可问</em></strong>
          <p>基于企业数据与指标口径，快速获取经营洞察，<br/>支持深度追问、可视化分析与图表共享。</p>
          <ul class="login-brand-points">
            <li><span class="login-feature-icon"><van-icon name="chat-o"/></span><b>自然语言问数</b><small>像聊天一样查数据</small></li>
            <li><span class="login-feature-icon"><van-icon name="cluster-o"/></span><b>指标语义理解</b><small>支持业务口径与别名</small></li>
            <li><span class="login-feature-icon"><van-icon name="bar-chart-o"/></span><b>可视化分析</b><small>自动生成分析图表</small></li>
            <li><span class="login-feature-icon"><van-icon name="friends-o"/></span><b>图表共享协作</b><small>一键分享，团队共用</small></li>
          </ul>
        </div>
        <div class="login-brand-visual" aria-hidden="true">
          <svg class="login-brand-hero" viewBox="0 0 560 620" role="img" focusable="false" aria-hidden="true">
            <defs>
              <linearGradient id="hero-blue" x1="0" y1="0" x2="1" y2="1">
                <stop offset="0%" stop-color="#79b7ff"/>
                <stop offset="52%" stop-color="#438ef8"/>
                <stop offset="100%" stop-color="#1f6fe7"/>
              </linearGradient>
              <linearGradient id="hero-blue-soft" x1="0" y1="0" x2="1" y2="1">
                <stop offset="0%" stop-color="#e8f3ff" stop-opacity=".96"/>
                <stop offset="100%" stop-color="#b9d9ff" stop-opacity=".78"/>
              </linearGradient>
              <linearGradient id="hero-glass" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stop-color="#ffffff" stop-opacity=".96"/>
                <stop offset="100%" stop-color="#eef6ff" stop-opacity=".78"/>
              </linearGradient>
              <linearGradient id="hero-base" x1="0" y1="0" x2="1" y2="1">
                <stop offset="0%" stop-color="#fafdff"/>
                <stop offset="38%" stop-color="#dbeaff"/>
                <stop offset="72%" stop-color="#76adff"/>
                <stop offset="100%" stop-color="#2f78e8"/>
              </linearGradient>
              <radialGradient id="hero-glow" cx="50%" cy="50%" r="50%">
                <stop offset="0%" stop-color="#6ca9ff" stop-opacity=".26"/>
                <stop offset="65%" stop-color="#b9d9ff" stop-opacity=".10"/>
                <stop offset="100%" stop-color="#dcecff" stop-opacity="0"/>
              </radialGradient>
              <filter id="hero-shadow" x="-35%" y="-35%" width="170%" height="190%">
                <feDropShadow dx="0" dy="22" stdDeviation="18" flood-color="#4a85d8" flood-opacity=".20"/>
              </filter>
              <filter id="hero-soft-shadow" x="-40%" y="-40%" width="180%" height="180%">
                <feGaussianBlur stdDeviation="10"/>
              </filter>
            </defs>

            <ellipse cx="304" cy="335" rx="236" ry="222" fill="url(#hero-glow)"/>
            <ellipse cx="294" cy="540" rx="206" ry="42" fill="#7baeff" opacity=".16" filter="url(#hero-soft-shadow)"/>

            <g filter="url(#hero-shadow)">
              <ellipse cx="294" cy="515" rx="184" ry="47" fill="url(#hero-base)" opacity=".94"/>
              <ellipse cx="294" cy="493" rx="156" ry="39" fill="#f8fcff" stroke="#d4e5ff" stroke-width="3"/>
              <ellipse cx="294" cy="485" rx="134" ry="31" fill="#d9e9ff" opacity=".95"/>
              <path d="M164 487 C165 525 423 530 430 489 L430 514 C422 552 169 549 164 509 Z" fill="url(#hero-blue)" opacity=".91"/>
              <ellipse cx="294" cy="482" rx="126" ry="26" fill="#ffffff" opacity=".86"/>
            </g>

            <g transform="translate(125 92) rotate(7 170 190)" filter="url(#hero-shadow)">
              <rect x="34" y="27" width="314" height="350" rx="27" fill="url(#hero-glass)" stroke="#c7ddff" stroke-width="3"/>
              <rect x="34" y="27" width="314" height="57" rx="27" fill="url(#hero-blue)"/>
              <path d="M34 62h314v22H34z" fill="url(#hero-blue)"/>
              <circle cx="62" cy="55" r="5.5" fill="#fff" opacity=".9"/>
              <circle cx="80" cy="55" r="5.5" fill="#fff" opacity=".82"/>
              <circle cx="98" cy="55" r="5.5" fill="#fff" opacity=".72"/>

              <rect x="65" y="101" width="248" height="61" rx="16" fill="#fff" opacity=".92"/>
              <circle cx="88" cy="132" r="10" fill="#2f7bea"/>
              <text x="108" y="139" fill="#596d8c" font-size="19" font-weight="600"
                font-family="Arial,'Microsoft YaHei',sans-serif">昨日营业额是多少？</text>

              <g transform="translate(55 188)">
                <rect width="268" height="143" rx="20" fill="#fff" opacity=".89"/>
                <g transform="translate(26 35)">
                  <rect x="0" y="48" width="24" height="43" rx="5" fill="#8fc1ff"/>
                  <rect x="36" y="33" width="24" height="58" rx="5" fill="#70adff"/>
                  <rect x="72" y="20" width="24" height="71" rx="5" fill="#559cff"/>
                  <rect x="108" y="5" width="24" height="86" rx="5" fill="#3888f5"/>
                  <rect x="144" y="29" width="24" height="62" rx="5" fill="#1f75ea"/>
                </g>
                <g transform="translate(197 67)">
                  <circle cx="25" cy="25" r="31" fill="none" stroke="#cfe3ff" stroke-width="14"/>
                  <path d="M25 -6 A31 31 0 0 1 54 35" fill="none" stroke="#1d74e8" stroke-width="14" stroke-linecap="round"/>
                  <path d="M54 35 A31 31 0 0 1 5 49" fill="none" stroke="#6bb4ff" stroke-width="14" stroke-linecap="round"/>
                </g>
              </g>
            </g>

            <g transform="translate(383 82)" filter="url(#hero-shadow)">
              <path d="M0 27c0-15 12-27 27-27h78c15 0 27 12 27 27v44c0 15-12 27-27 27H70l-25 18 4-18H27C12 98 0 86 0 71z" fill="url(#hero-blue)"/>
              <circle cx="39" cy="49" r="7" fill="#fff"/>
              <circle cx="65" cy="49" r="7" fill="#fff"/>
              <circle cx="91" cy="49" r="7" fill="#fff"/>
            </g>

            <g transform="translate(418 430)" filter="url(#hero-shadow)">
              <ellipse cx="44" cy="13" rx="44" ry="13" fill="#f8fdff" stroke="#b9d7ff" stroke-width="2"/>
              <path d="M0 13v24c0 8 20 15 44 15s44-7 44-15V13" fill="url(#hero-blue-soft)" stroke="#b9d7ff" stroke-width="2"/>
              <ellipse cx="44" cy="37" rx="44" ry="13" fill="#eff7ff" stroke="#8ebdff" stroke-width="2"/>
              <path d="M0 37v23c0 8 20 15 44 15s44-7 44-15V37" fill="url(#hero-blue)" opacity=".88"/>
              <ellipse cx="44" cy="60" rx="44" ry="13" fill="#5da0fb" opacity=".96"/>
            </g>

            <path d="M92 335 C24 352 24 418 101 438" fill="none" stroke="#78b2ff" stroke-width="2.5" opacity=".5"/>
            <path d="M450 269 C548 289 551 362 467 385" fill="none" stroke="#78b2ff" stroke-width="2.5" opacity=".5"/>
          </svg>
        </div>
      </aside>
      <main class="login-main">
        <div class="login-logo" aria-hidden="true"><svg class="brand-glyph" viewBox="0 0 36 36"><rect x="1.5" y="1.5" width="33" height="33" rx="10" fill="#2f6bff"/><path d="M10 8.5h8.2c4.8 0 8 3.5 8 8s-3.2 8-8 8H10z" fill="#fff"/><path d="M13 12h5c2.4 0 4 1.6 4 4.5s-1.6 4.5-4 4.5h-5z" fill="#2f6bff"/><path d="M7 27l5-5 4 3 7-8" fill="none" stroke="#b9d6ff" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg></div>
        <h1>{{ isDesktop ? 'DataAsk' : 'DataAsk 数问' }}</h1>
        <p>{{ isDesktop ? '企业经营数据问答平台' : '面向企业管理者的智能经营数据助手' }}</p>
        <div class="login-card">
          <svg v-if="isDesktop" class="login-card-corner" viewBox="0 0 520 190" preserveAspectRatio="none" aria-hidden="true">
            <defs>
              <linearGradient id="login-corner-outer" x1="0" y1="0" x2="1" y2="1">
                <stop offset="0%" stop-color="#dceaff" stop-opacity=".42"/>
                <stop offset="46%" stop-color="#a9ccff" stop-opacity=".72"/>
                <stop offset="100%" stop-color="#6ea9fa" stop-opacity=".96"/>
              </linearGradient>
              <linearGradient id="login-corner-inner" x1="0" y1="0" x2="1" y2="1">
                <stop offset="0%" stop-color="#f3f8ff" stop-opacity=".72"/>
                <stop offset="64%" stop-color="#dbeaff" stop-opacity=".42"/>
                <stop offset="100%" stop-color="#c1dcff" stop-opacity=".18"/>
              </linearGradient>
            </defs>
            <path d="M322 0 H520 V118 C488 84 454 55 415 35 C384 19 354 8 322 0 Z" fill="url(#login-corner-outer)"/>
            <path d="M365 0 H520 V160 C487 111 459 73 425 48 C404 32 384 18 365 0 Z" fill="url(#login-corner-inner)" stroke="rgba(255,255,255,.56)" stroke-width="1.4"/>
          </svg>
          <div v-if="isDesktop" class="login-card-head">
            <h2><span class="login-welcome">欢迎登录</span><span class="login-product">DataAsk 数问</span></h2>
            <p>企业经营数据智能问答平台</p>
          </div>
          <h2 v-else>登录到数问</h2>
          <span v-if="isDesktop" class="login-label">账号</span>
          <van-field v-model="loginForm.username" label="账号" placeholder="请输入账号" left-icon="manager-o" autocomplete="username"/>
          <span v-if="isDesktop" class="login-label login-label-password">密码</span>
          <van-field v-model="loginForm.password" label="密码" placeholder="请输入密码" left-icon="lock-o" :type="showPassword ? 'text' : 'password'" autocomplete="current-password" @keyup.enter="login">
            <template v-if="isDesktop" #right-icon>
              <button type="button" class="login-password-toggle" :aria-label="showPassword ? '隐藏密码' : '显示密码'" @click.stop="showPassword = !showPassword">
                <van-icon :name="showPassword ? 'closed-eye' : 'eye-o'"/>
              </button>
            </template>
          </van-field>
          <van-button type="primary" block :loading="loggingIn" @click="login">登录 <van-icon name="arrow"/></van-button>
        </div>
        <p v-if="isDesktop" class="login-copyright">© 2026 DataAsk 数问 · 企业经营数据问答平台</p>
      </main>
    </div>
    <template v-else>
      <aside class="desktop-sidebar" aria-label="DataAsk 功能导航">
        <div class="sidebar-brand">
          <div class="brand-icon" aria-hidden="true"><svg class="brand-glyph" viewBox="0 0 36 36"><rect x="1.5" y="1.5" width="33" height="33" rx="10" fill="#2f6bff"/><path d="M10 8.5h8.2c4.8 0 8 3.5 8 8s-3.2 8-8 8H10z" fill="#fff"/><path d="M13 12h5c2.4 0 4 1.6 4 4.5s-1.6 4.5-4 4.5h-5z" fill="#2f6bff"/><path d="M7 27l5-5 4 3 7-8" fill="none" stroke="#b9d6ff" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg></div>
          <div class="brand-title"><strong>DataAsk</strong></div>
        </div>
        <nav class="desktop-main-nav" aria-label="分析导航">
          <button v-if="canAsk" type="button" title="新建任务" :class="{active:desktopSection==='ask' && !sessionId}" @click="newConversation"><van-icon name="plus"/><span>新建任务</span></button>
          <button v-if="canCharts" type="button" title="我的图表" :class="{active:desktopSection==='charts'}" @click="switchDesktopSection('charts')"><van-icon name="chart-trending-o"/><span>我的图表</span></button>
        </nav>
        <section v-if="canAsk && sessions.length" class="da-sidebar-history" aria-label="最近提问">
          <div class="da-sidebar-history-head">
            <span>最近</span>
          </div>
          <div v-for="item in sessions.slice(0,5)" :key="item.id" class="da-sidebar-session"
            :class="{active:desktopSection==='ask' && sessionId===item.id}">
            <button type="button" class="da-sidebar-session-open" :title="item.title" @click="openSession(item)">
              <span>{{ item.title }}</span>
            </button>
            <div class="da-recent-menu" @click.stop>
              <button type="button" class="da-session-more" :aria-label="'会话操作：'+item.title"
                :aria-expanded="recentSessionMenuId===item.id" title="更多操作"
                @click.stop="toggleRecentSessionMenu(item.id)">•••</button>
              <div v-if="recentSessionMenuId===item.id" class="da-recent-session-menu" role="menu">
                <button type="button" role="menuitem" class="danger" aria-label="删除会话" @click="deleteRecentSession(item)">
                  <van-icon name="delete-o"/><span>删除会话</span>
                </button>
              </div>
            </div>
          </div>
        </section>
        <div class="tb-sidebar-fill"></div>
        <div v-if="isDesktop" class="da-account-menu" ref="accountMenuRef" @keydown="accountMenuKeydown">
          <button type="button" class="da-account-trigger" :aria-expanded="accountMenuOpen"
            aria-haspopup="menu" aria-label="当前用户菜单" :title="me.displayName || me.username" @click.stop="toggleAccountMenu">
            <span class="da-account-avatar">{{ (me.displayName || me.username || '用').slice(0,1) }}</span>
            <span class="da-account-label"><b>{{ me.displayName || me.username }}</b><small>{{ me.username }}</small></span>
          </button>
          <div v-if="accountMenuOpen" class="da-account-dropdown" role="menu" aria-label="账号选项" @click.stop>
            <div class="da-account-dropdown-heading"><span class="da-account-avatar">{{ (me.displayName || me.username || '用').slice(0,1) }}</span><span><strong>{{ me.displayName || me.username }}</strong><small>{{ me.username }}</small></span></div>
            <button type="button" role="menuitem" aria-label="个性化" @click="openPersonalization"><van-icon name="brush-o" aria-hidden="true"/> 个性化 <span class="da-menu-meta">{{ theme === 'dark' ? '深色' : '浅色' }}</span></button>
            <button type="button" role="menuitem" aria-label="个人资料" @click="openProfile"><van-icon name="user-o" aria-hidden="true"/> 个人资料</button>
            <button type="button" role="menuitem" aria-label="修改密码" @click="openPasswordChange"><van-icon name="lock" aria-hidden="true"/> 修改密码</button>
            <button v-if="canSystemAdmin" type="button" role="menuitem" aria-label="系统管理" @click="openAdmin"><van-icon name="setting-o" aria-hidden="true"/> 系统管理 <span class="da-menu-meta">管理</span></button>
            <div class="da-account-menu-divider" role="separator"></div>
            <button type="button" role="menuitem" aria-label="退出登录" class="da-logout-action" @click="desktopLogout"><van-icon name="sign" aria-hidden="true"/> 退出登录</button>
          </div>
        </div>
        <div v-if="isDesktop" class="da-sidebar-resizer" role="separator"
          aria-label="调整左侧导航宽度" aria-orientation="vertical"
          :aria-valuenow="sidebarWidth" aria-valuemin="176" aria-valuemax="360"
          tabindex="0"
          @pointerdown="beginSidebarDrag"
          @keydown.left.prevent="adjustSidebarWidth(-12)"
          @keydown.right.prevent="adjustSidebarWidth(12)"></div>
      </aside>
      <header class="topbar">
        <div v-if="isDesktop" class="tb-header-breadcrumb">
          <span>DataAsk</span><van-icon name="arrow" size="11"/>
          <template v-if="desktopIsAdminSection && desktopSection!=='admin-overview' && !adminWorkspaceOpen">
            <button type="button" class="tb-breadcrumb-admin" @click="openAdminSection('admin-overview')">系统管理</button>
            <van-icon name="arrow" size="11"/>
          </template>
          <strong>{{ desktopSectionTitle }}</strong>
          <template v-if="desktopSection==='charts' && activeChart">
            <van-icon name="arrow" size="11"/>
            <strong class="da-breadcrumb-detail" :title="activeChart.title">{{ activeChart.title }}</strong>
          </template>
        </div>
        <div v-else class="brand">
          <div class="brand-icon" aria-hidden="true"><svg class="brand-glyph" viewBox="0 0 36 36"><rect x="1.5" y="1.5" width="33" height="33" rx="10" fill="#2f6bff"/><path d="M10 8.5h8.2c4.8 0 8 3.5 8 8s-3.2 8-8 8H10z" fill="#fff"/><path d="M13 12h5c2.4 0 4 1.6 4 4.5s-1.6 4.5-4 4.5h-5z" fill="#2f6bff"/><path d="M7 27l5-5 4 3 7-8" fill="none" stroke="#b9d6ff" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg></div>
          <div class="brand-title"><strong class="mobile-brand">数问</strong>
            <strong class="desktop-current-session">{{ activeSessionTitle }}</strong>
            </div>
        </div>
        <div v-if="isDesktop" class="da-topbar-utilities">
          <div class="da-topbar-operations">
            <template v-if="canSystemAdmin && desktopIsAdminSection && !adminWorkspaceOpen">
              <button v-if="desktopSection==='admin-model'" type="button" class="td-btn"
                :disabled="adminSectionBusy || !modelCenterRef" @click="modelCenterRef?.load()">
                <van-icon name="replay"/> 刷新配置</button>
              <button v-if="desktopSection==='admin-status'" type="button" class="td-btn"
                :disabled="adminSectionBusy" @click="refreshAdminOverview"><van-icon name="replay"/> 刷新状态</button>
              <template v-if="desktopSection==='admin-tables'">
                <button type="button" class="td-btn" :disabled="adminSectionBusy"
                  @click="loadAdminSectionData('admin-tables')"><van-icon name="replay"/> 刷新表列表</button>
                <button type="button" class="td-btn" @click="openAdminSection('admin-source')"><van-icon name="cluster-o"/> 数据源配置</button>
              </template>
              <button v-if="desktopSection!=='admin-overview'" type="button" class="tb-admin-back td-btn td-btn--back"
                @click="openAdminSection('admin-overview')"><van-icon name="arrow-left"/> 返回系统管理</button>
            </template>
            <template v-if="desktopSection==='charts' && activeChart">
              <button type="button" class="da-chart-back td-btn td-btn--back"
                @click="closeChartDetail"><van-icon name="arrow-left"/> 返回我的图表</button>
              <span v-if="activeChartRefreshing" class="da-chart-refreshing"><van-loading size="14px"/> 正在更新数据…</span>
              <span v-else-if="activeChartCached" class="da-chart-cached">上次保存结果</span>
              <span v-else class="da-chart-current">实时更新成功</span>
              <button type="button" class="td-btn" :disabled="activeChartBusy || activeChartRefreshing"
                @click="refreshChartDetail"><van-icon name="replay"/> 刷新数据</button>
              <button v-if="activeChart.ownerUsername===me.username || isAdmin" class="td-btn"
                type="button" @click="openChartShare(activeChart)"><van-icon name="share-o"/> 共享</button>
              <button v-if="activeChart.ownerUsername===me.username || isAdmin" class="td-btn"
                type="button" @click="deleteSavedChart(activeChart)"><van-icon name="delete-o"/> 取消收藏</button>
            </template>
          </div>
          <span class="da-header-date">{{ todayLabel }}</span>
        </div>
        <div v-if="!isDesktop" class="top-actions">
          <button title="历史对话" @click="historyVisible = true"><van-icon name="clock-o" size="21"/></button>
          <button title="新对话" @click="newConversation"><van-icon name="add-o" size="21"/></button>
        </div>
      </header>
      <van-popup v-if="isDesktop && canSystemAdmin" v-model:show="adminWorkspaceOpen"
        position="center" :close-on-click-overlay="false" :close-on-popstate="false"
        class="da-admin-workspace-popup">
        <section class="da-admin-workspace" aria-label="系统管理工作区">
          <aside class="da-admin-workspace-sidebar" aria-label="系统管理导航">
            <div class="da-admin-workspace-brand">
              <div class="brand-icon" aria-hidden="true"><svg class="brand-glyph" viewBox="0 0 36 36"><rect x="1.5" y="1.5" width="33" height="33" rx="10" fill="#2f6bff"/><path d="M10 8.5h8.2c4.8 0 8 3.5 8 8s-3.2 8-8 8H10z" fill="#fff"/><path d="M13 12h5c2.4 0 4 1.6 4 4.5s-1.6 4.5-4 4.5h-5z" fill="#2f6bff"/><path d="M7 27l5-5 4 3 7-8" fill="none" stroke="#b9d6ff" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg></div>
              <div><strong>DataAsk</strong><small>系统管理</small></div>
            </div>
            <nav class="da-admin-workspace-nav">
              <section v-for="group in adminNavigationGroups" :key="group.label">
                <small class="da-admin-workspace-group">{{ group.label }}</small>
                <button v-for="item in group.items" :key="item.section" type="button"
                  :class="{active:desktopSection===item.section}"
                  :aria-current="desktopSection===item.section?'page':undefined"
                  @click="openAdminSection(item.section)">
                  <van-icon :name="item.icon"/><span>{{ item.label }}</span>
                </button>
              </section>
            </nav>
            <button type="button" class="da-admin-workspace-return" @click="closeAdminWorkspace">
              <van-icon name="arrow-left"/><span>返回 DataAsk</span>
            </button>
          </aside>
          <div class="da-admin-workspace-pane">
            <header class="da-admin-workspace-header">
              <div class="da-admin-workspace-heading">
                <span>系统管理</span><van-icon name="arrow" size="11"/>
                <strong>{{ desktopSectionTitle }}</strong>
              </div>
              <div class="da-admin-workspace-actions da-topbar-operations">
                <button v-if="desktopSection==='admin-model'" type="button" class="td-btn"
                  :disabled="adminSectionBusy || !modelCenterRef" @click="modelCenterRef?.load()">
                  <van-icon name="replay"/> 刷新配置</button>
                <button v-if="desktopSection==='admin-status'" type="button" class="td-btn"
                  :disabled="adminSectionBusy" @click="refreshAdminOverview"><van-icon name="replay"/> 刷新状态</button>
                <template v-if="desktopSection==='admin-tables'">
                  <button type="button" class="td-btn" :disabled="adminSectionBusy"
                    @click="loadAdminSectionData('admin-tables')"><van-icon name="replay"/> 刷新表列表</button>
                  <button type="button" class="td-btn" @click="openAdminSection('admin-source')"><van-icon name="cluster-o"/> 数据源配置</button>
                </template>
                <button type="button" class="da-admin-workspace-close" title="关闭系统管理"
                  aria-label="关闭系统管理" @click="closeAdminWorkspace"><van-icon name="cross"/></button>
              </div>
            </header>
            <main class="tb-admin-page da-admin-workspace-editor"
              :class="{'tb-admin-model-full':desktopSection==='admin-model'}">
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
        <AdminAuditLog v-if="desktopSection==='admin-audit'" :api="api"/>
        <div v-else-if="desktopSection==='admin-status'" class="tb-admin-panel-card tb-admin-status-page">
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
        </div>
        <AdminDataSources v-else-if="desktopSection==='admin-source'" :api="api" @changed="refreshAdminStatus"/>
        <div v-else-if="desktopSection==='admin-model'" class="tb-admin-panel-card tb-admin-config-page">
          <ModelConfigCenter ref="modelCenterRef" :api="api" @status-changed="refreshAdminStatus"/>
        </div>
        <div v-else-if="desktopSection==='admin-tables'" class="tb-admin-panel-card">
          <div class="da-list-toolbar td-filter-bar">
            <label class="td-filter-caption" for="ads-table-search">表名</label>
            <input id="ads-table-search" v-model="tableSearch" aria-label="搜索数据表" placeholder="输入表名筛选">
            <button type="button" class="td-btn" :disabled="!tableSearch" @click="tableSearch=''">重置</button>
          </div>
          <div v-for="(table,index) in pagedTables" :key="table.name" class="admin-table">
            <div class="table-head"><span class="da-sequence">{{ (tablePage-1)*tableSize+index+1 }}</span>
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
          <p v-if="!filteredTables.length" class="muted">暂无匹配的数据表</p>
          <ListPagination v-model:page="tablePage" v-model:size="tableSize" :total="filteredTables.length"/>
        </div>
        <AdminMetrics v-else-if="desktopSection==='admin-metrics'" :api="api"/>
        <AdminUsers v-else-if="desktopSection==='admin-users'" :api="api"/>
        <AdminRoles v-else-if="desktopSection==='admin-roles'" :api="api"/>
      </main>
          </div>
        </section>
      </van-popup>
      <main v-if="isDesktop && desktopSection==='charts'" class="tb-page-body tb-content-page tb-charts-page">
        <template v-if="activeChart">
          <div class="da-chart-detail">
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
                  :key="type" type="button" :class="{active:activeChartStyle===type}" :aria-pressed="activeChartStyle===type"
                  @click="activeChartStyle=type"><van-icon :name="{bar:'bar-chart-o',line:'chart-trending-o',pie:'pie-chart-o'}[type]"/>{{ {bar:'柱状图',line:'折线图',pie:'饼图'}[type] }}</button>
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
              <ResultTable v-if="activeChartMode==='table' || (!activeChartCanPlot && !activeChartIsMetric)" class="da-chart-detail-table" :message="activeChartAnswer" :label="columnLabel" :format="pretty"/>
              <QueryExplanation :message="activeChartAnswer"/>
              <section v-if="!activeChartAnswer.unsupported" class="da-analysis-summary" aria-label="AI 分析总结">
                <h3><van-icon name="description"/> AI 分析总结</h3>
                <p>{{ activeChartAnswer.summary || '本次未返回分析总结。' }}</p>
              </section>
            </div>
          </div>
        </template>
        <template v-else>
        <div class="tb-chart-workspace">
          <div class="tb-chart-topline">
            <div class="tb-chart-tabs" role="tablist" aria-label="图表范围">
              <button type="button" role="tab" :aria-selected="chartView==='mine'"
                :class="{active:chartView==='mine'}" @click="chartView='mine'">我收藏的</button>
              <button type="button" role="tab" :aria-selected="chartView==='shared'"
                :class="{active:chartView==='shared'}" @click="chartView='shared'">共享给我的</button>
            </div>
            <button type="button" class="tb-chart-refresh" aria-label="刷新图表列表"
              @click="chartListRefresh++"><van-icon name="replay"/> 刷新</button>
          </div>

          <form class="tb-chart-toolbar" role="search" @submit.prevent="applyChartFilters">
            <label class="tb-chart-search">
              <van-icon name="search" size="16"/>
              <input v-model="chartSearchDraft" placeholder="搜索图表名称或问数问题" aria-label="搜索图表"/>
            </label>
            <select v-model="chartTypeDraft" class="tb-chart-filter-select" aria-label="图表类型筛选">
              <option value="ALL">全部类型</option>
              <option v-for="type in chartTypeOptions" :key="type" :value="type">{{ chartTypeLabel(type) }}</option>
            </select>
            <select v-model="chartSortDraft" class="tb-chart-filter-select" aria-label="更新时间排序">
              <option value="recent">最近更新</option>
              <option value="oldest">最早更新</option>
            </select>
            <button type="submit" class="tb-chart-query">查询</button>
            <button type="button" class="tb-chart-reset" @click="resetChartFilters">重置</button>
          </form>

          <section class="tb-chart-list-shell">
            <RecordList :api="api" endpoint="/mini/charts/page"
              :params="{view:chartView,keyword:chartSearch,type:chartTypeFilter,sort:chartSortOrder}"
              :searchable="false" :refresh="chartListRefresh"
              :label="chartView==='mine'?'我收藏的图表':'共享给我的图表'"
              :columns="[{key:'title',label:'图表名称'},{key:'question',label:'自然语言问题'},{key:'chartType',label:'图表类型'},...(chartView==='shared'?[{key:'ownerUsername',label:'共享来源'}]:[]),{key:'updatedAt',label:'更新时间'}]">
              <template #title="{row}">
                <button type="button" class="tb-chart-name-link" @click="revisitChart(row)">{{ row.title }}</button>
              </template>
              <template #question="{row}"><span class="tb-chart-question">{{ row.question || '—' }}</span></template>
              <template #chartType="{row}"><span class="tb-chart-type">{{ chartTypeLabel(row.chartType) }}</span></template>
              <template #ownerUsername="{row}"><span class="tb-chart-owner">{{ row.ownerUsername || '—' }}</span></template>
              <template #updatedAt="{row}"><span class="tb-chart-date">{{ sessionTimeLabel(row.updatedAt) }}</span></template>
              <template #actions="{row}">
                <button type="button" class="tb-chart-open" @click="revisitChart(row)">查看</button>
              </template>
            </RecordList>
          </section>
        </div>
        </template>
      </main>
      <main v-if="isDesktop && desktopSection==='history'" class="tb-page-body tb-content-page">
        <RecordList :api="api" endpoint="/chat/sessions/page" label="历史会话" :params="{date:historyDate}" :refresh="historyListRefresh" selectable v-model:selected="historySelection" :columns="[{key:'title',label:'问数内容'},{key:'updatedAt',label:'提问时间'}]">
          <template #filters>
            <label class="da-history-date-filter"><span>日期</span><input v-model="historyDate" type="date" :max="currentDateInput()" aria-label="按日期筛选历史会话"></label>
            <button type="button" class="td-btn" @click="historyDate=''" :disabled="!historyDate">全部日期</button>
          </template>
          <template #toolbar>
            <button type="button" class="da-history-batch-delete td-btn td-btn--danger" :disabled="!historySelection.length" @click="batchDeleteSessions"><van-icon name="delete-o"/> 批量删除{{ historySelection.length ? '（'+historySelection.length+'）' : '' }}</button>
          </template>
          <template #title="{row}"><button type="button" @click="openSession(row)">{{ row.title }}</button></template>
          <template #updatedAt="{row}">{{ fullSessionTime(row.updatedAt) }}</template>
          <template #actions="{row}"><button type="button" @click="openSession(row)">查看</button><button type="button" class="da-danger-button" @click="deleteSession(row)">删除</button></template>
        </RecordList>
      </main>
      <main v-if="!isDesktop || desktopSection==='ask'" class="conversation">
        <section v-if="!isDesktop && !messages.length && !busy" class="welcome">
            <div class="welcome-icon"><van-icon name="chart-trending-o" size="27"/></div>
            <h2>今天想了解哪些经营数据？</h2>
            <div class="quick-grid">
              <button v-for="item in quickQuestions" :key="item" :disabled="busy" @click="ask(item)">
                {{ item }} <van-icon name="arrow" />
              </button>
            </div>
        </section>
        <section v-if="showDesktopAskLanding"
          class="da-empty-ask-home" aria-label="新建任务首页">
          <div class="da-empty-ask-inner">
            <section class="da-ask-hero">
              <h1>DataAsk，帮你问数</h1>
            </section>
            <div class="da-ask-categories" aria-label="常用分析类型">
              <button v-for="item in desktopAskCategories" :key="item" type="button"
                @click="chooseAskCategory(item)">{{ item }}</button>
            </div>
            <div class="da-home-question-box">
              <textarea ref="desktopQuestionRef" v-model="question" rows="4" maxlength="800"
                placeholder="例如：昨天各城市的销售额是多少？"
                aria-label="输入经营数据问题" @keydown.enter.exact.prevent="ask()"></textarea>
              <div class="da-home-question-actions">
                <div class="desktop-composer-hints">
                  <span>Enter 发送 · Shift + Enter 换行</span>
                  <span :class="{ 'near-limit': question.length > 700 }">{{ question.length }}/800</span>
                </div>
                <button class="send-btn" type="button" :disabled="!question.trim()"
                  title="发送问题" aria-label="发送问题" @click="ask()">
                  <svg width="18" height="18" viewBox="0 0 24 24" fill="none"
                    stroke="currentColor" stroke-width="2.5" stroke-linecap="round"
                    stroke-linejoin="round" aria-hidden="true"><path d="M12 19V5M5 12l7-7 7 7"/></svg>
                </button>
              </div>
            </div>
            <section class="da-suggestions" aria-label="示例问题">
              <div class="da-suggestions-label"><span>常用问题</span></div>
              <div class="da-suggestion-grid">
                <button v-for="item in desktopQuickQuestions" :key="item.question" :disabled="busy"
                  type="button" @click="ask(item.question)">
                  <span class="da-suggestion-icon"><van-icon :name="item.icon" size="15"/></span>
                  <span class="da-suggestion-copy">
                    <small>{{ item.type }}</small>
                    <strong>{{ item.question }}</strong>
                  </span>
                  <van-icon name="arrow" size="11"/>
                </button>
              </div>
            </section>
          </div>
        </section>
        <section v-for="msg in messages" :key="msg.id" class="turn">
          <div class="user-bubble">{{ msg.question }}</div>
          <div class="answer-card" :class="{ 'answer-needs-clarification': msg.unsupported && msg.followUps?.length }">
            <div v-if="isDesktop" class="da-answer-process-row">
              <button type="button" class="da-analysis-toggle" :class="{active:analysisPanelOpen && analysisMessage?.id===msg.id}"
                :aria-label="'查看分析过程：' + msg.question" @click="openAnalysis(msg)">
                <van-icon :name="analysisPanelOpen && analysisMessage?.id===msg.id ? 'passed' : 'cluster-o'"/>
                <span>分析过程{{ analysisPanelOpen && analysisMessage?.id===msg.id ? '已展开' : '' }}</span>
                <van-icon name="arrow" size="11"/>
              </button>
            </div>
            <div class="answer-heading"><span class="answer-mark">Q</span><span class="answer-agent-name">小Q</span><span class="answer-agent-divider">·</span><b>{{ msg.title }}</b>
              <span v-if="msg.timeLabel && !(isDesktop && answerScope(msg).length)" class="answer-time">{{ msg.timeLabel }}</span>
            </div>
            <div v-if="isDesktop && answerScope(msg).length" class="da-answer-scope" aria-label="查询口径">
              <span v-for="chip in answerScope(msg)" :key="chip.key" class="da-chip"><em>{{ chip.label }}</em>{{ chip.value }}</span>
            </div>
            <p v-if="!isDesktop || msg.unsupported"
              class="answer-summary da-answer-insight" :class="{ 'unsupported-text': msg.unsupported }">{{ answerSummary(msg) }}</p>
            <template v-if="msg.rows?.length">
              <div v-if="isDesktop" class="da-answer-result desktop-result-header">
                <div class="desktop-result-heading"><van-icon name="chart-trending-o"/> 查询结果 <span>共 {{ msg.rows.length }} 行</span></div>
                <div class="da-result-tools">
                  <div class="desktop-result-switch da-segmented" role="group" :aria-label="'图表视图切换：'+msg.title">
                    <button v-if="isSingleValue(msg)" type="button" :class="{active:resultView(msg)==='summary'}" :aria-pressed="resultView(msg)==='summary'" @click="setResultView(msg,'summary')">指标</button>
                    <button v-if="['bar','line','pie'].includes(msg.chartType) && msg.rows.length > 1" type="button"
                      :class="{active:resultView(msg)==='chart'}" :aria-pressed="resultView(msg)==='chart'" @click="setResultView(msg,'chart')">图表</button>
                    <button v-if="!isSingleValue(msg)" type="button" :class="{active:resultView(msg)==='table'}" :aria-pressed="resultView(msg)==='table'" @click="setResultView(msg,'table')">表格</button>
                  </div>
                  <div v-if="['bar','line','pie'].includes(msg.chartType) && msg.rows.length > 1 && resultView(msg)==='chart'" class="qbi-chart-type da-segmented" role="group" aria-label="图表类型">
                    <button type="button" :class="{active:chartStyle(msg)==='bar'}" :aria-pressed="chartStyle(msg)==='bar'" @click="setChartStyle(msg,'bar')"><van-icon name="bar-chart-o"/>柱状图</button>
                    <button type="button" :class="{active:chartStyle(msg)==='line'}" :aria-pressed="chartStyle(msg)==='line'" @click="setChartStyle(msg,'line')"><van-icon name="chart-trending-o"/>折线图</button>
                    <button v-if="pieSupported(msg)" type="button" :class="{active:chartStyle(msg)==='pie'}" :aria-pressed="chartStyle(msg)==='pie'" @click="setChartStyle(msg,'pie')"><van-icon name="pie-chart-o"/>饼图</button>
                  </div>
                </div>
              </div>
              <div v-if="isSingleValue(msg) && (!isDesktop || resultView(msg)==='summary')" class="metric-data">
                <div v-for="col in msg.columns" :key="col">
                  <small :title="col">{{ columnLabel(msg, col, msg.columns.indexOf(col)) }}</small><strong>{{ pretty(msg.rows[0][col]) }}</strong>
                </div>
              </div>
              <AnswerChart v-if="['bar','line','pie'].includes(msg.chartType) && msg.rows.length > 1 && (!isDesktop || resultView(msg)==='chart')"
                :message="msg" :desktop-expanded="isDesktop" :chart-type-override="isDesktop ? chartStyle(msg) : ''"/>
              <ResultTable v-if="isDesktop && resultView(msg)==='table' && !isSingleValue(msg)" :message="msg" :label="columnLabel" :format="pretty"/>
              <div v-if="!isDesktop && !isSingleValue(msg)" class="result-table-scroll" role="region" tabindex="0" aria-label="数据结果表格，可独立上下滚动">
                <table class="result-table">
                  <thead><tr><th v-for="(col, idx) in msg.columns" :key="col" :title="col">{{ columnLabel(msg, col, idx) }}</th></tr></thead>
                  <tbody><tr v-for="(row, idx) in msg.rows" :key="idx">
                    <td v-for="col in msg.columns" :key="col">{{ pretty(row[col]) }}</td>
                  </tr></tbody>
                </table>
              </div>
              <p v-if="(!isDesktop && !isSingleValue(msg)) || (isDesktop && resultView(msg)==='table' && !isSingleValue(msg))" class="table-foot">{{ msg.rows.length }} 行 · 最多 200 行</p>
            </template>
            <section v-if="isDesktop && !msg.unsupported" class="da-analysis-summary" aria-label="AI 分析总结">
              <h3><van-icon name="description"/> AI 分析总结</h3>
              <p>{{ isRedundantSummary(msg) ? '未记录分析总结' : msg.summary || '本次未返回数据' }}</p>
            </section>
            <div v-if="msg.unsupported && msg.followUps?.length" class="metric-clarification" aria-label="请选择查询指标">
              <div class="metric-clarification-heading">
                <van-icon name="question-o" size="18"/>
                <strong>你想查看哪项指标？</strong>
              </div>
              <div class="metric-clarification-options">
                <button v-for="item in msg.followUps" :key="item" type="button" :disabled="busy"
                  @click="ask(item, msg.id)">
                  {{ item }} <van-icon name="arrow" size="12"/>
                </button>
              </div>
            </div>
            <div v-if="isDesktop" class="desktop-answer-tools">
              <button type="button" @click="copyAnswer(msg)"><van-icon name="description"/> 复制结果</button>
              <button v-if="!msg.unsupported && msg.rows?.length" type="button" @click="openFullScreen(msg)"><van-icon name="expand-o"/> 全屏查看</button>
              <button v-if="!msg.unsupported && msg.rows?.length" type="button" @click="exportAnswer(msg)"><van-icon name="down"/> 导出数据</button>
              <button v-if="!msg.unsupported && msg.rows?.length" type="button" @click="saveChart(msg)"><van-icon name="star-o"/> 收藏</button>
              <button v-if="!msg.unsupported && msg.rows?.length" type="button" @click="shareChart(msg)"><van-icon name="share-o"/> 分享</button>
              <button v-if="!msg.unsupported" type="button" :disabled="busy" @click="startFollowUp(msg)"><van-icon name="comment-o"/> 追加提问</button>
              <button type="button" :class="{active:answerFeedback[msg.id]==='up'}" title="结果有帮助" aria-label="结果有帮助" @click="voteAnswer(msg,'up')"><van-icon :name="answerFeedback[msg.id]==='up'?'good-job':'good-job-o'"/></button>
              <button type="button" class="da-dislike-button" :class="{active:answerFeedback[msg.id]==='down'}" title="结果需改进" aria-label="结果需改进" @click="voteAnswer(msg,'down')"><van-icon :name="answerFeedback[msg.id]==='down'?'good-job':'good-job-o'"/></button>
              <button type="button" title="复制 Trace ID" aria-label="复制 Trace ID" @click="copyTraceId(msg)"><van-icon name="link-o"/></button>
              <button type="button" :disabled="busy" @click="ask(msg.question,null)"><van-icon name="replay"/> 重新查询</button>
            </div>
            <div v-if="!msg.unsupported && msg.followUps?.length" class="followups">
              <span v-if="isDesktop" class="da-followups-label">继续追问</span>
              <button v-for="item in msg.followUps" :key="item" :disabled="busy"
                @click="ask(item, msg.id)">{{ item }} <van-icon name="arrow" size="10"/></button>
            </div>
          </div>
        </section>
        <template v-if="busy && isDesktop">
          <div class="turn desktop-pending-turn"><div class="user-bubble">{{ pendingQuestion }}</div>
            <div class="da-pending-execution"><span><van-loading size="15px"/> 小Q 正在分析问题，过程已在右侧展开</span><button type="button" @click="stopAsk" class="da-pending-stop"><van-icon name="stop-circle-o"/> 停止查询</button></div>
          </div>
        </template>
        <div v-else-if="busy" class="loading-bubble"><van-loading size="16px"/> 小Q 正在理解问题并查询 ADS 数据...</div>
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
      <AnalysisPanel v-if="isDesktop && desktopSection==='ask' && analysisPanelOpen"
        :message="analysisMessage" :pending="busy && !analysisMessage" :question="analysisQuestion" @close="closeAnalysis"/>
      <footer v-if="!isDesktop || (desktopSection==='ask' && !showDesktopAskLanding)" class="composer"
        :style="isDesktop ? { '--da-composer-height': composerHeight + 'px' } : {}">
        <div v-if="isDesktop" class="da-composer-resize-edge" role="separator"
          aria-label="调整对话输入框高度" aria-orientation="horizontal"
          :aria-valuenow="composerHeight" aria-valuemin="94" aria-valuemax="430"
          tabindex="0"
          @pointerdown="beginComposerDrag"
          @keydown.up.prevent="adjustComposerHeight(24)"
          @keydown.down.prevent="adjustComposerHeight(-24)"></div>
        <div class="composer-row da-composer">
          <div v-if="isDesktop && followUpSource" class="da-followup-context">
            <span><van-icon name="comment-o"/> 正在追加提问：{{ followUpSource.title || followUpSource.question }}</span>
            <button type="button" aria-label="退出追加提问" title="退出追加提问" @click="cancelFollowUp"><van-icon name="cross"/></button>
          </div>
          <textarea ref="desktopQuestionRef" v-model="question" :rows="isDesktop ? (messages.length ? 1 : 3) : 1" maxlength="800"
            :placeholder="isDesktop ? (followUpSource ? '继续追问当前结果，例如：其中订单量最高的门店是哪家？' : '例如：昨天各城市的销售额是多少？') : '问问经营数据，例如：昨日营业额'"
            aria-label="输入经营数据问题" @keydown.enter.exact.prevent="ask()"></textarea>
          <div v-if="isDesktop" class="da-composer-bar">
            <div class="da-composer-actions">
              <div class="desktop-composer-hints">
                <span>Enter 发送 · Shift + Enter 换行</span>
                <span :class="{ 'near-limit': question.length > 700 }">{{ question.length }}/800</span>
              </div>
              <button v-if="busy" class="send-btn stop-btn" type="button"
                title="停止查询" aria-label="停止查询" @click="stopAsk"><span class="stop-square"></span></button>
              <button v-else class="send-btn" type="button" :disabled="busy || !question.trim()"
                title="发送问题" aria-label="发送问题" @click="ask()">
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none"
                  stroke="currentColor" stroke-width="2.5" stroke-linecap="round"
                  stroke-linejoin="round" aria-hidden="true"><path d="M12 19V5M5 12l7-7 7 7"/></svg>
              </button>
            </div>
          </div>
          <button v-else class="send-btn" type="button" :disabled="busy || !question.trim()"
            aria-label="发送问题" @click="ask()">
            <van-icon name="arrow-up" size="20"/>
          </button>
        </div>
      </footer>
      <van-popup v-if="isDesktop" v-model:show="resultFullscreenOpen" position="center"
        :close-on-click-overlay="false" class="da-result-fullscreen" @keydown.esc="closeFullScreen">
        <section v-if="fullScreenMessage" class="da-result-fullscreen-dialog" aria-label="全屏查看问数结果">
          <header>
            <div><small>问数结果</small><h2>{{ fullScreenMessage.title }}</h2></div>
            <div class="da-result-fullscreen-actions">
              <div class="da-segmented" role="group" aria-label="全屏结果视图">
                <button v-if="isSingleValue(fullScreenMessage)" type="button" :class="{active:fullScreenView==='summary'}" @click="fullScreenView='summary'">指标</button>
                <button v-if="['bar','line','pie'].includes(fullScreenMessage.chartType) && fullScreenMessage.rows?.length>1" type="button" :class="{active:fullScreenView==='chart'}" @click="fullScreenView='chart'">图表</button>
                <button v-if="!isSingleValue(fullScreenMessage)" type="button" :class="{active:fullScreenView==='table'}" @click="fullScreenView='table'">表格</button>
              </div>
              <button type="button" class="da-result-fullscreen-export" @click="exportAnswer(fullScreenMessage)"><van-icon name="down"/> 导出</button>
              <button type="button" class="da-result-fullscreen-close" aria-label="关闭全屏结果" title="关闭" @click="closeFullScreen"><van-icon name="cross"/></button>
            </div>
          </header>
          <div class="da-result-fullscreen-body">
            <div v-if="fullScreenView==='summary'" class="metric-data">
              <div v-for="col in fullScreenMessage.columns" :key="col"><small>{{ columnLabel(fullScreenMessage,col,fullScreenMessage.columns.indexOf(col)) }}</small><strong>{{ pretty(fullScreenMessage.rows[0][col]) }}</strong></div>
            </div>
            <AnswerChart v-else-if="fullScreenView==='chart'" :message="fullScreenMessage" :desktop-expanded="true" :chart-type-override="chartStyle(fullScreenMessage)"/>
            <ResultTable v-else-if="fullScreenView==='table' && !isSingleValue(fullScreenMessage)" :message="fullScreenMessage" :label="columnLabel" :format="pretty"/>
          </div>
          <footer><span>{{ fullScreenMessage.question }}</span><small>{{ fullScreenMessage.rows?.length || 0 }} 行 · 最多 200 行</small></footer>
        </section>
      </van-popup>
      <van-popup v-if="isDesktop" v-model:show="personalizationOpen" position="center" round closeable
        class="da-preferences-popup" :style="{width:'min(480px,calc(100vw - 36px))'}" aria-labelledby="da-personalization-title"
        @keydown.esc="personalizationOpen=false">
        <section class="da-preferences-dialog">
          <h2 id="da-personalization-title">个性化</h2>
          <div class="da-preference-heading"><h3>外观</h3><span>选择主题</span></div>
          <div class="da-theme-options" role="group" aria-label="主题选择">
            <button type="button" :aria-pressed="theme==='light'" :class="{selected:theme==='light'}" @click="chooseTheme('light')"><van-icon name="bulb-o"/><strong>浅色</strong><van-icon v-if="theme==='light'" name="checked" class="da-theme-check"/></button>
            <button type="button" :aria-pressed="theme==='dark'" :class="{selected:theme==='dark'}" @click="chooseTheme('dark')"><van-icon name="circle"/><strong>深色</strong><van-icon v-if="theme==='dark'" name="checked" class="da-theme-check"/></button>
          </div>
        </section>
      </van-popup>
      <van-popup v-if="isDesktop" v-model:show="profileOpen" position="center" round
        class="da-preferences-popup" :closeable="!profileSaving" :close-on-click-overlay="!profileSaving"
        :style="{width:'min(480px,calc(100vw - 36px))'}" aria-labelledby="da-profile-title" @keydown.esc="closeProfile" @closed="closeProfile">
        <section class="da-preferences-dialog da-profile-dialog">
          <h2 id="da-profile-title">个人资料</h2>
          <van-loading v-if="profileLoading" size="20px">正在读取资料…</van-loading>
          <div v-else-if="profileError" class="da-profile-error" role="alert"><p>{{ profileError }}</p><button type="button" @click="openProfile">重新加载</button></div>
          <template v-else>
            <van-field :model-value="me.username" label="登录账号" readonly/>
            <van-field v-model="profileForm.displayName" label="显示名称" placeholder="请输入显示名称" maxlength="100" :disabled="profileSaving"/>
            <van-field :model-value="me.roleName || me.role || '—'" label="账号角色" readonly/>
            <section class="da-profile-permissions" aria-label="城市数据权限">
              <div class="da-profile-permissions-title">
                <strong>{{ cityPermissionScope==='CITY' ? '数据权限 · 管理城市（'+cityPermissions.length+'）' : '数据权限 · 全部城市' }}</strong>
                <button type="button" :disabled="cityPermissionLoading" @click="loadCityPermissions">
                  {{ cityPermissionLoading ? '读取中…' : '刷新' }}
                </button>
              </div>
              <p v-if="cityPermissionError" role="alert">读取失败：{{ cityPermissionError }}</p>
              <p v-else-if="cityPermissionScope==='CITY' && !cityPermissions.length">当前账号暂无授权城市</p>
              <div v-else class="da-profile-city-list">
                <span v-for="city in cityPermissions" :key="city">{{ city }}</span>
              </div>
            </section>
            <van-field v-if="profileForm.createdAt" :model-value="fullSessionTime(profileForm.createdAt)" label="加入时间" readonly/>
            <div class="da-password-actions"><van-button :disabled="profileSaving" @click="closeProfile">取消</van-button><van-button type="primary" :loading="profileSaving" @click="saveProfile">保存资料</van-button></div>
          </template>
        </section>
      </van-popup>
      <van-popup v-if="isDesktop" v-model:show="changePasswordOpen"
        position="center" round :closeable="!changePasswordBusy" :close-on-click-overlay="!changePasswordBusy"
        :style="{width:'min(430px,calc(100vw - 36px))'}"
        @keydown.esc="closePasswordChange" @closed="closePasswordChange">
        <div class="da-password-dialog">
          <h2>修改密码</h2>
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
      <van-popup v-if="canSystemAdmin && !isDesktop" v-model:show="adminVisible" :position="isDesktop ? 'center' : 'bottom'" round
        :style="{ width: isDesktop ? 'min(920px, 92vw)' : '100%', height: isDesktop ? 'min(780px, 88vh)' : '88%' }">
        <div class="admin-panel">
          <div class="drawer-header"><b>系统管理</b><van-icon name="cross" @click="adminVisible=false"/></div>
          <button type="button" class="status-row status-action" @click="showConfig('starrocks')">
            <span>StarRocks ADS</span>
            <span class="status-value"><strong>{{ status.starrocksConfigured ? '已配置' : '未配置' }}</strong><van-icon name="arrow"/></span>
          </button>
          <button type="button" class="status-row status-action" @click="showConfig('models')">
            <span>模型配置</span>
            <span class="status-value">
              <strong>对话 {{ status.aiConfigured ? '已配置' : '待配置' }} · Embedding {{ status.embeddingConfigured ? '已配置' : '未启用' }}</strong>
              <van-icon name="arrow"/>
            </span>
          </button>
          <div class="admin-build-status">版本 {{ buildSha }}</div>
          <h3>开放给 AI 的 ADS 表</h3>
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
        </div>
      </van-popup>
      <van-popup v-if="canSystemAdmin && !isDesktop" v-model:show="configVisible"
        position="bottom" round :style="{width:'100%',height:'80%'}">
        <div class="config-panel">
          <div class="drawer-header"><b>StarRocks ADS 数据源</b><van-icon name="cross" @click="configVisible=false"/></div>
          <van-field v-model="srForm.jdbcUrl" label="JDBC 地址" label-width="85px" placeholder="jdbc:mysql://192.168.1.10:9030/ads"/>
          <van-field v-model="srForm.schema" label="ADS 数据库" label-width="85px" placeholder="ads"/>
          <van-field v-model="srForm.username" label="用户名" label-width="85px" placeholder="只读用户名"/>
          <van-field v-model="srForm.password" label="密码" label-width="85px" type="password"
            :placeholder="settingsData.starrocks?.passwordSet ? '已配置，留空不修改' : '请输入密码'"/>
          <p class="config-note">数据库密码由后端保存，留空保留现有密码。</p>
          <p v-if="configTestMessage" class="config-feedback" role="status">{{ configTestMessage }}</p>
          <div class="config-actions">
            <van-button block plain type="primary" :loading="configTestBusy"
              :disabled="configBusy" @click="testConfig">测试连接</van-button>
            <van-button block type="primary" :loading="configBusy"
              :disabled="configTestBusy" @click="saveConfig">保存配置</van-button>
          </div>
        </div>
      </van-popup>
      <van-popup v-if="canSystemAdmin && !isDesktop" v-model:show="modelConfigVisible"
        position="bottom" round :style="{width:'100%',height:'88%'}">
        <div class="config-panel da-mobile-model-center">
          <div class="drawer-header"><b>模型配置</b><div class="da-model-mobile-actions">
              <button type="button" @click="modelCenterRef?.load()"><van-icon name="replay"/> 刷新</button>
              <button type="button" aria-label="关闭模型配置" @click="modelConfigVisible=false"><van-icon name="cross"/></button>
            </div></div>
          <ModelConfigCenter v-if="modelConfigVisible" ref="modelCenterRef" :api="api" @status-changed="refreshAdminStatus"/>
        </div>
      </van-popup>
    </template>
  </div>
</template>

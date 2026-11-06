<script setup>
import { computed, onMounted, ref, nextTick } from 'vue'
import axios from 'axios'
import { showToast, showFailToast, showSuccessToast } from 'vant'
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
const messages = ref([])
const sessionId = ref('')
const historyVisible = ref(false)
const sessions = ref([])
const adminVisible = ref(false)
const status = ref({})
const allowedTables = ref([])
const expandedTable = ref('')
const users = ref([])
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
function newConversation() { sessionId.value = ''; messages.value = []; historyVisible.value = false }
async function ask(text = question.value, sourceMessageId = null) {
  text = (text || '').trim()
  if (!text || busy.value) return
  if (text.length > 800) return showToast('提问不能超过800字')
  busy.value = true
  question.value = ''
  try {
    const { data } = await api.post('/chat/ask', { question: text, sessionId: sessionId.value || null, sourceMessageId })
    sessionId.value = data.sessionId
    messages.value.push(data)
    scrollEnd()
    await loadSessions()
  } catch (e) {
    question.value = text
    showFailToast(errorText(e))
  } finally { busy.value = false }
}
async function openAdmin() {
  adminVisible.value = true
  try {
    const [s, t, u] = await Promise.all([
      api.get('/admin/status'), api.get('/admin/tables'), api.get('/admin/users')
    ])
    status.value = s.data; allowedTables.value = t.data; users.value = u.data
  } catch (e) { showFailToast(errorText(e)) }
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
onMounted(() => { if (loggedIn.value) loadSessions() })
</script>

<template>
  <div class="app-shell">
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
      <header class="topbar">
        <div class="brand">
          <div class="brand-icon">问</div>
          <div class="brand-title"><strong>数问</strong><small>经营数据助手</small></div>
        </div>
        <div class="top-actions">
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
            <div class="answer-heading"><span class="answer-mark">数</span><b>{{ msg.title }}</b></div>
            <p class="answer-summary">{{ msg.summary }}</p>
            <template v-if="msg.rows?.length">
              <div v-if="msg.chartType === 'metric' && msg.rows.length === 1" class="metric-data">
                <div v-for="col in msg.columns" :key="col">
                  <small>{{ col }}</small><strong>{{ pretty(msg.rows[0][col]) }}</strong>
                </div>
              </div>
              <AnswerChart v-if="['bar','line','pie'].includes(msg.chartType) && msg.rows.length > 1" :message="msg"/>
              <div class="result-table-scroll">
                <table class="result-table">
                  <thead><tr><th v-for="col in msg.columns" :key="col">{{ col }}</th></tr></thead>
                  <tbody><tr v-for="(row, idx) in msg.rows.slice(0, 30)" :key="idx">
                    <td v-for="col in msg.columns" :key="col">{{ pretty(row[col]) }}</td>
                  </tr></tbody>
                </table>
              </div>
              <p v-if="msg.rows.length > 30" class="table-foot">当前显示前30行；服务端单次最多返回200行。</p>
            </template>
            <div v-if="msg.followUps?.length" class="followups">
              <button v-for="item in msg.followUps" :key="item" :disabled="busy"
                @click="ask(item, msg.id)">{{ item }} <van-icon name="arrow" size="10"/></button>
            </div>
          </div>
        </section>
        <div v-if="busy" class="loading-bubble"><van-loading size="16px"/> 正在理解问题并查询 ADS 数据...</div>
        <div ref="footerRef" class="end-marker"></div>
      </main>
      <footer class="composer">
        <div class="composer-row">
          <textarea v-model="question" rows="1" maxlength="800" placeholder="问问经营数据，例如：昨日营业额"
            @keydown.enter.exact.prevent="ask()"></textarea>
          <button class="send-btn" :disabled="busy || !question.trim()" @click="ask()">
            <van-icon name="arrow-up" size="20"/>
          </button>
        </div>
        <small>DataAsk · 仅供内部经营分析 · 以实际业务口径为准</small>
      </footer>
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
      <van-popup v-model:show="adminVisible" position="bottom" round :style="{ height: '88%' }">
        <div class="admin-panel">
          <div class="drawer-header"><b>系统管理</b><van-icon name="cross" @click="adminVisible=false"/></div>
          <div class="status-row"><span>StarRocks ADS</span><strong>{{ status.starrocksConfigured ? '已配置' : '未配置' }}</strong></div>
          <div class="status-row"><span>AI 模型</span><strong>{{ status.aiConfigured ? (status.model || '已配置') : '未配置' }}</strong></div>
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
          <p class="muted">模型地址、密钥和 StarRocks 连接由服务端配置文件管理，不在浏览器存储。</p>
        </div>
      </van-popup>
    </template>
  </div>
</template>

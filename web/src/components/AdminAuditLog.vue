<script setup>
import { computed, onMounted, onBeforeUnmount, ref } from 'vue'
import { showConfirmDialog, showSuccessToast } from 'vant'
const props = defineProps({api:{type:Object,required:true}})
const tab = ref('logs'), rows = ref([]), total = ref(0), page = ref(1), size = ref(10)
const loading = ref(false), deleting = ref(false), error = ref(''), selected = ref([])
const today = ref(''), usernames = ref([]), applied = ref({}), details = ref({}), detailBusy = ref({}), detailErrors = ref({})
const initial = () => ({dateFrom:today.value,dateTo:today.value,allDates:false,username:'',keyword:'',requestId:'',module:'',action:'',status:'',source:''})
const filters = ref(initial())
// 时间范围参考阿里云日志页面：预设一键切换并立即查询，自定义时展开起止日期。
const range = ref('today')
const ranges = [['today','今天'],['yesterday','昨天'],['3d','近 3 天'],['7d','近 7 天'],['30d','近 30 天'],['custom','自定义区间'],['all','全部时间']]
const presetSpans = {today:[0,0],yesterday:[-1,-1],'3d':[-2,0],'7d':[-6,0],'30d':[-29,0]}
function shiftDay(dateString, delta) {
  const [y, m, d] = dateString.split('-').map(Number)
  const date = new Date(y, m - 1, d + delta)
  return [date.getFullYear(), String(date.getMonth() + 1).padStart(2, '0'), String(date.getDate()).padStart(2, '0')].join('-')
}
function applyRange(value) {
  range.value = value
  if (value === 'all') { filters.value.allDates = true; filters.value.dateFrom = ''; filters.value.dateTo = '' }
  else {
    filters.value.allDates = false
    if (value === 'custom') {
      if (!filters.value.dateFrom) filters.value.dateFrom = today.value
      if (!filters.value.dateTo) filters.value.dateTo = today.value
      return
    }
    const [from, to] = presetSpans[value]
    filters.value.dateFrom = shiftDay(today.value, from)
    filters.value.dateTo = shiftDay(today.value, to)
  }
  search()
}
const pages = computed(() => Math.max(1,Math.ceil(total.value / size.value)))
const allSelected = computed(() => rows.value.length > 0 && selected.value.length === rows.value.length)
const statuses = computed(() => tab.value === 'queries' ? ['SUCCESS','FAILED','CANCELLED','RUNNING'] : ['SUCCESS','FAILED','DENIED','RUNNING'])
const statusText = s => ({SUCCESS:'成功',FAILED:'失败',DENIED:'拒绝访问',CANCELLED:'已停止',RUNNING:'进行中 / 待核实'})[s] || s
const moduleText = s => ({AUTH:'登录与账号',CHAT:'智能问数',CHART:'图表',ADMIN:'系统管理',AUDIT:'审计管理',CLIENT:'界面操作',SYSTEM:'系统'})[s] || s
const errorText = e => e?.response?.data?.message || e.message || '加载失败，请重试'
let alive = true, sequence = 0
onBeforeUnmount(() => { alive = false; sequence++ })
onMounted(initialize)
async function initialize() {
  try {
    const {data} = await props.api.get('/admin/audit/options')
    if (!alive) return
    today.value = data.today
    usernames.value = Array.isArray(data.usernames) ? data.usernames : []
    range.value = 'today'
    filters.value = initial()
    await search()
  } catch(e) { if (alive) error.value = errorText(e) }
}
async function load() {
  const request = ++sequence
  loading.value = true; error.value = ''; selected.value = []; details.value = {}; detailErrors.value = {}
  try {
    const {data} = await props.api.get('/admin/audit/'+tab.value,{params:{...applied.value,page:page.value,pageSize:size.value}})
    if (!alive || request !== sequence) return
    rows.value = data.items || []; total.value = data.total || 0
    if (!rows.value.length && page.value > 1) { page.value = Math.max(1,Math.ceil(total.value/size.value)); await load() }
  } catch(e) { if (alive && request === sequence) { rows.value = []; total.value = 0; error.value = errorText(e) } }
  finally { if (alive && request === sequence) loading.value = false }
}
async function search() {
  if (deleting.value) return
  if (!filters.value.allDates && (!filters.value.dateFrom || !filters.value.dateTo)) { error.value = '请选择开始和结束日期'; return }
  if (!filters.value.allDates && filters.value.dateTo < filters.value.dateFrom) { error.value = '结束日期不能早于开始日期'; return }
  applied.value = {...filters.value}
  if (applied.value.allDates) { applied.value.dateFrom = ''; applied.value.dateTo = '' }
  page.value = 1; await load()
}
async function reset() { range.value = 'today'; filters.value = initial(); await search() }
async function changeTab(value) {
  if (deleting.value || tab.value === value) return
  tab.value = value; range.value = 'today'; filters.value = initial(); applied.value = {...filters.value}; page.value = 1; await load()
}
async function move(next) { if (loading.value || deleting.value) return; page.value = next; await load() }
function selectAll(event) { selected.value = event.target.checked ? rows.value.map(r=>r.id) : [] }
async function removeSelected() {
  const ids = [...selected.value], kind = tab.value
  if (!ids.length || loading.value || deleting.value) return
  try {
    await showConfirmDialog({title:'删除审计记录',message:`确定永久删除选中的 ${ids.length} 条${kind==='queries'?' SQL 查询':'操作审计'}记录？此操作不可撤销，删除行为本身将保留审计记录。`,confirmButtonText:'确认删除',confirmButtonColor:'#c33'})
  } catch { return }
  deleting.value = true
  try {
    const {data} = await props.api.post(`/admin/audit/${kind}/batch-delete`,{ids})
    if (!alive) return
    showSuccessToast(`已删除 ${data.deleted} 条记录`)
    await load()
  } catch(e) { if (alive) error.value = errorText(e) }
  finally { if (alive) deleting.value = false }
}
async function toggleSql(row) {
  const request = sequence
  if (details.value[row.id]) { delete details.value[row.id]; return }
  detailBusy.value[row.id] = true; delete detailErrors.value[row.id]
  try {
    const {data} = await props.api.get('/admin/audit/queries/'+encodeURIComponent(row.id))
    if (alive && request === sequence) details.value[row.id] = data
  } catch(e) { if (alive && request === sequence) detailErrors.value[row.id] = errorText(e) }
  finally { if (alive) detailBusy.value[row.id] = false }
}
function time(value) {
  if (!value) return '—'
  return new Intl.DateTimeFormat('zh-CN',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hour12:false}).format(new Date(value))
}
</script>

<template>
  <section class="audit-panel" aria-label="管理员审计日志">
    <div class="audit-tabs" role="tablist" aria-label="审计类型">
      <button v-for="item in [['logs','操作审计'],['queries','SQL 查询记录']]" :key="item[0]" role="tab" :aria-selected="tab===item[0]" :disabled="deleting" @click="changeTab(item[0])" @keydown.right.prevent="changeTab(tab==='logs'?'queries':'logs')" @keydown.left.prevent="changeTab(tab==='logs'?'queries':'logs')">{{ item[1] }}</button>
      <span>北京时间</span>
    </div>
    <form class="audit-querybar" role="search" @submit.prevent="search">
      <input class="audit-keyword-input" v-model.trim="filters.keyword" :placeholder="tab==='queries'?'搜索问题或 SQL 内容':'搜索操作、对象或详情'" maxlength="200" aria-label="关键词">
      <label class="audit-range-label">时间范围
        <select v-model="range" aria-label="时间范围" @change="applyRange(range)">
          <option v-for="item in ranges" :key="item[0]" :value="item[0]">{{ item[1] }}</option>
        </select>
      </label>
      <template v-if="range==='custom'">
        <input v-model="filters.dateFrom" type="date" aria-label="开始日期" @change="search">
        <span class="audit-range-sep">至</span>
        <input v-model="filters.dateTo" type="date" aria-label="结束日期" @change="search">
      </template>
      <button class="primary td-btn td-btn--primary" type="submit" :disabled="loading || deleting"><van-icon name="search"/> 查询</button>
      <button type="button" class="td-btn" :disabled="loading || deleting" @click="reset">重置</button>
    </form>
    <div class="audit-conditions">
      <input v-model.trim="filters.username" list="audit-usernames" placeholder="操作账号" maxlength="64" aria-label="操作账号">
      <datalist id="audit-usernames"><option v-for="name in usernames" :key="name" :value="name"/></datalist>
      <select v-model="filters.status" aria-label="执行状态"><option value="">全部状态</option><option v-for="s in statuses" :key="s" :value="s">{{ statusText(s) }}</option></select>
      <select v-if="tab==='logs'" v-model="filters.module" aria-label="所属模块"><option value="">全部模块</option><option v-for="m in ['AUTH','CHAT','CHART','ADMIN','AUDIT','CLIENT','SYSTEM']" :key="m" :value="m">{{ moduleText(m) }}</option></select>
      <select v-else v-model="filters.source" aria-label="查询来源"><option value="">全部来源</option><option value="CHAT">智能问数</option><option value="CHART_REFRESH">图表刷新</option></select>
      <input v-if="tab==='logs'" v-model.trim="filters.action" placeholder="操作名称" maxlength="80" aria-label="操作名称">
      <input v-model.trim="filters.requestId" placeholder="请求编号" maxlength="36" aria-label="请求编号">
    </div>
    <div class="audit-toolbar"><span>共 {{ total }} 条<span v-if="selected.length"> · 已选 {{ selected.length }} 条</span></span><button type="button" class="danger td-btn td-btn--danger" :disabled="!selected.length || loading || deleting" @click="removeSelected">{{ deleting ? '正在删除…' : '批量删除' }}</button></div>
    <p v-if="error" role="alert" class="audit-error">{{ error }} <button @click="today ? load() : initialize()">重试</button></p>
    <div :aria-busy="loading" class="audit-table-wrap">
      <table>
        <thead><tr><th><input type="checkbox" aria-label="选择本页全部记录" :checked="allSelected" :indeterminate="selected.length>0&&!allSelected" :disabled="loading||deleting||!rows.length" @change="selectAll"></th><th>序号</th><th>操作时间</th><th>账号</th><th>{{ tab==='queries'?'查询来源':'操作 / 模块' }}</th><th>{{ tab==='queries'?'业务问题':'操作对象 / 详情' }}</th><th>状态</th><th v-if="tab==='queries'">返回行数</th><th>耗时</th><th>请求编号</th><th v-if="tab==='queries'">SQL</th></tr></thead>
        <tbody v-if="!loading"><template v-for="(row,index) in rows" :key="row.id">
          <tr>
            <td><input v-model="selected" type="checkbox" :value="row.id" :aria-label="'选择记录 '+row.id" :disabled="deleting"></td>
            <td>{{ (page-1)*size+index+1 }}</td><td class="audit-time">{{ time(row.occurredAt) }}</td><td>{{ row.username }}<small v-if="row.actorRole==='ANONYMOUS'">未认证</small></td>
            <td>{{ tab==='queries' ? (row.source==='CHAT'?'智能问数':'图表刷新') : row.action }}<small v-if="tab==='logs'">{{ moduleText(row.module) }}</small></td>
            <td class="audit-content"><template v-if="tab==='queries'">{{ row.question }}</template><template v-else><span>{{ row.target }}</span><small v-if="row.detail">{{ row.detail }}</small><small>{{ row.method }} {{ row.path }}</small></template></td>
            <td><span class="audit-status" :class="row.status.toLowerCase()">{{ statusText(row.status) }}</span><small v-if="row.httpStatus">HTTP {{ row.httpStatus }}</small><small v-if="row.errorCode">{{ row.errorCode }}</small></td>
            <td v-if="tab==='queries'">{{ row.rowCount ?? '—' }}</td><td>{{ row.durationMs == null ? '—' : row.durationMs+' ms' }}</td>
            <td><code class="audit-request">{{ row.requestId || '—' }}</code></td>
            <td v-if="tab==='queries'"><button :disabled="detailBusy[row.id]" @click="toggleSql(row)">{{ detailBusy[row.id]?'加载中…':details[row.id]?'收起 SQL':'查看 SQL' }}</button><small v-if="detailErrors[row.id]" role="alert">{{ detailErrors[row.id] }}</small></td>
          </tr>
          <tr v-if="tab==='queries'&&details[row.id]"><td colspan="11" class="audit-sql"><b>实际执行 SQL</b><pre>{{ details[row.id].sql }}</pre><small>查询记录：{{ row.id }} · 会话：{{ details[row.id].sessionId || '—' }}</small></td></tr>
        </template></tbody>
      </table>
      <p v-if="loading" role="status" class="audit-empty">正在读取审计记录…</p>
      <p v-else-if="!rows.length&&!error" class="audit-empty">当前条件下暂无记录，可调整日期或搜索条件。</p>
    </div>
    <div class="audit-pagination"><label>每页 <select v-model.number="size" :disabled="loading||deleting" @change="move(1)"><option v-for="n in [10,20,50,100]" :key="n" :value="n">{{ n }} 条</option></select></label><span>第 {{ page }} / {{ pages }} 页</span><button type="button" class="td-btn" :disabled="loading||deleting||page<=1" @click="move(page-1)">上一页</button><button type="button" class="td-btn" :disabled="loading||deleting||page>=pages" @click="move(page+1)">下一页</button></div>
  </section>
</template>

<style scoped>
.audit-panel{background:var(--da-surface);border:0;border-radius:0;padding:var(--da-panel-padding,16px);min-width:0;min-height:calc(100% - var(--da-page-header-h,44px));color:var(--da-text);font-size:13px}
.audit-tabs{display:flex;gap:20px;align-items:center;border-bottom:1px solid var(--da-border)}
.audit-tabs>button{padding:0 0 14px;border:0;border-radius:0;font-weight:600;color:var(--da-muted);background:transparent}
.audit-tabs>button[aria-selected=true]{color:var(--da-primary);border-bottom:2px solid var(--da-primary)}
.audit-tabs>span{margin-left:auto;font-size:12px;color:var(--da-muted)}
/* 查询栏参考阿里云日志页：关键词宽框 + 时间范围下拉 + 查询按钮单行排布，附加条件第二行紧凑排列。 */
.audit-querybar{display:flex;gap:10px;align-items:center;flex-wrap:wrap;padding:14px 0;border-bottom:1px solid var(--da-border)}
.audit-keyword-input{flex:1 1 320px;min-width:260px;height:36px;border:1px solid var(--da-border-strong);border-radius:6px;padding:0 12px;background:var(--da-surface);color:var(--da-text);font:inherit;color-scheme:inherit}
.audit-range-label{display:flex;align-items:center;gap:8px;color:var(--da-text-2);font-size:12px;white-space:nowrap}
.audit-range-sep{color:var(--da-text-3);font-size:12px}
.audit-conditions{display:flex;gap:10px;flex-wrap:wrap;align-items:center;padding:12px 0;border-bottom:1px solid var(--da-border)}
input:not([type=checkbox]),select{height:36px;border:1px solid var(--da-border);border-radius:6px;padding:0 10px;background:var(--da-surface);color:var(--da-text);min-width:0;box-sizing:border-box;font:inherit;color-scheme:inherit}
.audit-querybar select,.audit-querybar input[type=date]{width:150px}
.audit-conditions input,.audit-conditions select{width:150px}
input[type=checkbox]{accent-color:var(--da-primary);cursor:pointer;width:15px;height:15px}
.audit-toolbar,.audit-pagination{display:flex;align-items:center;gap:10px}
button{height:var(--da-control-h,36px);border:1px solid var(--da-border);border-radius:6px;padding:8px 12px;background:var(--da-surface);color:var(--da-text-2);cursor:pointer;font:inherit;white-space:nowrap}
button.primary{background:var(--da-primary);border-color:var(--da-primary);color:#fff}button.danger{color:#c74040}button:disabled{opacity:.45;cursor:not-allowed}
button:focus-visible,input:focus-visible,select:focus-visible{outline:2px solid var(--da-primary);outline-offset:3px}
.audit-toolbar{justify-content:space-between;margin:20px 0 12px;color:var(--da-muted)}
.audit-error{padding:12px;border-radius:6px;background:var(--da-warning-soft);color:var(--da-warning)}
.audit-table-wrap{overflow:auto;border:1px solid var(--da-border);border-radius:8px;min-height:180px}
table{border-collapse:collapse;width:100%;text-align:left;min-width:1000px;font-size:12px}
th{font-weight:500;color:var(--da-muted);background:var(--da-panel);white-space:nowrap}td,th{padding:13px 12px;border-bottom:1px solid var(--da-border);vertical-align:top}
td{line-height:1.7}td:first-child,th:first-child{width:32px}td small{display:block;color:var(--da-muted);font-size:11px;overflow-wrap:anywhere;white-space:pre-wrap}
.audit-time{white-space:nowrap;font-variant-numeric:tabular-nums}.audit-content{min-width:190px;max-width:360px;overflow-wrap:anywhere}.audit-content small{max-height:90px;overflow:auto}
.audit-status{white-space:nowrap;color:var(--da-muted)}.audit-status.success{color:var(--da-success)}.audit-status.failed,.audit-status.denied{color:#c74040}
.audit-request{font-size:10px;display:block;max-width:115px;overflow-wrap:anywhere;color:var(--da-muted)}
.audit-sql{padding:18px 24px;background:var(--da-panel)}.audit-sql pre{font:12px/1.8 ui-monospace,Consolas,monospace;white-space:pre-wrap;overflow-wrap:anywhere;max-height:360px;overflow:auto;margin:12px 0}.audit-sql b{font-weight:500;color:var(--da-primary)}
.audit-empty{text-align:center;margin:36px 16px;color:var(--da-muted)}.audit-pagination{margin-top:18px;justify-content:flex-end}.audit-pagination label{display:flex;align-items:center;gap:6px;margin-right:auto}.audit-pagination select{min-width:80px}
@media(max-width:1200px){.audit-panel{padding:16px}.audit-tabs{gap:14px}.audit-tabs>span{font-size:11px}.audit-querybar,.audit-conditions{gap:8px}.audit-keyword-input{min-width:200px}.audit-querybar select,.audit-querybar input[type=date]{width:136px}.audit-conditions input,.audit-conditions select{width:136px}}
</style>

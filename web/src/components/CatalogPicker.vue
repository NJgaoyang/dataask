<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { showConfirmDialog, showSuccessToast } from 'vant'
import api from '../api'
const props = defineProps({ generation: { type: Number, default: 0 } })
const emit = defineEmits(['configure-source', 'saved'])
const step = ref(0), database = ref(''), databases = ref([]), tables = ref([])
const selected = ref({}), active = ref(''), keyword = ref(''), columnKeyword = ref('')
const sourceKey = ref(''), revision = ref('none'), configured = ref(false)
const loading = ref(false), saving = ref(false), error = ref(''), dirty = ref(false), page = ref(1)
const loaded = ref(false)
let requestId = 0
const filtered = computed(() => tables.value.filter(t => `${t.name} ${t.comment}`.toLowerCase().includes(keyword.value.trim().toLowerCase())))
const pages = computed(() => Math.max(1, Math.ceil(filtered.value.length / 10)))
const pageTables = computed(() => filtered.value.slice((page.value - 1) * 10, page.value * 10))
const activeTable = computed(() => tables.value.find(t => t.name === active.value))
const visibleColumns = computed(() => (activeTable.value?.columns || []).filter(c =>
  `${c.name} ${c.comment}`.toLowerCase().includes(columnKeyword.value.trim().toLowerCase())))
const selectedTables = computed(() => tables.value.filter(t => (selected.value[t.name] || []).length > 0))
const fieldCount = computed(() => Object.values(selected.value).reduce((sum, fields) => sum + fields.length, 0))
watch(keyword, () => { page.value = 1 })
watch(active, () => { columnKeyword.value = '' })
function message(e) { return e.response?.data?.message || e.message || '操作失败' }
async function loadTables(name) {
  const id = ++requestId
  loaded.value = false
  if (!name) { tables.value = []; selected.value = {}; return }
  loading.value = true; error.value = ''
  try {
    const { data } = await api.get('/admin/catalog/tables', { params: { database: name } })
    if (id !== requestId) return
    loaded.value = true
    tables.value = data.tables
    selected.value = Object.fromEntries(data.tables.map(t => [t.name, [...t.selectedColumns]]))
    sourceKey.value = data.sourceKey; revision.value = data.revision
    active.value = data.tables[0]?.name || ''; page.value = 1; dirty.value = false
  } catch (e) { if(id === requestId) { tables.value = []; selected.value = {}; error.value = message(e) } }
  finally { if(id === requestId) loading.value = false }
}
async function refresh() {
  loading.value = true; error.value = ''; loaded.value = false
  try {
    const { data } = await api.get('/admin/catalog/databases')
    configured.value = data.configured; databases.value = data.databases
    sourceKey.value = data.sourceKey; revision.value = data.revision
    const next = data.databases.includes(database.value) ? database.value
      : data.databases.includes(data.defaultDatabase) ? data.defaultDatabase : data.databases[0] || ''
    database.value = next
    await loadTables(next)
  } catch (e) { error.value = message(e) }
  finally { loading.value = false }
}
async function changeDatabase(event) {
  const next = event.target.value
  if (dirty.value) {
    try { await showConfirmDialog({ title: '切换数据库', message: '当前选择尚未保存，切换将放弃本库的未保存修改。' }) }
    catch { event.target.value = database.value; return }
  }
  database.value = next; await loadTables(next)
}
function toggleTable(table, checked) {
  selected.value[table.name] = checked ? table.columns.map(c => c.name) : []
  active.value = table.name; dirty.value = true
}
function toggleColumn(column, checked) {
  const fields = new Set(selected.value[active.value] || [])
  if (checked) fields.add(column); else fields.delete(column)
  selected.value[active.value] = [...fields]; dirty.value = true
}
function selectVisible(checked) {
  for (const table of pageTables.value) selected.value[table.name] = checked ? table.columns.map(c => c.name) : []
  dirty.value = true
}
function selectVisibleColumns(checked) {
  for (const col of visibleColumns.value) toggleColumn(col.name, checked)
}
async function save() {
  if (saving.value || !loaded.value) return
  if (!selectedTables.value.length) {
    try { await showConfirmDialog({ title: '撤销当前库的全部授权', message: '当前没有选中字段。保存后，此数据库将不再参与问数。其他数据库的选择不受影响。' }) }
    catch { return }
  }
  saving.value = true; error.value = ''
  try {
    const payload = Object.fromEntries(Object.entries(selected.value).filter(([, fields]) => fields.length > 0))
    const { data } = await api.post('/admin/catalog/selection', {
      database: database.value, sourceKey: sourceKey.value, revision: revision.value, tables: payload
    })
    revision.value = data.revision; dirty.value = false
    showSuccessToast(`已保存 ${selectedTables.value.length} 张表、${fieldCount.value} 个字段`)
    emit('saved', data)
  } catch (e) { error.value = message(e) }
  finally { saving.value = false }
}
watch(() => props.generation, refresh)
onMounted(refresh)
</script>

<template>
  <section class="catalog-picker">
    <ol class="catalog-steps" aria-label="配置步骤">
      <li v-for="(title, i) in ['选择数据库', '选择表与字段', '确认保存']" :key="title"
        :class="{ current: step === i, done: step > i }"><span>{{ i + 1 }}</span>{{ title }}</li>
    </ol>
    <p class="catalog-intro">从已连接的 StarRocks 读取真实元数据。只开放勾选的表和字段；选择结果保存在 MySQL，不复制业务数据。</p>
    <p v-if="error" class="catalog-error" role="alert">{{ error }}</p>
    <div v-if="loading" class="catalog-loading"><van-loading size="18"/> 正在读取数据库元数据…</div>
    <template v-else>
      <div v-if="step === 0" class="catalog-source">
        <h4>分析数据库 · StarRocks</h4>
        <p>{{ configured ? '连接已配置，选择要开放的数据库。' : '请先保存并测试分析数据源连接。' }}</p>
        <button type="button" class="catalog-link" @click="emit('configure-source')">{{ configured ? '修改连接配置' : '配置数据源' }}</button>
        <label for="catalog-db">来源数据库</label>
        <select id="catalog-db" :value="database" :disabled="!configured || saving" @change="changeDatabase">
          <option value="" disabled>请选择数据库</option>
          <option v-for="db in databases" :key="db" :value="db">{{ db }}</option>
        </select>
        <p v-if="configured && !databases.length">此账号没有可读取的数据库，请检查 StarRocks 权限。</p>
        <button type="button" class="catalog-link" @click="refresh">刷新数据库与元数据</button>
      </div>
      <div v-if="step === 1" class="catalog-workspace">
        <div class="catalog-table-pane">
          <header><b>来源表</b><small>{{ filtered.length }} 张</small></header>
          <input v-model="keyword" aria-label="搜索表名或注释" placeholder="搜索表名 / 中文注释"/>
          <div class="catalog-tools"><button type="button" @click="selectVisible(true)">全选本页</button><button type="button" @click="selectVisible(false)">清空本页</button></div>
          <div class="catalog-table-list">
            <div v-for="table in pageTables" :key="table.name" class="catalog-table-item" :class="{ active: active === table.name }">
              <input type="checkbox" :checked="(selected[table.name] || []).length > 0" :aria-label="'选择'+table.name"
                @change="toggleTable(table, $event.target.checked)"/>
              <button type="button" @click="active = table.name">
                <strong>{{ table.name }}</strong><span>{{ table.comment || '无表注释' }}</span>
                <small>已选 {{ (selected[table.name] || []).length }} / {{ table.columns.length }} 字段</small>
              </button>
            </div>
            <p v-if="!pageTables.length" class="catalog-empty">没有匹配的表</p>
          </div>
          <footer><button type="button" :disabled="page <= 1" @click="page--">上一页</button><span>{{ page }} / {{ pages }}</span><button type="button" :disabled="page >= pages" @click="page++">下一页</button></footer>
        </div>
        <div class="catalog-field-pane">
          <header><b>字段选择</b><small>{{ activeTable?.name || '请选择左侧表' }}</small></header>
          <input v-model="columnKeyword" aria-label="搜索字段名或注释" placeholder="搜索字段名 / 中文注释"/>
          <div class="catalog-tools"><button type="button" @click="selectVisibleColumns(true)">全选可见字段</button><button type="button" @click="selectVisibleColumns(false)">取消可见字段</button></div>
          <div class="catalog-fields-scroll">
            <table><thead><tr><th>选择</th><th>字段名</th><th>类型</th><th>中文注释</th></tr></thead>
              <tbody><tr v-for="col in visibleColumns" :key="col.name">
                <td><input type="checkbox" :checked="(selected[active] || []).includes(col.name)" :aria-label="'选择字段'+col.name" @change="toggleColumn(col.name, $event.target.checked)"/></td>
                <td>{{ col.name }}</td><td>{{ col.type }}</td><td>{{ col.comment || '—' }}</td>
              </tr></tbody>
            </table>
          </div>
        </div>
      </div>
      <div v-if="step === 2" class="catalog-confirm">
        <h4>{{ database }} · 已选 {{ selectedTables.length }} 张表，{{ fieldCount }} 个字段</h4>
        <p>日期、聚合、关联用到的字段也需要勾选；新增物理字段不会自动开放。</p>
        <div class="catalog-review-scroll"><article v-for="table in selectedTables" :key="table.name"><strong>{{ table.name }}</strong>
          <div class="catalog-tags"><span v-for="field in selected[table.name]" :key="field">{{ field }}</span></div></article></div>
        <p v-if="!selectedTables.length">当前库不开放任何表。其他库的已保存配置不会改变。</p>
      </div>
      <div class="catalog-actions">
        <span>{{ selectedTables.length }} 张表 · {{ fieldCount }} 字段 {{ dirty ? '· 尚未保存' : '· 已同步' }}</span>
        <button v-if="step > 0" type="button" :disabled="saving" @click="step--">上一步</button>
        <button v-if="step < 2" type="button" class="catalog-primary" :disabled="!database || !configured || !loaded" @click="step++">下一步</button>
        <button v-else type="button" class="catalog-primary" :disabled="saving || !loaded" @click="save">{{ saving ? '正在保存…' : '确认保存并生效' }}</button>
      </div>
    </template>
  </section>
</template>

<style scoped>
.catalog-picker{font-size:13px;color:#344960}.catalog-steps{list-style:none;display:flex;gap:10px;padding:0;margin:6px 0 18px}.catalog-steps li{flex:1;display:flex;align-items:center;gap:7px;color:#8695a5;font-size:12px}.catalog-steps span{display:grid;place-items:center;width:24px;height:24px;border-radius:50%;background:#edf1f6}.catalog-steps .current{color:#2569c5;font-weight:600}.catalog-steps .current span,.catalog-steps .done span{background:#e0ecff;color:#2569c5}.catalog-intro{color:#7a899d;line-height:1.7;font-size:12px}.catalog-error{color:#a63832;background:#fff0ee;padding:12px;border-radius:8px;overflow-wrap:anywhere}.catalog-source{padding:24px;border:1px solid #e3eaf4;background:#f8faff;border-radius:12px}.catalog-source label{display:block;margin:20px 0 8px}.catalog-source select{display:block;width:100%;max-width:440px;padding:10px;border:1px solid #d4dfed;border-radius:8px;background:#fff}.catalog-link{border:0;background:none;color:#286bbb;padding:6px 0;text-align:left}.catalog-workspace{display:grid;grid-template-columns:34% minmax(0,1fr);gap:14px}.catalog-table-pane,.catalog-field-pane{border:1px solid #e1e8f2;border-radius:10px;overflow:hidden;min-width:0;background:#fff}.catalog-workspace header{padding:13px;display:flex;gap:10px;justify-content:space-between;background:#f7f9fd}.catalog-workspace header small{overflow-wrap:anywhere;color:#8795a5;font-size:11px}.catalog-workspace input:not([type=checkbox]){margin:10px;width:calc(100% - 20px);padding:9px 10px;border:1px solid #dce5f0;border-radius:7px;font-size:12px}.catalog-tools{display:flex;gap:12px;padding:0 12px 10px}.catalog-tools button{border:0;background:none;color:#3170bb;font-size:11px;padding:3px 0}.catalog-table-list{height:342px;overflow-y:auto}.catalog-table-item{display:flex;align-items:flex-start;gap:8px;padding:12px 10px;border-top:1px solid #f0f2f6}.catalog-table-item.active{background:#eaf3ff}.catalog-table-item input{margin-top:3px}.catalog-table-item>button{border:0;background:none;text-align:left;display:flex;flex-direction:column;gap:5px;padding:0;min-width:0;flex:1}.catalog-table-item strong{font-size:12px;overflow-wrap:anywhere}.catalog-table-item span,.catalog-table-item small{font-size:11px;color:#8592a5;overflow-wrap:anywhere}.catalog-workspace footer{display:flex;justify-content:space-between;align-items:center;padding:11px;border-top:1px solid #e5ebf3;font-size:11px}.catalog-workspace footer button{border:0;background:none;font-size:11px;color:#316bb2}.catalog-fields-scroll{height:390px;overflow:auto}.catalog-fields-scroll table{border-collapse:collapse;min-width:100%;font-size:12px}.catalog-fields-scroll th{position:sticky;top:0;background:#f0f5fd;text-align:left;z-index:1}.catalog-fields-scroll td,.catalog-fields-scroll th{padding:10px;border-bottom:1px solid #edf1f6;white-space:nowrap}.catalog-fields-scroll td:last-child{white-space:normal;min-width:120px;max-width:240px;overflow-wrap:anywhere}.catalog-actions{display:flex;align-items:center;gap:10px;padding:17px 0 3px}.catalog-actions span{margin-right:auto;color:#8192a8;font-size:12px}.catalog-actions button{padding:9px 15px;border:1px solid #dce5f0;border-radius:7px;background:#fff;color:#546e8d}.catalog-actions .catalog-primary{background:#286ccd;color:#fff;border-color:#286ccd}.catalog-review-scroll{max-height:390px;overflow:auto}.catalog-review-scroll article{padding:13px 0;border-bottom:1px solid #e9eef5}.catalog-tags{display:flex;gap:6px;flex-wrap:wrap;margin-top:9px}.catalog-tags span{border-radius:5px;background:#edf4ff;color:#4473a9;padding:4px 7px;font-size:11px}.catalog-loading{display:flex;align-items:center;gap:8px;padding:28px;color:#8291a2}.catalog-empty{padding:20px;color:#93a0af}.catalog-confirm p{color:#8391a3;font-size:12px;line-height:1.8}
@media(max-width:700px){.catalog-workspace{grid-template-columns:1fr}.catalog-table-list{height:200px}.catalog-fields-scroll{height:280px}.catalog-steps{gap:4px}.catalog-steps li{font-size:11px}.catalog-actions{flex-wrap:wrap}.catalog-actions span{width:100%}.catalog-source{padding:16px}}
</style>

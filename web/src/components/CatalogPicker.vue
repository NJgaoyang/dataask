<script setup>
import { computed, onMounted, ref } from 'vue'
import { showFailToast, showSuccessToast, showConfirmDialog } from 'vant'

const props = defineProps({ api: { type: Object, required: true } })
const emit = defineEmits(['saved','policy'])
const loading = ref(false)
const saving = ref(false)
const configured = ref(false)
const database = ref('')
const sourceKey = ref('')
const revision = ref('none')
const tables = ref([])
const selected = ref({})
const search = ref('')
const error = ref('')
const opened = ref('')
const visible = computed(() => tables.value.filter(t =>
  t.name.toLowerCase().includes(search.value.trim().toLowerCase())))
const tableCount = computed(() => Object.keys(selected.value).filter(t => selected.value[t]?.length).length)
const fieldCount = computed(() => Object.values(selected.value).reduce((sum, cols) => sum + cols.length, 0))
function err(e) {
  return e.response?.data?.message || e.response?.data?.detail || e.message || '请求失败'
}
async function reload() {
  loading.value = true
  error.value = ''
  try {
    const {data:db} = await props.api.get('/admin/catalog/databases')
    configured.value = !!db.configured
    database.value = db.defaultDatabase || ''
    sourceKey.value = db.sourceKey || ''
    revision.value = db.revision || 'none'
    emit('policy', revision.value !== 'none')
    if (!configured.value) { tables.value = []; selected.value = {}; return }
    const {data} = await props.api.get('/admin/catalog/tables', {params:{database:database.value}})
    sourceKey.value = data.sourceKey
    revision.value = data.revision
    emit('policy', revision.value !== 'none')
    tables.value = data.tables || []
    selected.value = Object.fromEntries(tables.value.filter(t => t.selectedColumns?.length)
      .map(t => [t.name, [...t.selectedColumns]]))
  } catch(e) {
    error.value = err(e)
    showFailToast(error.value)
    tables.value = []
    selected.value = {}
  } finally {
    loading.value = false
  }
}
function selectedColumn(table, col) { return (selected.value[table] || []).includes(col) }
function setTable(table, value) {
  const copy = {...selected.value}
  if (value) copy[table.name] = table.columns.map(c => c.name)
  else delete copy[table.name]
  selected.value = copy
}
function setColumn(table, column, value) {
  const cols = new Set(selected.value[table] || [])
  if (value) cols.add(column)
  else cols.delete(column)
  const copy = {...selected.value}
  if (cols.size) copy[table] = [...cols]
  else delete copy[table]
  selected.value = copy
}
async function save() {
  if (loading.value || saving.value) return
  if (tableCount.value > 300) return showFailToast('一次最多选择300张表')
  try {
    await showConfirmDialog({title:'确认更新字段授权？',
      message:'未选择的字段将立即禁止用于 AI 查询，语义索引需重新构建。'})
  } catch { return }
  saving.value = true
  try {
    const clean = Object.fromEntries(Object.entries(selected.value).filter(([,cols]) => cols.length))
    await props.api.post('/admin/catalog/selection',
      {database:database.value,sourceKey:sourceKey.value,revision:revision.value,tables:clean})
    showSuccessToast('字段授权已保存')
    await reload()
    emit('saved')
  } catch(e) {
    error.value = err(e)
    showFailToast(error.value)
  } finally { saving.value = false }
}
onMounted(reload)
</script>

<template>
  <section class="catalog-picker" aria-label="ADS 数据库字段权限管理">
    <header class="catalog-header">
      <div>
        <strong>字段级授权</strong>
        <small>当前数据库：{{ database || '未配置' }} · 已选 {{ tableCount }} 张表、{{ fieldCount }} 个字段</small>
      </div>
      <div class="catalog-actions">
        <button type="button" @click="reload" :disabled="loading || saving">刷新字段</button>
        <button type="button" class="catalog-save" @click="save" :disabled="!configured || loading || saving">
          {{ saving ? '保存中…' : '保存字段权限' }}
        </button>
      </div>
    </header>
    <p class="catalog-notice">首次保存前沿用原表级授权；保存后仅允许使用勾选字段。取消表内所有字段即可撤销整张表的访问。</p>
    <p v-if="error" class="catalog-error">{{ error }}</p>
    <p v-if="!configured" class="catalog-notice">请先配置并启用 StarRocks ADS 数据源。</p>
    <template v-else>
      <input v-model="search" class="catalog-search" aria-label="搜索授权数据表" placeholder="输入表名筛选…" />
      <div class="catalog-table-list">
        <div v-for="table in visible" :key="table.name" class="catalog-table">
          <div class="catalog-table-title">
            <label><input type="checkbox" :checked="!!selected[table.name]?.length"
              :disabled="loading || saving"
              @change="setTable(table, $event.target.checked)" />
              <span>{{ table.name }}</span>
            </label>
            <button type="button" @click="opened = opened === table.name ? '' : table.name">
              {{ selected[table.name]?.length || 0 }}/{{ table.columns.length }} 字段 · {{ opened === table.name ? '收起' : '展开' }}
            </button>
          </div>
          <div v-if="opened === table.name" class="catalog-columns">
            <label v-for="col in table.columns" :key="col.name">
              <input type="checkbox" :checked="selectedColumn(table.name,col.name)"
                :disabled="loading || saving"
                @change="setColumn(table.name,col.name,$event.target.checked)" />
              <span><b>{{ col.name }}</b><small>{{ col.comment || col.type }}</small></span>
            </label>
          </div>
        </div>
      </div>
      <p v-if="!visible.length" class="catalog-notice">{{ loading ? '正在读取表结构…' : '没有匹配的数据表' }}</p>
    </template>
  </section>
</template>

<style scoped>
.catalog-picker{border:1px solid var(--van-border-color,#dfe5ed);border-radius:12px;padding:16px;margin:14px 0;background:var(--van-background-2,#fff)}
.catalog-header,.catalog-table-title{display:flex;align-items:center;justify-content:space-between;gap:12px}
.catalog-header strong{display:block;font-size:15px}.catalog-header small{display:block;color:var(--van-text-color-2,#677489);margin-top:4px}
.catalog-actions{display:flex;gap:8px}.catalog-actions button,.catalog-table-title button{border:1px solid #d5dfeb;border-radius:6px;background:transparent;padding:6px 10px;cursor:pointer;font-size:12px}
.catalog-actions .catalog-save{background:#2563eb;color:#fff;border-color:#2563eb}
.catalog-actions button:disabled{opacity:.5;cursor:not-allowed}
.catalog-notice{color:#64748b;font-size:12px;margin:10px 0}.catalog-error{color:#dc2626;font-size:12px}
.catalog-search{display:block;width:100%;box-sizing:border-box;border:1px solid #d5dfeb;border-radius:7px;padding:8px 10px;margin:12px 0}
.catalog-table-list{max-height:380px;overflow:auto}.catalog-table{border-bottom:1px solid #edf1f5;padding:9px 2px}
.catalog-table-title label{display:flex;align-items:center;gap:8px;min-width:0;word-break:break-all}
.catalog-columns{display:grid;grid-template-columns:repeat(auto-fill,minmax(230px,1fr));gap:8px;padding:12px 4px}
.catalog-columns label{display:flex;align-items:flex-start;gap:6px;font-size:12px}.catalog-columns small{display:block;color:#64748b;margin-top:2px}
@media(max-width:700px){.catalog-header{align-items:flex-start;flex-direction:column}.catalog-columns{grid-template-columns:1fr}}
</style>

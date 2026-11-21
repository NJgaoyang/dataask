<script setup>
import {computed,onMounted,ref} from 'vue'
import {showFailToast,showSuccessToast} from 'vant'
import ListPagination from './ListPagination.vue'

const props=defineProps({api:{type:Object,required:true}})
const sources=ref([]),sourceId=ref(''),tables=ref([])
const loading=ref(false),saving=ref(''),error=ref(''),keyword=ref('')
const page=ref(1),size=ref(10)
let loadVersion=0
const currentSource=computed(()=>sources.value.find(s=>s.id===sourceId.value))
const filtered=computed(()=>tables.value.filter(t=>t.name.toLowerCase().includes(keyword.value.trim().toLowerCase())))
const paged=computed(()=>filtered.value.slice((page.value-1)*size.value,page.value*size.value))
const totalAuthorized=computed(()=>tables.value.filter(t=>t.enabled).length)
const errorText=e=>e.response?.data?.message||e.message||'操作失败'

async function loadSources(){
  error.value=''
  try{
    const {data}=await props.api.get('/admin/table-permissions/sources')
    sources.value=Array.isArray(data)?data:[]
    if(!sources.value.some(s=>s.id===sourceId.value))
      sourceId.value=sources.value.find(s=>s.active)?.id||sources.value[0]?.id||''
    await loadTables()
  }catch(e){error.value='读取数据源失败：'+errorText(e)}
}
async function loadTables(){
  const id=sourceId.value,version=++loadVersion
  page.value=1
  tables.value=[]
  error.value=''
  if(!id)return
  loading.value=true
  try{
    const {data}=await props.api.get('/admin/table-permissions',{params:{sourceId:id}})
    if(version===loadVersion)tables.value=Array.isArray(data)?data:[]
  }catch(e){if(version===loadVersion)error.value='读取数据表失败：'+errorText(e)}
  finally{if(version===loadVersion)loading.value=false}
}
async function setAllowed(table,enabled){
  if(saving.value)return
  const selectedSource=sourceId.value
  saving.value=table.name
  try{
    await props.api.post('/admin/table-permissions',{sourceId:selectedSource,tableName:table.name,enabled})
    if(sourceId.value===selectedSource)table.enabled=enabled
    showSuccessToast(enabled?'已授权':'已取消授权')
  }catch(e){showFailToast(errorText(e))}
  finally{saving.value=''}
}
onMounted(loadSources)
</script>

<template>
  <section class="tb-admin-panel-card da-table-permissions">
    <div class="da-tables-filters">
      <label class="da-tables-source">
        <span>数据源</span>
        <select v-model="sourceId" aria-label="选择数据源" @change="loadTables">
          <option v-for="source in sources" :key="source.id" :value="source.id">
            {{ source.name }}（{{ source.schema }}）{{ source.active?' · 当前启用':'' }}
          </option>
        </select>
      </label>
      <label class="da-tables-search">
        <van-icon name="search"/>
        <input v-model="keyword" type="search" aria-label="搜索数据表" placeholder="搜索表名" @input="page=1">
      </label>
      <button type="button" class="td-btn" :disabled="loading||!sourceId" @click="loadTables">
        <van-icon name="replay"/> 刷新
      </button>
    </div>
    <p v-if="error" role="alert" class="metric-error">{{ error }}</p>
    <div v-if="currentSource" class="da-tables-meta">
      <span>已授权 {{ totalAuthorized }} / {{ tables.length }} 张表</span>
      <small v-if="!currentSource.active">当前数据源未启用，授权会保留，启用后才会用于问数</small>
    </div>
    <div class="da-tables-table">
      <div class="da-tables-head"><span>序号</span><span>表名</span><span>授权状态</span><span>操作</span></div>
      <div v-for="(table,index) in paged" :key="table.name" class="da-tables-row">
        <span>{{ (page-1)*size+index+1 }}</span>
        <strong :title="table.name">{{ table.name }}</strong>
        <span :class="['da-grant-status',table.enabled?'active':'']">
          {{ table.enabled?'已授权':'未授权' }}
        </span>
        <van-switch :model-value="table.enabled" size="20px" :disabled="loading||!!saving"
          :aria-label="'授权数据表 '+table.name" @update:model-value="value=>setAllowed(table,value)"/>
      </div>
      <div v-if="loading" class="da-tables-empty"><van-loading size="16px"/> 正在读取数据表…</div>
      <div v-else-if="!filtered.length" class="da-tables-empty">
        {{ sourceId?'暂无匹配的数据表':'请先在数据源配置中添加数据源' }}
      </div>
    </div>
    <ListPagination v-model:page="page" v-model:size="size" :total="filtered.length"/>
  </section>
</template>

<style scoped>
.da-table-permissions{min-height:250px}
.da-tables-filters{display:flex;align-items:end;gap:12px;padding:16px 18px;border-bottom:1px solid var(--da-border)}
.da-tables-source{display:flex;flex-direction:column;gap:7px;width:min(360px,42%)}
.da-tables-source>span{font-size:12px;color:var(--da-text-3)}
.da-tables-source select,.da-tables-search{height:36px;border:1px solid var(--da-border);border-radius:7px;background:var(--da-surface);color:var(--da-text);font-size:13px}
.da-tables-source select{padding:0 10px}
.da-tables-search{display:flex;align-items:center;gap:8px;flex:1;padding:0 10px}
.da-tables-search .van-icon{color:var(--da-text-3)}
.da-tables-search input{width:100%;min-width:0;border:0;outline:0;background:transparent;color:var(--da-text);font:inherit}
.da-tables-meta{display:flex;align-items:center;gap:16px;padding:12px 18px;color:var(--da-text-2);font-size:12px}
.da-tables-meta small{font-size:12px;color:var(--da-text-3)}
.da-tables-table{margin:0 18px;border:1px solid var(--da-border);border-radius:8px;overflow:hidden}
.da-tables-head,.da-tables-row{display:grid;grid-template-columns:70px minmax(200px,1fr) 140px 90px;align-items:center;gap:12px;padding:0 16px}
.da-tables-head{height:42px;background:var(--da-surface-2);color:var(--da-text-3);font-size:12px}
.da-tables-row{min-height:48px;border-top:1px solid var(--da-border);color:var(--da-text-3);font-size:12px}
.da-tables-row strong{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:13px;color:var(--da-text);font-weight:500}
.da-grant-status.active{color:var(--da-primary)}
.da-tables-empty{display:flex;justify-content:center;align-items:center;gap:8px;min-height:136px;color:var(--da-text-3);font-size:13px}
@media(max-width:800px){.da-tables-filters{flex-wrap:wrap}.da-tables-source{width:100%}.da-tables-head,.da-tables-row{grid-template-columns:40px minmax(120px,1fr) 80px 50px;gap:6px;padding:0 8px}}
</style>

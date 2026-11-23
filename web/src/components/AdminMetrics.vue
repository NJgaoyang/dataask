<script setup>
import {ref} from 'vue'
import {showConfirmDialog,showFailToast,showSuccessToast} from 'vant'
import RecordList from './RecordList.vue'

const props=defineProps({api:{type:Object,required:true}})
const refresh=ref(0)
const activeList=ref('metrics')
const selectedMetricIds=ref([])
const selectedBatchIds=ref([])
const deleteBusy=ref(false)
const fileInput=ref(null)
const editorOpen=ref(false)
const editorMode=ref('create')
const editorBusy=ref(false)
const editorError=ref('')
const importOpen=ref(false)
const importBusy=ref(false)
const importError=ref('')
const selectedFile=ref(null)
const preview=ref(null)
const autoParseOpen=ref(false),autoParseBusy=ref(false),autoParseSaving=ref(false),autoParseError=ref('')
const parseSources=ref([]),parseSourceId=ref(''),parsedPreview=ref(null)

const empty=()=>({
  id:null,metricName:'',aliases:'',definition:'',adsTable:'',adsField:'',
  sourceTables:'',sourceFields:'',calculationExpression:'',joinRules:'',
  timeField:'',dimensions:'',notes:''
})
const form=ref(empty())
const errorText=e=>e.response?.data?.message||e.response?.data?.detail||e.message||'操作失败'

function createMetric(){
  editorMode.value='create'
  form.value=empty()
  editorError.value=''
  editorOpen.value=true
}
function editMetric(row){
  editorMode.value='edit'
  form.value={
    ...empty(),...row,
    sourceTables:row.sourceTables||row.adsTable||'',
    sourceFields:row.sourceFields||row.adsField||''
  }
  editorError.value=''
  editorOpen.value=true
}
function closeEditor(){
  if(editorBusy.value)return
  editorOpen.value=false
  editorError.value=''
}
async function saveMetric(){
  if(editorBusy.value)return
  const payload={
    metricName:String(form.value.metricName||'').trim(),
    aliases:String(form.value.aliases||'').trim(),
    definition:String(form.value.definition||'').trim(),
    adsTable:String(form.value.adsTable||'').trim(),
    adsField:String(form.value.adsField||'').trim(),
    sourceTables:String(form.value.sourceTables||'').trim(),
    sourceFields:String(form.value.sourceFields||'').trim(),
    calculationExpression:String(form.value.calculationExpression||'').trim(),
    joinRules:String(form.value.joinRules||'').trim(),
    timeField:String(form.value.timeField||'').trim(),
    dimensions:String(form.value.dimensions||'').trim(),
    notes:String(form.value.notes||'').trim()
  }
  if(!payload.metricName){editorError.value='请输入指标名称';return}
  if(!payload.definition){editorError.value='请输入指标口径';return}
  editorBusy.value=true
  editorError.value=''
  try{
    if(editorMode.value==='create')await props.api.post('/admin/metrics',payload)
    else await props.api.put('/admin/metrics/'+form.value.id,payload)
    editorOpen.value=false
    refresh.value++
    showSuccessToast(editorMode.value==='create'?'指标已新增':'指标已更新')
  }catch(e){
    editorError.value=errorText(e)
  }finally{
    editorBusy.value=false
  }
}
async function removeMetric(row){
  try{
    await showConfirmDialog({title:'删除指标',message:'确定删除“'+row.metricName+'”？'})
  }catch{return}
  try{
    await props.api.delete('/admin/metrics/'+row.id)
    selectedMetricIds.value=selectedMetricIds.value.filter(id=>id!==row.id)
    refresh.value++
    showSuccessToast('指标已删除')
  }catch(e){
    showFailToast(errorText(e))
  }
}
async function deleteSelectedMetrics(){
  const ids=[...new Set(selectedMetricIds.value)]
  if(deleteBusy.value||!ids.length)return
  try{
    await showConfirmDialog({title:'批量删除指标',
      message:`确定删除已选中的 ${ids.length} 条指标吗？删除后无法恢复。`})
  }catch{return}
  deleteBusy.value=true
  try{
    const {data}=await props.api.post('/admin/metrics/batch-delete',{ids})
    selectedMetricIds.value=[]
    refresh.value++
    showSuccessToast(`已删除 ${data.deleted} 条指标`)
  }catch(e){showFailToast(errorText(e))}
  finally{deleteBusy.value=false}
}
async function deleteSelectedBatches(){
  const ids=[...new Set(selectedBatchIds.value)]
  if(deleteBusy.value||!ids.length)return
  try{
    await showConfirmDialog({title:'批量删除导入批次',
      message:`确定删除选中的 ${ids.length} 个文件/生成批次吗？该批次下所有指标也会一并删除，无法恢复。`})
  }catch{return}
  deleteBusy.value=true
  try{
    const {data}=await props.api.post('/admin/metrics/batches/batch-delete',{ids})
    selectedBatchIds.value=[]
    selectedMetricIds.value=[]
    refresh.value++
    showSuccessToast(`已删除 ${data.deleted} 个批次、${data.metricsDeleted} 条指标`)
  }catch(e){showFailToast(errorText(e))}
  finally{deleteBusy.value=false}
}
async function removeBatch(row){
  if(deleteBusy.value)return
  try{
    await showConfirmDialog({title:'删除导入批次',
      message:`确定删除“${row.filename}”及其中的 ${row.count} 条指标吗？删除后无法恢复。`})
  }catch{return}
  deleteBusy.value=true
  try{
    await props.api.delete('/admin/metrics/batches/'+row.id)
    selectedBatchIds.value=selectedBatchIds.value.filter(id=>id!==row.id)
    selectedMetricIds.value=[]
    refresh.value++
    showSuccessToast('批次及对应指标已删除')
  }catch(e){showFailToast(errorText(e))}
  finally{deleteBusy.value=false}
}
function chooseImport(){fileInput.value?.click()}
async function selectFile(event){
  const file=event.target.files?.[0]
  event.target.value=''
  if(!file)return
  if(!/\.xlsx?$/i.test(file.name)){showFailToast('请选择 .xlsx 或 .xls 文件');return}
  if(file.size>5*1024*1024){showFailToast('Excel 文件不能超过 5 MB');return}
  selectedFile.value=file
  preview.value=null
  importError.value=''
  importBusy.value=true
  importOpen.value=true
  try{
    const body=new FormData()
    body.append('file',file)
    preview.value=(await props.api.post('/admin/metrics/preview',body)).data
  }catch(e){
    importError.value=errorText(e)
  }finally{
    importBusy.value=false
  }
}
function closeImport(){
  if(importBusy.value)return
  importOpen.value=false
  selectedFile.value=null
  preview.value=null
  importError.value=''
}
async function confirmImport(){
  if(!selectedFile.value||!preview.value||importBusy.value)return
  importBusy.value=true
  importError.value=''
  try{
    const body=new FormData()
    body.append('file',selectedFile.value)
    const {data}=await props.api.post('/admin/metrics/import',body)
    importOpen.value=false
    selectedFile.value=null
    preview.value=null
    refresh.value++
    showSuccessToast(data.message||'指标已导入')
  }catch(e){
    importError.value=errorText(e)
  }finally{
    importBusy.value=false
  }
}
async function download(url,filename){
  try{
    const response=await props.api.get(url,{responseType:'blob'})
    const href=URL.createObjectURL(response.data)
    const a=document.createElement('a')
    a.href=href
    a.download=filename
    document.body.appendChild(a)
    a.click()
    a.remove()
    setTimeout(()=>URL.revokeObjectURL(href),2000)
  }catch(e){
    showFailToast(errorText(e))
  }
}
async function openAutoParse(){
  autoParseOpen.value=true
  autoParseError.value=''
  parsedPreview.value=null
  autoParseBusy.value=true
  try{
    const {data}=await props.api.get('/admin/table-permissions/sources')
    parseSources.value=Array.isArray(data)?data:[]
    parseSourceId.value=parseSources.value.find(s=>s.active)?.id||parseSources.value[0]?.id||''
  }catch(e){autoParseError.value=errorText(e)}
  finally{autoParseBusy.value=false}
  if(parseSourceId.value&&!autoParseError.value)await loadAutoParse()
}
async function loadAutoParse(){
  autoParseError.value=''
  parsedPreview.value=null
  if(!parseSourceId.value)return
  autoParseBusy.value=true
  try{
    const {data}=await props.api.get('/admin/table-permissions/auto-parse',
      {params:{sourceId:parseSourceId.value}})
    parsedPreview.value=data
  }catch(e){autoParseError.value=errorText(e)}
  finally{autoParseBusy.value=false}
}
function closeAutoParse(){if(!autoParseSaving.value)autoParseOpen.value=false}
async function confirmAutoParse(){
  if(autoParseBusy.value||autoParseSaving.value||!parsedPreview.value?.count)return
  autoParseError.value=''
  autoParseSaving.value=true
  try{
    const {data}=await props.api.post('/admin/table-permissions/auto-parse/confirm',
      {sourceId:parseSourceId.value})
    autoParseOpen.value=false
    refresh.value++
    showSuccessToast(data.message||'指标已生成')
  }catch(e){autoParseError.value=errorText(e)}
  finally{autoParseSaving.value=false}
}
const parseSourceActive=()=>parseSources.value.some(s=>s.id===parseSourceId.value&&s.active)
const exportMetrics=()=>download('/admin/metrics/export','DataAsk-指标口径.xlsx')
const downloadTemplate=()=>download('/admin/metrics/template','DataAsk-指标口径模板.xlsx')
</script>

<template>
  <div class="tb-admin-panel-card tb-admin-metrics-page">
    <input ref="fileInput" class="file-input-hidden" type="file" accept=".xlsx,.xls"
      aria-label="选择指标口径Excel文件" @change="selectFile">

    <div class="da-metric-list-tabs" role="tablist" aria-label="指标管理视图">
      <button type="button" role="tab" :aria-selected="activeList==='metrics'" :class="{active:activeList==='metrics'}"
        @click="activeList='metrics'">指标列表</button>
      <button type="button" role="tab" :aria-selected="activeList==='batches'" :class="{active:activeList==='batches'}"
        @click="activeList='batches'">导入批次</button>
    </div>
    <RecordList v-if="activeList==='metrics'" :api="api" endpoint="/admin/metrics/page" label="指标口径" :refresh="refresh"
      selectable v-model:selected="selectedMetricIds"
      :columns="[
        {key:'metricName',label:'指标名称'},
        {key:'definition',label:'业务口径'},
        {key:'sourceName',label:'数据源'},
        {key:'sourceTables',label:'关联来源表'}
      ]">
      <template #toolbar>
        <button type="button" class="td-btn td-btn--primary" @click="createMetric">
          <van-icon name="plus"/> 新增指标
        </button>
        <button type="button" class="td-btn" @click="chooseImport">
          <van-icon name="upgrade"/> 导入
        </button>
        <button type="button" class="td-btn" @click="exportMetrics">
          <van-icon name="down"/> 导出
        </button>
        <button type="button" class="td-btn" @click="openAutoParse">
          <van-icon name="cluster-o"/> 一键解析
        </button>
        <button type="button" class="td-btn da-metric-bulk-delete"
          :disabled="deleteBusy||!selectedMetricIds.length" @click="deleteSelectedMetrics">
          <van-icon name="delete-o"/> 批量删除（{{ selectedMetricIds.length }}）
        </button>
      </template>
      <template #sourceTables="{row}">
        <span class="da-metric-table-summary" :title="row.sourceTables||row.adsTable||''">
          {{ (row.sourceTables||row.adsTable||'—').replace(/[，,;；\n\r]+/g,'、') }}
        </span>
      </template>
      <template #actions="{row}">
        <button type="button" class="td-row-action" @click="editMetric(row)">编辑</button>
        <button type="button" class="td-row-action danger" @click="removeMetric(row)">删除</button>
      </template>
    </RecordList>

    <RecordList v-else :api="api" endpoint="/admin/metrics/batches/page" label="指标导入批次" :refresh="refresh"
      selectable v-model:selected="selectedBatchIds"
      :columns="[
        {key:'filename',label:'文件/批次'},
        {key:'count',label:'指标数量'},
        {key:'uploadedBy',label:'创建人'},
        {key:'createdAt',label:'创建时间'}
      ]">
      <template #toolbar>
        <button type="button" class="td-btn da-metric-bulk-delete"
          :disabled="deleteBusy||!selectedBatchIds.length" @click="deleteSelectedBatches">
          <van-icon name="delete-o"/> 批量删除（{{ selectedBatchIds.length }}）
        </button>
      </template>
      <template #actions="{row}">
        <button type="button" class="td-row-action danger" :disabled="deleteBusy"
          @click="removeBatch(row)">删除</button>
      </template>
    </RecordList>

    <van-popup :show="editorOpen" round :close-on-click-overlay="false"
      class="da-crud-modal da-metric-editor" @keydown.esc="closeEditor">
      <form @submit.prevent="saveMetric">
        <header>
          <h2>{{ editorMode==='create'?'新增指标':'编辑指标' }}</h2>
          <button type="button" aria-label="关闭指标弹窗" :disabled="editorBusy" @click="closeEditor">×</button>
        </header>
        <div class="da-crud-fields da-metric-fields">
          <label>指标名称<input v-model.trim="form.metricName" maxlength="150" required placeholder="例如 营业额"></label>
          <label>指标别名<input v-model.trim="form.aliases" maxlength="500" placeholder="例如 收入，营收"></label>
          <label class="wide">业务口径<textarea v-model.trim="form.definition" maxlength="5000" required rows="3"
            placeholder="例如：已支付金额减去成功退款金额；按支付完成日期统计，剔除测试订单"></textarea></label>
          <label class="wide">来源表（可多张）<textarea v-model.trim="form.sourceTables" maxlength="4000" rows="2"
            aria-label="来源表（可多张）" placeholder="每行一张表，例如：&#10;ads.ads_order_di&#10;ads.ads_refund_di"></textarea></label>
          <label class="wide">参与字段（可多个）<textarea v-model.trim="form.sourceFields" maxlength="4000" rows="2"
            aria-label="参与字段（可多个）" placeholder="例如：order_id, paid_amount, refund_amount"></textarea></label>
          <label class="wide">计算表达式（可选）<textarea v-model.trim="form.calculationExpression" maxlength="8000" rows="3"
            aria-label="计算表达式" placeholder="例如：SUM(paid_amount) - SUM(refund_amount)"></textarea></label>
          <label class="wide">表关联规则（可选）<textarea v-model.trim="form.joinRules" maxlength="5000" rows="2"
            aria-label="表关联规则" placeholder="例如：订单与退款表通过 order_id 关联；同一订单多次退款先汇总"></textarea></label>
          <label>时间字段<input v-model.trim="form.timeField" maxlength="128" placeholder="例如 pay_dt"></label>
          <label>分析维度<input v-model.trim="form.dimensions" maxlength="500" placeholder="例如 city_id, channel"></label>
          <label class="wide">备注<textarea v-model.trim="form.notes" maxlength="3000" rows="3"></textarea></label>
        </div>
        <p v-if="editorError" class="metric-error" role="alert">{{ editorError }}</p>
        <footer>
          <span/>
          <button type="button" class="td-btn" :disabled="editorBusy" @click="closeEditor">取消</button>
          <button type="submit" class="td-btn td-btn--primary" :disabled="editorBusy">
            {{ editorBusy?'保存中…':'保存' }}
          </button>
        </footer>
      </form>
    </van-popup>

    <van-popup :show="importOpen" round :close-on-click-overlay="false"
      class="da-crud-modal da-metric-import" @keydown.esc="closeImport">
      <form @submit.prevent="confirmImport">
        <header>
          <h2>导入指标口径</h2>
          <button type="button" aria-label="关闭导入弹窗" :disabled="importBusy" @click="closeImport">×</button>
        </header>
        <div class="da-import-body">
          <p v-if="importBusy&&!preview" class="metric-upload-state"><van-loading size="16px"/> 正在解析 Excel…</p>
          <p v-if="importError" class="metric-error" role="alert">{{ importError }}</p>
          <template v-if="preview">
            <div class="metric-preview-heading">
              <b>{{ preview.filename }}</b><span>识别到 {{ preview.count }} 条</span>
            </div>
            <div class="metric-preview-table">
              <table>
                <thead><tr><th>序号</th><th>指标名称</th><th>业务口径</th><th>关联来源表</th></tr></thead>
                <tbody>
                  <tr v-for="(item,index) in preview.samples" :key="index">
                    <td>{{ index+1 }}</td><td>{{ item.metricName }}</td><td>{{ item.definition }}</td><td>{{ item.sourceTables||item.adsTable||'—' }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </template>
        </div>
        <footer>
          <button type="button" class="td-btn" @click="downloadTemplate">下载模板</button>
          <span/>
          <button type="button" class="td-btn" :disabled="importBusy" @click="closeImport">取消</button>
          <button type="submit" class="td-btn td-btn--primary" :disabled="importBusy||!preview">
            {{ importBusy?'导入中…':'确认导入' }}
          </button>
        </footer>
      </form>
    </van-popup>
    <van-popup :show="autoParseOpen" round :close-on-click-overlay="false"
      class="da-crud-modal da-metric-auto-parse" @keydown.esc="closeAutoParse">
      <div>
        <header>
          <h2>一键解析授权表</h2>
          <button type="button" aria-label="关闭解析弹窗" :disabled="autoParseSaving" @click="closeAutoParse">×</button>
        </header>
        <div class="da-auto-body">
          <label class="da-auto-source">
            <span>数据源</span>
            <select v-model="parseSourceId" aria-label="选择解析数据源" :disabled="autoParseBusy" @change="loadAutoParse">
              <option v-for="source in parseSources" :key="source.id" :value="source.id">
                {{ source.name }}（{{ source.schema }}）{{ source.active?' · 当前启用':'' }}
              </option>
            </select>
          </label>
          <p v-if="autoParseBusy" class="da-auto-loading"><van-loading size="16px"/> 正在读取表字段…</p>
          <p v-if="autoParseError" class="metric-error" role="alert">{{ autoParseError }}</p>
          <template v-if="parsedPreview">
            <div class="da-auto-summary">发现 {{ parsedPreview.count }} 个数值字段，
              {{ parsedPreview.withoutPrimaryKey }} 张表无主键</div>
            <p v-if="!parseSourceActive()" class="da-auto-warning">
              可为该数据源生成指标草稿；只有启用这个数据源后，这些口径才会参与问数。
            </p>
            <p v-for="notice in parsedPreview.notices.slice(0,8)" :key="notice" class="da-auto-note">
              {{ notice }}
            </p>
            <div class="da-auto-table">
              <table>
                <thead><tr><th>来源表</th><th>指标字段</th><th>指标名称</th><th>分析维度（主键）</th></tr></thead>
                <tbody>
                  <tr v-for="item in parsedPreview.metrics" :key="item.adsTable+'.'+item.adsField">
                    <td>{{ item.adsTable }}</td><td>{{ item.adsField }}</td>
                    <td>{{ item.metricName }}</td><td>{{ item.dimensions||'待设置' }}</td>
                  </tr>
                  <tr v-if="!parsedPreview.metrics.length"><td colspan="4">暂无可解析的数值字段</td></tr>
                </tbody>
              </table>
            </div>
          </template>
        </div>
        <footer>
          <span class="da-auto-footer-hint">生成的是待确认草稿，请核实 SUM、单位与去重口径</span>
          <button type="button" class="td-btn" :disabled="autoParseSaving" @click="closeAutoParse">取消</button>
          <button type="button" class="td-btn td-btn--primary"
            :disabled="autoParseBusy||autoParseSaving||!parsedPreview?.count"
            @click="confirmAutoParse">{{ autoParseSaving?'生成中…':'确认生成指标' }}</button>
        </footer>
      </div>
    </van-popup>

  </div>
</template>

<style scoped>
.da-metric-list-tabs{display:flex;align-items:center;gap:18px;border-bottom:1px solid var(--da-border);margin:0 0 14px}
.da-metric-list-tabs button{background:none;border:0;border-bottom:2px solid transparent;padding:10px 4px;color:var(--da-text-2);font-size:13px;cursor:pointer}
.da-metric-list-tabs button.active{color:var(--da-primary);border-bottom-color:var(--da-primary);font-weight:600}
.da-metric-bulk-delete{color:var(--da-danger)}
.da-metric-bulk-delete:disabled{opacity:.45}
.da-metric-editor{width:min(860px,calc(100vw - 48px))}
.da-metric-fields{max-height:min(66vh,640px);overflow:auto}
.da-metric-import{width:min(900px,calc(100vw - 48px))}
.da-metric-auto-parse{width:min(1020px,calc(100vw - 48px))}
.da-auto-body{padding:18px 20px;max-height:62vh;overflow:auto}
.da-auto-source{display:flex;align-items:center;gap:12px}
.da-auto-source span{color:var(--da-text-2);font-size:12px}
.da-auto-source select{min-width:300px;height:36px;padding:0 12px;border:1px solid var(--da-border);border-radius:7px;background:var(--da-surface);color:var(--da-text);font-size:13px}
.da-auto-loading{display:flex;align-items:center;gap:8px;color:var(--da-text-3)}
.da-auto-summary{margin-top:16px;font-size:13px;color:var(--da-text-2)}
.da-auto-warning{font-size:12px;color:var(--da-danger)}
.da-auto-note{margin:6px 0;font-size:12px;color:var(--da-text-3)}
.da-auto-table{margin-top:14px;max-height:330px;overflow:auto;border:1px solid var(--da-border);border-radius:7px}
.da-auto-table table{width:100%;border-collapse:collapse;font-size:12px}
.da-auto-table th,.da-auto-table td{padding:10px;border-bottom:1px solid var(--da-border);text-align:left;color:var(--da-text-2)}
.da-auto-table th{position:sticky;top:0;background:var(--da-surface-2);color:var(--da-text-3)}
.da-auto-footer-hint{margin-right:auto;font-size:11px;color:var(--da-text-3)}

.da-metric-fields{display:grid!important;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px 18px}
.da-metric-fields label{min-width:0}
.da-metric-fields .wide{grid-column:1/-1}
.da-metric-fields textarea{width:100%;resize:vertical;min-height:88px;padding:9px 10px;border:1px solid var(--da-border);border-radius:6px;background:var(--da-surface);color:var(--da-text);font:inherit}
.da-import-body{padding:18px 20px;max-height:65vh;overflow:auto}
.metric-preview-heading{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:12px}
.metric-preview-heading span{color:var(--da-primary);font-size:12px}
.da-metric-table-summary{display:block;max-width:240px;overflow:hidden;white-space:nowrap;text-overflow:ellipsis;color:var(--da-text-2)}
.td-row-action{height:28px;padding:0 8px;border:1px solid var(--da-border);border-radius:5px;background:var(--da-surface);color:var(--da-text-2);font-size:12px}
.td-row-action.danger{color:var(--da-danger)}
@media(max-width:900px){
  .da-metric-fields{grid-template-columns:1fr}
  .da-metric-fields .wide{grid-column:auto}
}
</style>

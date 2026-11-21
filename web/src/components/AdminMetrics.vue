<script setup>
import {ref} from 'vue'
import {showConfirmDialog,showFailToast,showSuccessToast} from 'vant'
import RecordList from './RecordList.vue'

const props=defineProps({api:{type:Object,required:true}})
const refresh=ref(0)
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

const empty=()=>({
  id:null,metricName:'',aliases:'',definition:'',adsTable:'',adsField:'',timeField:'',dimensions:'',notes:''
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
  form.value={...empty(),...row}
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
    refresh.value++
    showSuccessToast('指标已删除')
  }catch(e){
    showFailToast(errorText(e))
  }
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
const exportMetrics=()=>download('/admin/metrics/export','DataAsk-指标口径.xlsx')
const downloadTemplate=()=>download('/admin/metrics/template','DataAsk-指标口径模板.xlsx')
</script>

<template>
  <div class="tb-admin-panel-card tb-admin-metrics-page">
    <input ref="fileInput" class="file-input-hidden" type="file" accept=".xlsx,.xls"
      aria-label="选择指标口径Excel文件" @change="selectFile">

    <RecordList :api="api" endpoint="/admin/metrics/page" label="指标口径" :refresh="refresh"
      :columns="[
        {key:'metricName',label:'指标名称'},
        {key:'definition',label:'业务口径'},
        {key:'adsTable',label:'ADS 表'},
        {key:'adsField',label:'指标字段'}
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
      </template>
      <template #actions="{row}">
        <button type="button" class="td-row-action" @click="editMetric(row)">编辑</button>
        <button type="button" class="td-row-action danger" @click="removeMetric(row)">删除</button>
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
          <label class="wide">指标口径<textarea v-model.trim="form.definition" maxlength="5000" required rows="4" placeholder="填写计算口径"></textarea></label>
          <label>ADS 表<input v-model.trim="form.adsTable" maxlength="255" placeholder="ads.xxx"></label>
          <label>指标字段<input v-model.trim="form.adsField" maxlength="255" placeholder="amount"></label>
          <label>时间字段<input v-model.trim="form.timeField" maxlength="128" placeholder="dt"></label>
          <label>分析维度<input v-model.trim="form.dimensions" maxlength="500" placeholder="city, channel"></label>
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
                <thead><tr><th>序号</th><th>指标名称</th><th>业务口径</th><th>ADS 表</th></tr></thead>
                <tbody>
                  <tr v-for="(item,index) in preview.samples" :key="index">
                    <td>{{ index+1 }}</td><td>{{ item.metricName }}</td><td>{{ item.definition }}</td><td>{{ item.adsTable||'—' }}</td>
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
  </div>
</template>

<style scoped>
.da-metric-editor{width:min(820px,calc(100vw - 48px))}
.da-metric-import{width:min(900px,calc(100vw - 48px))}
.da-metric-fields{display:grid!important;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px 18px}
.da-metric-fields label{min-width:0}
.da-metric-fields .wide{grid-column:1/-1}
.da-metric-fields textarea{width:100%;resize:vertical;min-height:88px;padding:9px 10px;border:1px solid var(--da-border);border-radius:6px;background:var(--da-surface);color:var(--da-text);font:inherit}
.da-import-body{padding:18px 20px;max-height:65vh;overflow:auto}
.metric-preview-heading{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:12px}
.metric-preview-heading span{color:var(--da-primary);font-size:12px}
.td-row-action{height:28px;padding:0 8px;border:1px solid var(--da-border);border-radius:5px;background:var(--da-surface);color:var(--da-text-2);font-size:12px}
.td-row-action.danger{color:var(--da-danger)}
@media(max-width:900px){
  .da-metric-fields{grid-template-columns:1fr}
  .da-metric-fields .wide{grid-column:auto}
}
</style>

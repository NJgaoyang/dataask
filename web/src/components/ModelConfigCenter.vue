<script setup>
import { ref,onMounted,onBeforeUnmount } from 'vue'
import { showConfirmDialog,showSuccessToast,showFailToast } from 'vant'

const props=defineProps({api:{type:Object,required:true}})
const emit=defineEmits(['status-changed'])
const loading=ref(true),error=ref(''),settings=ref({}),status=ref({}),semantic=ref({})
const ai=ref({baseUrl:'',model:'',apiKey:'',clearKey:false})
const embedding=ref({enabled:false,baseUrl:'https://dashscope.aliyuncs.com/api/v1',
  model:'qwen3.7-text-embedding-flash',dimensions:1024,apiKey:'',clearKey:false})
const progress=ref(''),feedback=ref({ai:'',embedding:'',semantic:''})
let alive=true
onBeforeUnmount(()=>{alive=false})
const errorText=e=>e.response?.data?.message || e.response?.data?.detail || e.message || '操作失败'
const locked=()=>!!progress.value || loading.value
function hydrate(){
  const c=settings.value.ai||{},e=settings.value.embedding||{}
  ai.value={baseUrl:c.baseUrl||'',model:c.model||'',apiKey:'',clearKey:false}
  embedding.value={enabled:!!e.enabled,baseUrl:e.baseUrl||'https://dashscope.aliyuncs.com/api/v1',
    model:e.model||'qwen3.7-text-embedding-flash',dimensions:e.dimensions||1024,apiKey:'',clearKey:false}
}
async function refresh(hydrateForms=false){
  const [s,t,m]=await Promise.all([
    props.api.get('/admin/status'),props.api.get('/admin/settings'),
    props.api.get('/admin/semantic/status').catch(()=>({data:{count:0,indexedModel:'',unavailable:true}}))
  ])
  if(!alive)return
  status.value=s.data||{}
  settings.value=t.data||{}
  semantic.value=m.data||{}
  if(hydrateForms)hydrate()
}
async function load(){
  loading.value=true;error.value=''
  try{await refresh(true)}
  catch(e){if(alive)error.value='读取模型配置失败：'+errorText(e)}
  finally{if(alive)loading.value=false}
}
async function act(kind,operation){
  if(locked())return
  const form=kind==='ai'?ai.value:embedding.value
  const label=kind==='ai'?'对话模型':'Embedding 模型'
  if(operation==='test' && form.clearKey){
    feedback.value[kind]='已开启清除原有 API Key，请先关闭开关再测试连接'
    showFailToast(feedback.value[kind])
    return
  }
  if(operation==='save' && form.clearKey){
    try{await showConfirmDialog({title:'清除 '+label+' API Key',
      message:'清除已保存的 API Key 后，该模型的远程请求可能无法通过身份认证。确定清除吗？',
      confirmButtonText:'确认清除',cancelButtonText:'保留密钥'})}
    catch{return}
  }
  if(!alive || locked())return
  progress.value=kind+'-'+operation
  feedback.value[kind]=''
  try{
    const url='/admin/settings/'+kind+(operation==='test'?'/test':'')
    const {data}=await props.api.post(url,{...form},operation==='test'?{timeout:45000}:{})
    if(!alive)return
    feedback.value[kind]=data.message||(operation==='test'?'连接测试成功':'模型配置已保存')
    if(operation==='save'){
      form.apiKey=''
      form.clearKey=false
      try{await refresh(false)}
      catch(e){feedback.value[kind]+='；状态刷新失败，可点击“刷新配置”重试'}
      emit('status-changed')
    }
    showSuccessToast(feedback.value[kind])
  }catch(e){
    if(!alive)return
    feedback.value[kind]=errorText(e)
    showFailToast(feedback.value[kind])
  }finally{if(alive)progress.value=''}
}
async function rebuild(){
  if(locked())return
  progress.value='semantic'
  feedback.value.semantic=''
  try{
    const {data}=await props.api.post('/admin/semantic/rebuild',{}, {timeout:180000})
    if(!alive)return
    feedback.value.semantic=data.message||'语义索引重建完成'
    try{semantic.value=(await props.api.get('/admin/semantic/status')).data}
    catch(e){feedback.value.semantic+='；索引状态暂时无法刷新'}
    showSuccessToast(feedback.value.semantic)
  }catch(e){
    if(!alive)return
    feedback.value.semantic=errorText(e)
    showFailToast(feedback.value.semantic)
  }finally{if(alive)progress.value=''}
}
onMounted(load)
</script>

<template>
  <section class="da-model-center" aria-label="模型统一配置">
    <div class="da-model-center-heading">
      <div><h2>模型配置</h2><p>统一管理智能问数与语义检索模型，配置分别保存，密钥不会明文回显。</p></div>
      <button type="button" class="td-btn" :disabled="locked()" @click="load"><van-icon name="replay"/> 刷新配置</button>
    </div>
    <p v-if="loading" class="da-model-center-loading"><van-loading size="17px"/> 正在读取模型配置…</p>
    <div v-if="error" class="metric-error" role="alert">{{ error }} <button type="button" class="td-btn" @click="load">重试</button></div>
    <template v-if="!loading && !error">
      <article class="da-model-setting-card" aria-labelledby="ai-settings-title">
        <header class="da-model-setting-header">
          <div><h3 id="ai-settings-title"><van-icon name="chat-o"/> 对话模型</h3>
            <p>用于理解经营问题、生成 SQL 与解释查询结果。</p></div>
          <span class="da-model-setting-state" :class="{ok:status.aiConfigured}">{{ status.aiConfigured?'已配置':'待配置' }}</span>
        </header>
        <div class="da-model-fields">
          <van-field v-model.trim="ai.baseUrl" label="Base URL" placeholder="https://dashscope.aliyuncs.com/compatible-mode/v1"
            :disabled="locked()"/>
          <van-field v-model.trim="ai.model" label="模型名称" placeholder="例如 qwen-plus" :disabled="locked()"/>
          <van-field v-model="ai.apiKey" label="API Key" type="password" autocomplete="new-password"
            :placeholder="settings.ai?.apiKeySet?'已保存，留空不修改':'输入 API Key'" :disabled="locked()"/>
          <van-cell title="清除原有 API Key" label="仅本地免密模型需要，操作保存前会再次确认">
            <template #right-icon><van-switch v-model="ai.clearKey" size="20px" :disabled="locked()"/></template>
          </van-cell>
        </div>
        <p v-if="settings.ai?.apiKeyUnreadable" class="da-model-warning"><van-icon name="warning-o"/> 已保存密钥无法解密，请重新填写有效 API Key。</p>
        <p v-if="ai.clearKey" class="da-model-warning">保存后将删除该模型已保存的 API Key。</p>
        <p v-if="feedback.ai" class="da-model-feedback" role="status">{{ feedback.ai }}</p>
        <footer class="da-model-actions">
          <button type="button" class="td-btn" :disabled="locked()" @click="act('ai','test')">
            <van-loading v-if="progress==='ai-test'" size="14px"/><van-icon v-else name="exchange"/> 测试连接
          </button>
          <button type="button" class="td-btn td-btn--primary" :disabled="locked()" @click="act('ai','save')">
            <van-loading v-if="progress==='ai-save'" size="14px"/><van-icon v-else name="success"/> 保存对话模型
          </button>
        </footer>
      </article>

      <article class="da-model-setting-card" aria-labelledby="embedding-settings-title">
        <header class="da-model-setting-header">
          <div><h3 id="embedding-settings-title"><van-icon name="cluster-o"/> Embedding 模型</h3>
            <p>用于 ADS 表字段、中文别名与指标口径的语义匹配。</p></div>
          <span class="da-model-setting-state" :class="{ok:status.embeddingConfigured}">{{ status.embeddingConfigured?'已配置':'未启用 / 待配置' }}</span>
        </header>
        <div class="da-model-fields">
          <van-cell title="启用 Embedding" label="关闭后不使用向量语义增强">
            <template #right-icon><van-switch v-model="embedding.enabled" size="20px" :disabled="locked()"/></template>
          </van-cell>
          <van-field v-model.trim="embedding.baseUrl" label="Base URL"
            placeholder="https://dashscope.aliyuncs.com/api/v1" :disabled="locked()"/>
          <van-field v-model.trim="embedding.model" label="模型名称"
            placeholder="qwen3.7-text-embedding-flash" :disabled="locked()"/>
          <van-field v-model.number="embedding.dimensions" label="向量维度"
            type="number" placeholder="1024" :disabled="locked()"/>
          <van-field v-model="embedding.apiKey" label="API Key" type="password"
            autocomplete="new-password" :placeholder="settings.embedding?.apiKeySet?'已保存，留空不修改':'本地免密模型可留空'"
            :disabled="locked()"/>
          <van-cell title="清除原有 API Key" label="保存前会再次确认">
            <template #right-icon><van-switch v-model="embedding.clearKey" size="20px" :disabled="locked()"/></template>
          </van-cell>
        </div>
        <p v-if="settings.embedding?.apiKeyUnreadable" class="da-model-warning"><van-icon name="warning-o"/> 已保存的 Embedding 密钥无法解密，请重新设置。</p>
        <p v-if="embedding.clearKey" class="da-model-warning">保存后将删除 Embedding 已保存的 API Key。</p>
        <div class="da-model-index">
          <div><strong>语义索引</strong><small>{{ semantic.unavailable?'状态暂不可用':(semantic.count||0)+' 条 · '+(semantic.indexedModel||'尚未构建') }}</small></div>
          <button type="button" class="td-btn" :disabled="locked() || !status.embeddingConfigured" @click="rebuild">
            <van-loading v-if="progress==='semantic'" size="14px"/><van-icon v-else name="replay"/> 重建语义索引
          </button>
        </div>
        <p v-if="feedback.embedding" class="da-model-feedback" role="status">{{ feedback.embedding }}</p>
        <p v-if="feedback.semantic" class="da-model-feedback" role="status">{{ feedback.semantic }}</p>
        <footer class="da-model-actions">
          <button type="button" class="td-btn" :disabled="locked()" @click="act('embedding','test')">
            <van-loading v-if="progress==='embedding-test'" size="14px"/><van-icon v-else name="exchange"/> 测试连接
          </button>
          <button type="button" class="td-btn td-btn--primary" :disabled="locked()" @click="act('embedding','save')">
            <van-loading v-if="progress==='embedding-save'" size="14px"/><van-icon v-else name="success"/> 保存 Embedding
          </button>
        </footer>
      </article>
    </template>
  </section>
</template>

<style scoped>
.da-model-center{min-width:0;color:var(--da-text,#1f2329)}
.da-model-center-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:16px;margin-bottom:16px}
.da-model-center-heading h2{margin:0;font-size:17px;font-weight:600}
.da-model-center-heading p{margin:6px 0 0;color:var(--da-text-3,#86909c);font-size:13px;line-height:1.6}
.da-model-center-heading button{flex-shrink:0}
.da-model-center-loading{display:flex;align-items:center;gap:8px;padding:22px;color:var(--da-text-3,#86909c)}
.da-model-setting-card{border:1px solid var(--da-border,#e6e8eb);border-radius:6px;background:var(--da-surface,#fff);margin:0 0 16px;overflow:hidden}
.da-model-setting-header{display:flex;align-items:flex-start;justify-content:space-between;gap:10px;padding:16px 20px;border-bottom:1px solid var(--da-border,#e6e8eb)}
.da-model-setting-header h3{display:flex;align-items:center;gap:8px;margin:0;font-size:15px;font-weight:600}
.da-model-setting-header h3 .van-icon{color:var(--da-primary,#0052d9);font-size:17px}
.da-model-setting-header p{margin:5px 0 0;font-size:12px;color:var(--da-text-3,#86909c)}
.da-model-setting-state{flex-shrink:0;padding:4px 8px;border-radius:4px;background:var(--da-surface-2,#f5f7fa);font-size:12px;color:var(--da-text-2,#4e5969)}
.da-model-setting-state.ok{color:#137b48;background:var(--da-success-soft,#e8f8ef)}
.da-model-fields{padding:8px 20px 4px}
.da-model-fields :deep(.van-cell){background:transparent;padding:10px 0;border-bottom:1px solid var(--da-border,#e6e8eb)}
.da-model-fields :deep(.van-cell)::after{border-color:transparent}
.da-model-fields :deep(.van-field__label){width:120px;flex:none;font-size:13px;color:var(--da-text-2,#4e5969)}
.da-model-fields :deep(.van-field__control){color:var(--da-text,#1f2329);font-size:13px}
.da-model-fields :deep(.van-cell__title){color:var(--da-text,#1f2329)}
.da-model-fields :deep(.van-cell__label){font-size:12px;color:var(--da-text-3,#86909c)}
.da-model-warning{margin:10px 20px;padding:9px 10px;border:1px solid #f4d9a9;border-radius:4px;background:#fff8ed;color:#98610a;font-size:12px}
.da-model-feedback{margin:10px 20px;color:var(--da-text-2,#4e5969);font-size:12px;overflow-wrap:anywhere}
.da-model-actions{display:flex;justify-content:flex-end;flex-wrap:wrap;gap:8px;padding:14px 20px;border-top:1px solid var(--da-border,#e6e8eb)}
.da-model-center .td-btn{display:inline-flex;align-items:center;justify-content:center;gap:6px;min-height:32px;padding:0 12px;border:1px solid var(--da-border-strong,#c9cdd4);border-radius:4px;background:var(--da-surface,#fff);color:var(--da-text-2,#4e5969);font-size:13px;cursor:pointer}
.da-model-center .td-btn--primary{background:#0052d9;color:white;border-color:#0052d9}
.da-model-center .td-btn--primary:hover:not(:disabled){background:#003cab;border-color:#003cab}
.da-model-center .td-btn:disabled{opacity:.48;cursor:not-allowed}
.da-model-index{display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:12px;padding:14px 20px;border-top:1px solid var(--da-border,#e6e8eb)}
.da-model-index strong{display:block;font-size:13px}
.da-model-index small{display:block;margin-top:4px;color:var(--da-text-3,#86909c);font-size:12px}
@media(max-width:767px){
  .da-model-center-heading{flex-direction:column;align-items:stretch}
  .da-model-setting-header{padding:14px}
  .da-model-fields{padding:6px 14px}
  .da-model-fields :deep(.van-field__label){width:92px}
  .da-model-actions{padding:12px 14px}
  .da-model-actions .td-btn{flex:1;min-height:40px}
  .da-model-index{padding:14px}
}
</style>

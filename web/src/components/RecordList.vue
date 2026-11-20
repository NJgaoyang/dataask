<script setup>
import { computed,ref,watch,onBeforeUnmount } from 'vue'
import ListPagination from './ListPagination.vue'
const props=defineProps({api:{type:Object,required:true},endpoint:{type:String,required:true},params:{type:Object,default:()=>({})},columns:{type:Array,required:true},label:{type:String,default:'数据列表'},refresh:{type:Number,default:0},searchable:{type:Boolean,default:true},selectable:{type:Boolean,default:false},selected:{type:Array,default:()=>[]}})
const emit=defineEmits(['update:selected'])
const page=ref(1),size=ref(10),total=ref(0),rows=ref([]),loading=ref(false),error=ref(''),keyword=ref(''),search=ref('')
const pageIds=computed(()=>rows.value.map(row=>row.id))
const allOnPage=computed(()=>pageIds.value.length>0&&pageIds.value.every(id=>props.selected.includes(id)))
const someOnPage=computed(()=>pageIds.value.some(id=>props.selected.includes(id))&&!allOnPage.value)
function toggleRow(row){
  emit('update:selected',props.selected.includes(row.id)?props.selected.filter(id=>id!==row.id):[...props.selected,row.id])
}
function togglePage(){
  emit('update:selected',allOnPage.value?props.selected.filter(id=>!pageIds.value.includes(id)):[...new Set([...props.selected,...pageIds.value])])
}
let alive=true,sequence=0
onBeforeUnmount(()=>{alive=false;sequence++})
const offset=computed(()=>(page.value-1)*size.value)
function display(row,col) {
  const value=row[col.key]
  if (value==null || value==='') return '—'
  if (col.key.endsWith('At')) return String(value).replace('T',' ').replace(/\.\d+(?:Z|[+-]\d\d:\d\d)?$/,'').replace(/Z$/,'')
  return value
}
async function load() {
  const ticket=++sequence
  loading.value=true;error.value=''
  try {
    const {data}=await props.api.get(props.endpoint,{params:{keyword:search.value,...props.params,page:page.value,pageSize:size.value}})
    if (!alive || ticket!==sequence) return
    rows.value=data.items || [];total.value=data.total || 0
    const last=Math.max(1,Math.ceil(total.value/size.value))
    if (page.value>last) page.value=last
  } catch(e) { if(alive && ticket===sequence) {rows.value=[];total.value=0;error.value=e.response?.data?.message || '加载失败，请重试'} }
  finally {if(alive && ticket===sequence) loading.value=false}
}
// getter 每次求值都返回新数组，Object.is 恒判为变化；父组件任何重渲染（如勾选行）
// 都会误触发重置页码，这里按序列化后的实际值比较，值未变化时保持当前页。
watch(()=>[props.endpoint,JSON.stringify(props.params),search.value],(next,prev)=>{if(prev&&next[0]===prev[0]&&next[1]===prev[1]&&next[2]===prev[2])return;if(page.value===1)load();else page.value=1})
watch(()=>[page.value,size.value,props.refresh],load,{immediate:true})
function submit(){const value=keyword.value.trim();if(search.value!==value)search.value=value;else if(page.value===1)load();else page.value=1}
function resetSearch(){
  keyword.value=''
  if(search.value)search.value=''
  else if(page.value!==1)page.value=1
  else load()
}
defineExpose({reload:load})
</script>
<template>
  <section class="da-record-list" :aria-label="label">
    <div class="da-list-toolbar td-filter-bar">
      <form v-if="searchable" class="da-list-search" role="search" @submit.prevent="submit">
        <label class="td-filter-caption" :for="'record-filter-'+endpoint.replaceAll('/','-')">关键词</label>
        <input :id="'record-filter-'+endpoint.replaceAll('/','-')" v-model="keyword" maxlength="180" :aria-label="'搜索'+label" placeholder="输入名称或关键词">
        <button type="submit" class="td-btn td-btn--primary" :disabled="loading"><van-icon name="search"/> 查询</button>
        <button type="button" class="td-btn" :disabled="loading || (!keyword && !search)" @click="resetSearch">重置</button>
      </form>
      <div v-if="$slots.filters" class="td-filter-extra"><slot name="filters"/></div>
      <div class="td-list-actions"><slot name="toolbar"/><button type="button" class="td-btn" :disabled="loading" @click="load"><van-icon name="replay"/> 刷新</button></div>
    </div>
    <p v-if="error" class="metric-error" role="alert">{{ error }}</p>
    <div class="da-list-table-wrap" :aria-busy="loading">
      <table class="da-list-table"><thead><tr><th v-if="selectable" class="da-check"><input type="checkbox" :checked="allOnPage" :indeterminate.prop="someOnPage" aria-label="全选本页" @change="togglePage"></th><th class="da-sequence">序号</th><th v-for="col in columns" :key="col.key">{{ col.label }}</th><th v-if="$slots.actions">操作</th></tr></thead>
        <tbody><tr v-for="(row,index) in rows" :key="row.id || row.username"><td v-if="selectable" class="da-check"><input type="checkbox" :checked="selected.includes(row.id)" :aria-label="'选择 '+row.title" @change="toggleRow(row)"></td><td class="da-sequence">{{ offset+index+1 }}</td><td v-for="col in columns" :key="col.key"><slot :name="col.key" :row="row">{{ display(row,col) }}</slot></td><td v-if="$slots.actions" class="da-row-actions"><slot name="actions" :row="row"/></td></tr>
          <tr v-if="!rows.length"><td :colspan="columns.length+($slots.actions?2:1)+(selectable?1:0)" class="da-list-empty"><van-icon name="description-o"/>{{ loading?'正在加载…':error?'加载失败':'暂无数据' }}</td></tr>
        </tbody>
      </table>
    </div>
    <ListPagination v-model:page="page" v-model:size="size" :total="total" :disabled="loading"/>
  </section>
</template>

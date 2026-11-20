<script setup>
import {ref,computed,watch} from 'vue'
import ListPagination from './ListPagination.vue'
const props=defineProps({message:{type:Object,required:true},label:{type:Function,required:true},format:{type:Function,required:true}})
const page=ref(1),size=ref(10)
const rows=computed(()=>props.message.rows || [])
const offset=computed(()=>(page.value-1)*size.value)
const visible=computed(()=>rows.value.slice(offset.value,offset.value+size.value))
watch(()=>props.message,()=>{page.value=1})
</script>
<template><div><div class="result-table-scroll" role="region" tabindex="0" aria-label="数据结果表格"><table class="result-table"><thead><tr><th class="da-sequence">序号</th><th v-for="(col,i) in message.columns" :key="col" :title="col">{{ label(message,col,i) }}</th></tr></thead><tbody><tr v-for="(row,index) in visible" :key="offset+index"><td class="da-sequence">{{ offset+index+1 }}</td><td v-for="col in message.columns" :key="col">{{ format(row[col]) }}</td></tr></tbody></table></div><ListPagination v-model:page="page" v-model:size="size" :total="rows.length"/></div></template>

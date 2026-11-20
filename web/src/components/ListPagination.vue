<script setup>
import { computed } from 'vue'
const props=defineProps({page:{type:Number,default:1},size:{type:Number,default:10},total:{type:Number,default:0},disabled:Boolean})
const emit=defineEmits(['update:page','update:size'])
const pages=computed(()=>Math.max(1,Math.ceil(props.total/props.size)))
</script>
<template>
  <nav class="da-pagination" aria-label="列表分页">
    <span>共 {{ total }} 条</span>
    <label>每页 <select :value="size" :disabled="disabled" aria-label="每页条数" @change="emit('update:size',Number($event.target.value));emit('update:page',1)"><option v-for="n in [5,10,20,50,100]" :key="n" :value="n">{{ n }} 条</option></select></label>
    <button type="button" :disabled="disabled || page<=1" @click="emit('update:page',page-1)">上一页</button>
    <span>{{ page }} / {{ pages }}</span>
    <button type="button" :disabled="disabled || page>=pages" @click="emit('update:page',page+1)">下一页</button>
  </nav>
</template>

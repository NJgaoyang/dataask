<script setup>
import {ref,onBeforeUnmount,onMounted} from 'vue'
import {showSuccessToast,showConfirmDialog} from 'vant'
import RecordList from './RecordList.vue'
const props=defineProps({api:{type:Object,required:true}})
const columns=[{key:'username',label:'账号'},{key:'displayName',label:'姓名'},{key:'role',label:'角色'},{key:'createdAt',label:'创建时间'}]
const open=ref(false),mode=ref('create'),form=ref({}),busy=ref(false),error=ref(''),refresh=ref(0)
const roles=ref([{code:'ADMIN',name:'管理员'},{code:'CITY_MANAGER',name:'城市经理'},{code:'USER',name:'普通用户'}])
async function loadRoles(){try{const {data}=await props.api.get('/admin/roles');if(Array.isArray(data)&&data.length)roles.value=data.filter(x=>x.enabled!==false)}catch{}}
function roleLabel(code){return roles.value.find(x=>x.code===code)?.name||code}
let alive=true
onBeforeUnmount(()=>{alive=false;form.value={}})
onMounted(loadRoles)
function edit(row,reset=false){mode.value=reset?'reset':row?'edit':'create';form.value={username:row?.username || '',displayName:row?.displayName || '',role:row?.role || 'USER',password:''};error.value='';open.value=true}
function close(){if(busy.value)return;open.value=false;form.value={}}
async function save(){if(busy.value)return;const f=form.value;error.value=''
  if(mode.value==='create' && !/^[A-Za-z0-9_-]{3,64}$/.test(f.username)) {error.value='账号需为3–64位字母、数字、下划线或横线';return}
  if(mode.value!=='reset' && (!f.displayName.trim() || f.displayName.trim().length>80)){error.value='姓名需为1–80字';return}
  if(mode.value!=='edit' && (f.password.length<10 || f.password.length>128)){error.value='密码需为10–128位';return}
  if(mode.value==='reset'){try{await showConfirmDialog({title:'重置密码',message:'重置“'+f.username+'”的密码后，该用户需要重新登录。'})}catch{return}}
  if(!alive)return;busy.value=true
  try{if(mode.value==='create')await props.api.post('/admin/users',{...f,displayName:f.displayName.trim()});else if(mode.value==='reset')await props.api.post('/admin/users/'+encodeURIComponent(f.username)+'/reset-password',{password:f.password});else await props.api.put('/admin/users/'+encodeURIComponent(f.username),{displayName:f.displayName.trim(),role:f.role});if(alive){open.value=false;form.value={};refresh.value++;showSuccessToast('已保存')}}catch(e){if(alive)error.value=e.response?.data?.message || '保存失败'}finally{busy.value=false}
}
</script>
<template><div class="tb-admin-panel-card tb-admin-users-page">
  <RecordList :api="api" endpoint="/admin/users/page" :columns="columns" label="用户" :refresh="refresh">
    <template #toolbar><button type="button" class="da-primary-button td-btn td-btn--primary" :disabled="busy" @click="edit()"><van-icon name="plus"/> 新增用户</button></template>
    <template #role="{row}">{{ roleLabel(row.role) }}</template>
    <template #actions="{row}"><button :disabled="busy" @click="edit(row)">编辑</button><button :disabled="busy" @click="edit(row,true)">重置密码</button></template>
  </RecordList>
  <van-popup :show="open" round :close-on-click-overlay="false" class="da-crud-modal" @keydown.esc="close"><form @submit.prevent="save"><header><h2>{{ mode==='create'?'新增用户':mode==='edit'?'编辑用户':'重置密码' }}</h2><button type="button" aria-label="关闭用户弹窗" :disabled="busy" @click="close">×</button></header><div class="da-crud-fields">
    <label>账号<input v-model.trim="form.username" :disabled="mode!=='create'" required maxlength="64" autocomplete="off"></label>
    <template v-if="mode!=='reset'"><label>姓名<input v-model.trim="form.displayName" required maxlength="80"></label><label>角色<select v-model="form.role"><option v-for="role in roles" :key="role.code" :value="role.code">{{ role.name }}</option></select></label></template>
    <label v-if="mode!=='edit'">{{ mode==='reset'?'新密码':'初始密码' }}<input v-model="form.password" type="password" required minlength="10" maxlength="128" autocomplete="new-password" placeholder="10–128 位"></label>
  </div><p v-if="error" class="metric-error" role="alert">{{ error }}</p><footer><span/><button type="button" class="td-btn" :disabled="busy" @click="close">取消</button><button type="submit" class="da-primary-button td-btn td-btn--primary" :disabled="busy">{{ busy?'保存中…':'保存' }}</button></footer></form></van-popup>
</div></template>

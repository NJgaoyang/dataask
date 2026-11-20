<script setup>
import {ref,onBeforeUnmount} from 'vue'
import {showConfirmDialog,showSuccessToast} from 'vant'
import RecordList from './RecordList.vue'
const props=defineProps({api:{type:Object,required:true}}),emit=defineEmits(['changed'])
const columns=[{key:'name',label:'名称'},{key:'active',label:'状态'},{key:'jdbcUrl',label:'连接地址'},{key:'schema',label:'数据库'},{key:'username',label:'账号'}]
const open=ref(false),busy=ref(false),error=ref(''),feedback=ref(''),refresh=ref(0),form=ref({}),editing=ref('')
let alive=true
onBeforeUnmount(()=>{alive=false;form.value={}})
function edit(row){editing.value=row?.id || '';form.value={name:row?.name || '',jdbcUrl:row?.jdbcUrl || '',schema:row?.schema || 'ads',username:row?.username || '',description:row?.description || '',password:''};error.value='';feedback.value='';open.value=true}
function close(){if(busy.value)return;open.value=false;form.value={}}
function valid(){if(!form.value.name.trim() || !form.value.jdbcUrl.trim() || !form.value.username.trim() || !/^[A-Za-z_][A-Za-z0-9_]{0,63}$/.test(form.value.schema)) {error.value='请填写名称、连接地址、账号和有效数据库名';return false}return true}
async function save(){if(busy.value || !valid())return;busy.value=true;error.value='';try{await props.api[editing.value?'put':'post']('/admin/datasources'+(editing.value?'/'+editing.value:''),form.value);if(!alive)return;open.value=false;form.value={};refresh.value++;emit('changed');showSuccessToast('数据源已保存')}catch(e){if(alive)error.value=e.response?.data?.message || '保存失败'}finally{busy.value=false}}
async function test(row){if(busy.value || (!row && !valid()))return;busy.value=true;error.value='';feedback.value='';try{const id=row?.id || editing.value;await props.api.post('/admin/datasources'+(id?'/'+id:'')+'/test',row?undefined:form.value);if(alive){feedback.value='连接成功';showSuccessToast('连接成功')}}catch(e){if(alive)error.value=e.response?.data?.message || '连接失败'}finally{busy.value=false}}
async function activate(row){if(busy.value)return;try{await showConfirmDialog({title:'启用数据源',message:'切换为“'+row.name+'”？原数据表授权将停用，需要重新授权并构建语义索引。'})}catch{return}if(!alive)return;busy.value=true;error.value='';try{await props.api.post('/admin/datasources/'+row.id+'/activate');if(alive){refresh.value++;emit('changed');showSuccessToast('已启用，请重新授权数据表')}}catch(e){if(alive)error.value=e.response?.data?.message || '启用失败'}finally{busy.value=false}}
async function remove(row){if(busy.value)return;try{await showConfirmDialog({title:'删除数据源',message:'确定删除“'+row.name+'”的连接配置？不删除业务数据库。'})}catch{return}if(!alive)return;busy.value=true;error.value='';try{await props.api.delete('/admin/datasources/'+row.id);if(alive)refresh.value++}catch(e){if(alive)error.value=e.response?.data?.message || '删除失败'}finally{busy.value=false}}
</script>
<template>
  <div class="tb-admin-panel-card tb-admin-source-page"><p v-if="error && !open" class="metric-error" role="alert">{{ error }}</p>
    <RecordList :api="api" endpoint="/admin/datasources" :columns="columns" label="数据源" :refresh="refresh">
      <template #toolbar><button type="button" class="da-primary-button" :disabled="busy" @click="edit()">新增数据源</button></template>
      <template #active="{row}"><span :class="row.active?'da-active-source':'da-idle-source'">{{ row.active?'当前启用':'未启用' }}</span></template>
      <template #actions="{row}"><button :disabled="busy" @click="test(row)">测试</button><button :disabled="busy" @click="edit(row)">编辑</button><button v-if="!row.active" :disabled="busy" @click="activate(row)">启用</button><button :disabled="busy || row.active" class="da-danger-button" @click="remove(row)">删除</button></template>
    </RecordList>
    <van-popup :show="open" round close-on-popstate :close-on-click-overlay="false" class="da-crud-modal" @keydown.esc="close">
      <form @submit.prevent="save"><header><h2>{{ editing?'编辑数据源':'新增数据源' }}</h2><button type="button" aria-label="关闭数据源弹窗" :disabled="busy" @click="close">×</button></header>
        <div class="da-crud-fields"><label>名称<input v-model.trim="form.name" required maxlength="100" autocomplete="off"></label><label>类型<select aria-label="数据源类型"><option>StarRocks</option></select></label><label>JDBC 地址<input v-model.trim="form.jdbcUrl" required maxlength="500" placeholder="jdbc:mysql://192.168.1.10:9030/ads"></label><label>数据库<input v-model.trim="form.schema" required maxlength="64" placeholder="ads"></label><label>用户名<input v-model.trim="form.username" required maxlength="120" autocomplete="off"></label><label>密码<input v-model="form.password" maxlength="500" type="password" autocomplete="new-password" :placeholder="editing?'留空保留原密码':'数据库密码'"></label><label>描述<textarea v-model="form.description" maxlength="500" rows="2"/></label></div>
        <p v-if="error" role="alert" class="metric-error">{{ error }}</p><p v-if="feedback" role="status">{{ feedback }}</p>
        <footer><button type="button" :disabled="busy" @click="test()">测试连接</button><span/><button type="button" :disabled="busy" @click="close">取消</button><button type="submit" class="da-primary-button" :disabled="busy">{{ busy?'处理中…':'保存' }}</button></footer>
      </form>
    </van-popup>
  </div>
</template>

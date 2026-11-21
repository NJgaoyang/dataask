<script setup>
import {computed,onMounted,ref} from 'vue'
import {showConfirmDialog,showSuccessToast} from 'vant'

const props=defineProps({api:{type:Object,required:true}})
const permissionOptions=[
  {code:'ASK',name:'智能问数',desc:'使用自然语言查询已授权 ADS 数据'},
  {code:'CHARTS',name:'我的图表',desc:'保存、查看和共享问数图表'},
  {code:'SYSTEM_ADMIN',name:'系统管理',desc:'进入系统管理并维护配置、用户与角色'}
]
const roles=ref([]),busy=ref(false),error=ref(''),open=ref(false),mode=ref('create')
const form=ref({code:'',name:'',permissions:[]})
const sorted=computed(()=>[...roles.value].sort((a,b)=>Number(b.builtIn)-Number(a.builtIn)||a.code.localeCompare(b.code)))
async function load(){busy.value=true;error.value='';try{const {data}=await props.api.get('/admin/roles');roles.value=Array.isArray(data)?data:[]}catch(e){error.value=e.response?.data?.message||'读取角色失败'}finally{busy.value=false}}
function createRole(){mode.value='create';form.value={code:'',name:'',permissions:['ASK','CHARTS']};error.value='';open.value=true}
function editRole(role){mode.value='edit';form.value={code:role.code,name:role.name,permissions:[...(role.permissions||[])]};error.value='';open.value=true}
function close(){if(!busy.value){open.value=false;error.value=''}}
function togglePermission(code){const set=new Set(form.value.permissions||[]);set.has(code)?set.delete(code):set.add(code);form.value.permissions=[...set]}
async function save(){
  if(busy.value)return
  const code=String(form.value.code||'').trim().toUpperCase(),name=String(form.value.name||'').trim()
  if(mode.value==='create'&&!/^[A-Z][A-Z0-9_]{2,15}$/.test(code)){error.value='角色编码需为3–16位大写字母、数字或下划线';return}
  if(!name||name.length>80){error.value='角色名称需为1–80字';return}
  busy.value=true;error.value=''
  try{
    if(mode.value==='create')await props.api.post('/admin/roles',{code,name,permissions:form.value.permissions})
    else await props.api.put('/admin/roles/'+encodeURIComponent(code),{name,permissions:form.value.permissions})
    open.value=false;showSuccessToast('角色已保存');await load()
  }catch(e){error.value=e.response?.data?.message||'保存角色失败'}finally{busy.value=false}
}
async function remove(role){
  try{await showConfirmDialog({title:'删除角色',message:'确定删除“'+role.name+'”？仅未绑定用户的自定义角色可以删除。'})}catch{return}
  busy.value=true
  try{await props.api.delete('/admin/roles/'+encodeURIComponent(role.code));showSuccessToast('角色已删除');await load()}
  catch(e){error.value=e.response?.data?.message||'删除角色失败'}finally{busy.value=false}
}
function permissionName(code){return permissionOptions.find(x=>x.code===code)?.name||code}
onMounted(load)
</script>

<template>
  <div class="tb-admin-panel-card da-role-admin">
    <div class="da-role-toolbar">
      <div><strong>角色与权限</strong><p>管理员拥有全部城市；城市经理、普通用户及自定义角色都按账号匹配城市权限。</p></div>
      <button type="button" class="td-btn td-btn--primary" :disabled="busy" @click="createRole"><van-icon name="plus"/> 新建角色</button>
    </div>
    <p v-if="error&&!open" class="metric-error" role="alert">{{ error }}</p>
    <div class="da-role-table">
      <div class="da-role-head"><span>角色</span><span>数据范围</span><span>功能权限</span><span>用户数</span><span>操作</span></div>
      <div v-for="role in sorted" :key="role.code" class="da-role-row">
        <span class="da-role-name"><b>{{ role.name }}</b><small>{{ role.code }}<em v-if="role.builtIn">内置</em></small></span>
        <span><i class="da-scope-badge" :class="{city:role.cityScoped}">{{ role.cityScoped?'按管理城市过滤':'全部城市' }}</i></span>
        <span class="da-role-permissions">
          <i v-for="p in role.permissions" :key="p">{{ permissionName(p) }}</i>
          <small v-if="!role.permissions?.length">无功能权限</small>
        </span>
        <span>{{ role.userCount }}</span>
        <span class="da-role-actions">
          <button v-if="!role.builtIn" type="button" @click="editRole(role)">编辑</button>
          <span v-else class="muted">系统内置</span>
          <button v-if="!role.builtIn" type="button" class="danger" @click="remove(role)">删除</button>
        </span>
      </div>
    </div>
    <van-popup :show="open" round :close-on-click-overlay="false" class="da-crud-modal" @keydown.esc="close">
      <form @submit.prevent="save">
        <header><h2>{{ mode==='create'?'新建角色':'编辑角色' }}</h2><button type="button" aria-label="关闭角色弹窗" :disabled="busy" @click="close">×</button></header>
        <div class="da-crud-fields">
          <label>角色编码<input v-model.trim="form.code" :disabled="mode==='edit'" maxlength="16" placeholder="例如 FINANCE"></label>
          <label>角色名称<input v-model.trim="form.name" maxlength="80" placeholder="例如 财务"></label>
          <div class="da-role-permission-editor">
            <strong>功能权限</strong>
            <label v-for="item in permissionOptions" :key="item.code" class="da-role-permission-option">
              <input type="checkbox" :checked="form.permissions.includes(item.code)" @change="togglePermission(item.code)">
              <span><b>{{ item.name }}</b><small>{{ item.desc }}</small></span>
            </label>
          </div>
          <p class="da-role-scope-note"><van-icon name="info-o"/> 数据范围固定：仅 ADMIN 为全部城市，其余角色都按用户城市权限过滤。</p>
        </div>
        <p v-if="error" class="metric-error" role="alert">{{ error }}</p>
        <footer><span/><button type="button" class="td-btn" :disabled="busy" @click="close">取消</button><button type="submit" class="td-btn td-btn--primary" :disabled="busy">{{ busy?'保存中…':'保存' }}</button></footer>
      </form>
    </van-popup>
  </div>
</template>

<script setup>
import {ref,onBeforeUnmount,onMounted} from 'vue'
import {showSuccessToast,showConfirmDialog} from 'vant'
import RecordList from './RecordList.vue'

const props=defineProps({api:{type:Object,required:true}})
const columns=[
  {key:'username',label:'账号'},
  {key:'displayName',label:'姓名'},
  {key:'role',label:'角色'},
  {key:'createdAt',label:'创建时间'}
]
const open=ref(false)
const mode=ref('create')
const form=ref({})
const busy=ref(false)
const error=ref('')
const refresh=ref(0)
const roles=ref([
  {code:'ADMIN',name:'管理员'},
  {code:'CITY_MANAGER',name:'城市经理'},
  {code:'USER',name:'普通用户'}
])
const cityState=ref({loading:false,loaded:false,available:[],selected:[]})
const cityViewOpen=ref(false)
const cityViewLoading=ref(false)
const cityViewError=ref('')
const cityView=ref({username:'',displayName:'',role:'',allCities:false,selectedCities:[]})

async function loadRoles(){
  try{
    const {data}=await props.api.get('/admin/roles')
    if(Array.isArray(data)&&data.length) roles.value=data.filter(x=>x.enabled!==false)
  }catch{}
}
function roleLabel(code){return roles.value.find(x=>x.code===code)?.name||code}
function resetCities(){cityState.value={loading:false,loaded:false,available:[],selected:[]}}
function cityScoped(){return !!form.value.role && form.value.role!=='ADMIN'}
function validCityAccount(){return /^[A-Za-z0-9_-]{3,64}$/.test(form.value.username||'')}

async function loadCityOptions(){
  if(!cityScoped()){resetCities();return}
  if(!validCityAccount()){resetCities();return}
  cityState.value={...cityState.value,loading:true}
  error.value=''
  try{
    const {data}=await props.api.get(
      '/admin/users/'+encodeURIComponent(form.value.username)+'/city-permissions',
      {params:{role:form.value.role}}
    )
    cityState.value={
      loading:false,
      loaded:true,
      available:Array.isArray(data.availableCities)?data.availableCities:[],
      selected:Array.isArray(data.selectedCities)?data.selectedCities:[]
    }
  }catch(e){
    cityState.value={loading:false,loaded:true,available:[],selected:[]}
    error.value=e.response?.data?.message||'读取城市权限失败'
  }
}
function toggleCity(city){
  const selected=new Set(cityState.value.selected)
  selected.has(city)?selected.delete(city):selected.add(city)
  cityState.value={...cityState.value,selected:[...selected]}
}
function selectAllCities(){cityState.value={...cityState.value,selected:[...cityState.value.available]}}
function clearCities(){cityState.value={...cityState.value,selected:[]}}
async function roleChanged(){error.value='';await loadCityOptions()}

async function viewCities(row){
  cityView.value={username:row.username,displayName:row.displayName||row.username,role:row.role,allCities:row.role==='ADMIN',selectedCities:[]}
  cityViewError.value=''
  cityViewLoading.value=true
  cityViewOpen.value=true
  try{
    const {data}=await props.api.get('/admin/users/'+encodeURIComponent(row.username)+'/city-permissions')
    cityView.value={...cityView.value,allCities:!!data.allCities,selectedCities:Array.isArray(data.selectedCities)?data.selectedCities:[]}
  }catch(e){
    cityViewError.value=e.response?.data?.message||'读取城市权限失败'
  }finally{
    cityViewLoading.value=false
  }
}
function closeCityView(){if(!cityViewLoading.value)cityViewOpen.value=false}

let alive=true
onBeforeUnmount(()=>{alive=false;form.value={}})
onMounted(loadRoles)

async function edit(row,reset=false){
  mode.value=reset?'reset':row?'edit':'create'
  form.value={
    username:row?.username||'',
    displayName:row?.displayName||'',
    role:row?.role||'USER',
    password:''
  }
  error.value=''
  resetCities()
  open.value=true
  if(mode.value!=='reset'&&form.value.username&&cityScoped()) await loadCityOptions()
}
function close(){
  if(busy.value)return
  open.value=false
  form.value={}
  resetCities()
}
async function save(){
  if(busy.value)return
  const f=form.value
  error.value=''
  if(mode.value==='create'&&!/^[A-Za-z0-9_-]{3,64}$/.test(f.username)){
    error.value='账号需为3–64位字母、数字、下划线或横线';return
  }
  if(mode.value!=='reset'&&(!f.displayName.trim()||f.displayName.trim().length>80)){
    error.value='姓名需为1–80字';return
  }
  if(mode.value!=='edit'&&(f.password.length<10||f.password.length>128)){
    error.value='密码需为10–128位';return
  }
  if(mode.value==='reset'){
    try{await showConfirmDialog({title:'重置密码',message:'重置“'+f.username+'”的密码后，该用户需要重新登录。'})}
    catch{return}
  }
  if(!alive)return
  busy.value=true
  try{
    if(mode.value==='create'){
      await props.api.post('/admin/users',{...f,displayName:f.displayName.trim()})
    }else if(mode.value==='reset'){
      await props.api.post('/admin/users/'+encodeURIComponent(f.username)+'/reset-password',{password:f.password})
    }else{
      await props.api.put('/admin/users/'+encodeURIComponent(f.username),{
        displayName:f.displayName.trim(),
        role:f.role
      })
    }
    if(mode.value!=='reset'&&f.role!=='ADMIN'&&cityState.value.loaded){
      await props.api.put('/admin/users/'+encodeURIComponent(f.username)+'/city-permissions',{
        selectedCities:cityState.value.selected
      })
    }
    if(alive){
      open.value=false
      form.value={}
      resetCities()
      refresh.value++
      showSuccessToast('已保存')
    }
  }catch(e){
    if(alive) error.value=e.response?.data?.message||'保存失败'
  }finally{
    busy.value=false
  }
}
</script>

<template>
  <div class="tb-admin-panel-card tb-admin-users-page">
    <RecordList :api="api" endpoint="/admin/users/page" :columns="columns" label="用户" :refresh="refresh">
      <template #toolbar>
        <button type="button" class="da-primary-button td-btn td-btn--primary" :disabled="busy" @click="edit()">
          <van-icon name="plus"/> 新增用户
        </button>
      </template>
      <template #role="{row}">{{ roleLabel(row.role) }}</template>
      <template #actions="{row}">
        <button :disabled="busy" @click="viewCities(row)">查看城市</button>
        <button :disabled="busy" @click="edit(row)">编辑</button>
        <button :disabled="busy" @click="edit(row,true)">重置密码</button>
      </template>
    </RecordList>

    <van-popup :show="open" round :close-on-click-overlay="false" class="da-crud-modal da-user-modal" @keydown.esc="close">
      <form @submit.prevent="save">
        <header>
          <h2>{{ mode==='create'?'新增用户':mode==='edit'?'编辑用户':'重置密码' }}</h2>
          <button type="button" aria-label="关闭用户弹窗" :disabled="busy" @click="close">×</button>
        </header>
        <div class="da-crud-fields">
          <label>
            账号
            <input v-model.trim="form.username" :disabled="mode!=='create'" required maxlength="64" autocomplete="off" @blur="loadCityOptions">
          </label>

          <template v-if="mode!=='reset'">
            <label>
              姓名
              <input v-model.trim="form.displayName" required maxlength="80">
            </label>
            <label>
              角色
              <select v-model="form.role" @change="roleChanged">
                <option v-for="role in roles" :key="role.code" :value="role.code">{{ role.name }}</option>
              </select>
            </label>

            <section v-if="form.role==='ADMIN'" class="da-user-city-scope all">
              <div><strong>城市数据权限</strong><span>全部城市</span></div>
            </section>

            <section v-else class="da-user-city-scope">
              <div class="da-user-city-head">
                <div>
                  <strong>城市数据权限</strong>
                </div>
                <button type="button" class="td-btn" :disabled="cityState.loading||!validCityAccount()" @click="loadCityOptions">
                  {{ cityState.loading?'读取中…':'刷新城市' }}
                </button>
              </div>

              <template v-if="cityState.loaded">
                <div class="da-user-city-actions">
                  <span>已选 {{ cityState.selected.length }} / {{ cityState.available.length }}</span>
                  <button type="button" @click="selectAllCities">全选</button>
                  <button type="button" @click="clearCities">取消全选</button>
                </div>
                <div v-if="cityState.available.length" class="da-user-city-grid">
                  <label v-for="city in cityState.available" :key="city">
                    <input type="checkbox" :checked="cityState.selected.includes(city)" @change="toggleCity(city)">
                    <span>{{ city }}</span>
                  </label>
                </div>
                <p v-else class="da-user-city-empty">该账号在 ADS 城市权限表中暂无有效城市。</p>
              </template>

            </section>
          </template>

          <label v-if="mode!=='edit'">
            {{ mode==='reset'?'新密码':'初始密码' }}
            <input v-model="form.password" type="password" required minlength="10" maxlength="128" autocomplete="new-password" placeholder="10–128 位">
          </label>
        </div>
        <p v-if="error" class="metric-error" role="alert">{{ error }}</p>
        <footer>
          <span/>
          <button type="button" class="td-btn" :disabled="busy" @click="close">取消</button>
          <button type="submit" class="da-primary-button td-btn td-btn--primary" :disabled="busy">{{ busy?'保存中…':'保存' }}</button>
        </footer>
      </form>
    </van-popup>

    <van-popup :show="cityViewOpen" round :close-on-click-overlay="false"
      class="da-crud-modal da-city-view-modal" @keydown.esc="closeCityView">
      <div>
        <header>
          <h2>查看城市</h2>
          <button type="button" aria-label="关闭城市弹窗" :disabled="cityViewLoading" @click="closeCityView">×</button>
        </header>
        <div class="da-city-view-body">
          <div class="da-city-view-user">
            <strong>{{ cityView.displayName }}</strong>
            <span>{{ cityView.username }} · {{ roleLabel(cityView.role) }}</span>
          </div>
          <p v-if="cityViewLoading" class="da-city-view-loading"><van-loading size="16px"/> 正在读取城市权限…</p>
          <p v-else-if="cityViewError" class="metric-error" role="alert">{{ cityViewError }}</p>
          <div v-else-if="cityView.allCities" class="da-city-view-all">
            <van-icon name="passed"/><strong>全部城市</strong>
          </div>
          <template v-else>
            <div class="da-city-view-summary">已授权 {{ cityView.selectedCities.length }} 个城市</div>
            <div v-if="cityView.selectedCities.length" class="da-city-view-grid">
              <span v-for="city in cityView.selectedCities" :key="city">{{ city }}</span>
            </div>
            <p v-else class="da-user-city-empty">该用户当前暂无授权城市。</p>
          </template>
        </div>
        <footer><span/><button type="button" class="td-btn" :disabled="cityViewLoading" @click="closeCityView">关闭</button></footer>
      </div>
    </van-popup>
  </div>
</template>

<style scoped>
.da-city-view-modal{width:min(680px,calc(100vw - 48px))}
.da-city-view-body{padding:18px 20px;min-height:180px}
.da-city-view-user{display:flex;align-items:baseline;gap:10px;padding-bottom:14px;border-bottom:1px solid var(--da-border)}
.da-city-view-user strong{font-size:15px;color:var(--da-text)}
.da-city-view-user span,.da-city-view-summary{font-size:12px;color:var(--da-text-3)}
.da-city-view-loading{display:flex;align-items:center;gap:8px;padding:28px 0;color:var(--da-text-2)}
.da-city-view-all{display:flex;align-items:center;gap:10px;margin-top:18px;padding:18px;border:1px solid var(--da-primary-border);border-radius:8px;background:var(--da-primary-soft);color:var(--da-primary)}
.da-city-view-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:8px;margin-top:12px;max-height:320px;overflow:auto}
.da-city-view-grid span{padding:8px 10px;border:1px solid var(--da-border);border-radius:6px;background:var(--da-surface-2);color:var(--da-text);font-size:12px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
@media(max-width:900px){.da-city-view-grid{grid-template-columns:repeat(2,minmax(0,1fr))}}
</style>

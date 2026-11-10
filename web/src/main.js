import { createApp } from 'vue'
import Vant from 'vant'
import 'vant/lib/index.css'
import App from './App.vue'
import './style.css'
import './pc-design-system.css'
import './pc-admin.css'
createApp(App).use(Vant).mount('#app')

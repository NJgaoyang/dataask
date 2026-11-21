import { createApp } from 'vue'
import Vant, { setToastDefaultOptions } from 'vant'
import 'vant/lib/index.css'
import App from './App.vue'
import './style.css'
import './pc-design-system.css'
import './pc-admin.css'
import './pc-lists.css'
import './tdesign-console.css'
import './edge-to-edge.css'
import './login-v2.css'
// Place all Vant success, error and info prompts in the viewport center.
setToastDefaultOptions({position:'middle'})
createApp(App).use(Vant).mount('#app')

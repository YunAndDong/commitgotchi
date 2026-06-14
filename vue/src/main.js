import { createApp } from 'vue'
import App from './App.vue'
import router from './router/index.js'
import './styles/tokens.css'
import './styles/base.css'

document.documentElement.setAttribute('data-theme', 'cozy')
try { localStorage.removeItem('cg.theme') } catch { /* ignore */ }

createApp(App).use(router).mount('#app')

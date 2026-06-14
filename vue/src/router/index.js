import { createRouter, createWebHashHistory } from 'vue-router'
import { authState, bootstrap, isAuthenticated } from '../stores/auth.js'

const routes = [
  { path: '/login', name: 'login', component: () => import('../views/LoginView.vue'), meta: { public: true } },
  { path: '/', name: 'dashboard', component: () => import('../views/DashboardView.vue') },
  { path: '/create', name: 'create', component: () => import('../views/CharacterCreateView.vue') },
  { path: '/complete/:id', name: 'complete', component: () => import('../views/CreationCompleteView.vue') },
  { path: '/character/:id', name: 'character', component: () => import('../views/CharacterDetailView.vue') },
  { path: '/report', name: 'report', component: () => import('../views/ReportWriteView.vue') },
  { path: '/report/result', name: 'report-result', component: () => import('../views/ReportResultView.vue') },
  { path: '/quiz', name: 'quiz', component: () => import('../views/QuizView.vue') },
  { path: '/ranking', name: 'ranking', component: () => import('../views/RankingView.vue') },
  { path: '/codex', name: 'codex', component: () => import('../views/CodexView.vue') },
  { path: '/board', name: 'board', component: () => import('../views/BoardView.vue') },
  { path: '/board/:id', name: 'board-detail', component: () => import('../views/BoardDetailView.vue') },
  { path: '/:pathMatch(.*)*', redirect: '/' },
]

const router = createRouter({
  // Hash history works identically on the web and inside a chrome-extension://
  // page (no server rewrite needed, no 404 on deep links).
  history: createWebHashHistory(),
  routes,
  scrollBehavior() { return { top: 0 } },
})

router.beforeEach(async (to) => {
  if (authState.status === 'idle') await bootstrap()
  if (!to.meta.public && !isAuthenticated()) return { name: 'login', query: { redirect: to.fullPath } }
  if (to.name === 'login' && isAuthenticated()) return { name: 'dashboard' }
  return true
})

export default router

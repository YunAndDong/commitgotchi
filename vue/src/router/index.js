import { createRouter, createWebHashHistory } from 'vue-router'
import { authState, bootstrap, isAuthenticated } from '../stores/auth.js'
import { activeCharacter, loadGameState } from '../stores/game.js'

const isExtensionPopup = () => (
  location.protocol === 'chrome-extension:' ||
  document.documentElement.classList.contains('is-ext-popup')
)

async function refreshGameState() {
  if (isAuthenticated()) {
    try { await loadGameState() } catch { /* keep current state and let the target view render its empty/error state */ }
  }
  return true
}

const routes = [
  { path: '/login', name: 'login', component: () => import('../views/LoginView.vue'), meta: { public: true } },
  { path: '/select', name: 'character-select', component: () => import('../views/CharacterSelectView.vue'), meta: { allowWithoutCharacter: true } },
  { path: '/', name: 'dashboard', component: () => import('../views/DashboardView.vue') },
  { path: '/create', name: 'create', component: () => import('../views/CharacterCreateView.vue'), meta: { allowWithoutCharacter: true } },
  { path: '/complete/:id', name: 'complete', component: () => import('../views/CreationCompleteView.vue'), meta: { allowWithoutCharacter: true } },
  { path: '/character/:id', name: 'character', component: () => import('../views/CharacterDetailView.vue') },
  { path: '/report', name: 'report', component: () => import('../views/ReportWriteView.vue') },
  { path: '/report/result', name: 'report-result', component: () => import('../views/ReportResultView.vue') },
  { path: '/report/:id', name: 'report-detail', component: () => import('../views/ReportDetailView.vue') },
  { path: '/quiz', name: 'quiz', component: () => import('../views/QuizView.vue'), beforeEnter: refreshGameState },
  { path: '/quiz/:id', name: 'quiz-detail', component: () => import('../views/QuizDetailView.vue') },
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
  if (to.name === 'login' && isAuthenticated()) {
    return activeCharacter.value
      ? { name: 'character', params: { id: activeCharacter.value.id } }
      : { name: 'character-select' }
  }
  if (!to.meta.public && !to.meta.allowWithoutCharacter && !activeCharacter.value) {
    return { name: 'character-select', query: { redirect: to.fullPath } }
  }
  if (isExtensionPopup() && to.name === 'dashboard' && activeCharacter.value) {
    return { name: 'character', params: { id: activeCharacter.value.id } }
  }
  return true
})

export default router

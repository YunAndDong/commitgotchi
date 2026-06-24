<script setup>
import { computed, onBeforeUnmount, watch } from 'vue'
import { useRoute } from 'vue-router'
import { authState } from './stores/auth.js'
import { gameState, clearNotice } from './stores/game.js'
import AppNav from './components/AppNav.vue'

const route = useRoute()
let noticeTimer = null
const showAppNav = computed(() => route.name === 'login' || (!route.meta.public && authState.user))
const showAppNavLogout = computed(() => route.name !== 'login')

watch(() => gameState.notice, notice => {
  if (noticeTimer) window.clearTimeout(noticeTimer)
  if (!notice) return
  noticeTimer = window.setTimeout(() => {
    if (gameState.notice === notice) clearNotice()
  }, 2000)
}, { immediate: true })

onBeforeUnmount(() => {
  if (noticeTimer) window.clearTimeout(noticeTimer)
})
</script>

<template>
  <div class="cg-app">
    <AppNav v-if="showAppNav" :show-logout="showAppNavLogout" />

    <main class="cg-main" :class="{ 'cg-main--auth': route.name === 'login' }">
      <RouterView v-slot="{ Component }">
        <Transition name="fade">
          <component :is="Component" :key="route.fullPath" />
        </Transition>
      </RouterView>
    </main>

    <Transition name="fade">
      <div v-if="gameState.notice" class="toast cg-card row between" @click="clearNotice">
        <span>{{ gameState.notice }}</span>
        <button class="cg-btn cg-btn--sm cg-btn--ghost" aria-label="닫기">✕</button>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.toast {
  position: fixed; left: 50%; bottom: 24px; transform: translateX(-50%);
  z-index: 60; max-width: 560px; gap: var(--sp-4);
  background: var(--popup-bg); border-color: var(--popup-edge);
  box-shadow: 4px 4px 0 var(--shadow-hard); cursor: pointer;
}
:global(html.is-ext-popup) .cg-main--auth {
  padding-top: var(--sp-2);
  padding-bottom: 0;
}
</style>

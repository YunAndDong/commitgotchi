<script setup>
import { useRoute } from 'vue-router'
import { authState } from './stores/auth.js'
import { gameState, clearNotice } from './stores/game.js'
import AppNav from './components/AppNav.vue'
import Mascot from './components/Mascot.vue'

const route = useRoute()
</script>

<template>
  <div class="cg-app">
    <AppNav v-if="!route.meta.public && authState.user" />

    <main class="cg-main">
      <RouterView v-slot="{ Component }">
        <Transition name="fade">
          <component :is="Component" :key="route.fullPath" />
        </Transition>
      </RouterView>
    </main>

    <Mascot v-if="!route.meta.public && authState.user" />

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
</style>

<script setup>
// Compact app bar: brand lockup + extension controls.
import { RouterLink, useRouter } from 'vue-router'
import { authState, logout } from '../stores/auth.js'
import GotchiVisibilityToggle from './GotchiVisibilityToggle.vue'

defineProps({
  showLogout: { type: Boolean, default: true },
})

const router = useRouter()
const isExtensionPopup = document.documentElement.classList.contains('is-ext-popup')
async function onLogout() {
  await logout()
  router.push('/login')
}
</script>

<template>
  <header class="nav">
    <div class="nav__inner row between">
      <RouterLink to="/" class="brand row">
        <span class="brand__mark" aria-hidden="true">🌱</span>
        <span class="brand__name">Commit-Gotchi</span>
      </RouterLink>

      <div class="nav__actions row">
        <GotchiVisibilityToggle v-if="isExtensionPopup" />
        <button v-if="showLogout && authState.user" class="cg-btn cg-btn--sm cg-btn--ghost" @click="onLogout">로그아웃</button>
      </div>
    </div>
  </header>
</template>

<style scoped>
.nav {
  position: sticky; top: 0; z-index: 30;
  background: var(--popup-bg);
  border-bottom: 2px solid var(--popup-edge);
}
.nav__inner { max-width: 1100px; margin: 0 auto; padding: 10px var(--sp-4); gap: var(--sp-4); }
.brand { gap: 8px; }
.brand__mark { font-size: 22px; }
.brand__name { font-family: var(--font-display); font-size: 18px; letter-spacing: .5px; }
.nav__actions { gap: var(--sp-3); margin-left: auto; }
:global(html.is-ext-popup) .nav {
  margin-top: 0;
}
:global(html.is-ext-popup) .nav__inner {
  padding-top: 0;
  padding-bottom: 8px;
}
@media (max-width: 480px) {
  .nav__inner { align-items: flex-start; flex-direction: column; }
  .nav__actions { margin-left: 0; width: 100%; justify-content: space-between; }
}
</style>

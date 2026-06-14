<script setup>
// Global nav: brand lockup + primary destinations + active character/account.
import { RouterLink, useRouter } from 'vue-router'
import { authState, logout } from '../stores/auth.js'
import { activeCharacter } from '../stores/game.js'
import GotchiVisibilityToggle from './GotchiVisibilityToggle.vue'

const router = useRouter()
const isExtensionPopup = document.documentElement.classList.contains('is-ext-popup')
const links = [
  { to: '/', label: '홈', icon: '🏠' },
  { to: '/quiz', label: '퀴즈', icon: '🧩' },
  { to: '/report', label: '리포트', icon: '📓' },
  { to: '/ranking', label: '랭킹', icon: '🏆' },
  { to: '/codex', label: '도감', icon: '📖' },
  { to: '/board', label: '게시판', icon: '🗨️' },
]
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

      <nav class="nav__links row wrap" aria-label="주요 화면">
        <RouterLink v-for="l in links" :key="l.to" :to="l.to" class="nav__link" active-class="is-active">
          <span aria-hidden="true">{{ l.icon }}</span><span class="nav__link-t">{{ l.label }}</span>
        </RouterLink>
      </nav>

      <div class="row" style="gap: var(--sp-3)">
        <GotchiVisibilityToggle v-if="isExtensionPopup" />
        <div class="acct row" v-if="authState.user">
          <span class="tiny muted">{{ activeCharacter?.name || '캐릭터 없음' }} · {{ authState.user.email }}</span>
          <button class="cg-btn cg-btn--sm cg-btn--ghost" @click="onLogout">로그아웃</button>
        </div>
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
.nav__inner { max-width: 1180px; margin: 0 auto; padding: 10px var(--sp-4); gap: var(--sp-4); flex-wrap: wrap; }
.brand { gap: 8px; }
.brand__mark { font-size: 22px; }
.brand__name { font-family: var(--font-display); font-size: 18px; letter-spacing: .5px; }
.nav__links { gap: 4px; }
.nav__link {
  display: inline-flex; align-items: center; gap: 5px;
  font-family: var(--font-head); font-size: 13px;
  padding: 7px 11px; border-radius: var(--r-pill); color: var(--ink-soft);
}
.nav__link:hover { background: var(--surface-2); color: var(--ink); }
.nav__link.is-active { background: var(--primary); color: var(--on-primary); }
.acct { gap: 8px; }
@media (max-width: 720px) { .nav__link-t { display: none; } }
</style>

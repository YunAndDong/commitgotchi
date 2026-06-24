<script setup>
/*
 * Screen #1 — 로그인 / 회원가입.
 * Left: pixel sky scene + character parade. Right: auth form (real Spring Boot API).
 */
import { ref, reactive } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { login, signup, authMessage } from '../stores/auth.js'
import CgSprite from '../components/CgSprite.vue'

const route = useRoute()
const router = useRouter()
const mode = ref('login')               // login | signup
const form = reactive({ email: '', password: '', confirm: '' })
const busy = ref(false)
const error = ref('')

const docsSpriteMeta = {
  columns: 3,
  rows: 1,
  frameMap: {
    joy: [0, 0],
    happy: [0, 0],
    sad: [0, 1],
    angry: [0, 2],
  },
  transparent: true,
}

const parade = [
  { emotion: 'joy', spriteSheetUrl: '/character-assets/default_image1.png', spriteMeta: docsSpriteMeta },
  { emotion: 'sad', spriteSheetUrl: '/character-assets/default_image2.png', spriteMeta: docsSpriteMeta },
  { emotion: 'joy', spriteSheetUrl: '/character-assets/default_image3.png', spriteMeta: docsSpriteMeta },
  { emotion: 'angry', spriteSheetUrl: '/character-assets/default_image1.png', spriteMeta: docsSpriteMeta },
]

async function submit() {
  error.value = ''
  if (mode.value === 'signup' && form.password !== form.confirm) {
    error.value = '비밀번호가 일치하지 않아요.'
    return
  }
  busy.value = true
  try {
    if (mode.value === 'login') await login(form.email, form.password)
    else await signup(form.email, form.password)
    router.push({ name: 'character-select', query: route.query.redirect ? { redirect: route.query.redirect } : {} })
  } catch (e) {
    error.value = authMessage(e)
  } finally {
    busy.value = false
  }
}

</script>

<template>
  <div class="auth" :class="{ 'auth--signup': mode === 'signup' }">
    <!-- left: pixel sky scene -->
    <section class="auth__scene cg-screen">
      <div class="sky">
        <img class="sky__logo" src="/brand/commitgotchi-logo-cutout.png" alt="" aria-hidden="true" />
        <span class="cloud cloud--1" aria-hidden="true">☁️</span>
        <span class="cloud cloud--2" aria-hidden="true">☁️</span>
        <span class="sun" aria-hidden="true">☀️</span>
      </div>
      <div class="parade">
        <CgSprite
          v-for="(p, i) in parade"
          :key="i"
          :size="78"
          :emotion="p.emotion"
          :sprite-sheet-url="p.spriteSheetUrl"
          :sprite-meta="p.spriteMeta"
        />
      </div>
      <p class="scene__copy">
        혼자 하는 공부를 <strong>귀여운 캐릭터와 함께!</strong><br />
        오늘도 커밋고치와 함께 성장해봐요.
      </p>
    </section>

    <!-- right: form -->
    <section class="auth__form">
      <div class="lockup row">
        <span class="lockup__mark" aria-hidden="true">🌱</span>
        <span class="lockup__name">Commit-Gotchi</span>
      </div>
      <h1 class="auth__title">{{ mode === 'login' ? '돌아오셨군요!' : '환영합니다!' }}</h1>

      <form class="auth__fields col" @submit.prevent="submit">
        <div class="cg-field">
          <label class="cg-label" for="email">이메일</label>
          <input id="email" class="cg-input" type="email" v-model="form.email"
                 autocomplete="email" placeholder="you@example.com" required />
        </div>
        <div class="cg-field">
          <label class="cg-label" for="pw">비밀번호</label>
          <input id="pw" class="cg-input" type="password" v-model="form.password"
                 autocomplete="current-password" placeholder="12~64자" required />
        </div>
        <div v-if="mode === 'signup'" class="cg-field">
          <label class="cg-label" for="pw2">비밀번호 확인</label>
          <input id="pw2" class="cg-input" type="password" v-model="form.confirm"
                 autocomplete="new-password" required />
        </div>

        <p v-if="error" class="auth__error" role="alert">{{ error }}</p>

        <button class="cg-btn cg-btn--primary cg-btn--block" :disabled="busy">
          {{ busy ? '잠시만요…' : (mode === 'login' ? '로그인' : '가입하고 시작하기') }}
        </button>
      </form>

      <p class="auth__switch tiny muted">
        {{ mode === 'login' ? '아직 계정이 없나요?' : '이미 계정이 있나요?' }}
        <button class="linkbtn" @click="mode = mode === 'login' ? 'signup' : 'login'; error = ''">
          {{ mode === 'login' ? '회원가입' : '로그인' }}
        </button>
      </p>
    </section>
  </div>
</template>

<style scoped>
.auth { display: grid; grid-template-columns: 1.1fr 1fr; gap: var(--sp-5); max-width: 960px; margin: 5vh auto; }
.auth__scene {
  position: relative; overflow: hidden; padding: var(--sp-6) var(--sp-5);
  display: flex; flex-direction: column; justify-content: space-between; height: 460px;
  background: linear-gradient(180deg, var(--sky-top) 0%, var(--sky-bottom) 70%);
}
:global(html.is-ext-popup .auth) {
  align-items: start;
  margin: var(--sp-2) auto 0;
}
:global(html.is-ext-popup .auth__scene) {
  height: 358px;
  padding-block: var(--sp-5) var(--sp-4);
}
[data-theme='cli'] .auth__scene { background: var(--screen); }
.sky { position: relative; height: 90px; }
.sky__logo {
  position: absolute;
  left: 50%;
  top: 2px;
  z-index: 1;
  width: 214px;
  height: auto;
  transform: translateX(-50%);
  pointer-events: none;
  image-rendering: pixelated;
  filter: drop-shadow(0 2px 0 rgba(70, 56, 43, .16));
}
:global(html.is-ext-popup) .sky__logo { top: 0; width: 196px; }
.sun { position: absolute; right: 12px; top: 0; font-size: 34px; }
.cloud, .sun { z-index: 2; }
.cloud { position: absolute; font-size: 28px; animation: drift 18s linear infinite; }
.cloud--1 { left: 6%; top: 18px; }
.cloud--2 { left: 44%; top: 48px; animation-duration: 26s; }
@keyframes drift { from { transform: translateX(-30px); } to { transform: translateX(40px); } }
.parade { display: flex; justify-content: space-around; align-items: flex-end; flex: 1; }
.scene__copy { font-family: var(--font-head); font-size: 15px; line-height: 1.8; color: var(--ink); }
.auth__form { display: flex; flex-direction: column; gap: var(--sp-4); justify-content: center; padding: var(--sp-4); }
:global(html.is-ext-popup) .auth__form { transform: translateY(-10px); }
.auth--signup .auth__form { gap: var(--sp-3); }
:global(html.is-ext-popup) .auth--signup .auth__form {
  justify-content: flex-start;
  padding-top: var(--sp-2);
}
.auth--signup .auth__fields { gap: var(--sp-2); }
.auth--signup .cg-field { gap: 4px; }
.auth--signup .cg-label { line-height: 1.3; }
.auth--signup .cg-input {
  padding-block: 8px;
  min-height: 40px;
}
.auth--signup .cg-btn { min-height: 40px; padding-block: 8px; }
.lockup { gap: 8px; }
.lockup__mark { font-size: 24px; }
.lockup__name { font-family: var(--font-display); font-size: 20px; }
.auth__title { font-size: 24px; }
.auth__error { color: var(--angry); font-size: 13px; font-family: var(--font-head); }
.linkbtn { background: none; border: none; color: var(--primary-d); font-family: var(--font-head); text-decoration: underline; }
@media (max-width: 760px) { .auth { grid-template-columns: 1fr; } .auth__scene { height: 280px; } }
</style>

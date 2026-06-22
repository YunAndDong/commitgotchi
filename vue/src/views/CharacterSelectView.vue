<script setup>
/*
 * 로그인 직후 커밋고치를 직접 고르는 화면.
 * 선택 전에는 activeCharacter가 비어 있어 대시보드/학습 흐름으로 바로 들어가지 않는다.
 */
import { computed } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { gameState, nurtureScore, setActive, MAX_CHARACTERS } from '../stores/game.js'
import CgSprite from '../components/CgSprite.vue'
import CgGauge from '../components/CgGauge.vue'
import CgEmo from '../components/CgEmo.vue'

const route = useRoute()
const router = useRouter()
const characters = computed(() => gameState.characters)
const slotsLeft = computed(() => Math.max(0, MAX_CHARACTERS - characters.value.length))

function safeRedirectTarget() {
  const target = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
  return target.startsWith('/login') || target.startsWith('/select') ? '/' : target
}

async function choose(character) {
  if (!await setActive(character.id)) return
  router.push(safeRedirectTarget())
}
</script>

<template>
  <div class="select col">
    <section class="select__head">
      <div class="col">
        <span class="tiny muted">Commit-Gotchi</span>
        <h1 class="title">오늘 함께할 커밋고치를 골라주세요</h1>
      </div>
      <RouterLink v-if="slotsLeft" to="/create" class="cg-btn cg-btn--primary">새 분신 만들기</RouterLink>
    </section>

    <section v-if="characters.length" class="grid" aria-label="보유 커밋고치">
      <article v-for="character in characters" :key="character.id" class="gotchi cg-card col">
        <div class="row between">
          <div>
            <h2 class="cg-section-title">{{ character.name }}</h2>
            <p class="tiny faint mono">{{ character.keyword || '기본 디자인' }}</p>
          </div>
          <CgEmo :emotion="character.emotion" />
        </div>

        <div class="gotchi__stage cg-screen center">
          <CgSprite :size="150" :emotion="character.emotion" :evolved="character.isEvolved"
                    :sprite-sheet-url="character.spriteSheetUrl" :sprite-meta="character.spriteMeta"
                    :pending="character.imageStatus === 'PENDING'" :failed="['FALLBACK', 'FAILED'].includes(character.imageStatus)" />
        </div>

        <p class="bubble">“{{ character.message }}”</p>
        <CgGauge :value="nurtureScore(character)" :max="1000" label="육아점수" />

        <div class="row between wrap">
          <span class="cg-badge" :class="character.active ? 'cg-badge--ok' : 'cg-badge--warn'">
            {{ character.active ? '현재 선택됨' : '대기 중' }}
          </span>
          <button class="cg-btn cg-btn--accent" @click="choose(character)">이 커밋고치 선택</button>
        </div>
      </article>
    </section>

    <section v-else class="empty cg-card center col">
      <CgSprite :size="128" emotion="joy" />
      <h2 class="cg-section-title big">아직 고를 커밋고치가 없어요</h2>
      <p class="muted">첫 분신을 만든 뒤 학습 리포트와 퀴즈를 시작할 수 있어요.</p>
      <RouterLink to="/create" class="cg-btn cg-btn--primary">첫 분신 만들기</RouterLink>
    </section>
  </div>
</template>

<style scoped>
.select { gap: var(--sp-5); max-width: 980px; margin: 0 auto; }
.select__head {
  display: flex; align-items: end; justify-content: space-between;
  gap: var(--sp-4); padding: 0 var(--sp-1);
}
.title { font-size: 24px; }
.grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(260px, 1fr)); gap: var(--sp-4); }
.gotchi { gap: var(--sp-3); }
.gotchi__stage { min-height: 210px; padding: var(--sp-4); }
.bubble {
  background: var(--surface-2); border: 2px solid var(--surface-edge); border-radius: var(--r);
  padding: 9px 12px; font-family: var(--font-head); font-size: 13px; text-align: center;
}
.empty { min-height: 430px; text-align: center; }
@media (max-width: 640px) {
  .select__head { align-items: stretch; flex-direction: column; }
}
</style>

<script setup>
/*
 * 로그인 직후 커밋고치를 직접 고르는 화면.
 * 선택 전에는 activeCharacter가 비어 있어 대시보드/학습 흐름으로 바로 들어가지 않는다.
 */
import { computed } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import {
  activeCharacter,
  clearCharacterSseResult,
  gameState,
  hasCharacterSseResult,
  nurtureScore,
  setActive,
  MAX_CHARACTERS,
} from '../stores/game.js'
import CgSprite from '../components/CgSprite.vue'
import CgGauge from '../components/CgGauge.vue'
import CgEmo from '../components/CgEmo.vue'

const route = useRoute()
const router = useRouter()
const characters = computed(() => gameState.characters)
const slotsLeft = computed(() => Math.max(0, MAX_CHARACTERS - characters.value.length))
const selectedCharacter = computed(() => activeCharacter.value)
const characterSlots = computed(() => (
  Array.from({ length: MAX_CHARACTERS }, (_, index) => characters.value[index] || null)
))

function safeRedirectTarget() {
  const target = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
  return target.startsWith('/login') || target.startsWith('/select') ? '/' : target
}

async function choose(character) {
  if (character.active) return
  if (!await setActive(character.id)) return
  clearCharacterSseResult(character.id)
  router.push(safeRedirectTarget())
}
</script>

<template>
  <div class="select col">
    <section class="select__head">
      <div class="col">
        <h1 class="title">오늘 함께할 커밋고치를 골라주세요</h1>
      </div>
      <div class="select__actions row">
        <RouterLink to="/codex" class="cg-btn cg-btn--ghost cg-btn--sm">도감 둘러보기</RouterLink>
        <RouterLink v-if="slotsLeft" to="/create" class="cg-btn cg-btn--primary cg-btn--sm">+ 새 커밋고치 생성</RouterLink>
      </div>
    </section>

    <section v-if="characters.length" class="select__body">
      <article v-if="selectedCharacter" class="gotchi gotchi--current cg-card col" aria-label="현재 선택된 커밋고치">
        <div class="row between">
          <div>
            <p class="panel-label">현재 선택된 커밋고치</p>
            <h2 class="cg-section-title">{{ selectedCharacter.name }}</h2>
            <p class="tiny faint mono">{{ selectedCharacter.keyword || '기본 디자인' }}</p>
          </div>
          <CgEmo :emotion="selectedCharacter.emotion" />
        </div>

        <div class="gotchi__stage cg-screen center">
          <CgSprite :size="138" :emotion="selectedCharacter.emotion" :evolved="selectedCharacter.isEvolved"
                    :sprite-sheet-url="selectedCharacter.spriteSheetUrl" :sprite-meta="selectedCharacter.spriteMeta"
                    :pending="selectedCharacter.imageStatus === 'PENDING'" />
        </div>

        <p class="bubble">“{{ selectedCharacter.message }}”</p>
        <CgGauge :value="nurtureScore(selectedCharacter)" :max="1000" label="육아점수" />
      </article>

      <article v-else class="gotchi gotchi--current cg-card center col" aria-label="현재 선택된 커밋고치">
        <CgSprite :size="116" emotion="joy" grey />
        <h2 class="cg-section-title">아직 선택된 커밋고치가 없어요</h2>
        <p class="muted">오른쪽 목록에서 함께할 커밋고치를 골라주세요.</p>
      </article>

      <section class="select-list col" aria-label="보유 커밋고치">
        <article v-for="(character, index) in characterSlots"
                 :key="character?.id || `empty-${index}`"
                 class="select-list__card cg-card">
          <template v-if="character">
            <div class="select-list__avatar cg-screen center">
              <CgSprite :size="54" :emotion="character.emotion" :evolved="character.isEvolved"
                        :sprite-sheet-url="character.spriteSheetUrl" :sprite-meta="character.spriteMeta"
                        :pending="character.imageStatus === 'PENDING'" />
            </div>

            <div class="select-list__info">
              <div class="row between select-list__topline">
                <h2 class="cg-section-title">{{ character.name }}</h2>
                <CgEmo :emotion="character.emotion" />
              </div>
              <p class="tiny faint mono">{{ character.keyword || '기본 디자인' }}</p>
              <CgGauge :value="nurtureScore(character)" :max="1000" label="육아점수" />
            </div>

            <div class="select-list__action">
              <span v-if="hasCharacterSseResult(character.id)"
                    class="select-list__result-badge"
                    role="status"
                    aria-live="polite">
                결과 도착
              </span>
              <span v-if="character.active" class="cg-badge cg-badge--ok">선택됨</span>
              <button v-else class="cg-btn cg-btn--accent cg-btn--sm" @click="choose(character)">선택하기</button>
            </div>
          </template>

          <template v-else>
            <div class="select-list__empty-mark center" aria-hidden="true">+</div>
            <div class="select-list__info">
              <h2 class="cg-section-title">빈 슬롯</h2>
              <p class="tiny muted">새 커밋고치를 만들 수 있어요.</p>
            </div>
            <RouterLink v-if="slotsLeft" to="/create" class="cg-btn cg-btn--primary cg-btn--sm">생성하기</RouterLink>
          </template>
        </article>
      </section>
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
.select { gap: var(--sp-4); max-width: 980px; margin: 0 auto; }
.select__head {
  display: flex; align-items: end; justify-content: space-between;
  gap: var(--sp-4); padding: 0 var(--sp-1);
}
.title { font-size: 24px; }
.select__actions {
  flex-wrap: wrap;
  justify-content: flex-end;
}
.select__body {
  display: grid;
  grid-template-columns: minmax(250px, .9fr) minmax(360px, 1.1fr);
  gap: var(--sp-4);
  align-items: start;
}
.gotchi { gap: var(--sp-3); }
.gotchi--current { min-height: 100%; }
.panel-label {
  font-family: var(--font-head);
  font-size: 12px;
  color: var(--ink-soft);
}
.gotchi__stage {
  display: flex;
  min-height: 176px;
  padding: var(--sp-3);
}
.bubble {
  background: var(--surface-2); border: 2px solid var(--surface-edge); border-radius: var(--r);
  padding: 9px 12px; font-family: var(--font-head); font-size: 13px; text-align: center;
}
.select-list { gap: var(--sp-3); }
.select-list__card {
  display: grid;
  grid-template-columns: 82px minmax(0, 1fr) auto;
  align-items: center;
  gap: var(--sp-3);
  min-height: 104px;
  padding: var(--sp-3);
}
.select-list__avatar {
  display: flex;
  width: 82px;
  min-height: 76px;
  padding: var(--sp-2);
}
.select-list__info {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.select-list__topline {
  gap: var(--sp-2);
  align-items: flex-start;
}
.select-list__topline .cg-section-title,
.select-list__info .tiny {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.select-list__action {
  min-width: 82px;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  justify-content: flex-end;
  gap: 7px;
}
.select-list__result-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 22px;
  padding: 3px 8px;
  border-radius: 999px;
  background: #e11937;
  color: #fff;
  box-shadow: 0 0 0 2px rgba(225, 25, 55, .16);
  font-family: var(--font-head);
  font-size: 11px;
  font-weight: 700;
  white-space: nowrap;
  animation: result-badge-blink .95s ease-in-out infinite;
}
@keyframes result-badge-blink {
  0%, 100% {
    opacity: 1;
    transform: translateY(0);
  }
  50% {
    opacity: .38;
    transform: translateY(-1px);
  }
}
.select-list__empty-mark {
  display: flex;
  width: 82px;
  height: 76px;
  border: 2px dashed var(--surface-edge);
  border-radius: var(--r);
  font-family: var(--font-head);
  font-size: 26px;
  color: var(--ink-faint);
}
.empty { min-height: 430px; text-align: center; }
@media (max-width: 760px) {
  .select__body { grid-template-columns: 1fr; }
}
@media (max-width: 640px) {
  .select__head { align-items: stretch; flex-direction: column; }
  .select__actions { justify-content: flex-start; }
  .select-list__card {
    grid-template-columns: 72px minmax(0, 1fr);
  }
  .select-list__avatar,
  .select-list__empty-mark {
    width: 72px;
  }
  .select-list__action,
  .select-list__card > .cg-btn {
    grid-column: 2;
    justify-self: start;
    min-width: 0;
  }
  .select-list__action {
    align-items: flex-start;
  }
}
@media (prefers-reduced-motion: reduce) {
  .select-list__result-badge {
    animation: none;
  }
}
</style>

<script setup>
/*
 * AI 일일 레포트 결과 뷰 (스파인 보강, 자정 배치 — FR-10·12).
 * 학습 분석 · 반영 후 성장/점수 변화량 · 퀴즈 종합 코멘트 · 다음 학습 추천.
 * 미생성/실패 시 상태 안내(빈 화면 금지).
 */
import { computed, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { gameState, markCharacterResultRead, nurtureScore, STAT_LABELS, EVOLVE_THRESHOLD } from '../stores/game.js'
import { WAITING, FALLBACK } from '../constants/aiStates.js'
import CgStatTile from '../components/CgStatTile.vue'
import CgState from '../components/CgState.vue'
import CgGauge from '../components/CgGauge.vue'
import CgRadar from '../components/CgRadar.vue'
import CgEmo from '../components/CgEmo.vue'

const rep = computed(() => gameState.dailyReport)
const deltas = computed(() => rep.value.deltas || {})
const character = computed(() => gameState.characters.find(
  item => String(item.id) === String(rep.value.characterId)
) || null)
watch([character, () => rep.value.status], ([value, status]) => {
  if (value && status !== 'pending') markCharacterResultRead(value.id)
}, { immediate: true })
const score = computed(() => character.value ? nurtureScore(character.value) : 0)
const nextRecommendationText = computed(() => {
  const value = rep.value.nextRecommendation
  if (!value) return ''
  if (typeof value === 'string') return value
  const topics = Array.isArray(value.topics) ? value.topics.filter(Boolean).join(', ') : ''
  const rationale = typeof value.rationale === 'string' ? value.rationale : ''
  if (topics && rationale) return `${rationale} (${topics})`
  return rationale || topics
})
</script>

<template>
  <div class="result col">
    <header class="cg-pagehead">
      <div class="cg-pagehead__main">
        <RouterLink to="/" class="cg-btn cg-btn--sm cg-back" aria-label="대시보드로 돌아가기">←</RouterLink>
        <h1 class="cg-page-title">어제의 일일 레포트</h1>
      </div>
    </header>

    <!-- 상태 패턴: 대기(자정 배치) / Fallback(실패) — 빈 화면 금지 -->
    <CgState v-if="rep.status === 'pending'" tone="waiting" :message="WAITING.reportResult">
      <RouterLink to="/" class="cg-btn">대시보드로</RouterLink>
    </CgState>

    <CgState v-else-if="rep.status === 'failed'" tone="fallback" :message="FALLBACK.report">
      <RouterLink to="/" class="cg-btn">대시보드로</RouterLink>
    </CgState>

    <!-- ready -->
    <template v-else>
      <div class="cg-card col">
        <div class="cg-section-title">📊 학습 분석</div>
        <p class="body">{{ rep.summary }}</p>
      </div>

      <div v-if="character" class="cg-card growth">
        <div class="col growth-main">
          <div class="row between">
            <div class="cg-section-title">🌱 반영 후 성장</div>
            <CgEmo :emotion="character.emotion" />
          </div>
          <CgGauge :value="score" :max="EVOLVE_THRESHOLD" label="육아점수" />
          <div class="growth-deltas">
            <CgStatTile v-for="(d, k) in deltas" :key="k" :label="STAT_LABELS[k]" :value="d" :delta="d" />
          </div>
        </div>
        <CgRadar class="growth-radar" :stats="character.stats" :size="220" />
      </div>

      <div class="cg-card col">
        <div class="cg-section-title">🧩 어제 퀴즈 종합 코멘트</div>
        <p class="body">{{ rep.quizComment }}</p>
      </div>

      <div class="cg-card col next">
        <div class="cg-section-title">🧭 다음 학습 추천</div>
        <p class="body">{{ nextRecommendationText }}</p>
        <div class="row" style="gap: var(--sp-2)">
          <RouterLink
            :to="{ name: 'quiz', query: { characterId: rep.characterId } }"
            class="cg-btn cg-btn--accent"
          >새 추천 퀴즈 풀기</RouterLink>
          <RouterLink to="/report" class="cg-btn cg-btn--primary">오늘 학습 기록하기</RouterLink>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.result { max-width: 760px; margin: 0 auto; gap: var(--sp-4); }
.pad { padding: var(--sp-6); gap: var(--sp-3); }
.body { line-height: 1.85; }
.next { border-color: var(--primary-d); }
.growth {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: var(--sp-4);
}
.growth-main { min-width: 0; gap: var(--sp-3); }
.growth-deltas {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 6px;
}
.growth-deltas :deep(.cg-stattile) {
  min-width: 0;
  padding: 8px 6px;
}
.growth-deltas :deep(.cg-stattile__v) { font-size: 18px; }
.growth-deltas :deep(.cg-stattile__k) {
  overflow-wrap: anywhere;
  line-height: 1.3;
}
.growth-radar { justify-self: end; }
@media (max-width: 620px) {
  .growth { grid-template-columns: 1fr; }
  .growth-radar { justify-self: center; }
}
@media (max-width: 420px) {
  .growth-deltas { grid-template-columns: repeat(auto-fit, minmax(76px, 1fr)); }
}
</style>
